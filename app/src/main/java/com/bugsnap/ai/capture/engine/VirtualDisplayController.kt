package com.bugsnap.ai.capture.engine

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface

internal class VirtualDisplayController(private val tracker: CaptureResourceTracker) {
    private var display: VirtualDisplay? = null
    private var created = false

    fun open(projection: MediaProjection, surface: Surface, config: CaptureConfig, handler: Handler) {
        check(!created) { "One VirtualDisplay per projection consent" }
        created = true
        display = checkNotNull(projection.createVirtualDisplay("BugSnapCapture", config.width, config.height,
            config.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler))
        tracker.acquired(CaptureResource.DISPLAY)
    }

    fun detach() { display?.surface = null }

    fun attach(surface: Surface, config: CaptureConfig) {
        val current = checkNotNull(display)
        current.resize(config.width, config.height, config.densityDpi)
        current.surface = surface
    }

    fun resize(config: CaptureConfig) { checkNotNull(display).resize(config.width, config.height, config.densityDpi) }

    fun close() {
        val current = display ?: return
        display = null
        current.release()
        tracker.released(CaptureResource.DISPLAY)
    }
}
