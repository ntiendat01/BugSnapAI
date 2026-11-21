package com.bugsnap.ai.spike

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.bugsnap.ai.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.io.File
import com.bugsnap.ai.data.media.VideoGalleryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Smoke test through the real permission dialog, encoder, post-roll and file export. */
class CaptureFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun dismissEmulatorStartupDialog() { EmulatorDialogs.dismissStartupError() }

    @Test fun capturesAndSavesRealVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.executeShellCommand("pm grant com.bugsnap.ai android.permission.POST_NOTIFICATIONS").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
        compose.onNodeWithText("Bắt đầu quay").performClick()
        compose.waitUntil(15_000) { automation.rootInActiveWindow?.packageName == "com.android.systemui" }
        var root = automation.rootInActiveWindow
        val text = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.let { text.add(it.toString()) }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(root)
        android.util.Log.i("CaptureFlowTest", text.joinToString(" | "))
        fun clickText(label: String): Boolean {
            root = automation.rootInActiveWindow
            var node = root?.findAccessibilityNodeInfosByText(label)?.firstOrNull() ?: return false
            while (!node.isClickable && node.parent != null) node = node.parent
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Android's consent sheet may initially offer a single app. Select the whole screen for this smoke test.
        if (clickText("Share one app") || clickText("A single app") || clickText("A single application")) {
            compose.waitUntil(5_000) { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("entire screen")?.isNotEmpty() == true || automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Entire screen")?.isNotEmpty() == true }
            clickText("Share entire screen") || clickText("Entire screen")
        }
        text.clear()
        visit(automation.rootInActiveWindow)
        android.util.Log.i("CaptureFlowTest", "After screen choice: " + text.joinToString(" | "))
        val consent = clickText("Start recording") || clickText("Start now") || clickText("Start sharing") || clickText("Share screen") || clickText("Start")
        assertTrue("Consent button not found: $text", consent)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Đang quay").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mark Bug").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Đã đánh dấu #01").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Đang lưu clip #01…").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Đã lưu clip #01").fetchSemanticsNodes().isNotEmpty()
        }
        saveScreenshot("06-real-mark")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Đã lưu clip #01").fetchSemanticsNodes().isNotEmpty() }
        saveScreenshot("07-real-saved")
        val context = instrumentation.targetContext
        val gallery = VideoGalleryStore(context, Dispatchers.IO)
        val clips = runBlocking(Dispatchers.IO) { SpikeClipIndex(context, gallery).load() }
        val latest = clips.maxByOrNull { it.savedAtMs }
        assertTrue("No DCIM video saved", latest != null && VideoGalleryStore.isVideoUri(latest.clipPath))
        assertTrue(runBlocking { gallery.contains(latest!!.clipPath).getOrNull() == true })
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, android.net.Uri.parse(latest!!.clipPath))
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            assertTrue("DCIM video has no playable duration", duration > 0)
        } finally {
            retriever.release()
        }
        compose.onNodeWithText("Dừng quay").performClick()
        val stopButtons = compose.onAllNodesWithText("Dừng quay")
        stopButtons[stopButtons.fetchSemanticsNodes().lastIndex].performClick()
        compose.waitUntil(10_000) {
            instrumentation.targetContext.getSystemService(android.app.NotificationManager::class.java)
                .activeNotifications.none { it.id == SpikeNotifications.CAPTURE_ID }
        }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        // The emulator compositor can present a frame after Compose has updated its semantics.
        android.os.SystemClock.sleep(1_000)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "ui-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
