package com.bugsnap.ai.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bugsnap.ai.capture.export.ExportResult
import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.core.common.Result
import com.bugsnap.ai.spike.MarkerStage
import com.bugsnap.ai.spike.SpikeClipIndex
import com.bugsnap.ai.spike.SpikeClipInfo
import com.bugsnap.ai.spike.SpikeExportFeedback
import com.bugsnap.ai.spike.SpikeIntents
import com.bugsnap.ai.spike.SpikeSession
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoGalleryStoreTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var gallery: VideoGalleryStore
    private val ids = mutableListOf<String>()

    @Before
    fun setUp() {
        gallery = VideoGalleryStore(context, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        ids.forEach { id ->
            rows(id).forEach { context.contentResolver.delete(it, null, null) }
            File(File(context.filesDir, "spike"), id).deleteRecursively()
        }
    }

    @Test
    fun publishesCompleteVideoInDcimAndOpensWithContentUri() = runBlocking {
        val id = id()
        val payload = "test-video-bytes".toByteArray()
        val uri = gallery.saveVideo(id, 1234) { it.write(payload) }.value()
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("DCIM/BugSnap/", it.getString(0))
            assertEquals(0, it.getInt(1))
            assertEquals("BugSnap_$id.mp4", it.getString(2))
        }
        assertTrue(gallery.contains(uri.toString()).value())
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(payload.contentEquals(bytes))
        val intent = SpikeIntents.viewClip(context, uri.toString())!!
        assertEquals(uri, intent.data)
        assertEquals("video/mp4", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun failureAndEmptyWriteLeaveNoPublishedOrPendingRow() = runBlocking {
        val id = id()
        val failure = gallery.saveVideo(id, 1) {
            it.write(byteArrayOf(1, 2))
            throw IOException("ENOSPC: No space left on device")
        }
        assertEquals(DomainError.StorageLow, failure.errorOrNull())
        assertTrue(rows(id).isEmpty())
        assertTrue(gallery.saveVideo(id, 1) { }.isErr)
        assertTrue(rows(id).isEmpty())
    }

    @Test
    fun cancellationRollsBackPendingVideo() = runBlocking {
        val id = id()
        try {
            gallery.saveVideo(id, 1) {
                it.write(1)
                throw CancellationException("cancel save")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // A cancelled export must never become a successful Gallery item.
        }
        assertTrue(rows(id).isEmpty())
    }

    @Test
    fun duplicateConcurrentSaveReusesOneVideo() = runBlocking {
        val id = id()
        val writes = AtomicInteger()
        val results = (1..3).map {
            async(Dispatchers.Default) {
                gallery.saveVideo(id, 1) { output ->
                    writes.incrementAndGet()
                    output.write("video".toByteArray())
                }.value()
            }
        }.map { it.await() }
        assertEquals(1, results.distinct().size)
        assertEquals(1, writes.get())
        assertEquals(1, rows(id).size)
    }

    @Test
    fun retriesReplaceInterruptedRowAndCleanupKeepsOtherFolders() = runBlocking {
        val interruptedId = id()
        insertPending(interruptedId)
        val uri = gallery.saveVideo(interruptedId, 1) { it.write(1) }.value()
        assertEquals(listOf(uri), rows(interruptedId))
        val orphanId = id()
        val orphan = insertPending(orphanId)
        val outside = insertPending(id(), "Movies/BugSnapTest/")
        assertEquals(1, gallery.cleanupPendingVideos().value())
        assertTrue(rows(orphanId).isEmpty())
        assertTrue(gallery.contains(uri.toString()).value())
        assertTrue(context.contentResolver.query(outside, arrayOf(MediaStore.Video.Media._ID), null, null, null)!!.use { it.moveToFirst() })
        assertFalse(orphan == outside)
    }

    @Test
    fun cleanupWaitsUntilActiveVideoIsPublished() = runBlocking {
        val id = id()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val save = async(Dispatchers.Default) {
            gallery.saveVideo(id, 1) {
                it.write(1)
                writing.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }.value()
        }
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            val cleanup = async(Dispatchers.Default) {
                gallery.cleanupPendingVideos().value().also { cleaned.countDown() }
            }
            assertFalse(cleaned.await(200, TimeUnit.MILLISECONDS))
            release.countDown()
            val uri = save.await()
            assertEquals(0, cleanup.await())
            assertTrue(gallery.contains(uri.toString()).value())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun indexReloadUsesUriWithoutPrivateMp4AndOmitsGalleryDeletedVideo() = runBlocking(Dispatchers.IO) {
        val id = id()
        val uri = gallery.saveVideo(id, 1) { it.write("video".toByteArray()) }.value()
        val clip = SpikeClipInfo(id, uri.toString(), 1000, 500, 500, 100, savedAtMs = 1)
        SpikeClipIndex(context, gallery).save(clip)
        assertFalse(File(File(File(context.filesDir, "spike"), id), "clip.mp4").exists())
        val reloaded = SpikeClipIndex(context, VideoGalleryStore(context, Dispatchers.IO)).load()
        assertEquals(clip, reloaded.find { it.id == id })
        context.contentResolver.delete(uri, null, null)
        assertNull(SpikeClipIndex(context, gallery).load().find { it.id == id })
    }

    @Test
    fun legacyPrivateClipStillLoadsAndUsesFileProvider() = runBlocking(Dispatchers.IO) {
        val id = id()
        val video = privateVideo(id)
        val clip = SpikeClipInfo(id, video.absolutePath, 1000, 500, 500, 100, savedAtMs = 1)
        val index = SpikeClipIndex(context, gallery)
        index.save(clip)
        assertEquals(clip, index.load().find { it.id == id })
        assertEquals("${context.packageName}.fileprovider", SpikeIntents.viewClip(context, video.absolutePath)!!.data!!.authority)
    }

    @Test
    fun exportFeedbackSavesToDcimBeforeSuccessAndDeletesOnlyTemporaryVideo() = runBlocking {
        val id = id()
        val source = privateVideo(id)
        val image = File(source.parentFile, "screenshot_mark.jpg").apply { writeText("private-image") }
        val session = SpikeSession().apply { acceptMarker(id, android.os.SystemClock.elapsedRealtime()) }
        val index = SpikeClipIndex(context, gallery)
        SpikeExportFeedback(context, session, index, gallery).complete(export(id))
        val clip = session.state.value.clips.single()
        assertEquals(MarkerStage.Saved, session.state.value.markers.single().stage)
        assertTrue(gallery.contains(clip.clipPath).value())
        assertFalse(source.exists())
        assertTrue(image.exists())
        assertTrue(File(source.parentFile, "clip.json").exists())
    }

    @Test
    fun failedGalleryExportNeverReportsSaved() = runBlocking {
        val id = id()
        val session = SpikeSession().apply { acceptMarker(id, 0) }
        SpikeExportFeedback(context, session, SpikeClipIndex(context, gallery), gallery).complete(export(id))
        assertEquals(MarkerStage.Failed, session.state.value.markers.single().stage)
        assertTrue(session.state.value.clips.isEmpty())
        assertTrue(rows(id).isEmpty())
    }

    @Test
    fun rejectsUnsafeIdsAndUnrelatedUris() = runBlocking {
        assertTrue(gallery.saveVideo("../outside", 1) { fail("Invalid ID reached writer") }.isErr)
        listOf("file:///sdcard/DCIM/a.mp4", "content://other/video/1",
            "content://media/external_primary/images/media/1", "content://media/external_primary/video/media/-1",
            "content://media/external_primary/video/media/1?unsafe=true").forEach {
            assertFalse(VideoGalleryStore.isVideoUri(it))
            assertNull(SpikeIntents.viewClip(context, it))
        }
    }

    private fun id(): String = "dcimtest-${UUID.randomUUID()}".also { ids.add(it) }

    private fun privateVideo(id: String) = File(File(File(context.filesDir, "spike"), id), "clip.mp4").apply {
        parentFile!!.mkdirs()
        writeText("video-bytes")
    }

    private fun export(id: String) = ExportResult.Success(id, "$id/clip.mp4", "$id/screenshot_mark.jpg", null, 1000, 500, 500)

    private fun insertPending(id: String, directory: String = VideoGalleryStore.DIRECTORY): Uri =
        context.contentResolver.insert(VideoGalleryStore.COLLECTION, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "BugSnap_$id.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, directory)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        })!!

    private fun rows(id: String): List<Uri> {
        val results = mutableListOf<Uri>()
        val arguments = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.DISPLAY_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("BugSnap_$id.mp4"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(VideoGalleryStore.COLLECTION, arrayOf(MediaStore.Video.Media._ID), arguments, null)!!.use {
            while (it.moveToNext()) results.add(ContentUris.withAppendedId(VideoGalleryStore.COLLECTION, it.getLong(0)))
        }
        return results
    }

    private fun <T> Result<T, DomainError>.value(): T = when (this) {
        is Result.Ok -> value
        is Result.Err -> throw AssertionError("Expected success, got $error")
    }
}
