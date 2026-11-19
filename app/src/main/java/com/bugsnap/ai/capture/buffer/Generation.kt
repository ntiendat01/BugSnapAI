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
    val csd0: ByteArray,
    val csd1: ByteArray?,
    val startPtsUs: Long,
)
