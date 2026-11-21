package com.bugsnap.ai.spike

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

class SpikeClipIndex @Inject constructor(@ApplicationContext private val context: Context) {
    fun save(clip: SpikeClipInfo) {
        val file = File(clip.clipPath)
        val json = JSONObject().apply {
            put("id", clip.id)
            put("number", clip.number)
            put("duration", clip.durationMs)
            put("pre", clip.actualPreRollMs)
            put("post", clip.actualPostRollMs)
            put("latency", clip.latencyMs)
            put("images", clip.imageCount)
            put("savedAt", clip.savedAtMs)
        }
        val part = File(file.parentFile, "clip.json.part")
        part.writeText(json.toString())
        check(part.renameTo(File(file.parentFile, "clip.json"))) { "clip index rename failed" }
    }

    fun load(): List<SpikeClipInfo> = File(context.filesDir, "spike").listFiles().orEmpty()
        .filter { it.isDirectory }.mapNotNull { directory ->
            val video = File(directory, "clip.mp4")
            val metadata = File(directory, "clip.json")
            if (!video.isFile) return@mapNotNull null
            if (!metadata.isFile) return@mapNotNull legacyClip(directory, video)
            try {
                val json = JSONObject(metadata.readText())
                SpikeClipInfo(
                    id = json.getString("id"), clipPath = video.absolutePath,
                    durationMs = json.getLong("duration"), actualPreRollMs = json.getLong("pre"),
                    actualPostRollMs = json.getLong("post"), latencyMs = json.getLong("latency"),
                    number = json.getInt("number"), imageCount = json.getInt("images"),
                    savedAtMs = json.getLong("savedAt"),
                )
            } catch (error: Exception) {
                SpikeLog.w("clip index unreadable", error)
                null
            }
        }.sortedByDescending { it.savedAtMs }

    private fun legacyClip(directory: File, video: File): SpikeClipInfo? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            SpikeClipInfo(directory.name, video.absolutePath, duration, duration, 0, 0,
                number = directory.name.removePrefix("m").toIntOrNull() ?: 1,
                imageCount = listOf("screenshot_mark.jpg", "screenshot_pre.jpg").count { File(directory, it).isFile },
                savedAtMs = video.lastModified())
        } catch (error: Exception) {
            SpikeLog.w("legacy clip unavailable", error)
            null
        } finally { retriever.release() }
    }
}
