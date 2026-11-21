package com.bugsnap.ai.capture.engine

import kotlin.math.roundToInt

data class CaptureConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val frameRate: Int = 30,
    val bitrate: Int = 4_000_000,
) {
    init {
        require(width > 0 && height > 0 && width % 16 == 0 && height % 16 == 0)
        require(densityDpi > 0 && frameRate > 0 && bitrate > 0)
    }

    fun sameEncoder(other: CaptureConfig): Boolean = copy(densityDpi = other.densityDpi) == other

    companion object {
        const val MODERATE_THERMAL = 2
        const val SEVERE_THERMAL = 3

        fun forDisplay(width: Int, height: Int, densityDpi: Int, thermalStatus: Int = 0): CaptureConfig {
            require(width > 0 && height > 0 && densityDpi > 0)
            val scale = minOf(1.0, 1280.0 / maxOf(width, height))
            fun aligned(value: Int) = ((value * scale).roundToInt() / 16 * 16).coerceAtLeast(16)
            val reduced = thermalStatus >= MODERATE_THERMAL
            return CaptureConfig(aligned(width), aligned(height), densityDpi,
                frameRate = if (reduced) 24 else 30, bitrate = if (reduced) 3_000_000 else 4_000_000)
        }
    }
}

enum class CapturePhase { IDLE, STARTING, RECORDING, PAUSED, STOPPING, STOPPED, FAILED }

data class CaptureEngineState(
    val phase: CapturePhase = CapturePhase.IDLE,
    val config: CaptureConfig? = null,
    val generation: Int = -1,
    val thermalStatus: Int = 0,
    val error: com.bugsnap.ai.core.common.DomainError? = null,
    val cleanupError: com.bugsnap.ai.core.common.DomainError? = null,
)
