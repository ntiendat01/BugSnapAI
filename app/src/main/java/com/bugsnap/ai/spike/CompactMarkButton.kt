package com.bugsnap.ai.spike

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.bugsnap.ai.R

/** A 36 dp visual in a fixed 48 dp touch window, independent of the device button theme. */
class CompactMarkButton(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressBounds = RectF()
    private val tick = Path()
    private val icon = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_mark_bug))
    private var state = SpikeUiState()
    private var showResult = false

    init {
        isClickable = true
        isLongClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun update(value: SpikeUiState, resultVisible: Boolean) {
        state = value
        showResult = resultVisible
        val action = context.getString(if (state.quickMark) R.string.overlay_mark_description else R.string.overlay_controls)
        val detail = SpikeFeedbackText.title(context, state) + ". " + SpikeFeedbackText.detail(context, state)
        contentDescription = if (Build.VERSION.SDK_INT >= 30) action else "$action. $detail"
        if (Build.VERSION.SDK_INT >= 30) stateDescription = detail
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.ACTION_LONG_CLICK, context.getString(R.string.overlay_controls),
        ))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val latest = state.markers.lastOrNull()
        val pending = state.markers.lastOrNull { it.pending }
        val marker = if (showResult) latest else pending
        val error = state.phase == SpikePhase.ProjectionLost || state.lastError != null || marker?.stage == MarkerStage.Failed
        val saving = marker?.stage in setOf(MarkerStage.Queued, MarkerStage.Exporting) || state.phase == SpikePhase.Starting
        val collecting = marker?.stage == MarkerStage.Collecting
        val saved = marker?.stage == MarkerStage.Saved
        val stopped = state.phase == SpikePhase.Stopped && pending == null
        val color = when {
            error -> R.color.bugsnap_error
            saved -> R.color.bugsnap_success
            saving || collecting || stopped -> R.color.bugsnap_surface
            else -> R.color.bugsnap_primary
        }
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, color)
        paint.alpha = if (!isPressed && marker == null && !error) 205 else 255
        canvas.drawCircle(cx, cy, dp(if (isPressed) 19f else 18f), paint)
        paint.alpha = 255
        if (saving || collecting) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = ContextCompat.getColor(context, R.color.bugsnap_primary)
            val radius = dp(19f)
            progressBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            val animate = saving && ValueAnimator.areAnimatorsEnabled()
            val start = if (animate) SystemClock.uptimeMillis() % 1_200 / 1_200f * 360 else -90f
            val sweep = if (collecting) (marker.remainingSeconds(state.nowMs) / 5f * 360).coerceIn(0f, 360f) else 250f
            canvas.drawArc(progressBounds, start, sweep, false, paint)
            if (animate && isAttachedToWindow) postInvalidateOnAnimation()
        }
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, if (saving || collecting || (stopped && !saved)) R.color.bugsnap_text else R.color.bugsnap_on_primary_overlay)
        when {
            error -> drawGlyph(canvas, "!", cx, cy)
            collecting -> drawGlyph(canvas, marker.remainingSeconds(state.nowMs).toString(), cx, cy)
            saved -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2.5f)
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                tick.reset()
                tick.moveTo(cx - dp(7f), cy)
                tick.lineTo(cx - dp(2f), cy + dp(5f))
                tick.lineTo(cx + dp(8f), cy - dp(6f))
                canvas.drawPath(tick, paint)
            }
            stopped -> canvas.drawRect(cx - dp(4f), cy - dp(4f), cx + dp(4f), cy + dp(4f), paint)
            !saving -> {
                val half = dp(10f).toInt()
                icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
                icon.draw(canvas)
            }
        }
    }

    private fun drawGlyph(canvas: Canvas, value: String, cx: Float, cy: Float) {
        // Keep the glyph inside the icon; the full status remains available to TalkBack.
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics).coerceAtMost(dp(24f))
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(value, cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
