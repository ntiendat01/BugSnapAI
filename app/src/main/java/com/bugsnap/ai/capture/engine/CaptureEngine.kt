package com.bugsnap.ai.capture.engine

import com.bugsnap.ai.capture.buffer.ClipSnapshot
import com.bugsnap.ai.capture.buffer.EncodedSample
import com.bugsnap.ai.capture.buffer.Generation
import com.bugsnap.ai.capture.buffer.MarkResult
import com.bugsnap.ai.capture.buffer.RingBuffer
import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.core.common.DomainException
import com.bugsnap.ai.core.common.Result
import com.bugsnap.ai.core.common.catchDomain
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Per-consent engine. Owns its buffer and child scope; has no database, UI or file access. */
class CaptureEngine(
    private val backend: CaptureBackend,
    parentScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val listener: Listener,
) {
    interface Listener {
        fun onSample(sample: EncodedSample)
        fun onClip(snapshot: ClipSnapshot)
        fun onStopped(error: DomainError?)
    }

    val buffer = RingBuffer()
    private val mutableState = MutableStateFlow(CaptureEngineState())
    val state = mutableState.asStateFlow()
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + dispatcher)
    private val mutex = Mutex()
    private val disposed = AtomicBoolean()
    private val minimumGeneration = AtomicInteger(1)
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var density = 0

    init {
        job.invokeOnCompletion { dispose() }
    }

    suspend fun start(width: Int, height: Int, densityDpi: Int): Result<Unit, DomainError> = control {
        check(state.value.phase == CapturePhase.IDLE) { "Consent has already been used" }
        sourceWidth = width
        sourceHeight = height
        density = densityDpi
        val config = CaptureConfig.forDisplay(width, height, density)
        mutableState.value = CaptureEngineState(CapturePhase.STARTING, config)
        guarded { backend.start(config, events) }
    }

    suspend fun resize(width: Int, height: Int, densityDpi: Int): Result<Unit, DomainError> = control {
        if (state.value.phase !in LIVE || width <= 0 || height <= 0 || densityDpi <= 0) return@control
        sourceWidth = width
        sourceHeight = height
        density = densityDpi
        reconfigure()
    }

    suspend fun pause(): Result<Unit, DomainError> = control {
        if (state.value.phase !in RUNNING) return@control
        mutableState.update { it.copy(phase = CapturePhase.PAUSED) }
        guarded { backend.pause() }
        flushAndClear()
    }

    suspend fun resume(): Result<Unit, DomainError> = control {
        check(state.value.phase == CapturePhase.PAUSED) { "Capture is not paused" }
        minimumGeneration.incrementAndGet()
        mutableState.update { it.copy(phase = CapturePhase.STARTING) }
        guarded { backend.resume(state.value.config!!) }
    }

    suspend fun stop(): Result<Unit, DomainError> = control {
        terminate(null)
        state.value.error?.let { throw DomainException(it) }
    }

    fun mark(id: String): MarkResult = if (state.value.phase == CapturePhase.RECORDING &&
        state.value.generation >= minimumGeneration.get()) buffer.mark(id)
        else MarkResult.Rejected("Capture is not recording")

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        mutableState.update { if (it.phase in TERMINAL) it else it.copy(phase = CapturePhase.STOPPED) }
        buffer.clear()
        try { backend.dispose() } finally { job.cancel() }
    }

    private suspend fun reconfigure() {
        val config = CaptureConfig.forDisplay(sourceWidth, sourceHeight, density, state.value.thermalStatus)
        val previous = state.value.config
        if (previous == config) return
        val paused = state.value.phase == CapturePhase.PAUSED
        val newEncoder = !paused && previous?.sameEncoder(config) != true
        if (newEncoder) minimumGeneration.incrementAndGet()
        mutableState.update { it.copy(config = config,
            phase = if (newEncoder) CapturePhase.STARTING else it.phase) }
        if (newEncoder) flushAndClear()
        if (!paused) guarded { backend.reconfigure(config) }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { terminate(null) }
            throw cancelled
        } catch (error: Exception) {
            val domain = (error as? DomainException)?.error ?: DomainError.EncoderFailure(error)
            terminate(domain)
            throw DomainException(domain, error)
        }
    }

    private suspend fun terminate(error: DomainError?) {
        if (state.value.phase in TERMINAL) return
        mutableState.update { it.copy(phase = CapturePhase.STOPPING) }
        var failure = error
        var cleanupError: DomainError? = null
        withContext(NonCancellable) {
            try {
                backend.close()
            } catch (cleanup: Exception) {
                cleanupError = DomainError.EncoderFailure(cleanup)
                failure = failure ?: DomainError.EncoderFailure(cleanup)
            } finally {
                flushAndClear()
                mutableState.update { it.copy(phase = if (failure == null) CapturePhase.STOPPED else CapturePhase.FAILED,
                    error = failure, cleanupError = cleanupError) }
                listener.onStopped(failure)
            }
        }
    }

    private fun flushAndClear() {
        buffer.forceFinalizePending().forEach(listener::onClip)
        buffer.clear()
    }

    private suspend fun control(block: suspend () -> Unit): Result<Unit, DomainError> = withContext(dispatcher) {
        catchDomain { mutex.withLock { block() } }
    }

    private val events = object : CaptureBackend.Events {
        override fun onGeneration(generation: Generation) {
            if (state.value.phase in RUNNING && generation.id >= minimumGeneration.get() && buffer.registerGeneration(generation)) {
                minimumGeneration.updateAndGet { maxOf(it, generation.id) }
                mutableState.update { it.copy(generation = generation.id) }
            }
        }

        override fun onSample(sample: EncodedSample) {
            if (state.value.phase !in RUNNING || sample.generation < minimumGeneration.get() ||
                sample.generation != buffer.stats().currentGenerationId) return
            buffer.append(sample).forEach(listener::onClip)
            if (sample.isKeyFrame && buffer.stats().sampleCount > 0) {
                mutableState.update { if (it.phase == CapturePhase.STARTING && sample.generation >= minimumGeneration.get() &&
                    it.generation == sample.generation) it.copy(phase = CapturePhase.RECORDING) else it }
            }
            listener.onSample(sample)
        }

        override fun onResize(width: Int, height: Int, densityDpi: Int) {
            scope.launch { resize(width, height, densityDpi) }
        }

        override fun onThermal(status: Int) {
            scope.launch {
                control {
                    if (state.value.phase !in LIVE) return@control
                    mutableState.update { it.copy(thermalStatus = status) }
                    reconfigure()
                }
            }
        }

        override fun onStopped(error: DomainError) {
            scope.launch { control { terminate(error) } }
        }
    }

    companion object {
        private val RUNNING = setOf(CapturePhase.STARTING, CapturePhase.RECORDING)
        private val LIVE = RUNNING + CapturePhase.PAUSED
        private val TERMINAL = setOf(CapturePhase.STOPPED, CapturePhase.FAILED)
    }
}
