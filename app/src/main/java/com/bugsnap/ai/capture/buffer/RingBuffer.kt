package com.bugsnap.ai.capture.buffer

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-RAM ring of encoded samples. Evicts by GOP so the oldest retained
 * sample is always a keyframe. All public methods are thread-safe.
 *
 * Pre-roll cap is [maxDurationUs] (product max: 30 s). [marginUs] keeps one
 * extra GOP so a mark at the eviction edge still starts on a keyframe.
 */
class RingBuffer(
    private val maxDurationUs: Long = DEFAULT_PRE_ROLL_US,
    private val marginUs: Long = DEFAULT_MARGIN_US,
) {
    init {
        require(maxDurationUs > 0L) { "maxDurationUs must be positive" }
        require(maxDurationUs <= ABSOLUTE_MAX_PRE_ROLL_US) {
            "maxDurationUs exceeds the 30 s product cap"
        }
    }

    private val lock = ReentrantLock()
    private val samples = ArrayDeque<EncodedSample>()
    private val generations = LinkedHashMap<Int, Generation>()
    private val pending = ArrayList<PendingClip>()
    private var lastPtsUs: Long = -1L
    private var currentGenerationId: Int = -1

    fun registerGeneration(generation: Generation) {
        lock.withLock {
            generations[generation.id] = generation
            currentGenerationId = generation.id
            evictOtherGenerationsLocked(generation.id)
        }
    }

    fun append(sample: EncodedSample): List<ClipSnapshot> {
        if (sample.isCodecConfig) return emptyList()
        val completed = ArrayList<PendingClip>()
        lock.withLock {
            samples.addLast(sample)
            lastPtsUs = sample.ptsUs
            evictLocked()
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val clip = iterator.next()
                if (clip.offer(sample)) {
                    iterator.remove()
                    completed.add(clip)
                }
            }
        }
        return completed.map { it.finalizeNow() }
    }

    /**
     * Opens a new marker: snapshot pre-roll now, keep collecting post-roll.
     * Returns null if there is no keyframe in the current generation yet.
     */
    fun mark(
        id: String,
        tMarkUs: Long = lastPts(),
        preRollUs: Long = maxDurationUs,
        postRollUs: Long = DEFAULT_POST_ROLL_US,
    ): MarkResult {
        require(preRollUs <= ABSOLUTE_MAX_PRE_ROLL_US) { "pre-roll exceeds 30 s cap" }
        lock.withLock {
            val generation = generations[currentGenerationId]
                ?: return MarkResult.Rejected("no generation registered")
            val pre = preRollSamplesLocked(tMarkUs, preRollUs, generation.id)
                ?: return MarkResult.Rejected("no keyframe in current generation")
            val pendingClip = PendingClip(
                id = id,
                tMarkUs = tMarkUs,
                preRollUs = preRollUs,
                postRollUs = postRollUs,
                generation = generation,
                preSamples = pre,
            )
            if (pendingClip.isComplete) {
                return MarkResult.Ready(pendingClip.finalizeNow())
            }
            pending.add(pendingClip)
            return MarkResult.Pending(id)
        }
    }

    fun lastPts(): Long = lock.withLock { lastPtsUs }

    fun stats(): BufferStats = lock.withLock { statsLocked() }

    fun forceFinalizePending(): List<ClipSnapshot> {
        val clips: List<PendingClip>
        lock.withLock {
            clips = pending.toList()
            pending.clear()
        }
        return clips.map { it.finalizeNow() }
    }

    fun clear() {
        lock.withLock {
            samples.clear()
            generations.clear()
            pending.clear()
            lastPtsUs = -1L
            currentGenerationId = -1
        }
    }

    fun pendingCount(): Int = lock.withLock { pending.size }

    private fun preRollSamplesLocked(
        tMarkUs: Long,
        preRollUs: Long,
        generationId: Int,
    ): List<EncodedSample>? {
        val ofGen = samples.filter { it.generation == generationId && !it.isCodecConfig }
        if (ofGen.isEmpty()) return null
        val startBound = tMarkUs - preRollUs
        val keyframes = ofGen.filter { it.isKeyFrame }
        if (keyframes.isEmpty()) return null
        val startKey = keyframes.lastOrNull { it.ptsUs <= startBound } ?: keyframes.first()
        return ofGen.filter { it.ptsUs >= startKey.ptsUs && it.ptsUs <= tMarkUs }
    }

    private fun evictLocked() {
        if (samples.isEmpty() || lastPtsUs < 0L) return
        val cutoff = lastPtsUs - maxDurationUs - marginUs
        val firstEligibleKey = samples.lastOrNull { it.isKeyFrame && it.ptsUs <= cutoff }
            ?: return
        while (samples.isNotEmpty() && samples.first().ptsUs < firstEligibleKey.ptsUs) {
            samples.removeFirst()
        }
    }

    private fun evictOtherGenerationsLocked(keepGenerationId: Int) {
        val iterator = samples.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().generation != keepGenerationId) {
                iterator.remove()
            }
        }
        val stale = generations.keys.filter { it != keepGenerationId }
        stale.forEach { generations.remove(it) }
    }

    private fun statsLocked(): BufferStats {
        val duration = if (samples.size < 2) {
            0L
        } else {
            samples.last().ptsUs - samples.first().ptsUs
        }
        var ram = 0L
        var keys = 0
        for (sample in samples) {
            ram += sample.data.size
            if (sample.isKeyFrame) keys += 1
        }
        return BufferStats(
            sampleCount = samples.size,
            keyframeCount = keys,
            durationUs = duration,
            ramBytes = ram,
            lastPtsUs = lastPtsUs,
            currentGenerationId = currentGenerationId,
            pendingCount = pending.size,
        )
    }

    companion object {
        const val DEFAULT_PRE_ROLL_US = 30_000_000L
        const val ABSOLUTE_MAX_PRE_ROLL_US = 30_000_000L
        const val DEFAULT_MARGIN_US = 1_000_000L
        const val DEFAULT_POST_ROLL_US = 5_000_000L
    }
}

sealed class MarkResult {
    data class Pending(val id: String) : MarkResult()
    data class Ready(val snapshot: ClipSnapshot) : MarkResult()
    data class Rejected(val reason: String) : MarkResult()
}
