package com.bugsnap.ai.data.files

import android.os.StatFs
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bugsnap.ai.core.common.DomainError
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidenceStoreTest : EvidenceStoreFixture() {
    @Test
    fun writesReadsAndReplacesFinalFile() = runBlocking {
        val result = store.writeAtomic(clip) { staging ->
            assertTrue(staging.name.endsWith(".part"))
            assertEquals(File(root, clip).parentFile!!.canonicalFile, staging.parentFile!!.canonicalFile)
            assertFalse(File(root, clip).exists())
            staging.writeText("first")
        }.value()
        assertEquals(StoredEvidenceFile(clip, 5), result)
        assertFalse(File(result.relPath).isAbsolute)
        assertEquals("first", store.read(clip) { it.readText() }.value())
        store.writeAtomic(clip) { it.writeText("replacement") }.value()
        assertEquals("replacement", store.read(clip) { it.readText() }.value())
        assertTrue(partials().isEmpty())
    }

    @Test
    fun failedWriteKeepsPreviousFileAndRemovesStaging() = runBlocking {
        store.writeAtomic(clip) { it.writeText("original") }.value()
        val failure = IOException("writer failed")
        val result = store.writeAtomic(clip) {
            it.writeText("unfinished replacement")
            throw failure
        }
        assertEquals(failure, (result.errorOrNull() as DomainError.Unknown).cause)
        assertEquals("original", store.read(clip) { it.readText() }.value())
        assertTrue(partials().isEmpty())
    }

    @Test
    fun failedFirstWriteAndEmptyEvidenceLeaveNoFinalFile() = runBlocking {
        assertEquals(DomainError.StorageLow, store.writeAtomic(clip) {
            it.writeText("partial")
            throw IOException("ENOSPC: No space left on device")
        }.errorOrNull())
        assertTrue(store.writeAtomic(clip) { }.isErr)
        assertFalse(File(root, clip).exists())
        assertTrue(partials().isEmpty())
        assertTrue(store.read(clip) { it.readText() }.isErr)
    }

    @Test
    fun cancellationIsPropagatedAndStagingIsRemoved() = runBlocking {
        try {
            store.writeAtomic(clip) {
                it.writeText("partial")
                throw CancellationException("cancel export")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Cancellation must not be converted into a domain error.
        }
        assertFalse(File(root, clip).exists())
        assertTrue(partials().isEmpty())
    }

    @Test
    fun rejectsUnsafePathsWithoutCallingWriter() = runBlocking {
        val paths = listOf(
            "/tmp/outside.mp4", "sessions/s1/markers/../../outside.mp4",
            "sessions/s1/markers/m1/clip.mp4.part", "sessions/s1/markers/m1\\bad/clip.mp4",
            "sessions/s1/exports/../outside.mp4", "spike/session/clip.mp4", "",
        )
        paths.forEach { path ->
            assertTrue(store.writeAtomic(path) { fail("Unsafe path reached writer") }.isErr)
            assertTrue(store.read(path) { fail("Unsafe path reached reader") }.isErr)
        }
        listOf("", "..", "s1/../s2", "s1\\s2").forEach {
            assertTrue(store.deleteSession(it).isErr)
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun storagePreflightUsesStartThresholdOnlyForStarting() = runBlocking {
        freeBytes = StorageStatus.MIN_START_BYTES - 1
        assertEquals(DomainError.StorageLow, store.checkCanStartSession().errorOrNull())
        assertTrue(store.storageStatus().value().shouldWarn)
        // An in-flight export may finish below the new-session threshold.
        store.writeAtomic(clip, expectedBytes = 5) { it.writeText("video") }.value()
        freeBytes = StorageStatus.MIN_START_BYTES
        assertTrue(store.checkCanStartSession().value().canStartSession)
        freeBytes = StorageStatus.WARN_BYTES
        assertFalse(store.storageStatus().value().shouldWarn)
        freeBytes = 4
        assertEquals(DomainError.StorageLow, store.writeAtomic(clip, 5) {
            fail("Insufficient space must fail before writing")
        }.errorOrNull())
        freeBytes = 0
        assertEquals(DomainError.StorageLow, store.writeAtomic(clip) { fail("No space") }.errorOrNull())
        assertTrue(store.writeAtomic(clip, -1) { fail("Invalid estimate") }.isErr)
        assertEquals("video", store.read(clip) { it.readText() }.value())
    }

    @Test
    fun productionSpaceProbeReadsAndroidFilesystem() = runBlocking {
        val realProbeStore = EvidenceStore(root, Dispatchers.IO, EvidenceStoreModule.storageSpace())
        val available = realProbeStore.storageStatus().value().availableBytes
        assertTrue(available > 0)
        assertTrue(available <= StatFs(root.path).totalBytes)
    }

    @Test
    fun cleanupOnlyRemovesOrphansInSessions() = runBlocking {
        createFile(clip)
        createFile("sessions/s1/markers/m1/clip.mp4.part")
        createFile("sessions/s1/markers/m1/frames/.f1.jpg.123.part")
        createFile("sessions/s2/exports/.report.md.456.part")
        val spikePart = createFile("spike/old/clip.mp4.part")
        assertEquals(3, store.cleanupOrphanParts().value())
        assertTrue(File(root, clip).exists())
        assertTrue(spikePart.exists())
        assertEquals(0, store.cleanupOrphanParts().value())
    }

    @Test
    fun deletesWholeSessionAndKeepsOtherSessions() = runBlocking {
        listOf(
            clip, EvidencePaths.markerFile("s1", "m1", "screenshot_mark.jpg"),
            EvidencePaths.markerFile("s1", "m2", "screenshot_pre.jpg"),
            EvidencePaths.frame("s1", "m2", 1), EvidencePaths.export("s1", "report.md"),
        ).forEach { store.writeAtomic(it) { it.writeText("evidence") }.value() }
        createFile("sessions/s1/markers/m2/unfinished.part")
        val other = createFile("sessions/s2/markers/m3/clip.mp4", "keep")
        store.deleteSession("s1").value()
        assertFalse(File(root, "sessions/s1").exists())
        assertEquals("keep", other.readText())
        store.deleteSession("s1").value()
    }

    @Test
    fun symlinkPathsAreRejectedAndDeletionNeverTraversesThem() = runBlocking {
        val outside = File(sandbox, "outside").apply { mkdirs() }
        val survivor = File(outside, "keep.mp4").apply { writeText("keep") }
        val markerDir = File(root, "sessions/s1/markers").apply { mkdirs() }
        Files.createSymbolicLink(File(markerDir, "m1").toPath(), outside.toPath())
        assertTrue(store.writeAtomic(clip) { fail("Symlink traversal") }.isErr)
        assertTrue(store.read(EvidencePaths.markerFile("s1", "m1", "keep.mp4")) { fail("Symlink read") }.isErr)
        // A .part symlink is unlinked without touching the external target.
        Files.createSymbolicLink(File(markerDir, "orphan.part").toPath(), survivor.toPath())
        assertEquals(1, store.cleanupOrphanParts().value())
        store.deleteSession("s1").value()
        assertEquals("keep", survivor.readText())
        Files.createSymbolicLink(File(root, "sessions/s2").toPath(), outside.toPath())
        store.deleteSession("s2").value()
        assertEquals("keep", survivor.readText())
    }

    @Test
    fun refusesSymlinkSessionsRoot() = runBlocking {
        val outside = File(sandbox, "outside").apply { mkdirs() }
        val survivor = File(outside, "keep.part").apply { writeText("keep") }
        Files.createSymbolicLink(File(root, "sessions").toPath(), outside.toPath())
        assertTrue(store.writeAtomic(clip) { fail("Symlink root") }.isErr)
        assertTrue(store.deleteSession("s1").isErr)
        assertTrue(store.cleanupOrphanParts().isErr)
        assertEquals("keep", survivor.readText())
    }
}
