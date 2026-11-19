package com.bugsnap.ai.session

/**
 * Pure-Kotlin session state machine (docs/05 §5).
 * Invalid transitions return [ReduceResult.IllegalTransition] and never throw.
 */
object SessionStateMachine {
    fun initial(): SessionSnapshot = SessionSnapshot.Idle

    fun reduce(state: SessionSnapshot, event: SessionEvent): ReduceResult {
        val next = nextSnapshot(state, event)
        return if (next == null) {
            ReduceResult.IllegalTransition(state, event)
        } else {
            ReduceResult.Applied(next)
        }
    }

    private fun nextSnapshot(current: SessionSnapshot, event: SessionEvent): SessionSnapshot? {
        return when (current.state) {
            SessionState.IDLE -> when (event) {
                SessionEvent.CreateAndStart -> SessionSnapshot(SessionState.PREPARING)
                else -> null
            }
            SessionState.PREPARING -> when (event) {
                SessionEvent.ProjectionReady -> SessionSnapshot(SessionState.RECORDING)
                SessionEvent.PermissionDenied -> SessionSnapshot(SessionState.IDLE)
                SessionEvent.EngineFailed -> failed(FailureReason.ENGINE)
                SessionEvent.ProcessDied -> failed(FailureReason.PROCESS_DIED)
                else -> null
            }
            SessionState.RECORDING -> when (event) {
                SessionEvent.UserPause -> paused(PauseReason.USER)
                SessionEvent.ProjectionLost -> paused(PauseReason.PROJECTION_LOST)
                SessionEvent.LowStorage -> paused(PauseReason.LOW_STORAGE)
                SessionEvent.ScreenLocked -> paused(PauseReason.SCREEN_LOCKED)
                SessionEvent.Stop -> SessionSnapshot(SessionState.STOPPING)
                SessionEvent.EncoderFailed -> failed(FailureReason.ENCODER)
                SessionEvent.StorageFailed -> failed(FailureReason.STORAGE)
                SessionEvent.ProcessDied -> failed(FailureReason.PROCESS_DIED)
                SessionEvent.MarkBug -> current
                else -> null
            }
            SessionState.PAUSED -> when (event) {
                SessionEvent.Resume, SessionEvent.PermissionRestored ->
                    SessionSnapshot(SessionState.RECORDING)
                SessionEvent.Stop -> SessionSnapshot(SessionState.STOPPING)
                SessionEvent.ProcessDied -> failed(FailureReason.PROCESS_DIED)
                else -> null
            }
            SessionState.STOPPING -> when (event) {
                SessionEvent.FlushCompleted -> SessionSnapshot(SessionState.COMPLETED)
                SessionEvent.FlushFailed -> failed(FailureReason.FLUSH)
                SessionEvent.ProcessDied -> failed(FailureReason.PROCESS_DIED)
                else -> null
            }
            SessionState.COMPLETED, SessionState.FAILED -> null
        }
    }

    private fun paused(reason: PauseReason) = SessionSnapshot(SessionState.PAUSED, pauseReason = reason)

    private fun failed(reason: FailureReason) = SessionSnapshot(SessionState.FAILED, failureReason = reason)
}
