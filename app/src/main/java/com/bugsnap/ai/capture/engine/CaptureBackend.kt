package com.bugsnap.ai.capture.engine

import com.bugsnap.ai.capture.buffer.EncodedSample
import com.bugsnap.ai.capture.buffer.Generation
import com.bugsnap.ai.core.common.DomainError

/** Backend methods serialize native operations; close/dispose are idempotent. */
interface CaptureBackend {
    interface Events {
        fun onGeneration(generation: Generation)
        fun onSample(sample: EncodedSample)
        fun onResize(width: Int, height: Int, densityDpi: Int)
        fun onThermal(status: Int)
        fun onStopped(error: DomainError)
    }

    suspend fun start(config: CaptureConfig, events: Events)
    suspend fun reconfigure(config: CaptureConfig)
    suspend fun pause()
    suspend fun resume(config: CaptureConfig)
    suspend fun close()
    /** Schedule native cleanup even when the owning coroutine scope has been cancelled. */
    fun dispose()
}
