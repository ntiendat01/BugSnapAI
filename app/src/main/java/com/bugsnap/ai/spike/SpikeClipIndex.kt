package com.bugsnap.ai.spike

import android.content.Context
import com.bugsnap.ai.core.common.DomainException
import com.bugsnap.ai.data.media.VideoGalleryStore
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class SpikeClipIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gallery: VideoGalleryStore,
) {
    fun save(clip: SpikeClipInfo) {
        require(clip.id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid clip ID" }
        val directory = File(File(context.filesDir, "spike"), clip.id)
        Files.createDirectories(directory.toPath())
        val json = JSONObject().apply {
            put("id", clip.id)
            put("number", clip.number)
            put("duration", clip.durationMs)
            put("pre", clip.actualPreRollMs)
            put("post", clip.actualPostRollMs)
            put("latency", clip.latencyMs)
            put("images", clip.imageCount)
            put("savedAt", clip.savedAtMs)
            if (VideoGalleryStore.isVideoUri(clip.clipPath)) put("videoUri", clip.clipPath)
            else require(File(clip.clipPath).canonicalFile == File(directory, "clip.mp4").canonicalFile)
        }
        val part = Files.createTempFile(directory.toPath(), ".clip.json.", ".part")
        try {
            part.toFile().writeText(json.toString())
            RandomAccessFile(part.toFile(), "rw").use { it.fd.sync() }
            Files.move(part, File(directory, "clip.json").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (failure: Throwable) {
            try {
                Files.deleteIfExists(part)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    /** Called on IO. Gallery-deleted videos are omitted; legacy private clips remain readable. */
    suspend fun load(): List<SpikeClipInfo> = File(context.filesDir, "spike").listFiles().orEmpty()
        .filter { it.isDirectory }.mapNotNull { directory ->
            val video = File(directory, "clip.mp4")
            val metadata = File(directory, "clip.json")
            if (!metadata.isFile) return@mapNotNull if (video.isFile) legacyClip(directory, video) else null
            try {
                val json = JSONObject(metadata.readText())
                require(json.getString("id") == directory.name) { "Clip ID does not match its directory" }
                val uri = json.optString("videoUri").takeIf { it.isNotBlank() }
                if (uri != null) {
                    val available = gallery.contains(uri)
                    available.errorOrNull()?.let { throw DomainException(it) }
                    if (available.getOrNull() != true) return@mapNotNull null
                } else if (!video.isFile) return@mapNotNull null
                SpikeClipInfo(
                    id = json.getString("id"), clipPath = uri ?: video.absolutePath,
                    durationMs = json.getLong("duration"), actualPreRollMs = json.getLong("pre"),
                    actualPostRollMs = json.getLong("post"), latencyMs = json.getLong("latency"),
                    number = json.getInt("number"), imageCount = json.getInt("images"),
                    savedAtMs = json.getLong("savedAt"),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
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
