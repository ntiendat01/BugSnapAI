package com.bugsnap.ai.capture.engine

import android.app.UiAutomation
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.bugsnap.ai.MainActivity
import com.bugsnap.ai.spike.CaptureTestControls
import com.bugsnap.ai.spike.EmulatorDialogs
import com.bugsnap.ai.spike.SpikeCaptureService
import com.bugsnap.ai.spike.SpikePhase
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real permission, one VirtualDisplay, 20 rotations and a timed capture resource soak. */
class CaptureEngineFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun dismissStartupError() { EmulatorDialogs.dismissStartupError() }

    @Test fun twentyRotationsAndTenMinuteCaptureReleaseAllOwnedResources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val diagnostics = EntryPointAccessors.fromApplication(context, CaptureDiagnostics::class.java)
        val tracker = diagnostics.tracker()
        val session = diagnostics.session()
        val soakMs = InstrumentationRegistry.getArguments().getString("captureSoakMs")?.toLong() ?: 600_000L
        require(soakMs >= 0)
        CaptureTestControls.grantNotifications()
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        try {
            compose.onNodeWithText("Bắt đầu quay").performClick()
            CaptureTestControls.acceptConsent()
            CaptureTestControls.waitFor(30_000) { session.state.value.phase == SpikePhase.Recording }
            val startedAt = SystemClock.elapsedRealtime()
            repeat(20) { index ->
                val previous = session.state.value.generation
                automation.setRotation(if (index % 2 == 0) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
                CaptureTestControls.waitFor(20_000) {
                    val state = session.state.value
                    state.generation > previous && state.phase == SpikePhase.Recording &&
                        (if (index % 2 == 0) state.encodeWidth > state.encodeHeight else state.encodeHeight > state.encodeWidth)
                }
                assertOwnedResources(tracker)
                progress("rotation=${index + 1} generation=${session.state.value.generation} ${tracker.snapshot()}")
            }
            var lastMinute = -1L
            while (SystemClock.elapsedRealtime() - startedAt < soakMs) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                assertEquals(SpikePhase.Recording, session.state.value.phase)
                assertOwnedResources(tracker)
                if (elapsed / 60_000 != lastMinute) {
                    lastMinute = elapsed / 60_000
                    val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    progress("soak elapsedMs=$elapsed pssKb=${memory.totalPss} nativeBytes=${Debug.getNativeHeapAllocatedSize()} ringBytes=${session.state.value.bufferRamBytes} ${tracker.snapshot()}")
                }
                SystemClock.sleep(1_000)
            }
            val clipIds = session.state.value.clips.map { it.id }.toSet()
            context.startService(Intent(context, SpikeCaptureService::class.java).setAction(SpikeCaptureService.ACTION_MARK))
            CaptureTestControls.waitFor(30_000) { session.state.value.clips.any { it.id !in clipIds } }
            val clip = session.state.value.clips.first { it.id !in clipIds }
            assertTrue(clip.actualPreRollMs <= 30_000)
            assertTrue(clip.actualPostRollMs <= 5_000)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(clip.clipPath))
                assertTrue((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
            } finally { retriever.release() }
            context.startService(Intent(context, SpikeCaptureService::class.java).setAction(SpikeCaptureService.ACTION_STOP))
            CaptureTestControls.waitFor(15_000) { tracker.snapshot().active.values.all { it == 0 } }
            assertEquals(0, tracker.snapshot().releaseErrors)
            assertTrue(tracker.snapshot().peak.values.all { it <= 1 })
            progress("completed elapsedMs=${SystemClock.elapsedRealtime() - startedAt} resources=${tracker.snapshot()}")
        } finally {
            context.startService(Intent(context, SpikeCaptureService::class.java).setAction(SpikeCaptureService.ACTION_STOP))
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    private fun assertOwnedResources(tracker: CaptureResourceTracker) {
        val snapshot = tracker.snapshot()
        CaptureResource.entries.forEach { assertEquals("$it ownership", 1, snapshot.active.getValue(it)) }
        assertEquals(0, snapshot.releaseErrors)
    }

    private fun progress(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "\nT012 $message\n") })
    }
}
