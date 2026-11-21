package com.bugsnap.ai.capture.engine

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.core.common.DomainException

internal class ProjectionHolder(
    private val context: Context,
    private val handler: Handler,
    private val tracker: CaptureResourceTracker,
) {
    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var consumed = false

    fun open(resultCode: Int, data: Intent, listener: MediaProjection.Callback): MediaProjection {
        check(!consumed) { "Projection consent is single use" }
        consumed = true
        val created = try {
            context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
                ?: throw DomainException(DomainError.ProjectionLost)
        } catch (error: SecurityException) {
            throw DomainException(DomainError.PermissionDenied(), error)
        }
        projection = created
        tracker.acquired(CaptureResource.PROJECTION)
        created.registerCallback(listener, handler)
        callback = listener
        return created
    }

    fun close() {
        val current = projection ?: return
        projection = null
        val listener = callback
        callback = null
        releaseAll(
            { if (listener != null) current.unregisterCallback(listener) },
            { current.stop(); tracker.released(CaptureResource.PROJECTION) },
        )
    }
}
