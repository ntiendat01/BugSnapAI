package com.bugsnap.ai.spike

import android.os.Debug
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SpikeMetrics {
    private val encodedSinceTick = AtomicInteger(0)
    private val keyframes = AtomicInteger(0)
    private val lastKeyframePtsUs = AtomicLong(-1L)
    private val lastKeyframeIntervalMs = AtomicLong(-1L)
    @Volatile var encodedFps: Double = 0.0
        private set
    private var tickStartMs = SystemClock.elapsedRealtime()

    fun onEncodedFrame(ptsUs: Long, isKeyFrame: Boolean) {
        encodedSinceTick.incrementAndGet()
        if (isKeyFrame) {
            keyframes.incrementAndGet()
            val previous = lastKeyframePtsUs.getAndSet(ptsUs)
            if (previous >= 0L) {
                val intervalMs = (ptsUs - previous) / 1_000L
                lastKeyframeIntervalMs.set(intervalMs)
                SpikeLog.i("keyframe ptsUs=$ptsUs intervalMs=$intervalMs totalKeys=${keyframes.get()}")
            } else {
                SpikeLog.i("keyframe first ptsUs=$ptsUs")
            }
        }
        maybePublishFps()
    }

    fun lastKeyframeIntervalMs(): Long = lastKeyframeIntervalMs.get()

    fun keyframeCount(): Int = keyframes.get()

    fun javaHeapBytes(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    fun nativeHeapBytes(): Long = Debug.getNativeHeapAllocatedSize()

    fun logRam(bufferBytes: Long, label: String = "ram") {
        SpikeLog.i(
            "$label bufferBytes=$bufferBytes javaHeap=${javaHeapBytes()} " +
                "nativeHeap=${nativeHeapBytes()} fps=${"%.1f".format(encodedFps)}",
        )
    }

    fun logMarkLatency(
        markerId: String,
        latencyMs: Long,
        actualPreRollMs: Long,
        actualPostRollMs: Long,
        clipPath: String,
        durationMs: Long,
    ) {
        SpikeLog.i(
            "markDone id=$markerId latencyMs=$latencyMs actualPreRollMs=$actualPreRollMs " +
                "actualPostRollMs=$actualPostRollMs durationMs=$durationMs path=$clipPath",
        )
    }

    private fun maybePublishFps() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - tickStartMs
        if (elapsed >= 1_000L) {
            encodedFps = encodedSinceTick.getAndSet(0) * 1000.0 / elapsed
            tickStartMs = now
            SpikeLog.i("fps=${"%.1f".format(encodedFps)}")
        }
    }
}
