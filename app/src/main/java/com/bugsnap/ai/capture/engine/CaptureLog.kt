package com.bugsnap.ai.capture.engine

import android.util.Log
import com.bugsnap.ai.BuildConfig

internal object CaptureLog {
    fun info(message: String) { if (BuildConfig.DEBUG) Log.i("BugSnapCapture", message) }
    fun error(message: String, error: Throwable) { if (BuildConfig.DEBUG) Log.w("BugSnapCapture", message, error) }
}

internal fun releaseAll(vararg actions: () -> Unit) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}
