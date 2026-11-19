package com.bugsnap.ai.capture.buffer

/**
 * One encoded access unit from MediaCodec.
 * [flags] uses the same bits as [android.media.MediaCodec] so the Android
 * pipeline can pass [MediaCodec.BufferInfo.flags] through unchanged.
 * This type has no Android dependency.
 */
class EncodedSample(
    val data: ByteArray,
    val ptsUs: Long,
    val flags: Int,
    val generation: Int,
) {
    val isKeyFrame: Boolean
        get() = flags and FLAG_KEY_FRAME != 0

    val isCodecConfig: Boolean
        get() = flags and FLAG_CODEC_CONFIG != 0

    companion object {
        /** Same value as MediaCodec.BUFFER_FLAG_KEY_FRAME. */
        const val FLAG_KEY_FRAME = 1

        /** Same value as MediaCodec.BUFFER_FLAG_CODEC_CONFIG. */
        const val FLAG_CODEC_CONFIG = 2

        fun keyFrame(ptsUs: Long, generation: Int, size: Int = 16): EncodedSample =
            EncodedSample(ByteArray(size), ptsUs, FLAG_KEY_FRAME, generation)

        fun delta(ptsUs: Long, generation: Int, size: Int = 8): EncodedSample =
            EncodedSample(ByteArray(size), ptsUs, 0, generation)
    }
}
