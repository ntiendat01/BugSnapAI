package com.bugsnap.ai.capture.engine

import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

internal class AndroidCaptureBackend(
    private val context: Context,
    private val resultCode: Int,
    private val data: Intent,
    private val tracker: CaptureResourceTracker,
) : CaptureBackend {
    private val closing = AtomicBoolean()
    private val closed = CompletableDeferred<Unit>()
    private val thread = object : HandlerThread("BugSnapCapture") {
        override fun run() {
            tracker.acquired(CaptureResource.WORKER)
            try { super.run() } finally { tracker.released(CaptureResource.WORKER) }
        }
    }.apply { start() }
    private val handler = Handler(thread.looper)
    private val holder = ProjectionHolder(context, handler, tracker)
    private val display = VirtualDisplayController(tracker)
    private var encoder: EncoderPipeline? = null
    private var config: CaptureConfig? = null
    private var events: CaptureBackend.Events? = null
    private var capturedWidth = 0
    private var capturedHeight = 0
    private var thermalRegistered = false
    private var configRegistered = false
    private val power = context.getSystemService(PowerManager::class.java)
    private val thermal = PowerManager.OnThermalStatusChangedListener { status -> events?.onThermal(status) }
    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            handler.post {
                if (closing.get()) return@post
                val bounds = displayBounds(context)
                val width = if (Build.VERSION.SDK_INT >= 34 && capturedWidth > 0) capturedWidth else bounds.first
                val height = if (Build.VERSION.SDK_INT >= 34 && capturedHeight > 0) capturedHeight else bounds.second
                events?.onResize(width, height, context.resources.displayMetrics.densityDpi)
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    override suspend fun start(config: CaptureConfig, events: CaptureBackend.Events) = onWorker {
        check(this.events == null)
        this.events = events
        this.config = config
        val projection = holder.open(resultCode, data, object : MediaProjection.Callback() {
            override fun onStop() { if (!closing.get()) events.onStopped(com.bugsnap.ai.core.common.DomainError.ProjectionLost) }
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (closing.get() || width <= 0 || height <= 0) return
                capturedWidth = width
                capturedHeight = height
                events.onResize(width, height, context.resources.displayMetrics.densityDpi)
            }
        })
        val pipeline = EncoderPipeline(handler, tracker, events)
        encoder = pipeline
        display.open(projection, pipeline.start(config), config, handler)
        context.registerComponentCallbacks(configuration)
        configRegistered = true
        power.addThermalStatusListener({ runnable -> handler.post(runnable) }, thermal)
        thermalRegistered = true
        events.onThermal(power.currentThermalStatus)
    }

    override suspend fun reconfigure(config: CaptureConfig) = onWorker {
        val previous = checkNotNull(this.config)
        if (previous.sameEncoder(config)) display.resize(config) else {
            display.detach()
            val pipeline = checkNotNull(encoder)
            pipeline.close()
            display.attach(pipeline.start(config), config)
        }
        this.config = config
    }

    override suspend fun pause() = onWorker {
        display.detach()
        encoder?.close()
    }

    override suspend fun resume(config: CaptureConfig) = onWorker {
        display.attach(checkNotNull(encoder).start(config), config)
        this.config = config
    }

    override suspend fun close() { dispose(); closed.await() }

    override fun dispose() {
        if (!closing.compareAndSet(false, true)) return
        check(handler.post {
            try {
                releaseAll(
                    { if (configRegistered) { context.unregisterComponentCallbacks(configuration); configRegistered = false } },
                    { if (thermalRegistered) { power.removeThermalStatusListener(thermal); thermalRegistered = false } },
                    { display.close() }, { encoder?.close(); encoder = null }, { holder.close() },
                )
                closed.complete(Unit)
            } catch (error: Throwable) {
                tracker.releaseFailed()
                CaptureLog.error("Capture resource cleanup failed", error)
                closed.completeExceptionally(error)
            } finally {
                events = null
                thread.quitSafely()
            }
        }) { "Capture worker terminated before cleanup" }
    }

    private suspend fun onWorker(block: () -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        if (closing.get()) { continuation.resumeWithException(IllegalStateException("Capture is closed")); return@suspendCancellableCoroutine }
        if (!handler.post {
                if (!continuation.isActive) return@post
                try {
                    check(!closing.get()) { "Capture is closed" }
                    block()
                    continuation.resume(Unit)
                } catch (error: Throwable) { continuation.resumeWithException(error) }
            }) continuation.resumeWithException(IllegalStateException("Capture worker unavailable"))
    }

    companion object {
        fun displayBounds(context: Context): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else context.resources.displayMetrics.run { widthPixels to heightPixels }
    }
}
