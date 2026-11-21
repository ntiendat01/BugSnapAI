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

    @Test
    fun manyMarkersUseDistinctPathsAndAtMostThreeParallelExporters() {
        val entered = CountDownLatch(3)
        val done = CountDownLatch(10)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        val results = CopyOnWriteArrayList<ExportResult>()
        val queue = MarkerExportQueue(
            exporter = ClipExporter { snapshot, dir ->
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try {
                    entered.countDown()
                    check(entered.await(5, TimeUnit.SECONDS)) { "Three exports must run concurrently" }
                    paths.add(dir)
                    ExportResult.Success(snapshot.id, "$dir/clip.mp4", null, null, 1_000, 500, 500)
                } finally {
                    active.decrementAndGet()
                }
            },
            outputRoot = "markers",
            onResult = { results.add(it); done.countDown() },
        )
        try {
            repeat(10) { queue.submit(snapshot("marker-$it")) }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(3, peak.get())
            assertEquals(10, paths.distinct().size)
            assertEquals((0..9).map { "markers/marker-$it" }.toSet(), paths.toSet())
            assertTrue(results.all { it is ExportResult.Success })
        } finally {
            queue.shutdown()
        }
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
