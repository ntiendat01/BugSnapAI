package com.bugsnap.ai.capture.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.view.Surface
import com.bugsnap.ai.capture.buffer.EncodedSample
import com.bugsnap.ai.capture.buffer.Generation
import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.core.common.DomainException

/** Polling and all lifecycle operations share one worker: no dequeue/stop race or async callback dependency. */
internal class EncoderPipeline(
    private val handler: Handler,
    private val tracker: CaptureResourceTracker,
    private val events: CaptureBackend.Events,
) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var started = false
    private var generationId = 0
    private var configuration: CaptureConfig? = null
    private var published: Generation? = null
    private var lastPtsUs = -1L
    private val info = MediaCodec.BufferInfo()
    private val syncRequests = SyncFrameRequests()
    private val drain = object : Runnable {
        override fun run() {
            val current = codec ?: return
            try {
                var drained = 0
                while (drained++ < 8) {
                    val index = current.dequeueOutputBuffer(info, 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> publish(current.outputFormat)
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        index >= 0 -> {
                            consume(current, index)
                            check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "Encoder ended before capture was stopped" }
                        }
                        else -> break
                    }
                }
                if (codec === current && started) handler.postDelayed(this, 5)
            } catch (error: Exception) {
                CaptureLog.error("Encoder output failed gen=$generationId", error)
                events.onStopped(DomainError.EncoderFailure(error))
            }
        }
    }
    private val sync = object : Runnable {
        override fun run() {
            val current = codec ?: return
            val accepted = syncRequests.attempt(
                request = { current.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) },
                rejected = { CaptureLog.error("Optional sync request rejected; continuing capture gen=$generationId", it) },
            )
            if (accepted) handler.postDelayed(this, 1_000)
        }
    }

    fun start(config: CaptureConfig): Surface {
        check(codec == null)
        generationId++
        configuration = config
        published = null
        lastPtsUs = -1
        syncRequests.reset()
        try {
            return open(config, repeatFrames = true)
        } catch (error: Exception) {
            try { close() } catch (cleanup: Exception) { error.addSuppressed(cleanup); throw error }
            CaptureLog.error("Retry encoder without repeat-previous-frame", error)
            return try { open(config, repeatFrames = false) } catch (fallback: Exception) {
                try { close() } catch (cleanup: Exception) { fallback.addSuppressed(cleanup) }
                throw DomainException(DomainError.EncoderFailure(fallback), fallback)
            }
        }
    }

    private fun open(config: CaptureConfig, repeatFrames: Boolean): Surface {
        val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = created
        tracker.acquired(CaptureResource.CODEC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.frameRate.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (repeatFrames) setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000)
        }
        created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = created.createInputSurface()
        surface = input
        tracker.acquired(CaptureResource.SURFACE)
        created.start()
        started = true
        handler.post(drain)
        handler.postDelayed(sync, 1_000)
        CaptureLog.info("Encoder opened polling gen=$generationId ${config.width}x${config.height} fps=${config.frameRate} codec=${created.name}")
        return input
    }

    private fun publish(format: MediaFormat) {
        val config = checkNotNull(configuration)
        val generation = Generation(generationId, config.width, config.height, MediaFormat.MIMETYPE_VIDEO_AVC,
            bytes(format, "csd-0") ?: error("Missing H.264 SPS"), bytes(format, "csd-1"), 0)
        check(published?.sameConfiguration(generation) != false) { "Codec changed format within one generation" }
        published = generation
        events.onGeneration(generation)
    }

    private fun consume(current: MediaCodec, index: Int) {
        try {
            if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            if (published == null) publish(current.outputFormat)
            // Baseline does not reorder pictures. Dropping a reference frame here would corrupt later frames.
            check(info.presentationTimeUs > lastPtsUs) { "Non-monotonic encoder output at ${info.presentationTimeUs}, previous=$lastPtsUs" }
            val output = checkNotNull(current.getOutputBuffer(index)).duplicate()
            output.position(info.offset)
            output.limit(info.offset + info.size)
            val payload = ByteArray(info.size)
            output.get(payload)
            lastPtsUs = info.presentationTimeUs
            events.onSample(EncodedSample(payload, info.presentationTimeUs, info.flags, generationId))
        } finally {
            current.releaseOutputBuffer(index, false)
        }
    }

    fun close() {
        handler.removeCallbacks(drain)
        handler.removeCallbacks(sync)
        val current = codec
        val input = surface
        val wasStarted = started
        codec = null
        surface = null
        started = false
        releaseAll(
            { if (current != null && wasStarted) current.stop() },
            { if (current != null) { current.release(); tracker.released(CaptureResource.CODEC) } },
            { if (input != null) { input.release(); tracker.released(CaptureResource.SURFACE) } },
        )
    }

    private fun bytes(format: MediaFormat, key: String): ByteArray? {
        val buffer = format.getByteBuffer(key)?.duplicate() ?: return null
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}
