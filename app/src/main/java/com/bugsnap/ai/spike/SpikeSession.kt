package com.bugsnap.ai.spike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
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
    val clipPath: String,
    val durationMs: Long,
    val actualPreRollMs: Long,
    val actualPostRollMs: Long,
    val latencyMs: Long,
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
)

@Singleton
class SpikeSession @Inject constructor() {
    private val _state = MutableStateFlow(SpikeUiState())
    val state: StateFlow<SpikeUiState> = _state.asStateFlow()
    private val markSequence = AtomicLong(0)

    fun nextMarkerId(): String = "m${markSequence.incrementAndGet()}"

    fun update(transform: (SpikeUiState) -> SpikeUiState) {
        _state.update(transform)
    }

    fun setPhase(phase: SpikePhase, message: String? = null) {
        update { it.copy(phase = phase, message = message, lastError = if (phase == SpikePhase.Idle) it.lastError else it.lastError) }
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
                clips = (listOf(info) + it.clips).take(20),
                exportingCount = (it.exportingCount - 1).coerceAtLeast(0),
            )
        }
    }

    fun resetForNewSession() {
        markSequence.set(0)
        _state.value = SpikeUiState(phase = SpikePhase.Starting)
    }
}
