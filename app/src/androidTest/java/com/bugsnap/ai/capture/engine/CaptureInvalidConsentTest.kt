package com.bugsnap.ai.capture.engine

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.bugsnap.ai.capture.buffer.ClipSnapshot
import com.bugsnap.ai.capture.buffer.EncodedSample
import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.spike.CaptureTestControls
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureInvalidConsentTest {
    @Test fun invalidConsentDoesNotLeaveWorkerOrNativeHandles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostics = EntryPointAccessors.fromApplication(context, CaptureDiagnostics::class.java)
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = diagnostics.factory().create(parent, Activity.RESULT_OK, Intent(), object : CaptureEngine.Listener {
            override fun onSample(sample: EncodedSample) = Unit
            override fun onClip(snapshot: ClipSnapshot) = Unit
            override fun onStopped(error: DomainError?) = Unit
        })
        try {
            assertTrue(engine.start(1080, 2400, 420).isErr)
            assertEquals(CapturePhase.FAILED, engine.state.value.phase)
        } finally {
            engine.dispose()
            parent.cancel()
        }
        CaptureTestControls.waitFor(5_000) { diagnostics.tracker().snapshot().active.values.all { it == 0 } }
        assertEquals(0, diagnostics.tracker().snapshot().releaseErrors)
    }
}
