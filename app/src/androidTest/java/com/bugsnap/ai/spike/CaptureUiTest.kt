package com.bugsnap.ai.spike

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.bugsnap.ai.ui.theme.BugSnapTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.io.File

/** Rendered Android screenshots with explicit sample data for repeatable UI review. */
class CaptureUiTest {
    @get:Rule val compose = createComposeRule()

    @Before fun dismissEmulatorStartupDialog() { EmulatorDialogs.dismissStartupError() }

    @Test fun readyAndMarkToSavedStates() {
        val state = mutableStateOf(SpikeUiState())
        compose.setContent {
            BugSnapTheme {
                SpikeCaptureContent(state.value,
                    onStart = { state.value = SpikeUiState(phase = SpikePhase.Recording, bufferDurationMs = 30_000, recordingElapsedMs = 134_000) },
                    onMark = { state.value = state.value.copy(markers = listOf(SpikeMarker("sample", 3, 1000, 6000)), nowMs = 1000) },
                )
            }
        }
        screenshot("01-ready")
        compose.onNodeWithText("Bắt đầu quay").performClick()
        compose.onNodeWithText("Mark Bug").performClick()
        compose.onNodeWithText("Ghi thêm 5 giây").assertExists()
        screenshot("02-mark-accepted")
        compose.runOnIdle {
            val clip = SpikeClipInfo("sample", "sample.mp4", 35000, 30000, 5000, 6500, number = 3)
            state.value = state.value.copy(markers = state.value.markers.map { it.copy(stage = MarkerStage.Saved) }, clips = listOf(clip))
        }
        compose.onNodeWithText("Đã lưu clip #03").assertExists()
        screenshot("03-clip-saved")
        assertEquals(1, state.value.clips.size)
    }

    @Test fun nativeOverlayShowsAcceptedAndSavedFeedback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        compose.setContent { BugSnapTheme { SpikeCaptureContent(SpikeUiState(phase = SpikePhase.Recording)) } }
        val overlay = SpikeOverlay(context, onMark = { true }, onStop = {}, onQuickMark = {})
        val now = SystemClock.elapsedRealtime()
        val marker = SpikeMarker("sample", 3, now, now + 5000)
        try {
            compose.runOnIdle { overlay.render(SpikeUiState(phase = SpikePhase.Recording, markers = listOf(marker), nowMs = now, quickMark = true)) }
            compose.waitForIdle()
            EmulatorDialogs.dismissStartupError()
            saveBitmap(instrumentation.uiAutomation.takeScreenshot(), "04-overlay-mark-accepted")
            compose.runOnIdle {
                overlay.render(SpikeUiState(phase = SpikePhase.Recording, markers = listOf(marker.copy(stage = MarkerStage.Saved)),
                    clips = listOf(SpikeClipInfo("sample", "sample.mp4", 35000, 30000, 5000, 6500, number = 3)), nowMs = now + 6500, quickMark = true))
            }
            compose.waitForIdle()
            EmulatorDialogs.dismissStartupError()
            saveBitmap(instrumentation.uiAutomation.takeScreenshot(), "05-overlay-clip-saved")
        } finally { compose.runOnIdle { overlay.dismiss() } }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        saveBitmap(compose.onRoot().captureToImage().asAndroidBitmap(), name)
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
