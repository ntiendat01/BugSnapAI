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
import org.junit.Assert.assertTrue
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
        var accepted = 0
        val overlay = SpikeOverlay(context, onMark = { accepted++; true }, onStop = {})
        val now = SystemClock.elapsedRealtime()
        val marker = SpikeMarker("sample", 3, now, now + 5000)
        val recording = SpikeUiState(phase = SpikePhase.Recording, nowMs = now, quickMark = true)
        fun renderAndCapture(state: SpikeUiState, name: String) {
            compose.runOnIdle { overlay.render(state) }
            compose.waitForIdle()
            EmulatorDialogs.dismissStartupError()
            SystemClock.sleep(400)
            val node = bubbleNode()
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val touchSize = (48 * context.resources.displayMetrics.density).toInt()
            assertEquals(touchSize, bounds.width())
            assertEquals(touchSize, bounds.height())
            assertTrue(nativeNodes().none { it.text?.toString() == "Xem clip" })
            saveBitmap(instrumentation.uiAutomation.takeScreenshot(), name)
        }
        try {
            renderAndCapture(recording, "08-compact-ready")
            val touchBounds = android.graphics.Rect()
            bubbleNode().getBoundsInScreen(touchBounds)
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    touchBounds.exactCenterX(), touchBounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            compose.runOnIdle { assertEquals(1, accepted) }
            renderAndCapture(recording.copy(markers = listOf(marker)), "09-compact-countdown")
            renderAndCapture(recording.copy(markers = listOf(marker.copy(stage = MarkerStage.Exporting))), "10-compact-saving")
            val saved = recording.copy(markers = listOf(marker.copy(stage = MarkerStage.Saved)),
                clips = listOf(SpikeClipInfo("sample", "sample.mp4", 35000, 30000, 5000, 6500, number = 3)))
            renderAndCapture(saved, "11-compact-saved")
            assertTrue(bubbleNode().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK))
            compose.waitUntil(5_000) { nativeNodes().any { it.text?.toString() == "Xem clip" } }
            val menu = nativeNodes().first { it.text?.toString() == "Xem clip" }
            val bounds = android.graphics.Rect()
            menu.getBoundsInScreen(bounds)
            assertTrue(bounds.width() <= (168 * context.resources.displayMetrics.density).toInt())
            SystemClock.sleep(400)
            saveBitmap(instrumentation.uiAutomation.takeScreenshot(), "12-compact-menu")
        } finally { compose.runOnIdle { overlay.dismiss() } }
    }

    private fun nativeNodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            result.add(node)
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        automation.windows.forEach { visit(it.root) }
        return result
    }

    private fun bubbleNode(): android.view.accessibility.AccessibilityNodeInfo = nativeNodes().first {
        it.contentDescription?.toString() == InstrumentationRegistry.getInstrumentation().targetContext.getString(com.bugsnap.ai.R.string.overlay_mark_description)
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
