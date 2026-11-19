package com.bugsnap.ai.capture.buffer

data class BufferStats(
    val sampleCount: Int,
    val keyframeCount: Int,
    val durationUs: Long,
    val ramBytes: Long,
    val lastPtsUs: Long,
    val currentGenerationId: Int,
    val pendingCount: Int,
)
