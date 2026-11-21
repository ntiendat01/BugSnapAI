package com.bugsnap.ai.spike

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.bugsnap.ai.capture.buffer.ClipSnapshot
import com.bugsnap.ai.capture.export.ClipExporter
import com.bugsnap.ai.capture.export.ExportResult
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class AndroidClipMuxer(
    private val filesRoot: File,
) : ClipExporter {
    override fun export(snapshot: ClipSnapshot, outputDir: String): ExportResult {
        val dir = File(filesRoot, outputDir)
        if (!dir.exists() && !dir.mkdirs()) {
            return ExportResult.Failure(snapshot.id, "cannot create $outputDir", 1)
        }
        val part = File(dir, "clip.mp4.part")
        val dest = File(dir, "clip.mp4")
        if (part.exists()) part.delete()
        if (snapshot.samples.isEmpty()) {
            return ExportResult.Failure(snapshot.id, "no samples", 1)
        }
        if (!snapshot.samples.first().isKeyFrame) {
            return ExportResult.Failure(snapshot.id, "clip does not start on a keyframe", 1)
        }
        try {
            mux(snapshot, part)
            val durationMs = validateDurationMs(part)
            if (durationMs <= 0L) {
                part.delete()
                return ExportResult.Failure(snapshot.id, "muxed file has no duration", 1)
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                return ExportResult.Failure(snapshot.id, "rename part -> mp4 failed", 1)
            }
            val markJpeg = extractJpeg(dest, markTimeUs(snapshot), File(dir, "screenshot_mark.jpg"))
            val preTimeUs = (markTimeUs(snapshot) - 2_000_000L).coerceAtLeast(0L)
            val preJpeg = extractJpeg(dest, preTimeUs, File(dir, "screenshot_pre.jpg"))
            return ExportResult.Success(
                id = snapshot.id,
                clipRelativePath = relative(dest),
                markJpegRelativePath = markJpeg?.let { relative(it) },
                preJpegRelativePath = preJpeg?.let { relative(it) },
                durationMs = durationMs,
                actualPreRollMs = snapshot.actualPreRollMs,
                actualPostRollMs = snapshot.actualPostRollMs,
            )
        } catch (error: Exception) {
            if (part.exists()) part.delete()
            throw error
        }
    }

    fun deleteOrphanParts(root: File = filesRoot) {
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { orphan ->
            SpikeLog.i("deleting orphan ${orphan.name}")
            orphan.delete()
        }
    }

    private fun mux(snapshot: ClipSnapshot, part: File) {
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(part.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createVideoFormat(
                snapshot.generation.mime,
                snapshot.generation.width,
                snapshot.generation.height,
            )
            format.setByteBuffer("csd-0", ByteBuffer.wrap(snapshot.generation.csd0))
            snapshot.generation.csd1?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            val track = muxer.addTrack(format)
            muxer.start()
            val firstPts = snapshot.samples.first().ptsUs
            val info = MediaCodec.BufferInfo()
            for (sample in snapshot.samples) {
                info.offset = 0
                info.size = sample.sizeBytes
                info.presentationTimeUs = sample.ptsUs - firstPts
                info.flags = sample.flags
                muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
            }
        } finally {
            try {
                muxer?.stop()
            } catch (error: Exception) {
                SpikeLog.w("muxer.stop failed", error)
            }
            try {
                muxer?.release()
            } catch (error: Exception) {
                SpikeLog.w("muxer.release failed", error)
            }
        }
    }

    private fun validateDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun markTimeUs(snapshot: ClipSnapshot): Long {
        val firstPts = snapshot.samples.first().ptsUs
        return (snapshot.tMarkUs - firstPts).coerceAtLeast(0L)
    }

    private fun extractJpeg(clip: File, timeUs: Long, output: File): File? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(clip.absolutePath)
            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val part = File(output.parentFile, output.name + ".part")
            try {
                val compressed = FileOutputStream(part).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                }
                check(compressed && part.length() > 0 && part.renameTo(output)) { "image write failed" }
                output
            } finally {
                bitmap.recycle()
                if (part.exists()) part.delete()
            }
        } catch (error: Exception) {
            SpikeLog.w("frame extract failed at timeUs=$timeUs", error)
            if (output.exists()) output.delete()
            null
        } finally {
            retriever.release()
        }
    }

    private fun relative(file: File): String {
        return file.relativeTo(filesRoot).path
    }
}
