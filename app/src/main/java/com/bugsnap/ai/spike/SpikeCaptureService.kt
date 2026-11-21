package com.bugsnap.ai.spike

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.bugsnap.ai.R
import com.bugsnap.ai.capture.buffer.ClipSnapshot
import com.bugsnap.ai.capture.buffer.EncodedSample
import com.bugsnap.ai.capture.buffer.MarkResult
import com.bugsnap.ai.capture.engine.CaptureEngine
import com.bugsnap.ai.capture.engine.CaptureEngineFactory
import com.bugsnap.ai.capture.engine.CapturePhase
import com.bugsnap.ai.capture.export.MarkerExportQueue
import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.core.common.DomainException
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Spike adapter: FGS, controls and export feedback. Native capture belongs to CaptureEngine. */
@AndroidEntryPoint
class SpikeCaptureService : Service() {
    @Inject lateinit var session: SpikeSession
    @Inject lateinit var exportFeedback: SpikeExportFeedback
    @Inject lateinit var engineFactory: CaptureEngineFactory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val metrics = SpikeMetrics()
    private lateinit var exportQueue: MarkerExportQueue
    private lateinit var overlay: SpikeOverlay
    private var engine: CaptureEngine? = null
    private var heartbeatJob: Job? = null
    private var stateJob: Job? = null
    private var finishJob: Job? = null
    private lateinit var cleanupJob: Job
    private var lastMarkAtMs = -1_000L
    private var startedAtMs = 0L
    private var finishing = false

    override fun onCreate() {
        super.onCreate()
        SpikeNotifications.ensureChannels(this)
        val muxer = AndroidClipMuxer(File(filesDir, "spike"))
        cleanupJob = scope.launch(Dispatchers.IO) {
            try { muxer.deleteOrphanParts() }
            catch (error: Exception) {
                SpikeLog.w("spike partial cleanup failed", error)
                session.setError(getString(R.string.evidence_cleanup_failed))
            }
        }
        exportQueue = MarkerExportQueue(muxer, ".",
            onResult = { result -> scope.launch { exportFeedback.complete(result); finishWhenReady() } },
            onStarted = { id -> session.markerStage(id, MarkerStage.Exporting) })
        overlay = SpikeOverlay(this, onMark = { mark() }, onStop = { stopCapture() })
        scope.launch {
            session.state.collect { state ->
                overlay.render(state)
                if (engine?.state?.value?.phase in LIVE || state.markers.any { it.pending }) {
                    SpikeNotifications.update(this@SpikeCaptureService, state)
                }
            }
        }
        scope.launch {
            while (isActive) {
                session.update { it.copy(nowMs = SystemClock.elapsedRealtime()) }
                delay(250)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_MARK -> mark()
            ACTION_STOP -> stopCapture()
            ACTION_REUSE_TOKEN -> session.update { it.copy(lastTokenReuseResult = getString(R.string.capture_single_use_consent)) }
            else -> SpikeLog.w("unknown capture command")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlay.dismiss()
        engine?.dispose()
        engine = null
        exportQueue.shutdown(waitForTermination = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (engine?.state?.value?.phase in LIVE) return
        val data = intent.projectionData() ?: run {
            session.setError(getString(R.string.capture_consent_missing))
            stopSelf()
            return
        }
        finishJob?.cancel()
        finishing = false
        startForeground(SpikeNotifications.CAPTURE_ID, SpikeNotifications.captureNotification(this, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        engine?.dispose()
        var owner: CaptureEngine? = null
        val current = engineFactory.create(scope, intent.getIntExtra(EXTRA_RESULT_CODE, 0), data,
            object : CaptureEngine.Listener {
                override fun onSample(sample: EncodedSample) {
                    if (engine === owner) metrics.onEncodedFrame(sample.ptsUs, sample.isKeyFrame)
                }
                override fun onClip(snapshot: ClipSnapshot) { submitClip(snapshot) }
                override fun onStopped(error: DomainError?) {
                    scope.launch {
                        if (engine !== owner) return@launch
                        if (error != null) {
                            SpikeLog.w("capture engine stopped", DomainException(error))
                            session.setError(getString(if (error == DomainError.ProjectionLost)
                                R.string.spike_status_projection_lost else R.string.capture_engine_failed))
                        }
                        session.setPhase(if (error == DomainError.ProjectionLost) SpikePhase.ProjectionLost else SpikePhase.Stopped)
                        finishWhenReady()
                    }
                }
            })
        owner = current
        engine = current
        startedAtMs = SystemClock.elapsedRealtime()
        lastMarkAtMs = -1_000
        stateJob?.cancel()
        stateJob = scope.launch {
            current.state.collect { state ->
                if (state.phase == CapturePhase.STARTING) session.setPhase(SpikePhase.Starting)
                if (state.phase == CapturePhase.RECORDING) session.setPhase(SpikePhase.Recording)
                session.update { it.copy(encodeWidth = state.config?.width ?: 0, encodeHeight = state.config?.height ?: 0,
                    generation = state.generation, thermalStatus = state.thermalStatus, overlayActive = overlay.isShowing) }
            }
        }
        scope.launch {
            cleanupJob.join()
            val bounds = engineFactory.displayBounds()
            val result = current.start(bounds.first, bounds.second, resources.displayMetrics.densityDpi)
            if (result.isErr && current.state.value.phase == CapturePhase.IDLE) {
                current.dispose()
                session.setPhase(SpikePhase.Stopped)
                session.setError(getString(R.string.capture_engine_failed))
                finishWhenReady()
            }
        }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.Default) { heartbeat(current) }
    }

    private fun submitClip(snapshot: ClipSnapshot) {
        session.markerStage(snapshot.id, MarkerStage.Queued)
        exportQueue.submit(snapshot)
    }

    fun mark(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMarkAtMs < 300) return false
        val current = engine
        val id = session.nextMarkerId()
        val result = current?.mark(id)
        if (result == null || result is MarkResult.Rejected) {
            session.setError(getString(R.string.marker_not_ready))
            return false
        }
        lastMarkAtMs = now
        session.acceptMarker(id, now)
        if (result is MarkResult.Ready) submitClip(result.snapshot)
        return true
    }

    private fun stopCapture() {
        val current = engine ?: return
        scope.launch { current.stop(); finishWhenReady() }
    }

    private fun finishWhenReady() {
        if (engine?.state?.value?.phase in LIVE || session.state.value.markers.any { it.pending } || finishing) return
        finishing = true
        finishJob = scope.launch {
            delay(4_000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun heartbeat(current: CaptureEngine) {
        var minute = 0L
        var previousThermal = -1
        while (scope.isActive && engine === current && current.state.value.phase !in TERMINAL) {
            val stats = current.buffer.stats()
            val thermal = current.state.value.thermalStatus
            val elapsed = SystemClock.elapsedRealtime() - startedAtMs
            val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            metrics.logRam(stats.ramBytes, "heartbeat")
            session.update { it.copy(bufferDurationMs = stats.durationUs / 1_000, bufferRamBytes = stats.ramBytes,
                javaHeapBytes = metrics.javaHeapBytes(), encodedFps = metrics.encodedFps,
                lastKeyframeIntervalMs = metrics.lastKeyframeIntervalMs().takeIf { value -> value >= 0 },
                keyframeCount = metrics.keyframeCount(), recordingElapsedMs = elapsed, batteryPct = battery.takeIf { value -> value >= 0 }) }
            if (elapsed / 60_000 > minute) {
                minute = elapsed / 60_000
                SpikeLog.i("heartbeat minute=$minute stillRunning=true thermal=$thermal batteryPct=$battery")
            }
            if (thermal >= 3 && thermal != previousThermal) session.setError(getString(R.string.spike_thermal_severe))
            previousThermal = thermal
            delay(1_000)
        }
    }

    private fun Intent.projectionData(): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(EXTRA_RESULT_DATA)
    }

    companion object {
        private val LIVE = setOf(CapturePhase.STARTING, CapturePhase.RECORDING, CapturePhase.PAUSED, CapturePhase.STOPPING)
        private val TERMINAL = setOf(CapturePhase.STOPPED, CapturePhase.FAILED)
        const val ACTION_START = "com.bugsnap.ai.spike.START"
        const val ACTION_MARK = "com.bugsnap.ai.spike.MARK"
        const val ACTION_STOP = "com.bugsnap.ai.spike.STOP"
        const val ACTION_REUSE_TOKEN = "com.bugsnap.ai.spike.REUSE_TOKEN"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
