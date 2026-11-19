package com.bugsnap.ai.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    @Test
    fun fakeClockIsDeterministic() {
        val clock = Clock { 1_700_000_000_000L }
        assertEquals(1_700_000_000_000L, clock.nowEpochMs())
    }

    @Test
    fun systemClockIsNearWallClock() {
        val before = System.currentTimeMillis()
        val now = Clock.System.nowEpochMs()
        val after = System.currentTimeMillis()
        assertTrue(now in before..after)
    }
}
