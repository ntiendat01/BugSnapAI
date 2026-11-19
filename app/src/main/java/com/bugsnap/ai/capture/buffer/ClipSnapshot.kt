package com.bugsnap.ai.capture.buffer

/**
 * Immutable view of encoded samples for one Mark Bug event.
 * [samples] always starts at a keyframe and belongs to [generation] only.
 */
class ClipSnapshot(
    val id: String,
    val samples: List<EncodedSample>,
    val generation: Generation,
    val tMarkUs: Long,
    val actualPreRollUs: Long,
    val actualPostRollUs: Long,
) {
    val actualPreRollMs: Long get() = actualPreRollUs / 1_000L
    val actualPostRollMs: Long get() = actualPostRollUs / 1_000L
    val durationUs: Long
        get() = if (samples.size < 2) 0L else samples.last().ptsUs - samples.first().ptsUs
}
