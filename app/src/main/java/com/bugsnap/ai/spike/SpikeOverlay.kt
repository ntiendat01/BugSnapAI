package com.bugsnap.ai.spike

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bugsnap.ai.R

/**
 * Tiny in-display badge used only for V2/V3 (does the overlay appear in the clip?).
 * Requires SYSTEM_ALERT_WINDOW; if missing, the spike UI banner is the fallback.
 */
class SpikeOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (!Settings.canDrawOverlays(context)) {
            SpikeLog.i("overlay skipped: SYSTEM_ALERT_WINDOW not granted")
            return
        }
        if (view != null) return
        val density = context.resources.displayMetrics.density
        val size = (48 * density).toInt()
        val badge = TextView(context).apply {
            text = context.getString(R.string.spike_overlay_label)
            setTextColor(ContextCompat.getColor(context, R.color.bugsnap_on_primary_overlay))
            setBackgroundColor(ContextCompat.getColor(context, R.color.bugsnap_primary))
            textSize = 14f
            gravity = Gravity.CENTER
            alpha = 0.85f
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.y = (48 * density).toInt()
        params.x = (8 * density).toInt()
        windowManager.addView(badge, params)
        view = badge
        SpikeLog.i("overlay shown")
    }

    fun dismiss() {
        val current = view ?: return
        try {
            windowManager.removeView(current)
        } catch (error: Exception) {
            SpikeLog.w("overlay remove failed", error)
        }
        view = null
        SpikeLog.i("overlay dismissed")
    }
}
