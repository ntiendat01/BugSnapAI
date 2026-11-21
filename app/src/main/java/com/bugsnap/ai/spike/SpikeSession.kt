package com.bugsnap.ai.spike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SpikePhase {
    Idle,
    Starting,
    Recording,
    ProjectionLost,
    Stopped,
}

data class SpikeClipInfo(
    val id: String,
    /** MediaStore content URI for new clips; private file path for legacy recordings. */
    val clipPath: String,
    val durationMs: Long,
    val actualPreRollMs: Long,
    val actualPostRollMs: Long,
    val latencyMs: Long,
    val number: Int = 1,
    val imageCount: Int = 2,
    val savedAtMs: Long = 0,
)

data class SpikeUiState(
    val phase: SpikePhase = SpikePhase.Idle,
    val message: String? = null,
    val bufferDurationMs: Long = 0,
    val bufferRamBytes: Long = 0,
    val javaHeapBytes: Long = 0,
    val encodedFps: Double = 0.0,
    val lastKeyframeIntervalMs: Long? = null,
    val keyframeCount: Int = 0,
    val generation: Int = 0,
    val encodeWidth: Int = 0,
    val encodeHeight: Int = 0,
    val lastMarkLatencyMs: Long? = null,
    val lastClip: SpikeClipInfo? = null,
    val clips: List<SpikeClipInfo> = emptyList(),
    val lastError: String? = null,
    val lastTokenReuseResult: String? = null,
    val exportingCount: Int = 0,
    val overlayActive: Boolean = false,
    val recordingElapsedMs: Long = 0,
    val thermalStatus: Int? = null,
    val batteryPct: Int? = null,
    val markers: List<SpikeMarker> = emptyList(),
    val quickMark: Boolean = true,
    val nowMs: Long = 0,
    val appVisible: Boolean = false,
)

@Singleton
class SpikeSession @Inject constructor() {
    private val _state = MutableStateFlow(SpikeUiState())
    val state: StateFlow<SpikeUiState> = _state.asStateFlow()
    private val markSequence = AtomicLong(0)

    fun nextMarkerId(): String = UUID.randomUUID().toString()

    fun acceptMarker(id: String, nowMs: Long) {
        val marker = SpikeMarker(id, markSequence.incrementAndGet().toInt(), nowMs, nowMs + 5_000, acceptedInApp = state.value.appVisible)
        update { it.copy(markers = it.markers + marker, lastError = null, nowMs = nowMs) }
    }

    fun markerStage(id: String, stage: MarkerStage, error: String? = null) {
        update { state ->
            val markers = state.markers.map { if (it.id == id) it.copy(stage = stage, error = error) else it }
            state.copy(markers = markers, exportingCount = markers.count { it.pending })
        }
    }

    fun update(transform: (SpikeUiState) -> SpikeUiState) {
        _state.update(transform)
    }

    fun setPhase(phase: SpikePhase, message: String? = null) {
        update { it.copy(phase = phase, message = message) }
    }

    fun setError(message: String) {
        SpikeLog.e(message)
        update { it.copy(lastError = message, message = message) }
    }

    fun addClip(info: SpikeClipInfo) {
        update {
            it.copy(
                lastClip = info,
                lastMarkLatencyMs = info.latencyMs,
                clips = (listOf(info) + it.clips.filterNot { clip -> clip.id == info.id }).sortedByDescending { clip -> clip.savedAtMs },
            )
        }
    }

    fun resetForNewSession() {
        markSequence.set(0)
        update { it.copy(phase = SpikePhase.Starting, markers = emptyList(), lastError = null, lastTokenReuseResult = null) }
    }
}
