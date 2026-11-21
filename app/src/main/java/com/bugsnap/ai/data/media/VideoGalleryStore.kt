package com.bugsnap.ai.data.media

import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import com.bugsnap.ai.core.common.DomainError
import com.bugsnap.ai.core.common.IoDispatcher
import com.bugsnap.ai.core.common.Result
import com.bugsnap.ai.core.common.catchDomain
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Publishes app-owned videos through scoped storage, without broad storage permissions. */
@Singleton
class VideoGalleryStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val resolver get() = context.contentResolver
    private val mutex = Mutex()

    /** writer runs on IO. It must finish writing before returning and must not reenter this store. */
    suspend fun saveVideo(
        markerId: String,
        savedAtMs: Long,
        writer: (OutputStream) -> Unit,
    ): Result<Uri, DomainError> = withContext(ioDispatcher) {
        catchDomain {
            mutex.withLock {
                require(markerId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid video ID" }
                require(savedAtMs >= 0)
                val name = "BugSnap_$markerId.mp4"
                val selection = "$OWNED_SELECTION AND ${MediaStore.Video.Media.DISPLAY_NAME} = ?"
                val arguments = arrayOf(context.packageName, DIRECTORY, name)
                queryVideos(COLUMNS, selection, arguments).use { cursor ->
                    if (cursor == null) throw IOException("Cannot query video store")
                    while (cursor.moveToNext()) {
                        val existing = ContentUris.withAppendedId(COLLECTION, cursor.getLong(0))
                        if (cursor.getInt(1) == 0 && cursor.getLong(2) > 0) return@withLock existing
                        check(resolver.delete(existing, null, null) == 1) { "Cannot remove incomplete video" }
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, DIRECTORY)
                    put(MediaStore.Video.Media.DATE_TAKEN, savedAtMs)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(COLLECTION, values) ?: throw IOException("Cannot create video")
                try {
                    val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open video")
                    stream.use { output ->
                        val counted = CountingOutput(output)
                        writer(counted)
                        counted.flush()
                        require(counted.bytes > 0) { "Video must not be empty" }
                        if (output is FileOutputStream) output.fd.sync()
                    }
                    currentCoroutineContext().ensureActive()
                    val published = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    check(resolver.update(uri, published, null, null) == 1) { "Cannot publish video" }
                    uri
                } catch (failure: Throwable) {
                    try {
                        check(resolver.delete(uri, null, null) == 1) { "Cannot remove failed video" }
                    } catch (cleanupFailure: Throwable) {
                        failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        }
    }

    suspend fun contains(location: String): Result<Boolean, DomainError> = withContext(ioDispatcher) {
        catchDomain {
            require(isVideoUri(location)) { "Invalid video URI" }
            resolver.query(Uri.parse(location), COLUMNS, null, null, null).use { cursor ->
                if (cursor == null) throw IOException("Cannot query saved video")
                cursor.moveToFirst() && cursor.getInt(1) == 0 && cursor.getLong(2) > 0
            }
        }
    }

    /** Only this app's unfinished videos in DCIM/BugSnap; active writers use the same mutex. */
    suspend fun cleanupPendingVideos(): Result<Int, DomainError> = withContext(ioDispatcher) {
        catchDomain {
            mutex.withLock {
                val pending = mutableListOf<Uri>()
                val selection = "$OWNED_SELECTION AND ${MediaStore.Video.Media.IS_PENDING} = 1"
                queryVideos(arrayOf(MediaStore.Video.Media._ID), selection,
                    arrayOf(context.packageName, DIRECTORY)).use { cursor ->
                    if (cursor == null) throw IOException("Cannot query unfinished videos")
                    while (cursor.moveToNext()) pending.add(ContentUris.withAppendedId(COLLECTION, cursor.getLong(0)))
                }
                pending.forEach { uri ->
                    currentCoroutineContext().ensureActive()
                    check(resolver.delete(uri, null, null) == 1) { "Cannot remove unfinished video" }
                }
                pending.size
            }
        }
    }

    @Suppress("DEPRECATION") // API 29 has no QUERY_ARG_MATCH_PENDING replacement; see ADR-21.
    private fun queryVideos(columns: Array<String>, selection: String, arguments: Array<String>) =
        if (Build.VERSION.SDK_INT >= 30) {
            resolver.query(COLLECTION, columns, Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null)
        } else {
            resolver.query(MediaStore.setIncludePending(COLLECTION), columns, selection, arguments, null)
        }

    companion object {
        val DIRECTORY: String = "${Environment.DIRECTORY_DCIM}/BugSnap/"
        val COLLECTION: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        private val COLUMNS = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.SIZE)
        private const val OWNED_SELECTION = "${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?"

        fun isVideoUri(location: String): Boolean {
            val uri = Uri.parse(location)
            val parts = uri.pathSegments
            return uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY &&
                uri.query == null && uri.fragment == null && parts.size == 4 &&
                parts[0] in setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY, MediaStore.VOLUME_EXTERNAL) &&
                parts[1] == "video" && parts[2] == "media" && (parts[3].toLongOrNull() ?: -1) > 0
        }
    }
}

private class CountingOutput(private val target: OutputStream) : OutputStream() {
    var bytes: Long = 0
        private set

    override fun write(value: Int) {
        target.write(value)
        bytes++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        target.write(buffer, offset, length)
        bytes += length
    }

    override fun flush() = target.flush()
}
