package com.bugsnap.ai.capture.buffer

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe ring of immutable encoded samples. Only complete, decodable GOP prefixes
 * remain eligible for a mark. Duration and pre/post-roll limits are strict PTS bounds.
 */
class RingBuffer(
    private val maxDurationUs: Long = DEFAULT_PRE_ROLL_US,
    private val marginUs: Long = DEFAULT_MARGIN_US,
) {
    init {
        require(maxDurationUs in 1..ABSOLUTE_MAX_PRE_ROLL_US) { "Buffer must be within 30 seconds" }
        require(marginUs >= 0 && marginUs <= Long.MAX_VALUE - maxDurationUs) { "Invalid GOP margin" }
    }

    private val lock = ReentrantLock()
    private val samples = ArrayDeque<EncodedSample>()
    private val pending = ArrayList<PendingClip>()
    private var generation: Generation? = null
    private var lastPtsUs: Long = -1L

    /**
     * IDs increase within a stream. Stale/reused IDs cannot replace a newer configuration.
     * Pending old-generation clips close on the first new sample, or forceFinalizePending.
     */
    fun registerGeneration(next: Generation): Boolean = lock.withLock {
        val current = generation
        if (current != null && next.id <= current.id) return next.sameConfiguration(current)
        generation = next
        samples.clear()
        lastPtsUs = -1L
        true
    }

    /** Stale, duplicate, backwards, empty and codec-config samples do not enter the ring. */
    fun append(sample: EncodedSample): List<ClipSnapshot> = lock.withLock {
        val current = generation ?: return emptyList()
        if (sample.generation != current.id || sample.isCodecConfig || sample.sizeBytes == 0 ||
            sample.ptsUs < current.startPtsUs || sample.ptsUs <= lastPtsUs
        ) return emptyList()
        lastPtsUs = sample.ptsUs
        if (samples.isNotEmpty() || sample.isKeyFrame) samples.addLast(sample)
        evictLocked()
        val completed = ArrayList<ClipSnapshot>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val clip = iterator.next()
            if (clip.offer(sample)) {
                iterator.remove()
                completed.add(clip.finalizeNow())
            }
        }
        completed
    }

    /** A null mark time samples the latest accepted PTS while holding the same lock. */
    fun mark(
        id: String,
        tMarkUs: Long? = null,
        preRollUs: Long = maxDurationUs,
        postRollUs: Long = DEFAULT_POST_ROLL_US,
    ): MarkResult = lock.withLock {
        if (id.isBlank() || pending.any { it.id == id }) return MarkResult.Rejected("invalid or pending marker ID")
        if (preRollUs !in 0..maxDurationUs || postRollUs !in 0..MAX_POST_ROLL_US) {
            return MarkResult.Rejected("invalid pre/post-roll")
        }
        val current = generation ?: return MarkResult.Rejected("no generation registered")
        val markPts = tMarkUs ?: lastPtsUs
        if (markPts < 0 || markPts > lastPtsUs || markPts > Long.MAX_VALUE - postRollUs) {
            return MarkResult.Rejected("mark PTS is outside the captured timeline")
        }
        val available = samples.toList()
        val startBound = (markPts - preRollUs).coerceAtLeast(0)
        val start = available.indexOfFirst { it.isKeyFrame && it.ptsUs >= startBound && it.ptsUs <= markPts }
        if (start < 0) return MarkResult.Rejected("no keyframe within requested pre-roll")
        val selected = available.drop(start)
        val clip = PendingClip(
            id = id, tMarkUs = markPts, postRollUs = postRollUs, generation = current,
            preSamples = selected.takeWhile { it.ptsUs <= markPts },
        )
        // Explicit historical marks also collect post-roll which is already in the ring.
        for (sample in selected) {
            if (sample.ptsUs > markPts && clip.offer(sample)) break
        }
        if (clip.isComplete) MarkResult.Ready(clip.finalizeNow()) else {
            pending.add(clip)
            MarkResult.Pending(id)
        }
    }

    fun lastPts(): Long = lock.withLock { lastPtsUs }

    fun stats(): BufferStats = lock.withLock {
        BufferStats(
            sampleCount = samples.size,
            keyframeCount = samples.count { it.isKeyFrame },
            durationUs = if (samples.size < 2) 0 else samples.last().ptsUs - samples.first().ptsUs,
            ramBytes = samples.sumOf { it.sizeBytes.toLong() },
            lastPtsUs = lastPtsUs,
            currentGenerationId = generation?.id ?: -1,
            pendingCount = pending.size,
        )
    }

    fun forceFinalizePending(): List<ClipSnapshot> = lock.withLock {
        pending.map { it.finalizeNow() }.also { pending.clear() }
    }

    /** Drops ring and pending clips. Call forceFinalizePending first when stopping capture. */
    fun clear() = lock.withLock {
        samples.clear()
        pending.clear()
        generation = null
        lastPtsUs = -1L
    }

    fun pendingCount(): Int = lock.withLock { pending.size }

    private fun evictLocked() {
        val cutoff = (lastPtsUs - (maxDurationUs + marginUs)).coerceAtLeast(0)
        // Evict whole GOPs. If no usable keyframe remains, wait for a new keyframe rather
        // than retain a broken or unbounded GOP when an encoder stops emitting sync frames.
        while (samples.isNotEmpty() && samples.first().ptsUs < cutoff) samples.removeFirst()
        while (samples.isNotEmpty() && !samples.first().isKeyFrame) samples.removeFirst()
    }

    companion object {
        const val DEFAULT_PRE_ROLL_US = 30_000_000L
        const val ABSOLUTE_MAX_PRE_ROLL_US = 30_000_000L
        const val DEFAULT_MARGIN_US = 1_000_000L
        const val DEFAULT_POST_ROLL_US = 5_000_000L
        const val MAX_POST_ROLL_US = 10_000_000L
    }
}

sealed class MarkResult {
    data class Pending(val id: String) : MarkResult()
    data class Ready(val snapshot: ClipSnapshot) : MarkResult()
    data class Rejected(val reason: String) : MarkResult()
}
