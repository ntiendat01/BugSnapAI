package com.bugsnap.ai.spike

import kotlin.math.max
import kotlin.math.roundToInt

object EncodeSizing {
    const val LONG_EDGE = 1280
    const val BITRATE_BPS = 4_000_000
    const val FRAME_RATE = 30
    const val I_FRAME_INTERVAL_SEC = 1
    const val REPEAT_PREVIOUS_FRAME_US = 1_000_000

    fun forDisplay(rawWidth: Int, rawHeight: Int, longEdge: Int = LONG_EDGE): Pair<Int, Int> {
        val safeW = rawWidth.coerceAtLeast(16)
        val safeH = rawHeight.coerceAtLeast(16)
        val longest = max(safeW, safeH).toFloat()
        val scale = longEdge / longest
        val width = align16((safeW * scale).roundToInt())
        val height = align16((safeH * scale).roundToInt())
        return width to height
    }

    private fun align16(value: Int): Int {
        val aligned = (value / 16) * 16
        return aligned.coerceAtLeast(16)
    }
}
