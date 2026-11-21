package com.bugsnap.ai.capture.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureConfigTest {
    @Test fun portraitAndLandscapeStayWithin1280AndAlignTo16() {
        val portrait = CaptureConfig.forDisplay(1080, 2400, 420)
        val landscape = CaptureConfig.forDisplay(2400, 1080, 420)
        assertEquals(portrait.width, landscape.height)
        assertEquals(portrait.height, landscape.width)
        assertEquals(1280, portrait.height)
        assertEquals(0, portrait.width % 16)
    }

    @Test fun smallDisplayIsNotUpscaled() {
        assertEquals(CaptureConfig(640, 480, 160), CaptureConfig.forDisplay(640, 480, 160))
    }

    @Test fun tinyAndExtremeAspectRatiosRemainValid() {
        val config = CaptureConfig.forDisplay(1, Int.MAX_VALUE, 160)
        assertEquals(16, config.width)
        assertEquals(1280, config.height)
    }

    @Test fun moderateAndSevereThermalReduceEncoderLoad() {
        for (thermal in 2..6) {
            val config = CaptureConfig.forDisplay(1080, 2400, 420, thermal)
            assertEquals(24, config.frameRate)
            assertEquals(3_000_000, config.bitrate)
        }
        assertEquals(30, CaptureConfig.forDisplay(1080, 2400, 420, 1).frameRate)
    }

    @Test fun densityChangeDoesNotRequireNewEncoderButBitrateDoes() {
        val config = CaptureConfig(1280, 720, 420)
        assertTrue(config.sameEncoder(config.copy(densityDpi = 320)))
        assertFalse(config.sameEncoder(config.copy(bitrate = 3_000_000)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDisplayIsRejected() { CaptureConfig.forDisplay(0, 2400, 420) }
}
