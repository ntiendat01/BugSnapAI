package com.bugsnap.ai.capture.engine

import com.bugsnap.ai.capture.buffer.ClipSnapshot
import com.bugsnap.ai.capture.buffer.EncodedSample
import com.bugsnap.ai.capture.buffer.Generation
import com.bugsnap.ai.capture.buffer.MarkResult
import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.core.common.DomainException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureEngineTest {
    @Test fun startsAndAcceptsMarkersOnlyAfterKeyframe() = runTest {
        val rig = rig()
        assertTrue(rig.engine.mark("idle") is MarkResult.Rejected)
        assertTrue(rig.engine.start(1080, 2400, 420).isOk)
        assertEquals(CapturePhase.STARTING, rig.engine.state.value.phase)
        rig.backend.keyframe()
        assertEquals(CapturePhase.RECORDING, rig.engine.state.value.phase)
        assertTrue(rig.engine.mark("mark") is MarkResult.Pending)
    }

    @Test fun cannotStartTwiceOrReuseStoppedConsent() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        assertTrue(rig.engine.start(1080, 2400, 420).isErr)
        assertEquals(1, rig.backend.starts)
        rig.engine.stop()
        assertTrue(rig.engine.start(1080, 2400, 420).isErr)
    }

    @Test fun stopFlushesMarkerExactlyOnceAndRejectsLateCallbacks() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        rig.backend.keyframe()
        rig.backend.sample(1_000_000)
        rig.engine.mark("pending")
        rig.engine.stop()
        rig.engine.stop()
        rig.backend.sample(2_000_000)
        assertEquals(listOf("pending"), rig.clips.map { it.id })
        assertEquals(1, rig.backend.closes)
        assertEquals(1, rig.stopped.size)
        assertEquals(0, rig.engine.buffer.stats().sampleCount)
    }

    @Test fun partialStartFailureClosesBackendAndReportsFailure() = runTest {
        val rig = rig()
        rig.backend.failure = "start"
        assertTrue(rig.engine.start(1080, 2400, 420).isErr)
        assertEquals(CapturePhase.FAILED, rig.engine.state.value.phase)
        assertEquals(1, rig.backend.closes)
        assertTrue(rig.stopped.single() is DomainError.EncoderFailure)
    }

    @Test fun sameSizeIsNoOpAndRotationReconfiguresOneBackend() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        rig.backend.keyframe()
        rig.engine.resize(1080, 2400, 420)
        assertEquals(0, rig.backend.reconfigurations)
        rig.engine.resize(2400, 1080, 420)
        assertEquals(1, rig.backend.reconfigurations)
        assertEquals(1, rig.backend.starts)
        assertEquals(1280, rig.engine.state.value.config!!.width)
    }

    @Test fun resizeFailureClosesResourcesAndFlushesOldGeneration() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        rig.backend.keyframe()
        rig.engine.mark("rotation")
        rig.backend.failure = "resize"
        assertTrue(rig.engine.resize(2400, 1080, 420).isErr)
        assertEquals(CapturePhase.FAILED, rig.engine.state.value.phase)
        assertEquals(listOf("rotation"), rig.clips.map { it.id })
        assertEquals(1, rig.backend.closes)
    }

    @Test fun oldKeyframeDuringResizeCannotMakeNewGenerationReady() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        rig.backend.keyframe()
        rig.engine.mark("before-resize")
        rig.backend.oldKeyframeDuringResize = true
        rig.engine.resize(2400, 1080, 420)
        assertEquals(CapturePhase.STARTING, rig.engine.state.value.phase)
        assertTrue(rig.engine.mark("during-resize") is MarkResult.Rejected)
        assertEquals(listOf(1), rig.clips.map { it.generation.id })
        rig.backend.keyframe()
        assertEquals(CapturePhase.RECORDING, rig.engine.state.value.phase)
        assertEquals(2, rig.engine.state.value.generation)
    }

    @Test fun pauseFlushesAndResumeStartsNewGenerationWithoutMixing() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        rig.backend.keyframe()
        rig.engine.mark("before-pause")
        rig.engine.pause()
        assertEquals(CapturePhase.PAUSED, rig.engine.state.value.phase)
        assertTrue(rig.engine.mark("paused") is MarkResult.Rejected)
        assertEquals(1, rig.clips.size)
        rig.engine.resume()
        rig.backend.keyframe()
        assertEquals(2, rig.engine.state.value.generation)
        rig.engine.mark("after-pause")
        rig.engine.stop()
        assertEquals(listOf(1, 2), rig.clips.map { it.generation.id })
        assertEquals(1, rig.backend.starts)
    }

    @Test fun projectionLossIsTerminalAndCannotReopenOldConsent() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        rig.backend.events.onStopped(DomainError.ProjectionLost)
        runCurrent()
        assertEquals(DomainError.ProjectionLost, rig.engine.state.value.error)
        assertEquals(1, rig.backend.closes)
        assertTrue(rig.engine.resume().isErr)
    }

    @Test fun thermalTransitionsReconfigureOnlyWhenProfileChanges() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        rig.backend.events.onThermal(2)
        runCurrent()
        assertEquals(24, rig.engine.state.value.config!!.frameRate)
        assertEquals(1, rig.backend.reconfigurations)
        rig.backend.events.onThermal(3)
        runCurrent()
        assertEquals(1, rig.backend.reconfigurations)
        assertEquals(3, rig.engine.state.value.thermalStatus)
        rig.backend.events.onThermal(0)
        runCurrent()
        assertEquals(30, rig.engine.state.value.config!!.frameRate)
        assertEquals(2, rig.backend.reconfigurations)
    }

    @Test fun thermalChangesWhilePausedDoNotResumeCapture() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        rig.engine.pause()
        rig.backend.events.onThermal(2)
        runCurrent()
        assertEquals(CapturePhase.PAUSED, rig.engine.state.value.phase)
        assertEquals(0, rig.backend.reconfigurations)
        rig.engine.resume()
        assertEquals(24, rig.backend.lastConfig!!.frameRate)
    }

    @Test fun parentCancellationSchedulesBackendDisposal() = runTest {
        val backend = FakeBackend()
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val rig = Rig(backend, parent, StandardTestDispatcher(testScheduler))
        rig.engine.start(1080, 2400, 420)
        parent.cancel()
        runCurrent()
        assertEquals(1, backend.disposals)
        assertEquals(CapturePhase.STOPPED, rig.engine.state.value.phase)
        assertTrue(rig.engine.mark("cancelled") is MarkResult.Rejected)
    }

    @Test fun cleanupErrorRemainsVisibleInTerminalState() = runTest {
        val rig = rig()
        rig.engine.start(1080, 2400, 420)
        rig.backend.failure = "close"
        assertTrue(rig.engine.stop().isErr)
        assertEquals(CapturePhase.FAILED, rig.engine.state.value.phase)
        assertFalse(rig.stopped.single() == null)
    }

    @Test fun cancellingPartialStartStillClosesBackend() = runTest {
        val rig = rig()
        rig.backend.startGate = CompletableDeferred()
        val start = launch { rig.engine.start(1080, 2400, 420) }
        runCurrent()
        start.cancelAndJoin()
        assertEquals(1, rig.backend.closes)
        assertEquals(CapturePhase.STOPPED, rig.engine.state.value.phase)
    }

    private fun TestScope.rig() = Rig(FakeBackend(), backgroundScope, StandardTestDispatcher(testScheduler))

    private class Rig(val backend: FakeBackend, parent: CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val clips = mutableListOf<ClipSnapshot>()
        val stopped = mutableListOf<DomainError?>()
        val engine = CaptureEngine(backend, parent, dispatcher, object : CaptureEngine.Listener {
            override fun onSample(sample: EncodedSample) = Unit
            override fun onClip(snapshot: ClipSnapshot) { clips.add(snapshot) }
            override fun onStopped(error: DomainError?) { stopped.add(error) }
        })
    }

    private class FakeBackend : CaptureBackend {
        lateinit var events: CaptureBackend.Events
        var failure: String? = null
        var startGate: CompletableDeferred<Unit>? = null
        var starts = 0
        var closes = 0
        var disposals = 0
        var reconfigurations = 0
        var generation = 0
        var lastConfig: CaptureConfig? = null
        var oldKeyframeDuringResize = false
        override suspend fun start(config: CaptureConfig, events: CaptureBackend.Events) {
            starts++
            this.events = events
            startGate?.await()
            failIf("start")
            configure(config)
        }
        override suspend fun reconfigure(config: CaptureConfig) {
            reconfigurations++
            if (oldKeyframeDuringResize) keyframe()
            failIf("resize")
            configure(config)
        }
        override suspend fun pause() = Unit
        override suspend fun resume(config: CaptureConfig) { configure(config) }
        override suspend fun close() { closes++; failIf("close") }
        override fun dispose() { disposals++ }
        fun keyframe() { sample(generation * 10_000_000L, true) }
        fun sample(pts: Long, key: Boolean = false) {
            events.onSample(if (key) EncodedSample.keyFrame(pts, generation) else EncodedSample.delta(pts, generation))
        }
        private fun configure(config: CaptureConfig) {
            lastConfig = config
            generation++
            events.onGeneration(Generation(generation, config.width, config.height, "video/avc", byteArrayOf(1), null, 0))
        }
        private fun failIf(operation: String) {
            if (failure == operation) throw DomainException(DomainError.EncoderFailure(IOException(operation)))
        }
    }
}
