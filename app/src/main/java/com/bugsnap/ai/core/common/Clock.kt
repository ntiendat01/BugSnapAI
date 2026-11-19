package com.bugsnap.ai.core.common

/** Wall-clock time in epoch milliseconds (UTC). Abstracted so tests can freeze time. */
fun interface Clock {
    fun nowEpochMs(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
