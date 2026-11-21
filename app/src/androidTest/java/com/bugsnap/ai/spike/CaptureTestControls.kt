package com.bugsnap.ai.spike

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

internal object CaptureTestControls {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    fun grantNotifications() {
        automation.executeShellCommand("pm grant com.bugsnap.ai android.permission.POST_NOTIFICATIONS").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }

    fun acceptConsent() {
        waitFor(20_000) { automation.rootInActiveWindow?.packageName == "com.android.systemui" }
        if (click("Share one app") || click("A single app") || click("A single application")) {
            waitFor(5_000) { hasText("entire screen") || hasText("Entire screen") }
            assertTrue(click("Share entire screen") || click("Entire screen"))
        }
        assertTrue("MediaProjection consent button missing", click("Start recording") || click("Start now") ||
            click("Start sharing") || click("Share screen") || click("Start"))
    }

    fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Condition not reached after $timeoutMs ms", condition())
    }

    private fun hasText(text: String) = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true

    private fun click(text: String): Boolean {
        var node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: return false
        while (!node.isClickable && node.parent != null) node = node.parent
        return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }
}
