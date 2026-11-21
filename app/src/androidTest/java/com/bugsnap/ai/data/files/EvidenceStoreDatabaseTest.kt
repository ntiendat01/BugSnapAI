package com.bugsnap.ai.data.files

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bugsnap.ai.data.db.BugMarkerEntity
import com.bugsnap.ai.data.db.BugSnapDatabase
import com.bugsnap.ai.data.db.EvidenceEntity
import com.bugsnap.ai.data.db.EvidenceType
import com.bugsnap.ai.data.db.SessionEntity
import com.bugsnap.ai.session.SessionState
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidenceStoreDatabaseTest : EvidenceStoreFixture() {
    @Test
    fun persistsRelativePathAndDeletesFilesBeforeRoomCascade() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BugSnapDatabase::class.java).build()
        try {
            val saved = store.writeAtomic(clip) { it.writeText("video") }.value()
            seed(database, saved)
            val evidence = database.evidenceDao().getById("e1")!!
            assertEquals(clip, evidence.relPath)
            assertFalse(File(evidence.relPath).isAbsolute)
            assertEquals("video", store.read(evidence.relPath) { it.readText() }.value())
            store.deleteSession("s1").value()
            assertFalse(File(root, "sessions/s1").exists())
            assertTrue(database.evidenceDao().getById("e1") != null)
            database.sessionDao().deleteById("s1")
            assertNull(database.sessionDao().getById("s1"))
            assertNull(database.markerDao().getById("m1"))
            assertNull(database.evidenceDao().getById("e1"))
        } finally {
            database.close()
        }
    }

    @Test
    fun failedFileDeletionRetainsDatabaseRecordForRetry() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BugSnapDatabase::class.java).build()
        try {
            seed(database, StoredEvidenceFile(clip, 5))
            val outside = File(sandbox, "outside").apply { mkdirs() }
            val sessions = File(root, "sessions")
            Files.createSymbolicLink(sessions.toPath(), outside.toPath())
            assertTrue(store.deleteSession("s1").isErr)
            // This is the future repository's required response to file deletion failure.
            database.sessionDao().setPendingDelete("s1", true)
            assertTrue(database.sessionDao().getById("s1")!!.pendingDelete)
            assertEquals(SessionState.COMPLETED, database.sessionDao().getById("s1")!!.state)
            assertTrue(database.evidenceDao().getById("e1") != null)
            Files.delete(sessions.toPath())
            createFile(clip, "video")
            store.deleteSession("s1").value()
            database.sessionDao().deleteById("s1")
            assertTrue(database.sessionDao().getPendingDelete().isEmpty())
            assertNull(database.evidenceDao().getById("e1"))
        } finally {
            database.close()
        }
    }

    @Test
    fun sessionsEvidenceCanBeOpenedThroughFileProvider() = runBlocking {
        val sessionId = "t014-${UUID.randomUUID()}"
        val realStore = EvidenceStore(context.filesDir, Dispatchers.IO, StorageSpace { Long.MAX_VALUE })
        try {
            val saved = realStore.writeAtomic(EvidencePaths.markerFile(sessionId, "m1", "clip.mp4")) {
                it.writeText("private-video")
            }.value()
            val uri = realStore.read(saved.relPath) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            }.value()
            assertEquals("content", uri.scheme)
            val content = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertEquals("private-video", content)
        } finally {
            realStore.deleteSession(sessionId).value()
        }
    }

    private suspend fun seed(database: BugSnapDatabase, saved: StoredEvidenceFile) {
        database.sessionDao().insert(SessionEntity("s1", "session", state = SessionState.COMPLETED, createdAt = 1))
        database.markerDao().insert(BugMarkerEntity("m1", "s1", 0, 0, 1, 0))
        database.evidenceDao().insert(EvidenceEntity("e1", "m1", EvidenceType.CLIP, saved.relPath, saved.sizeBytes))
    }
}
