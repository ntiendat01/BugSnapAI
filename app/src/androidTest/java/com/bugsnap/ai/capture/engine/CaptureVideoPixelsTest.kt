package com.bugsnap.ai.capture.engine

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.bugsnap.ai.MainActivity
import com.bugsnap.ai.spike.CaptureTestControls
import com.bugsnap.ai.spike.EmulatorDialogs
import com.bugsnap.ai.spike.SpikeCaptureService
import com.bugsnap.ai.spike.SpikeClipInfo
import com.bugsnap.ai.spike.SpikePhase
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Verifies decoded pixels, not only MP4 duration, across two marks while recording stays active. */
class CaptureVideoPixelsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun dismissStartupError() { EmulatorDialogs.dismissStartupError() }

    @Test fun clipsHaveMovingNonBlackFramesThroughPostRollAndRecordingContinues() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val diagnostics = EntryPointAccessors.fromApplication(context, CaptureDiagnostics::class.java)
        val session = diagnostics.session()
        var pattern: PatternView? = null
        CaptureTestControls.grantNotifications()
        try {
            compose.onNodeWithText("Bắt đầu quay").performClick()
            CaptureTestControls.acceptConsent()
            CaptureTestControls.waitFor(30_000) { session.state.value.phase == SpikePhase.Recording }
            instrumentation.runOnMainSync {
                pattern = PatternView(compose.activity)
                compose.activity.addContentView(pattern, ViewGroup.LayoutParams(-1, -1))
            }
            fun color(value: Int) = instrumentation.runOnMainSync { pattern!!.color = value; pattern!!.invalidate() }
            fun capture(before: Int, middle: Int, end: Int): SpikeClipInfo {
                color(before)
                SystemClock.sleep(3_000)
                val proof = File(context.filesDir, "capture-pixel-proof").apply { mkdirs() }
                instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                    try { File(proof, "source-$before.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { image.recycle() }
                }
                val previous = session.state.value.markers.map { it.id }.toSet()
                context.startService(Intent(context, SpikeCaptureService::class.java).setAction(SpikeCaptureService.ACTION_MARK))
                CaptureTestControls.waitFor(5_000) { session.state.value.markers.any { it.id !in previous } }
                val marker = session.state.value.markers.first { it.id !in previous }
                SystemClock.sleep(300)
                color(middle)
                val changeAt = marker.acceptedAtMs + 2_500
                while (SystemClock.elapsedRealtime() < changeAt) SystemClock.sleep(50)
                color(end)
                CaptureTestControls.waitFor(30_000) { session.state.value.clips.any { it.id == marker.id } }
                assertEquals(SpikePhase.Recording, session.state.value.phase)
                return session.state.value.clips.first { it.id == marker.id }.also { clip ->
                    assertTrue(clip.actualPostRollMs >= 4_700)
                    assertColor(context, clip, (clip.actualPreRollMs - 1_000).coerceAtLeast(0), before)
                    assertColor(context, clip, clip.actualPreRollMs + 1_500, middle)
                    val late = assertColor(context, clip, clip.actualPreRollMs + 4_000, end)
                    val tail = assertColor(context, clip, clip.actualPreRollMs + clip.actualPostRollMs - 100, end)
                    assertTrue("Video froze during the last second", !late.contentEquals(tail))
                }
            }
            val first = capture(Color.RED, Color.GREEN, Color.BLUE)
            val second = capture(Color.YELLOW, Color.MAGENTA, Color.CYAN)
            assertTrue(first.id != second.id)
            assertTrue(session.state.value.clips.any { it.id == first.id })
        } finally {
            instrumentation.runOnMainSync { pattern?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            context.startService(Intent(context, SpikeCaptureService::class.java).setAction(SpikeCaptureService.ACTION_STOP))
            CaptureTestControls.waitFor(15_000) { diagnostics.tracker().snapshot().active.values.all { it == 0 } }
        }
    }

    private fun assertColor(context: Context, clip: SpikeClipInfo, timeMs: Long, expected: Int): IntArray {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(clip.clipPath))
            val frame = checkNotNull(retriever.getScaledFrameAtTime(timeMs * 1_000,
                MediaMetadataRetriever.OPTION_CLOSEST, 96, 96)) { "Cannot decode frame at $timeMs" }
            try {
                val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
                val distance = kotlin.math.abs(Color.red(pixel) - Color.red(expected)) +
                    kotlin.math.abs(Color.green(pixel) - Color.green(expected)) +
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(expected))
                val proof = File(context.filesDir, "capture-pixel-proof").apply { mkdirs() }
                File(proof, "frame-${clip.id}-$timeMs.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue("Wrong/black/frozen frame at $timeMs ms: expected=$expected actual=$pixel", distance < 120)
                return IntArray(frame.width * frame.height).also {
                    frame.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height)
                }
            } finally { frame.recycle() }
        } finally { retriever.release() }
    }

    private class PatternView(context: Context) : View(context) {
        var color: Int = Color.RED
        private val paint = Paint().apply { color = Color.WHITE }
        private var frame = 0
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(color)
            val x = ((frame++ * 13) % width.coerceAtLeast(1)).toFloat()
            canvas.drawRect(x, height * 0.8f, x + 20, height * 0.9f, paint)
            if (isAttachedToWindow) postInvalidateDelayed(100)
        }
    }
}
