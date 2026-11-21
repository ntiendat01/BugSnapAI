package com.bugsnap.ai.capture.engine

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class CaptureResource { PROJECTION, DISPLAY, CODEC, SURFACE, WORKER }

data class CaptureResourceSnapshot(val active: Map<CaptureResource, Int>, val peak: Map<CaptureResource, Int>, val releaseErrors: Int)

/** Counts owned native handles for diagnostics; does not measure allocations inside vendor drivers. */
@Singleton
class CaptureResourceTracker @Inject constructor() {
    private val counts = CaptureResource.entries.associateWith { AtomicInteger() }
    private val peaks = CaptureResource.entries.associateWith { AtomicInteger() }
    private val errors = AtomicInteger()

    fun acquired(resource: CaptureResource) {
        val count = counts.getValue(resource).incrementAndGet()
        peaks.getValue(resource).updateAndGet { maxOf(it, count) }
    }

    fun released(resource: CaptureResource) { counts.getValue(resource).decrementAndGet() }
    fun releaseFailed() { errors.incrementAndGet() }
    fun snapshot() = CaptureResourceSnapshot(counts.mapValues { it.value.get() }, peaks.mapValues { it.value.get() }, errors.get())
}
