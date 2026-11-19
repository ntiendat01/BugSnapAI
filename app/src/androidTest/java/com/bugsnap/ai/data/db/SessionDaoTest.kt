package com.bugsnap.ai.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bugsnap.ai.session.FailureReason
import com.bugsnap.ai.session.PauseReason
import com.bugsnap.ai.session.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    private lateinit var database: BugSnapDatabase
    private lateinit var sessions: SessionDao
    private lateinit var markers: MarkerDao
    private lateinit var evidence: EvidenceDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, BugSnapDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = database.sessionDao()
        markers = database.markerDao()
        evidence = database.evidenceDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertGetUpdateAndDeleteSession() = runBlocking {
        val session = session("s1")
        sessions.insert(session)
        assertEquals(session, sessions.getById("s1"))

        sessions.updateState("s1", SessionState.PAUSED, PauseReason.USER, null)
        val paused = sessions.getById("s1")
        assertEquals(SessionState.PAUSED, paused?.state)
        assertEquals(PauseReason.USER, paused?.pauseReason)

        sessions.updateState("s1", SessionState.FAILED, null, FailureReason.PROCESS_DIED)
        val failed = sessions.getById("s1")
        assertEquals(SessionState.FAILED, failed?.state)
        assertNull(failed?.pauseReason)
        assertEquals(FailureReason.PROCESS_DIED, failed?.failureReason)

        sessions.setPendingDelete("s1", true)
        assertEquals(listOf("s1"), sessions.getPendingDelete().map { it.id })

        assertEquals(1, sessions.deleteById("s1"))
        assertNull(sessions.getById("s1"))
        assertTrue(sessions.getPendingDelete().isEmpty())
    }

    @Test
    fun deleteSessionCascadesMarkersAndEvidence() = runBlocking {
        sessions.insert(session("s1"))
        sessions.insert(session("s2", createdAt = 2L))
        markers.insert(marker("m1", "s1", 0))
        markers.insert(marker("m2", "s1", 1))
        markers.insert(marker("m3", "s2", 0))
        evidence.insert(evidence("e1", "m1"))
        evidence.insert(evidence("e2", "m2"))
        evidence.insert(evidence("e3", "m3", relPath = "sessions/s2/markers/m3/clip.mp4"))

        sessions.deleteById("s1")

        assertNull(sessions.getById("s1"))
        assertTrue(markers.getBySession("s1").isEmpty())
        assertTrue(evidence.getByMarker("m1").isEmpty())
        assertTrue(evidence.getByMarker("m2").isEmpty())
        assertEquals("s2", sessions.getById("s2")?.id)
        assertEquals(listOf("m3"), markers.getBySession("s2").map { it.id })
        assertEquals(listOf("e3"), evidence.getByMarker("m3").map { it.id })
    }

    @Test
    fun uniqueIndexOnSessionAndMarkerIndex() = runBlocking {
        sessions.insert(session("s1"))
        markers.insert(marker("m1", "s1", 0))
        try {
            markers.insert(marker("m2", "s1", 0))
            fail("expected unique constraint on (sessionId, indexInSession)")
        } catch (_: SQLiteConstraintException) {
            // expected
        }
        markers.insert(marker("m2", "s1", 1))
        assertEquals(listOf("m1", "m2"), markers.getBySession("s1").map { it.id })
    }

    @Test
    fun markerNoteAndClipStatusUpdate() = runBlocking {
        sessions.insert(session("s1"))
        markers.insert(marker("m1", "s1", 0))
        markers.updateNote("m1", "tap crashed")
        markers.updateClipStatus("m1", ClipStatus.CLIP_LOST)
        val stored = markers.getById("m1")
        assertEquals("tap crashed", stored?.note)
        assertEquals(ClipStatus.CLIP_LOST, stored?.clipStatus)
    }

    private fun session(id: String, createdAt: Long = 1L) = SessionEntity(
        id = id,
        name = "session $id",
        state = SessionState.RECORDING,
        bufferSec = 30,
        createdAt = createdAt,
    )

    private fun marker(id: String, sessionId: String, index: Int) = BugMarkerEntity(
        id = id,
        sessionId = sessionId,
        indexInSession = index,
        tMarkPtsUs = 1_000_000,
        wallClockMs = 10L,
        sessionOffsetMs = 5L,
    )

    private fun evidence(
        id: String,
        markerId: String,
        relPath: String = "sessions/s1/markers/$markerId/clip.mp4",
    ) = EvidenceEntity(
        id = id,
        markerId = markerId,
        type = EvidenceType.CLIP,
        relPath = relPath,
        sizeBytes = 12,
    )
}
