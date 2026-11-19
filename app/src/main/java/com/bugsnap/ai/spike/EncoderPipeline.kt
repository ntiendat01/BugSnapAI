package com.bugsnap.ai.spike

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.bugsnap.ai.capture.buffer.EncodedSample
import com.bugsnap.ai.capture.buffer.Generation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * H.264 MediaCodec encoder with a Surface input. Owns codec + surface and
 * must be [release]d in a finally / onStop path.
 */
class EncoderPipeline(
    private val scope: CoroutineScope,
    private val metrics: SpikeMetrics,
    private val onSample: (EncodedSample) -> Unit,
    private val onGeneration: (Generation) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val generationIds = AtomicInteger(0)
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainJob: Job? = null
    private var syncJob: Job? = null
    private var currentGenerationId: Int = -1
    private var width: Int = 0
    private var height: Int = 0

    @Synchronized
    fun start(width: Int, height: Int): Surface {
        releaseLocked()
        this.width = width
        this.height = height
        val generationId = generationIds.incrementAndGet()
        currentGenerationId = generationId
        val format = buildFormat(width, height, includeRepeatFrame = true)
        val created = try {
            openCodec(format, generationId)
        } catch (error: Exception) {
            SpikeLog.w("configure with REPEAT_PREVIOUS_FRAME failed, retrying without it", error)
            openCodec(buildFormat(width, height, includeRepeatFrame = false), generationId)
        }
        running.set(true)
        drainJob = scope.launch(Dispatchers.Default) { drainLoop(created.codec, generationId) }
        syncJob = scope.launch(Dispatchers.Default) { syncFrameLoop() }
        SpikeLog.i("encoder started gen=$generationId ${width}x$height bitrate=${EncodeSizing.BITRATE_BPS}")
        return created.surface
    }

    @Synchronized
    fun restart(width: Int, height: Int): Surface {
        SpikeLog.i("encoder restart requested ${width}x$height")
        return start(width, height)
    }

    fun requestSyncFrame() {
        val current = codec ?: return
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            current.setParameters(params)
        } catch (error: Exception) {
            SpikeLog.w("requestSyncFrame failed", error)
        }
    }

    fun release() {
        synchronized(this) { releaseLocked() }
    }

    private fun releaseLocked() {
        running.set(false)
        drainJob?.cancel()
        drainJob = null
        syncJob?.cancel()
        syncJob = null
        val currentCodec = codec
        codec = null
        val surface = inputSurface
        inputSurface = null
        try {
            currentCodec?.stop()
        } catch (error: Exception) {
            SpikeLog.w("codec.stop failed", error)
        }
        try {
            currentCodec?.release()
        } catch (error: Exception) {
            SpikeLog.w("codec.release failed", error)
        }
        try {
            surface?.release()
        } catch (error: Exception) {
            SpikeLog.w("surface.release failed", error)
        }
        SpikeLog.i("encoder released gen=$currentGenerationId")
    }

    private data class Opened(val codec: MediaCodec, val surface: Surface)

    private fun openCodec(format: MediaFormat, generationId: Int): Opened {
        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = mediaCodec.createInputSurface()
        mediaCodec.start()
        codec = mediaCodec
        inputSurface = surface
        return Opened(mediaCodec, surface)
    }

    private fun buildFormat(width: Int, height: Int, includeRepeatFrame: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, EncodeSizing.BITRATE_BPS)
            setInteger(MediaFormat.KEY_FRAME_RATE, EncodeSizing.FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, EncodeSizing.I_FRAME_INTERVAL_SEC)
            if (includeRepeatFrame) {
                setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, EncodeSizing.REPEAT_PREVIOUS_FRAME_US)
            }
        }
    }

    private suspend fun drainLoop(mediaCodec: MediaCodec, generationId: Int) {
        val info = MediaCodec.BufferInfo()
        var csd0: ByteArray? = null
        var csd1: ByteArray? = null
        var generationPublished = false
        try {
            while (running.get() && scope.isActive) {
                val index = try {
                    mediaCodec.dequeueOutputBuffer(info, 10_000L)
                } catch (error: Exception) {
                    if (running.get()) onError("encoder drain: ${error.message}")
                    break
                }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = mediaCodec.outputFormat
                        csd0 = byteArrayOf(format, "csd-0") ?: csd0
                        csd1 = byteArrayOf(format, "csd-1") ?: csd1
                        publishGeneration(generationId, csd0, csd1)
                        generationPublished = csd0 != null
                    }
                    index >= 0 -> {
                        try {
                            handleOutput(mediaCodec, index, info, generationId)
                            if (!generationPublished && csd0 != null) {
                                publishGeneration(generationId, csd0, csd1)
                                generationPublished = true
                            }
                        } finally {
                            try {
                                mediaCodec.releaseOutputBuffer(index, false)
                            } catch (_: Exception) {
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            SpikeLog.i("drain loop ended gen=$generationId")
        }
    }

    private fun handleOutput(
        mediaCodec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        generationId: Int,
    ) {
        val output = mediaCodec.getOutputBuffer(index) ?: return
        if (info.size <= 0) return
        output.position(info.offset)
        output.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        output.get(bytes)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            SpikeLog.i("codec config size=${bytes.size} gen=$generationId")
            return
        }
        val sample = EncodedSample(bytes, info.presentationTimeUs, info.flags, generationId)
        metrics.onEncodedFrame(sample.ptsUs, sample.isKeyFrame)
        onSample(sample)
    }

    private fun publishGeneration(generationId: Int, csd0: ByteArray?, csd1: ByteArray?) {
        val header = csd0 ?: return
        onGeneration(
            Generation(
                id = generationId,
                width = width,
                height = height,
                mime = MediaFormat.MIMETYPE_VIDEO_AVC,
                csd0 = header,
                csd1 = csd1,
                startPtsUs = 0L,
            ),
        )
        SpikeLog.i("generation ready id=$generationId ${width}x$height csd0=${header.size} csd1=${csd1?.size ?: 0}")
    }

    private suspend fun syncFrameLoop() {
        while (running.get() && scope.isActive) {
            delay(1_000L)
            if (running.get()) requestSyncFrame()
        }
    }

    private fun byteArrayOf(format: MediaFormat, key: String): ByteArray? {
        val buffer: ByteBuffer = format.getByteBuffer(key) ?: return null
        val copy = ByteArray(buffer.remaining())
        val duplicate = buffer.asReadOnlyBuffer()
        duplicate.get(copy)
        return copy
    }
}
