package com.bugsnap.ai.spike

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/** Clears the emulator's initial System UI ANR dialog so it cannot obscure UI captures. */
internal object EmulatorDialogs {
    fun dismissStartupError() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val settings = automation.serviceInfo
        settings.flags = settings.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = settings
        val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
        for (root in roots) {
            if (root.findAccessibilityNodeInfosByText("isn't responding").isEmpty()) continue
            val node = root.findAccessibilityNodeInfosByText("Wait").firstOrNull() ?: continue
            var target: AccessibilityNodeInfo = node
            while (!target.isClickable && target.parent != null) target = target.parent
            android.util.Log.i("EmulatorDialogs", "Dismiss startup ANR: " + target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }
}
