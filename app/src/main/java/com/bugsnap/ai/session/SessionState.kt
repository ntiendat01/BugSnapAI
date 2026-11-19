package com.bugsnap.ai.session

/** Lifecycle states of a test session. Matches docs/05 §5 (seven states, no PENDING_DELETE). */
enum class SessionState {
    IDLE,
    PREPARING,
    RECORDING,
    PAUSED,
    STOPPING,
    COMPLETED,
    FAILED,
}

/** Why a session is PAUSED. PROCESS_DIED is a [FailureReason], not a pause reason. */
enum class PauseReason {
    USER,
    PROJECTION_LOST,
    LOW_STORAGE,
    SCREEN_LOCKED,
}

/** Why a session is FAILED. */
enum class FailureReason {
    PROCESS_DIED,
    ENGINE,
    FLUSH,
    ENCODER,
    STORAGE,
}

data class SessionSnapshot(
    val state: SessionState,
    val pauseReason: PauseReason? = null,
    val failureReason: FailureReason? = null,
) {
    init {
        require((pauseReason != null) == (state == SessionState.PAUSED)) {
            "pauseReason must be set only when PAUSED, was $state / $pauseReason"
        }
        require((failureReason != null) == (state == SessionState.FAILED)) {
            "failureReason must be set only when FAILED, was $state / $failureReason"
        }
    }

    companion object {
        val Idle: SessionSnapshot = SessionSnapshot(SessionState.IDLE)
    }
}

sealed interface SessionEvent {
    data object CreateAndStart : SessionEvent
    data object ProjectionReady : SessionEvent
    data object PermissionDenied : SessionEvent
    data object EngineFailed : SessionEvent
    data object UserPause : SessionEvent
    data object ProjectionLost : SessionEvent
    data object LowStorage : SessionEvent
    data object ScreenLocked : SessionEvent
    data object Resume : SessionEvent
    data object PermissionRestored : SessionEvent
    data object Stop : SessionEvent
    data object FlushCompleted : SessionEvent
    data object FlushFailed : SessionEvent
    data object EncoderFailed : SessionEvent
    data object StorageFailed : SessionEvent
    data object ProcessDied : SessionEvent
    /** Does not change state; only legal while [SessionState.RECORDING]. */
    data object MarkBug : SessionEvent
}

sealed class ReduceResult {
    data class Applied(val snapshot: SessionSnapshot) : ReduceResult()

    data class IllegalTransition(
        val snapshot: SessionSnapshot,
        val event: SessionEvent,
    ) : ReduceResult()
}
