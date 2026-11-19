package com.bugsnap.ai.capture.export

import com.bugsnap.ai.capture.buffer.ClipSnapshot
import com.bugsnap.ai.capture.buffer.EncodedSample
import com.bugsnap.ai.capture.buffer.Generation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MarkerExportQueueTest {
    private val generation = Generation(
        id = 1,
        width = 1280,
        height = 720,
        mime = "video/avc",
        csd0 = byteArrayOf(1),
        csd1 = null,
        startPtsUs = 0L,
    )

    @Test
    fun queueExportsAllSnapshots() {
        val results = CopyOnWriteArrayList<ExportResult>()
        val latch = CountDownLatch(5)
        val queue = MarkerExportQueue(
            exporter = ClipExporter { snapshot, dir ->
                ExportResult.Success(snapshot.id, "$dir/clip.mp4", null, null, 1_000L, 500L, 500L)
            },
            outputRoot = "spike",
            onResult = {
                results.add(it)
                latch.countDown()
            },
        )
        repeat(5) { index -> queue.submit(snapshot("q$index")) }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        queue.shutdown()
        assertEquals(5, results.size)
        assertTrue(results.all { it is ExportResult.Success })
        assertEquals((0 until 5).map { "q$it" }.toSet(), results.map { (it as ExportResult.Success).id }.toSet())
    }

    @Test
    fun retriesOnceThenSucceeds() {
        val attempts = AtomicInteger(0)
        val results = CopyOnWriteArrayList<ExportResult>()
        val latch = CountDownLatch(1)
        val queue = MarkerExportQueue(
            exporter = ClipExporter { snapshot, dir ->
                val n = attempts.incrementAndGet()
                if (n == 1) error("mux failed")
                ExportResult.Success(snapshot.id, "$dir/clip.mp4", null, null, 1_000L, 500L, 500L)
            },
            outputRoot = "spike",
            onResult = {
                results.add(it)
                latch.countDown()
            },
        )
        queue.submit(snapshot("retry"))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        queue.shutdown()
        assertEquals(1, results.size)
        assertTrue(results[0] is ExportResult.Success)
        assertEquals(2, attempts.get())
    }

    @Test
    fun givesUpAfterRetryLimit() {
        val results = CopyOnWriteArrayList<ExportResult>()
        val latch = CountDownLatch(1)
        val queue = MarkerExportQueue(
            exporter = ClipExporter { _, _ -> error("always fail") },
            outputRoot = "spike",
            retryLimit = 1,
            onResult = {
                results.add(it)
                latch.countDown()
            },
        )
        queue.submit(snapshot("fail"))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        queue.shutdown()
        assertTrue(results[0] is ExportResult.Failure)
        assertEquals(2, (results[0] as ExportResult.Failure).attempt)
    }

    private fun snapshot(id: String) = ClipSnapshot(
        id = id,
        samples = listOf(
            EncodedSample.keyFrame(0L, 1),
            EncodedSample.delta(33_333L, 1),
        ),
        generation = generation,
        tMarkUs = 33_333L,
        actualPreRollUs = 33_333L,
        actualPostRollUs = 0L,
    )
}
