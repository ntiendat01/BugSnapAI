package com.bugsnap.ai.capture.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFrameRequestsTest {
    @Test fun successfulRequestsContinue() {
        val policy = SyncFrameRequests()
        var calls = 0
        repeat(3) { assertTrue(policy.attempt({ calls++ }, { throw AssertionError(it) })) }
        assertEquals(3, calls)
    }

    @Test fun rejectedVendorParameterDoesNotEscapeOrRepeatUntilNewGeneration() {
        val policy = SyncFrameRequests()
        var requests = 0
        var reported = 0
        val request = { requests++; throw IllegalStateException("Vendor rejected sync request") }
        repeat(3) { assertFalse(policy.attempt(request) { reported++ }) }
        assertEquals(1, requests)
        assertEquals(1, reported)
        policy.reset()
        assertTrue(policy.attempt({ requests++ }, { throw AssertionError(it) }))
        assertEquals(2, requests)
    }
}
