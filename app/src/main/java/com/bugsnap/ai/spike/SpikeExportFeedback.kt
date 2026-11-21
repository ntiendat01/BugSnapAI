package com.bugsnap.ai.spike

import android.content.Context
import android.os.SystemClock
import com.bugsnap.ai.R
import com.bugsnap.ai.capture.export.ExportResult
import com.bugsnap.ai.core.common.DomainException
import com.bugsnap.ai.data.media.VideoGalleryStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import javax.inject.Inject

class SpikeExportFeedback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SpikeSession,
    private val clipIndex: SpikeClipIndex,
    private val gallery: VideoGalleryStore,
) {
    suspend fun complete(result: ExportResult) {
        when (result) {
            is ExportResult.Success -> {
                val marker = session.state.value.markers.find { it.id == result.id } ?: return
                val source = File(File(context.filesDir, "spike"), result.clipRelativePath)
                val savedAtMs = System.currentTimeMillis()
                val published = gallery.saveVideo(result.id, savedAtMs) { output ->
                    source.inputStream().use { it.copyTo(output) }
                }
                val uri = published.getOrNull()
                if (uri == null) {
                    SpikeLog.w("DCIM video save failed", DomainException(published.errorOrNull()!!))
                    session.markerStage(result.id, MarkerStage.Failed, context.getString(R.string.video_gallery_failed))
                    SpikeNotifications.result(context, session.state.value.copy(markers = session.state.value.markers.filter { it.id == result.id }))
                    return
                }
                val clip = SpikeClipInfo(
                    id = result.id, clipPath = uri.toString(), durationMs = result.durationMs,
                    actualPreRollMs = result.actualPreRollMs, actualPostRollMs = result.actualPostRollMs,
                    latencyMs = SystemClock.elapsedRealtime() - marker.acceptedAtMs,
                    number = marker.number,
                    imageCount = listOfNotNull(result.markJpegRelativePath, result.preJpegRelativePath).size,
                    savedAtMs = savedAtMs,
                )
                try {
                    withContext(Dispatchers.IO) { clipIndex.save(clip) }
                    session.addClip(clip)
                    session.markerStage(result.id, MarkerStage.Saved)
                    SpikeNotifications.result(context, session.state.value.copy(markers = listOf(marker.copy(stage = MarkerStage.Saved))), clip)
                    SpikeMetrics().logMarkLatency(clip.id, clip.latencyMs, clip.actualPreRollMs,
                        clip.actualPostRollMs, clip.clipPath, clip.durationMs)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    SpikeLog.w("clip index save failed", error)
                    session.markerStage(result.id, MarkerStage.Failed, context.getString(R.string.marker_index_failed))
                    return
                }
                try {
                    withContext(Dispatchers.IO) { Files.deleteIfExists(source.toPath()) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    SpikeLog.w("temporary private video cleanup failed", error)
                    session.setError(context.getString(R.string.video_cache_cleanup_failed))
                }
            }
            is ExportResult.Failure -> {
                session.markerStage(result.id, MarkerStage.Failed, context.getString(R.string.marker_error_help))
                SpikeNotifications.result(context, session.state.value.copy(markers = session.state.value.markers.filter { it.id == result.id }))
            }
        }
    }

}
