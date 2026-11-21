package com.bugsnap.ai.capture.buffer

/**
 * Collects post-roll samples after a Mark until [tMarkUs] + [postRollUs],
 * or until [finalizeNow] is called (projection lost / session stop).
 */
internal class PendingClip(
    val id: String,
    val tMarkUs: Long,
    val postRollUs: Long,
    val generation: Generation,
    preSamples: List<EncodedSample>,
) {
    private val collected = preSamples.toMutableList()
    var isComplete: Boolean = postRollUs <= 0L
        private set

    fun offer(sample: EncodedSample): Boolean {
        if (isComplete) return true
        if (sample.isCodecConfig) return false
        if (sample.generation != generation.id) {
            // Do not mix generations; close the clip with what we already have.
            isComplete = true
            return true
        }
        if (sample.ptsUs <= collected.last().ptsUs) return false
        val endPtsUs = tMarkUs + postRollUs
        if (sample.ptsUs <= endPtsUs) collected.add(sample)
        if (sample.ptsUs >= endPtsUs) {
            isComplete = true
        }
        return isComplete
    }

    fun finalizeNow(): ClipSnapshot {
        isComplete = true
        val samples = collected.toList()
        val firstPts = samples.firstOrNull()?.ptsUs ?: tMarkUs
        val lastPts = samples.lastOrNull()?.ptsUs ?: tMarkUs
        return ClipSnapshot(
            id = id,
            samples = samples,
            generation = generation,
            tMarkUs = tMarkUs,
            actualPreRollUs = (minOf(tMarkUs, lastPts) - firstPts).coerceAtLeast(0L),
            actualPostRollUs = (lastPts - tMarkUs).coerceAtLeast(0L),
        )
    }
}
