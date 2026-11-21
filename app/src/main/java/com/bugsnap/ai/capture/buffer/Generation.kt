package com.bugsnap.ai.capture.buffer

/**
 * One encoder configuration (resolution / CSD). A clip may only contain
 * samples from a single generation (see docs/07).
 */
class Generation(
    val id: Int,
    val width: Int,
    val height: Int,
    val mime: String,
    csd0: ByteArray,
    csd1: ByteArray?,
    val startPtsUs: Long,
) {
    private val sps = csd0.copyOf()
    private val pps = csd1?.copyOf()
    val csd0: ByteArray get() = sps.copyOf()
    val csd1: ByteArray? get() = pps?.copyOf()

    init {
        require(id >= 0 && width > 0 && height > 0 && startPtsUs >= 0)
        require(mime.isNotBlank() && sps.isNotEmpty())
    }

    internal fun sameConfiguration(other: Generation): Boolean =
        id == other.id && width == other.width && height == other.height &&
            mime == other.mime && startPtsUs == other.startPtsUs &&
            sps.contentEquals(other.sps) && pps.contentEquals(other.pps)
}
