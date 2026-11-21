package com.bugsnap.ai.capture.export

import com.bugsnap.ai.capture.buffer.ClipSnapshot
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded export queue (max [maxParallel] at once, one retry on failure).
 * Pure JVM: the actual muxer is injected via [ClipExporter].
 */
class MarkerExportQueue(
    private val exporter: ClipExporter,
    private val outputRoot: String,
    private val maxParallel: Int = DEFAULT_MAX_PARALLEL,
    private val retryLimit: Int = DEFAULT_RETRY_LIMIT,
    private val onResult: (ExportResult) -> Unit,
    executor: ExecutorService? = null,
    private val onStarted: (String) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val waiting = ArrayDeque<ClipSnapshot>()
    private val inFlight = AtomicInteger(0)
    private val ownExecutor = executor == null
    private val executorService = executor ?: Executors.newFixedThreadPool(maxParallel) { runnable ->
        Thread(runnable, "bugsnap-export").apply { isDaemon = true }
    }
    @Volatile private var shutdown = false

    fun submit(snapshot: ClipSnapshot) {
        if (shutdown) {
            onResult(ExportResult.Failure(snapshot.id, "queue shutdown", 0))
            return
        }
        val startNow: Boolean = lock.withLock {
            waiting.addLast(snapshot)
            inFlight.get() < maxParallel
        }
        if (startNow) pump()
    }

    fun pendingAndInFlight(): Int = lock.withLock { waiting.size + inFlight.get() }

    fun shutdown(waitForTermination: Boolean = true) {
        shutdown = true
        if (ownExecutor) {
            executorService.shutdown()
            if (waitForTermination) executorService.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun pump() {
        while (true) {
            val next: ClipSnapshot? = lock.withLock {
                if (inFlight.get() >= maxParallel) return
                val snapshot = waiting.pollFirst() ?: return
                inFlight.incrementAndGet()
                snapshot
            }
            if (next == null) return
            executorService.execute { runExport(next) }
        }
    }

    private fun runExport(snapshot: ClipSnapshot) {
        onStarted(snapshot.id)
        var attempt = 0
        var last: ExportResult
        while (true) {
            attempt += 1
            last = try {
                exporter.export(snapshot, "$outputRoot/${snapshot.id}")
            } catch (error: Exception) {
                ExportResult.Failure(snapshot.id, error.message ?: error::class.java.simpleName, attempt)
            }
            if (last is ExportResult.Success || attempt > retryLimit) break
        }
        try {
            onResult(last)
        } finally {
            lock.withLock { inFlight.decrementAndGet() }
            if (!shutdown) pump()
        }
    }

    companion object {
        const val DEFAULT_MAX_PARALLEL = 3
        const val DEFAULT_RETRY_LIMIT = 1
    }
}
