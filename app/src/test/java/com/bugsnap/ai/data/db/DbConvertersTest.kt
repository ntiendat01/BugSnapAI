package com.bugsnap.ai.data.db

import com.bugsnap.ai.session.FailureReason
import com.bugsnap.ai.session.PauseReason
import com.bugsnap.ai.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DbConvertersTest {
    private val converters = DbConverters()

    @Test
    fun sessionStateRoundTrip() {
        for (state in SessionState.entries) {
            assertEquals(state, converters.toSessionState(converters.fromSessionState(state)))
        }
    }

    @Test
    fun pauseAndFailureReasonsRoundTripAndNull() {
        for (reason in PauseReason.entries) {
            assertEquals(reason, converters.toPauseReason(converters.fromPauseReason(reason)))
        }
        assertNull(converters.fromPauseReason(null))
        assertNull(converters.toPauseReason(null))
        for (reason in FailureReason.entries) {
            assertEquals(reason, converters.toFailureReason(converters.fromFailureReason(reason)))
        }
        assertNull(converters.toFailureReason(null))
    }

    @Test
    fun clipStatusAndEvidenceTypeRoundTrip() {
        for (status in ClipStatus.entries) {
            assertEquals(status, converters.toClipStatus(converters.fromClipStatus(status)))
        }
        for (type in EvidenceType.entries) {
            assertEquals(type, converters.toEvidenceType(converters.fromEvidenceType(type)))
        }
    }
}
