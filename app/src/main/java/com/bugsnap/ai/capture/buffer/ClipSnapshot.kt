package com.bugsnap.ai.capture.buffer

import java.util.Collections

/**
 * Immutable view of encoded samples for one Mark Bug event.
 * [samples] always starts at a keyframe and belongs to [generation] only.
 */
class ClipSnapshot(
    val id: String,
    samples: List<EncodedSample>,
    val generation: Generation,
    val tMarkUs: Long,
    val actualPreRollUs: Long,
    val actualPostRollUs: Long,
) {
    val samples: List<EncodedSample> = Collections.unmodifiableList(ArrayList(samples))

    init {
        require(id.isNotBlank())
        require(this.samples.isNotEmpty() && this.samples.first().isKeyFrame)
        require(this.samples.all { it.generation == generation.id && !it.isCodecConfig })
        require(this.samples.zipWithNext().all { (a, b) -> a.ptsUs < b.ptsUs })
        require(tMarkUs >= this.samples.first().ptsUs)
        require(actualPreRollUs == minOf(tMarkUs, this.samples.last().ptsUs) - this.samples.first().ptsUs)
        require(actualPostRollUs == (this.samples.last().ptsUs - tMarkUs).coerceAtLeast(0))
    }

    val actualPreRollMs: Long get() = actualPreRollUs / 1_000L
    val actualPostRollMs: Long get() = actualPostRollUs / 1_000L
    val durationUs: Long
        get() = if (samples.size < 2) 0L else samples.last().ptsUs - samples.first().ptsUs
}
