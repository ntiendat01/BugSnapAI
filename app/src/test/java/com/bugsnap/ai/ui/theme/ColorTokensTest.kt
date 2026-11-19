package com.bugsnap.ai.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorTokensTest {
    @Test
    fun darkTokensMatchUiSpec() {
        assertEquals(0xFF0B1220.toInt(), BugSnapColors.Background.toArgb())
        assertEquals(0xFF111B2E.toInt(), BugSnapColors.Surface.toArgb())
        assertEquals(0xFF1A2740.toInt(), BugSnapColors.SurfaceVariant.toArgb())
        assertEquals(0xFF22D3EE.toInt(), BugSnapColors.Primary.toArgb())
        assertEquals(0xFF04222A.toInt(), BugSnapColors.OnPrimary.toArgb())
        assertEquals(0xFFF87171.toInt(), BugSnapColors.Danger.toArgb())
        assertEquals(0xFFFBBF24.toInt(), BugSnapColors.Warning.toArgb())
        assertEquals(0xFF34D399.toInt(), BugSnapColors.Success.toArgb())
        assertEquals(0xFFE6EDF7.toInt(), BugSnapColors.Text.toArgb())
        assertEquals(0xFF93A4BD.toInt(), BugSnapColors.TextMuted.toArgb())
    }
}
