package com.bugsnap.ai.capture.engine

/** Optional vendor parameter: rejection disables requests, not the active recording. */
internal class SyncFrameRequests {
    private var enabled = true

    fun reset() { enabled = true }

    fun attempt(request: () -> Unit, rejected: (Exception) -> Unit): Boolean {
        if (!enabled) return false
        return try {
            request()
            true
        } catch (error: Exception) {
            enabled = false
            rejected(error)
            false
        }
    }
}
