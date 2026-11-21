package com.bugsnap.ai.capture.engine

import com.bugsnap.ai.spike.SpikeSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug graph access for instrumentation; no diagnostic entry point is shipped in release. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptureDiagnostics {
    fun tracker(): CaptureResourceTracker
    fun session(): SpikeSession
    fun factory(): CaptureEngineFactory
}
