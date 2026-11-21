package com.bugsnap.ai.spike

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import com.bugsnap.ai.MainActivity
import com.bugsnap.ai.R
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlin.math.abs

/** Separate touch windows keep the area around the bubble available to the tested app. */
class SpikeOverlay(
    private val context: Context,
    private val onMark: () -> Boolean,
    private val onStop: () -> Unit,
    private val onQuickMark: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private var bubble: LinearLayout? = null
    private var card: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var confirmation = false
    private var lastSignature = ""
    private var cardKey = ""
    private var visibleUntil = 0L
    private var lastState = SpikeUiState()
    private val prefs = context.getSharedPreferences("capture_controls", Context.MODE_PRIVATE)
    val isShowing: Boolean get() = bubble != null

    fun render(state: SpikeUiState) {
        lastState = state
        if (state.appVisible) { dismiss(); return }
        if (!Settings.canDrawOverlays(context)) { dismiss(); return }
        val active = state.phase == SpikePhase.Recording || state.phase == SpikePhase.Starting
        val pending = state.markers.any { it.pending }
        if (!active && !pending && state.markers.isEmpty() && state.phase != SpikePhase.ProjectionLost) {
            dismiss(); return
        }
        if (bubble == null) createBubble()
        val signature = "${state.phase}:${state.markers.lastOrNull()?.id}:${state.markers.lastOrNull()?.stage}:${state.lastError}"
        if (signature != lastSignature) {
            lastSignature = signature
            visibleUntil = SystemClock.elapsedRealtime() + timeout(4_000)
            expanded = false
        }
        val mark = bubble?.getChildAt(0) as? Button
        mark?.text = if (state.quickMark) context.getString(R.string.spike_mark) else ""
        mark?.setCompoundDrawablesWithIntrinsicBounds(if (state.quickMark) 0 else R.drawable.ic_mark_bug, 0, 0, 0)
        bubble?.getChildAt(1)?.visibility = if (state.quickMark) View.VISIBLE else View.GONE
        mark?.contentDescription = context.getString(if (state.quickMark) R.string.overlay_mark_description else R.string.overlay_controls)
        val terminal = state.markers.lastOrNull()?.stage in setOf(MarkerStage.Saved, MarkerStage.Failed)
        val showCard = expanded || pending || state.markers.any { it.stage == MarkerStage.Failed } || state.lastError != null ||
            state.phase == SpikePhase.ProjectionLost || SystemClock.elapsedRealtime() < visibleUntil
        if (expanded && SystemClock.elapsedRealtime() > visibleUntil && !accessibility.isEnabled) expanded = false
        if (showCard) {
            val key = "$signature:$expanded:$confirmation:${state.quickMark}:${if (expanded) 0 else state.markers.lastOrNull()?.remainingSeconds(state.nowMs)}"
            if (key != cardKey) { renderCard(state); cardKey = key }
        } else { removeCard(); cardKey = "" }
        mark?.isEnabled = active || terminal
    }

    private fun createBubble() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(R.string.overlay_controls) {
            if (lastState.quickMark && lastState.phase == SpikePhase.Recording) acceptMark(it)
            else { expanded = !expanded; confirmation = false; visibleUntil = SystemClock.elapsedRealtime() + timeout(3_000); render(lastState) }
        })
        row.addView(button(R.string.overlay_menu) {
            expanded = !expanded; confirmation = false; visibleUntil = SystemClock.elapsedRealtime() + timeout(3_000); render(lastState)
        }.apply { contentDescription = context.getString(R.string.overlay_controls) })
        val params = params(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = prefs.getInt(positionKey("x"), dp(16))
            y = prefs.getInt(positionKey("y"), dp(320)).coerceIn(safeTop(), (context.resources.displayMetrics.heightPixels - dp(120)).coerceAtLeast(safeTop()))
        }
        bubbleParams = params
        bubble = row
        attach(row, params)
        installDrag(row.getChildAt(0), params)
    }

    private fun renderCard(state: SpikeUiState) {
        removeCard()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = background(ContextCompat.getColor(context, R.color.bugsnap_surface))
        }
        val marker = state.markers.lastOrNull()
        val title = if (state.phase == SpikePhase.ProjectionLost) context.getString(R.string.spike_status_projection_lost)
            else SpikeFeedbackText.title(context, state)
        panel.addView(text(title, 16f, when (marker?.stage) {
            MarkerStage.Saved -> ContextCompat.getColor(context, R.color.bugsnap_success)
            MarkerStage.Failed -> ContextCompat.getColor(context, R.color.bugsnap_error)
            else -> ContextCompat.getColor(context, R.color.bugsnap_text)
        }))
        panel.addView(text(state.lastError ?: SpikeFeedbackText.detail(context, state), 14f, ContextCompat.getColor(context, R.color.bugsnap_text_muted)))
        if (marker?.stage == MarkerStage.Saved) {
            val clip = state.clips.find { it.id == marker.id }
            if (clip != null) panel.addView(button(R.string.capture_view_clip) { SpikeIntents.openClip(context, clip.clipPath) })
        }
        if (expanded) {
            if (state.phase == SpikePhase.Recording) panel.addView(button(R.string.spike_mark) { acceptMark(it) })
            panel.addView(button(R.string.capture_open_app) { openApp() })
            panel.addView(button(if (state.quickMark) R.string.quick_mark_disable else R.string.quick_mark_enable) {
                prefs.edit { putBoolean("quick_mark", !state.quickMark) }
                onQuickMark(!state.quickMark)
            })
            panel.addView(button(R.string.overlay_reset_position) {
                bubbleParams?.let { it.x = dp(16); it.y = dp(320); bubble?.let { view -> manager.updateViewLayout(view, it) } }
                render(lastState)
            })
            if (state.phase in setOf(SpikePhase.Recording, SpikePhase.Starting)) {
                panel.addView(button(if (confirmation) R.string.capture_stop_confirm else R.string.capture_stop) {
                    if (confirmation) onStop() else { confirmation = true; render(lastState) }
                })
            }
            panel.addView(button(R.string.capture_close) { expanded = false; visibleUntil = 0; render(lastState) })
        }
        val width = minOf(dp(264), screenWidth() - dp(32))
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = panel.measuredHeight.coerceAtMost((context.resources.displayMetrics.heightPixels - safeTop() - dp(64)).coerceAtLeast(dp(120)))
        val container = ScrollView(context).apply { addView(panel) }
        val params = params(width).apply {
            this.height = height
            x = (bubbleParams?.x ?: dp(16)).coerceAtMost((screenWidth() - width - dp(8)).coerceAtLeast(dp(8)))
            val maximumY = (context.resources.displayMetrics.heightPixels - height - dp(48)).coerceAtLeast(safeTop())
            y = ((bubbleParams?.y ?: dp(320)) - height - dp(12)).coerceIn(safeTop(), maximumY)
        }
        card = container
        attach(container, params)
    }

    private fun acceptMark(view: View) {
        if (onMark()) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            expanded = false
        } else view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }

    private fun installDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var dragging = false
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(context).scaledTouchSlop) dragging = true
                    if (dragging) {
                        params.x = (originX - dx.toInt()).coerceIn(dp(8), (screenWidth() - (bubble?.width ?: dp(160)) - dp(8)).coerceAtLeast(dp(8)))
                        params.y = (originY + dy.toInt()).coerceIn(safeTop(), (context.resources.displayMetrics.heightPixels - dp(120)).coerceAtLeast(safeTop()))
                        bubble?.let { manager.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) target.performClick()
                    else prefs.edit { putInt(positionKey("x"), params.x); putInt(positionKey("y"), params.y) }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
    }

    private fun params(width: Int) = WindowManager.LayoutParams(
        width, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.END }

    private fun attach(view: View, params: WindowManager.LayoutParams) {
        try { manager.addView(view, params) }
        catch (error: Exception) { SpikeLog.w("overlay unavailable", error) }
    }

    private fun button(label: Int, action: (View) -> Unit) = Button(context).apply {
        text = context.getString(label)
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.bugsnap_on_primary_overlay))
        backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bugsnap_primary))
        minHeight = dp(56)
        minWidth = dp(56)
        background = background(ContextCompat.getColor(context, R.color.bugsnap_primary)).apply { cornerRadius = dp(28).toFloat() }
        isAllCaps = false
        setOnClickListener(action)
    }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }
    private fun background(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
    private fun openApp() { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun screenWidth() = context.resources.displayMetrics.widthPixels
    private fun positionKey(axis: String) = "$axis-${context.resources.configuration.orientation}"
    private fun safeTop(): Int = if (Build.VERSION.SDK_INT >= 30) {
        manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).top + dp(12)
    } else dp(40)
    private fun timeout(value: Int): Int = accessibility.getRecommendedTimeoutMillis(value, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS)
    private fun removeCard() { card?.let { remove(it) }; card = null }
    private fun remove(view: View) { try { manager.removeView(view) } catch (error: Exception) { SpikeLog.w("overlay cleanup failed", error) } }
    fun dismiss() { removeCard(); bubble?.let { remove(it) }; bubble = null; bubbleParams = null }
}
