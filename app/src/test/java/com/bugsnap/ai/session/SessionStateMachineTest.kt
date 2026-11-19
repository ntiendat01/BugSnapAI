package com.bugsnap.ai.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class SessionStateMachineTest {
    @Test
    fun initialStateIsIdle() {
        assertEquals(SessionSnapshot.Idle, SessionStateMachine.initial())
    }

    @Test
    fun validTransitionsMatchSpec() {
        for (case in TransitionMatrix.valid) {
            val result = SessionStateMachine.reduce(case.from, case.event)
            assertEquals(
                "expected ${case.from.state} + ${case.event} -> ${case.to.state}",
                ReduceResult.Applied(case.to),
                result,
            )
        }
    }

    @Test
    fun everyUnlistedPairIsIllegalAndDoesNotChangeState() {
        val validKeys = TransitionMatrix.valid.map { it.from to it.event }.toSet()
        val failures = mutableListOf<String>()
        for (from in TransitionMatrix.allSnapshots) {
            for (event in TransitionMatrix.allEvents) {
                if (from to event in validKeys) continue
                val result = SessionStateMachine.reduce(from, event)
                if (result != ReduceResult.IllegalTransition(from, event)) {
                    failures += "expected IllegalTransition for ${from.state}/${from.pauseReason}/${from.failureReason} + $event, got $result"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun reduceNeverThrows() {
        for (from in TransitionMatrix.allSnapshots) {
            for (event in TransitionMatrix.allEvents) {
                SessionStateMachine.reduce(from, event)
            }
        }
    }

    @Test
    fun markBugStaysRecordingAndIsIllegalElsewhere() {
        val recording = SessionSnapshot(SessionState.RECORDING)
        assertEquals(
            ReduceResult.Applied(recording),
            SessionStateMachine.reduce(recording, SessionEvent.MarkBug),
        )
        val others = TransitionMatrix.allSnapshots.filter { it.state != SessionState.RECORDING }
        for (from in others) {
            assertTrue(
                SessionStateMachine.reduce(from, SessionEvent.MarkBug) is ReduceResult.IllegalTransition,
            )
        }
    }

    @Test
    fun processDiedFailsInFlightSessionsOnly() {
        val inFlight = listOf(
            SessionSnapshot(SessionState.PREPARING),
            SessionSnapshot(SessionState.RECORDING),
            SessionSnapshot(SessionState.PAUSED, pauseReason = PauseReason.USER),
            SessionSnapshot(SessionState.STOPPING),
        )
        val failed = SessionSnapshot(SessionState.FAILED, failureReason = FailureReason.PROCESS_DIED)
        for (from in inFlight) {
            assertEquals(
                ReduceResult.Applied(failed),
                SessionStateMachine.reduce(from, SessionEvent.ProcessDied),
            )
        }
        for (terminal in listOf(
            SessionSnapshot.Idle,
            SessionSnapshot(SessionState.COMPLETED),
            SessionSnapshot(SessionState.FAILED, failureReason = FailureReason.ENGINE),
        )) {
            assertTrue(
                SessionStateMachine.reduce(terminal, SessionEvent.ProcessDied) is ReduceResult.IllegalTransition,
            )
        }
    }
}

@RunWith(Parameterized::class)
class SessionStateMachineValidTransitionTest(
    private val case: TransitionCase,
) {
    @Test
    fun applies() {
        assertEquals(ReduceResult.Applied(case.to), SessionStateMachine.reduce(case.from, case.event))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<TransitionCase> = TransitionMatrix.valid
    }
}

data class TransitionCase(
    val from: SessionSnapshot,
    val event: SessionEvent,
    val to: SessionSnapshot,
) {
    override fun toString(): String {
        val fromExtra = from.pauseReason ?: from.failureReason
        val toExtra = to.pauseReason ?: to.failureReason
        val fromLabel = if (fromExtra == null) from.state.name else "${from.state}($fromExtra)"
        val toLabel = if (toExtra == null) to.state.name else "${to.state}($toExtra)"
        return "$fromLabel + ${event::class.simpleName} -> $toLabel"
    }
}

object TransitionMatrix {
    val allEvents: List<SessionEvent> = listOf(
        SessionEvent.CreateAndStart,
        SessionEvent.ProjectionReady,
        SessionEvent.PermissionDenied,
        SessionEvent.EngineFailed,
        SessionEvent.UserPause,
        SessionEvent.ProjectionLost,
        SessionEvent.LowStorage,
        SessionEvent.ScreenLocked,
        SessionEvent.Resume,
        SessionEvent.PermissionRestored,
        SessionEvent.Stop,
        SessionEvent.FlushCompleted,
        SessionEvent.FlushFailed,
        SessionEvent.EncoderFailed,
        SessionEvent.StorageFailed,
        SessionEvent.ProcessDied,
        SessionEvent.MarkBug,
    )

    val allSnapshots: List<SessionSnapshot> = buildList {
        add(SessionSnapshot.Idle)
        add(SessionSnapshot(SessionState.PREPARING))
        add(SessionSnapshot(SessionState.RECORDING))
        for (reason in PauseReason.entries) {
            add(SessionSnapshot(SessionState.PAUSED, pauseReason = reason))
        }
        add(SessionSnapshot(SessionState.STOPPING))
        add(SessionSnapshot(SessionState.COMPLETED))
        for (reason in FailureReason.entries) {
            add(SessionSnapshot(SessionState.FAILED, failureReason = reason))
        }
    }

    private fun t(from: SessionSnapshot, event: SessionEvent, to: SessionSnapshot) =
        TransitionCase(from, event, to)

    private val idle = SessionSnapshot.Idle
    private val preparing = SessionSnapshot(SessionState.PREPARING)
    private val recording = SessionSnapshot(SessionState.RECORDING)
    private val stopping = SessionSnapshot(SessionState.STOPPING)
    private val completed = SessionSnapshot(SessionState.COMPLETED)
    private fun paused(reason: PauseReason) = SessionSnapshot(SessionState.PAUSED, pauseReason = reason)
    private fun failed(reason: FailureReason) = SessionSnapshot(SessionState.FAILED, failureReason = reason)

    val valid: List<TransitionCase> = buildList {
        add(t(idle, SessionEvent.CreateAndStart, preparing))
        add(t(preparing, SessionEvent.ProjectionReady, recording))
        add(t(preparing, SessionEvent.PermissionDenied, idle))
        add(t(preparing, SessionEvent.EngineFailed, failed(FailureReason.ENGINE)))
        add(t(preparing, SessionEvent.ProcessDied, failed(FailureReason.PROCESS_DIED)))
        add(t(recording, SessionEvent.UserPause, paused(PauseReason.USER)))
        add(t(recording, SessionEvent.ProjectionLost, paused(PauseReason.PROJECTION_LOST)))
        add(t(recording, SessionEvent.LowStorage, paused(PauseReason.LOW_STORAGE)))
        add(t(recording, SessionEvent.ScreenLocked, paused(PauseReason.SCREEN_LOCKED)))
        add(t(recording, SessionEvent.Stop, stopping))
        add(t(recording, SessionEvent.EncoderFailed, failed(FailureReason.ENCODER)))
        add(t(recording, SessionEvent.StorageFailed, failed(FailureReason.STORAGE)))
        add(t(recording, SessionEvent.ProcessDied, failed(FailureReason.PROCESS_DIED)))
        add(t(recording, SessionEvent.MarkBug, recording))
        for (reason in PauseReason.entries) {
            add(t(paused(reason), SessionEvent.Resume, recording))
            add(t(paused(reason), SessionEvent.PermissionRestored, recording))
            add(t(paused(reason), SessionEvent.Stop, stopping))
            add(t(paused(reason), SessionEvent.ProcessDied, failed(FailureReason.PROCESS_DIED)))
        }
        add(t(stopping, SessionEvent.FlushCompleted, completed))
        add(t(stopping, SessionEvent.FlushFailed, failed(FailureReason.FLUSH)))
        add(t(stopping, SessionEvent.ProcessDied, failed(FailureReason.PROCESS_DIED)))
    }
}
