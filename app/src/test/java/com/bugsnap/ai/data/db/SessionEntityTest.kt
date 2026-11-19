package com.bugsnap.ai.data.db

import com.bugsnap.ai.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class SessionEntityTest {
    @Test
    fun acceptsFifteenAndThirtySecondBuffers() {
        assertEquals(15, session(bufferSec = 15).bufferSec)
        assertEquals(30, session(bufferSec = 30).bufferSec)
        assertEquals(30, session().bufferSec)
    }

    @Test
    fun rejectsBuffersOutsideFifteenOrThirty() {
        for (invalid in listOf(0, 10, 20, 60, 90)) {
            try {
                session(bufferSec = invalid)
                fail("expected bufferSec=$invalid to be rejected")
            } catch (error: IllegalArgumentException) {
                assertFalse(error.message.isNullOrBlank())
            }
        }
    }

    @Test
    fun pendingDeleteDefaultsFalse() {
        assertFalse(session().pendingDelete)
    }

    private fun session(bufferSec: Int = 30) = SessionEntity(
        id = "s1",
        name = "test",
        state = SessionState.IDLE,
        bufferSec = bufferSec,
        createdAt = 1L,
    )
}
