package com.bugsnap.ai.capture.engine

import android.content.Context
import android.content.Intent
import com.bugsnap.ai.core.common.DefaultDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

class CaptureEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
    private val tracker: CaptureResourceTracker,
) {
    fun create(parentScope: CoroutineScope, resultCode: Int, data: Intent, listener: CaptureEngine.Listener): CaptureEngine =
        CaptureEngine(AndroidCaptureBackend(context, resultCode, data, tracker), parentScope, dispatcher, listener)

    fun displayBounds(): Pair<Int, Int> = AndroidCaptureBackend.displayBounds(context)
}
