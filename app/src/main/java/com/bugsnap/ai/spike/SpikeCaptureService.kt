package com.bugsnap.ai.spike

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import com.bugsnap.ai.capture.buffer.EncodedSample
import com.bugsnap.ai.capture.buffer.Generation
import com.bugsnap.ai.capture.buffer.RingBuffer
import com.bugsnap.ai.capture.export.ExportResult
import com.bugsnap.ai.capture.export.MarkerExportQueue
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@AndroidEntryPoint
class SpikeCaptureService : Service() {
    @Inject lateinit var session: SpikeSession

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val metrics = SpikeMetrics()
    private val buffer = RingBuffer()
    private val markStartedAt = ConcurrentHashMap<String, Long>()

    private lateinit var muxer: AndroidClipMuxer
    private lateinit var exportQueue: MarkerExportQueue
    private lateinit var overlay: SpikeOverlay

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: EncoderPipeline? = null
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var encodeWidth: Int = 0
    private var encodeHeight: Int = 0
    private var startedAtElapsedMs: Long = 0L
    private var projectionCallback: MediaProjection.Callback? = null
    private var configCallback: ConfigListener? = null

    override fun onCreate() {
        super.onCreate()
        SpikeNotifications.ensureChannels(this)
        val root = File(filesDir, "spike")
        muxer = AndroidClipMuxer(root).also { it.deleteOrphanParts() }
        exportQueue = MarkerExportQueue(
            exporter = muxer,
            outputRoot = ".",
            onResult = { result -> handleExport(result) },
        )
        overlay = SpikeOverlay(this)
        SpikeLog.i("service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_MARK -> mark()
            ACTION_STOP -> stopCapture("user")
            ACTION_REUSE_TOKEN -> reuseToken()
            else -> SpikeLog.w("unknown action ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        teardown("onDestroy")
        exportQueue.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.parcelableIntent(EXTRA_RESULT_DATA)
        if (data == null) {
            session.setError("missing projection result")
            stopSelf()
            return
        }
        SpikeNotifications.ensureChannels(this)
        val notification = SpikeNotifications.captureNotification(this, recording = true)
        startForeground(
            SpikeNotifications.CAPTURE_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        if (projection != null) {
            SpikeLog.w("start ignored: already capturing")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val created = try {
            manager.getMediaProjection(resultCode, data)
        } catch (error: Exception) {
            session.setError("getMediaProjection: ${error.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (created == null) {
            session.setError("getMediaProjection returned null")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                SpikeLog.i("projection onStop elapsedMs=${SystemClock.elapsedRealtime() - startedAtElapsedMs}")
                scope.launch { onProjectionStopped() }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                SpikeLog.i("onCapturedContentResize ${width}x$height")
                scope.launch { handleResize(width, height) }
            }
        }
        projectionCallback = callback
        created.registerCallback(callback, null)
        projection = created
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        buffer.clear()
        resolveDisplaySize()
        try {
            openPipeline()
        } catch (error: Exception) {
            session.setError("pipeline: ${error.message}")
            teardown("start-failed")
            stopSelf()
            return
        }
        overlay.show()
        listenToConfiguration()
        session.update {
            it.copy(
                phase = SpikePhase.Recording,
                message = getString(com.bugsnap.ai.R.string.spike_status_recording),
                encodeWidth = encodeWidth,
                encodeHeight = encodeHeight,
                overlayActive = overlay.isShowing,
                lastError = null,
            )
        }
        scope.launch(Dispatchers.Default) { heartbeat() }
        SpikeLog.i("recording started encode=${encodeWidth}x$encodeHeight display=${displayWidth}x$displayHeight")
    }

    private fun openPipeline() {
        val sizes = EncodeSizing.forDisplay(displayWidth, displayHeight)
        encodeWidth = sizes.first
        encodeHeight = sizes.second
        val pipeline = EncoderPipeline(
            scope = scope,
            metrics = metrics,
            onSample = { sample -> onSample(sample) },
            onGeneration = { generation -> buffer.registerGeneration(generation) },
            onError = { message -> session.setError(message) },
        )
        encoder = pipeline
        val surface = pipeline.start(encodeWidth, encodeHeight)
        val dpi = resources.displayMetrics.densityDpi
        virtualDisplay = projection!!.createVirtualDisplay(
            "BugSnapSpike",
            encodeWidth,
            encodeHeight,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        SpikeLog.i("virtual display created ${encodeWidth}x$encodeHeight dpi=$dpi")
    }

    private fun onSample(sample: EncodedSample) {
        val completed = buffer.append(sample)
        completed.forEach { exportQueue.submit(it) }
        if (completed.isNotEmpty()) {
            session.update { it.copy(exportingCount = it.exportingCount + completed.size) }
        }
    }

    fun mark() {
        if (session.state.value.phase != SpikePhase.Recording) {
            SpikeLog.w("mark ignored: phase=${session.state.value.phase}")
            return
        }
        val id = session.nextMarkerId()
        markStartedAt[id] = SystemClock.elapsedRealtime()
        val result = buffer.mark(id)
        SpikeLog.i("mark id=$id result=${result::class.java.simpleName} lastPts=${buffer.lastPts()}")
        when (result) {
            is com.bugsnap.ai.capture.buffer.MarkResult.Pending -> Unit
            is com.bugsnap.ai.capture.buffer.MarkResult.Ready -> {
                session.update { it.copy(exportingCount = it.exportingCount + 1) }
                exportQueue.submit(result.snapshot)
            }
            is com.bugsnap.ai.capture.buffer.MarkResult.Rejected -> session.setError("mark rejected: ${result.reason}")
        }
    }

    private fun stopCapture(reason: String) {
        SpikeLog.i("stop requested reason=$reason")
        flushPending()
        teardown(reason)
        session.setPhase(SpikePhase.Stopped, reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onProjectionStopped() {
        flushPending()
        teardown("projection-lost", stopProjection = false)
        session.setPhase(SpikePhase.ProjectionLost, getString(com.bugsnap.ai.R.string.spike_status_projection_lost))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun flushPending() {
        val leftover = buffer.forceFinalizePending()
        leftover.forEach { snapshot ->
            session.update { it.copy(exportingCount = it.exportingCount + 1) }
            exportQueue.submit(snapshot)
        }
        SpikeLog.i("flushed pending clips=${leftover.size}")
    }

    private fun reuseToken() {
        val current = projection
        if (current == null) {
            session.update { it.copy(lastTokenReuseResult = "no active projection") }
            SpikeLog.i("reuseToken: no active projection")
            return
        }
        val result = try {
            val dpi = resources.displayMetrics.densityDpi
            val second = current.createVirtualDisplay(
                "BugSnapSpikeReuse",
                encodeWidth,
                encodeHeight,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                virtualDisplay?.surface,
                null,
                null,
            )
            second?.release()
            if (second == null) {
                "null VirtualDisplay (no exception)"
            } else {
                "SUCCESS (second VirtualDisplay created — unexpected on API 34+)"
            }
        } catch (error: SecurityException) {
            "SecurityException: ${error.message}"
        } catch (error: Exception) {
            "${error::class.java.simpleName}: ${error.message}"
        }
        SpikeLog.i("reuseToken result=$result")
        session.update { it.copy(lastTokenReuseResult = result) }
    }

    private fun handleResize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        displayWidth = width
        displayHeight = height
        val sizes = EncodeSizing.forDisplay(width, height)
        if (sizes.first == encodeWidth && sizes.second == encodeHeight) {
            virtualDisplay?.resize(sizes.first, sizes.second, resources.displayMetrics.densityDpi)
            SpikeLog.i("resize same encode size ${sizes.first}x${sizes.second}")
            return
        }
        encodeWidth = sizes.first
        encodeHeight = sizes.second
        val pipeline = encoder ?: return
        val newSurface = pipeline.restart(encodeWidth, encodeHeight)
        virtualDisplay?.resize(encodeWidth, encodeHeight, resources.displayMetrics.densityDpi)
        virtualDisplay?.setSurface(newSurface)
        session.update { it.copy(encodeWidth = encodeWidth, encodeHeight = encodeHeight, generation = it.generation + 1) }
        SpikeLog.i("rotation/resize new encoder ${encodeWidth}x$encodeHeight")
    }

    private suspend fun heartbeat() {
        var minute = 0
        while (scope.isActive && session.state.value.phase == SpikePhase.Recording) {
            val stats = buffer.stats()
            metrics.logRam(stats.ramBytes, label = "heartbeat")
            val thermal = thermalStatus()
            val battery = batteryPct()
            val elapsed = SystemClock.elapsedRealtime() - startedAtElapsedMs
            session.update {
                it.copy(
                    bufferDurationMs = stats.durationUs / 1_000L,
                    bufferRamBytes = stats.ramBytes,
                    javaHeapBytes = metrics.javaHeapBytes(),
                    encodedFps = metrics.encodedFps,
                    lastKeyframeIntervalMs = metrics.lastKeyframeIntervalMs().takeIf { v -> v >= 0 },
                    keyframeCount = metrics.keyframeCount(),
                    generation = stats.currentGenerationId,
                    recordingElapsedMs = elapsed,
                    thermalStatus = thermal,
                    batteryPct = battery,
                    overlayActive = overlay.isShowing,
                )
            }
            if (elapsed / 60_000L > minute) {
                minute = (elapsed / 60_000L).toInt()
                SpikeLog.i("heartbeat minute=$minute stillRunning=true thermal=$thermal batteryPct=$battery")
            }
            if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                session.setError(getString(com.bugsnap.ai.R.string.spike_thermal_severe))
            }
            delay(1_000L)
        }
    }

    private fun handleExport(result: ExportResult) {
        when (result) {
            is ExportResult.Success -> {
                val started = markStartedAt.remove(result.id) ?: SystemClock.elapsedRealtime()
                val latency = SystemClock.elapsedRealtime() - started
                val path = File(File(filesDir, "spike"), result.clipRelativePath).absolutePath
                metrics.logMarkLatency(
                    markerId = result.id,
                    latencyMs = latency,
                    actualPreRollMs = result.actualPreRollMs,
                    actualPostRollMs = result.actualPostRollMs,
                    clipPath = path,
                    durationMs = result.durationMs,
                )
                session.addClip(
                    SpikeClipInfo(
                        id = result.id,
                        clipPath = path,
                        durationMs = result.durationMs,
                        actualPreRollMs = result.actualPreRollMs,
                        actualPostRollMs = result.actualPostRollMs,
                        latencyMs = latency,
                    ),
                )
            }
            is ExportResult.Failure -> {
                markStartedAt.remove(result.id)
                session.update { it.copy(exportingCount = (it.exportingCount - 1).coerceAtLeast(0)) }
                session.setError("export ${result.id} failed: ${result.message}")
            }
        }
    }

    private fun teardown(reason: String, stopProjection: Boolean = true) {
        SpikeLog.i("teardown reason=$reason")
        overlay.dismiss()
        configCallback?.let { callbacks ->
            try {
                applicationContext.unregisterComponentCallbacks(callbacks)
            } catch (_: Exception) {
            }
            configCallback = null
        }
        try {
            virtualDisplay?.release()
        } catch (error: Exception) {
            SpikeLog.w("virtualDisplay.release failed", error)
        }
        virtualDisplay = null
        encoder?.release()
        encoder = null
        val currentProjection = projection
        projection = null
        val callback = projectionCallback
        projectionCallback = null
        if (currentProjection != null) {
            try {
                if (callback != null) currentProjection.unregisterCallback(callback)
            } catch (_: Exception) {
            }
            if (stopProjection) {
                try {
                    currentProjection.stop()
                } catch (error: Exception) {
                    SpikeLog.w("projection.stop failed", error)
                }
            }
        }
        buffer.clear()
    }

    private fun listenToConfiguration() {
        val listener = ConfigListener { newConfig ->
            SpikeLog.i("configuration changed orientation=${newConfig.orientation} sw=${newConfig.screenWidthDp}")
            // Prefer physical bounds over dp for encoder size.
            resolveDisplaySize()
            scope.launch { handleResize(displayWidth, displayHeight) }
        }
        configCallback = listener
        applicationContext.registerComponentCallbacks(listener)
    }

    private fun resolveDisplaySize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            displayWidth = bounds.width().coerceAtLeast(1)
            displayHeight = bounds.height().coerceAtLeast(1)
        } else {
            displayWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
            displayHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        }
    }

    private fun thermalStatus(): Int? {
        return getSystemService(PowerManager::class.java)?.currentThermalStatus
    }

    private fun batteryPct(): Int? {
        val manager = getSystemService(BatteryManager::class.java) ?: return null
        val value = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (value >= 0) value else null
    }

    private fun Intent.parcelableIntent(key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    private class ConfigListener(
        private val onChange: (Configuration) -> Unit,
    ) : android.content.ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = onChange(newConfig)
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
        override fun onTrimMemory(level: Int) = Unit
    }

    companion object {
        const val ACTION_START = "com.bugsnap.ai.spike.START"
        const val ACTION_MARK = "com.bugsnap.ai.spike.MARK"
        const val ACTION_STOP = "com.bugsnap.ai.spike.STOP"
        const val ACTION_REUSE_TOKEN = "com.bugsnap.ai.spike.REUSE_TOKEN"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
