package com.bugsnap.ai.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidenceStoreConcurrencyTest : EvidenceStoreFixture() {
    @Test
    fun sameDestinationIsSerializedAndReadersSeeWholeFiles() = runBlocking {
        store.writeAtomic(clip) { it.writeText("original") }.value()
        val firstWriting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondWriting = CountDownLatch(1)
        val first = async(Dispatchers.Default) {
            store.writeAtomic(clip) {
                it.writeText("unfinished")
                firstWriting.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                it.writeText("first complete")
            }.value()
        }
        try {
            assertTrue(firstWriting.await(10, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) {
                store.writeAtomic(clip) {
                    secondWriting.countDown()
                    it.writeText("second complete")
                }.value()
            }
            val reader = async(Dispatchers.Default) { store.read(clip) { it.readText() }.value() }
            assertFalse(secondWriting.await(200, TimeUnit.MILLISECONDS))
            assertEquals("original", File(root, clip).readText())
            release.countDown()
            first.await()
            second.await()
            assertTrue(reader.await() in listOf("first complete", "second complete"))
            assertEquals("second complete", store.read(clip) { it.readText() }.value())
            assertTrue(partials().isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun differentEvidenceFilesMayWriteConcurrently() = runBlocking {
        val bothWriting = CountDownLatch(2)
        val paths = listOf(clip, EvidencePaths.markerFile("s1", "m2", "clip.mp4"))
        val writes = paths.map { path ->
            async(Dispatchers.Default) {
                store.writeAtomic(path) {
                    bothWriting.countDown()
                    assertTrue("Different files must not share a write lock", bothWriting.await(10, TimeUnit.SECONDS))
                    it.writeText(path)
                }.value()
            }
        }
        writes.forEach { it.await() }
        paths.forEach { path -> assertEquals(path, store.read(path) { it.readText() }.value()) }
    }

    @Test
    fun deletingSessionWaitsForWriterThenRemovesItsCommittedFile() = runBlocking {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deleting = CountDownLatch(1)
        val write = async(Dispatchers.Default) {
            store.writeAtomic(clip) {
                it.writeText("partial")
                writing.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                it.writeText("complete")
            }.value()
        }
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            val delete = async(Dispatchers.Default) {
                store.deleteSession("s1").value()
                deleting.countDown()
            }
            assertFalse(deleting.await(200, TimeUnit.MILLISECONDS))
            assertEquals(1, partials().size)
            release.countDown()
            write.await()
            delete.await()
            assertFalse(File(root, "sessions/s1").exists())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun cleanupWaitsForWriterAndKeepsItsFinalFile() = runBlocking {
        createFile("sessions/s1/exports/abandoned.part")
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val write = async(Dispatchers.Default) {
            store.writeAtomic(clip) {
                it.writeText("video")
                writing.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }.value()
        }
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            val cleanup = async(Dispatchers.Default) {
                store.cleanupOrphanParts().value().also { cleaned.countDown() }
            }
            assertFalse(cleaned.await(200, TimeUnit.MILLISECONDS))
            release.countDown()
            write.await()
            assertEquals(1, cleanup.await())
            assertEquals("video", store.read(clip) { it.readText() }.value())
            assertTrue(partials().isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun cancellingBlockedWriterRemovesStagingAndPreservesOriginal() = runBlocking {
        store.writeAtomic(clip) { it.writeText("original") }.value()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val write = async(Dispatchers.Default) {
            store.writeAtomic(clip) {
                it.writeText("partial")
                writing.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            write.cancelAndJoin()
            assertEquals("original", store.read(clip) { it.readText() }.value())
            assertTrue(partials().isEmpty())
        } finally {
            release.countDown()
        }
    }
}
