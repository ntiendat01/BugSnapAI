package com.bugsnap.ai.spike

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.bugsnap.ai.MainActivity
import com.bugsnap.ai.R

/** Automatic feedback stays inside one 48 dp window. The menu opens only on user request. */
class SpikeOverlay(
    private val context: Context,
    private val onMark: () -> Boolean,
    private val onStop: () -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private val prefs = context.getSharedPreferences("capture_controls", Context.MODE_PRIVATE)
    private var bubble: CompactMarkButton? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var expanded = false
    private var confirmation = false
    private var signature = ""
    private var panelKey = ""
    private var resultUntil = 0L
    private var menuUntil = 0L
    private var orientation = context.resources.configuration.orientation
    private var lastState = SpikeUiState()
    val isShowing: Boolean get() = bubble?.isAttachedToWindow == true

    fun render(state: SpikeUiState) {
        lastState = state
        val active = state.phase in setOf(SpikePhase.Starting, SpikePhase.Recording)
        if (state.appVisible || !Settings.canDrawOverlays(context) ||
            (!active && state.markers.isEmpty() && state.phase != SpikePhase.ProjectionLost)) {
            dismiss()
            return
        }
        if (orientation != context.resources.configuration.orientation) {
            dismiss()
            orientation = context.resources.configuration.orientation
        }
        if (bubble == null) createBubble()
        val now = SystemClock.elapsedRealtime()
        val current = "${state.phase}:${state.markers.lastOrNull()?.id}:${state.markers.lastOrNull()?.stage}:${state.lastError}"
        if (current != signature) {
            signature = current
            resultUntil = now + accessibility.getRecommendedTimeoutMillis(2_000, AccessibilityManager.FLAG_CONTENT_ICONS)
            closeMenu()
        }
        bubble?.update(state, now < resultUntil)
        if (expanded && now >= menuUntil && !accessibility.isEnabled) closeMenu()
        if (expanded) {
            val key = "$current:$confirmation"
            if (panelKey != key) { showMenu(); panelKey = key }
        }
    }

    private fun createBubble() {
        val view = CompactMarkButton(context).apply {
            setOnClickListener {
                if (lastState.quickMark && lastState.phase == SpikePhase.Recording) acceptMark(it)
                else toggleMenu()
            }
            setOnLongClickListener { toggleMenu(); true }
        }
        val params = params(dp(48)).apply {
            height = dp(48)
            x = prefs.getInt(positionKey("x"), dp(8)).coerceIn(dp(8), maximumX())
            y = prefs.getInt(positionKey("y"), screenHeight() * 2 / 5).coerceIn(safeTop(), maximumY())
        }
        if (!attach(view, params)) return
        bubble = view
        bubbleParams = params
        installGestures(view, params)
    }

    private fun toggleMenu() {
        if (expanded) closeMenu()
        else {
            expanded = true
            confirmation = false
            menuUntil = SystemClock.elapsedRealtime() + accessibility.getRecommendedTimeoutMillis(3_000, AccessibilityManager.FLAG_CONTENT_CONTROLS)
            panelKey = ""
            render(lastState)
        }
    }

    private fun showMenu() {
        removePanel()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = background(R.color.bugsnap_surface, 16)
        }
        if (!lastState.quickMark && lastState.phase == SpikePhase.Recording) {
            content.addView(button(R.string.spike_mark, primary = true) { acceptMark(it) })
        }
        val actions = mutableListOf<Button>()
        val latestClip = lastState.clips.firstOrNull()
        if (latestClip != null) actions.add(button(R.string.capture_view_clip) {
            closeMenu()
            SpikeIntents.openClip(context, latestClip.clipPath)
        })
        actions.add(button(R.string.overlay_open_app_short) { closeMenu(); openApp() })
        if (lastState.phase in setOf(SpikePhase.Recording, SpikePhase.Starting)) {
            actions.add(button(if (confirmation) R.string.overlay_stop_confirm_short else R.string.spike_stop) {
                if (confirmation) { closeMenu(); onStop() }
                else {
                    confirmation = true
                    menuUntil = SystemClock.elapsedRealtime() + accessibility.getRecommendedTimeoutMillis(3_000, AccessibilityManager.FLAG_CONTENT_CONTROLS)
                    panelKey = ""
                    render(lastState)
                }
            }.apply { contentDescription = context.getString(if (confirmation) R.string.capture_stop_confirm else R.string.capture_stop) })
        }
        actions.add(button(R.string.capture_close) { closeMenu() })
        actions.chunked(2).forEach { buttons ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.forEachIndexed { index, action ->
                row.addView(action, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0 && buttons.size == 2) marginEnd = dp(8)
                    bottomMargin = dp(8)
                })
            }
            content.addView(row)
        }
        val width = minOf(dp(168), screenWidth() - dp(32))
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val bubbleY = bubbleParams?.y ?: safeTop()
        val spaceAbove = (bubbleY - safeTop() - dp(8)).coerceAtLeast(0)
        val spaceBelow = (screenHeight() - dp(40) - bubbleY - dp(56)).coerceAtLeast(0)
        val height = content.measuredHeight.coerceAtMost(maxOf(spaceAbove, spaceBelow).coerceAtLeast(dp(48)))
        val container = ScrollView(context).apply { addView(content) }
        val params = params(width).apply {
            this.height = height
            x = (bubbleParams?.x ?: dp(8)).coerceIn(dp(8), (screenWidth() - width - dp(8)).coerceAtLeast(dp(8)))
            val anchorY = if (spaceAbove >= height) bubbleY - height - dp(8) else bubbleY + dp(56)
            y = anchorY.coerceIn(safeTop(), (screenHeight() - height - dp(40)).coerceAtLeast(safeTop()))
        }
        if (attach(container, params)) panel = container
    }

    private fun acceptMark(view: View) {
        val feedbackTarget = bubble ?: view
        closeMenu()
        val accepted = onMark()
        feedbackTarget.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) {
            if (accepted) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
        } else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // GestureDetector invokes performClick in onSingleTapUp; the injected-touch UI test covers this path.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installGestures(view: View, params: WindowManager.LayoutParams) {
        var originX = 0
        var originY = 0
        var dragging = false
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                originX = params.x; originY = params.y; dragging = false
                view.isPressed = true
                view.invalidate()
                return true
            }
            override fun onSingleTapUp(event: MotionEvent): Boolean = view.performClick()
            override fun onLongPress(event: MotionEvent) { view.performLongClick() }
            override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (first == null) return false
                dragging = true
                closeMenu()
                params.x = (originX - (current.rawX - first.rawX).toInt()).coerceIn(dp(8), maximumX())
                params.y = (originY + (current.rawY - first.rawY).toInt()).coerceIn(safeTop(), maximumY())
                manager.updateViewLayout(view, params)
                return true
            }
        })
        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                view.isPressed = false
                view.invalidate()
                if (dragging) {
                    params.x = if (params.x < maximumX() / 2) dp(8) else maximumX()
                    manager.updateViewLayout(view, params)
                    prefs.edit { putInt(positionKey("x"), params.x); putInt(positionKey("y"), params.y) }
                }
            }
            true
        }
    }

    private fun button(label: Int, primary: Boolean = false, action: (View) -> Unit) = Button(context).apply {
        text = context.getString(label)
        textSize = 14f
        isAllCaps = false
        minWidth = 0
        minHeight = dp(48)
        minimumWidth = 0
        minimumHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, if (primary) R.color.bugsnap_primary else R.color.bugsnap_surface))
        setTextColor(ContextCompat.getColor(context, if (primary) R.color.bugsnap_on_primary_overlay else R.color.bugsnap_text))
        setOnClickListener(action)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        }
    }

    private fun background(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, color)); cornerRadius = dp(radius).toFloat()
    }
    private fun params(width: Int) = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END }
    private fun attach(view: View, params: WindowManager.LayoutParams): Boolean = try {
        manager.addView(view, params); true
    } catch (error: Exception) { SpikeLog.w("overlay unavailable", error); false }
    private fun remove(view: View) { try { manager.removeView(view) } catch (error: Exception) { SpikeLog.w("overlay cleanup failed", error) } }
    private fun removePanel() { panel?.let { remove(it) }; panel = null }
    private fun closeMenu() { expanded = false; confirmation = false; panelKey = ""; removePanel() }
    private fun openApp() { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun screenWidth() = context.resources.displayMetrics.widthPixels
    private fun screenHeight() = context.resources.displayMetrics.heightPixels
    private fun maximumX() = (screenWidth() - dp(56)).coerceAtLeast(dp(8))
    private fun maximumY() = (screenHeight() - dp(88)).coerceAtLeast(safeTop())
    private fun positionKey(axis: String) = "$axis-${context.resources.configuration.orientation}"
    private fun safeTop(): Int = if (Build.VERSION.SDK_INT >= 30) {
        manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).top + dp(8)
    } else dp(40)
    fun dismiss() { closeMenu(); bubble?.let { remove(it) }; bubble = null; bubbleParams = null }
}
