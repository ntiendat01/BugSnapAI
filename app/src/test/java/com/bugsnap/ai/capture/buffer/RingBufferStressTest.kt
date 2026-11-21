package com.bugsnap.ai.capture.buffer

import java.util.Random
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBufferStressTest {
    @Test
    fun seededStreamsPreserveBoundsGenerationsAndEveryAcceptedMarker() {
        repeat(20) { seed ->
            val random = Random(seed.toLong())
            val buffer = RingBuffer(maxDurationUs = 3_000_000, marginUs = 1_000_000)
            val requested = linkedMapOf<String, Long>()
            val completed = mutableListOf<ClipSnapshot>()
            val frozenPts = mutableMapOf<String, List<Long>>()
            var gen = 1
            var pts = 0L
            var nextKey = 0L
            buffer.registerGeneration(generation(gen))
            fun capture(snapshot: ClipSnapshot) {
                assertTrue(snapshot.samples.first().isKeyFrame)
                assertTrue(snapshot.samples.all { it.generation == snapshot.generation.id })
                assertTrue(snapshot.samples.zipWithNext().all { (a, b) -> a.ptsUs < b.ptsUs })
                assertTrue(snapshot.actualPreRollUs <= 3_000_000)
                assertTrue(snapshot.actualPostRollUs <= requested.getValue(snapshot.id))
                assertEquals(snapshot.durationUs, snapshot.actualPreRollUs + snapshot.actualPostRollUs)
                completed.add(snapshot)
                frozenPts[snapshot.id] = snapshot.samples.map { it.ptsUs }
            }
            repeat(1_200) { index ->
                if (index > 0 && index % 300 == 0) {
                    gen++
                    buffer.registerGeneration(generation(gen, pts))
                    nextKey = pts
                }
                val key = pts >= nextKey
                if (key) nextKey = pts + 250_000 + random.nextInt(650_000)
                val sample = if (key) EncodedSample.keyFrame(pts, gen) else EncodedSample.delta(pts, gen)
                buffer.append(sample).forEach(::capture)
                // Late/duplicate callbacks must not contaminate the stream or close a marker.
                if (index % 47 == 0) {
                    assertTrue(buffer.append(EncodedSample.keyFrame(pts, gen - 1)).isEmpty())
                    assertTrue(buffer.append(sample).isEmpty())
                }
                if (index % 17 == 0) {
                    val id = "$seed-$index"
                    val post = random.nextInt(1_000_001).toLong()
                    requested[id] = post
                    when (val result = buffer.mark(id, postRollUs = post)) {
                        is MarkResult.Pending -> Unit
                        is MarkResult.Ready -> capture(result.snapshot)
                        is MarkResult.Rejected -> error("Seed $seed rejected $id: ${result.reason}")
                    }
                }
                assertTrue(buffer.stats().durationUs <= 4_000_000)
                pts += 10_000 + random.nextInt(70_000)
            }
            buffer.forceFinalizePending().forEach(::capture)
            assertEquals(requested.keys, completed.map { it.id }.toSet())
            assertEquals(requested.size, completed.size)
            buffer.clear()
            completed.forEach { assertEquals(frozenPts.getValue(it.id), it.samples.map { sample -> sample.ptsUs }) }
        }
    }

    @Test
    fun concurrentAppendMarkAndStatsProduceConsistentFrozenSnapshots() {
        val buffer = RingBuffer(maxDurationUs = 2_000_000)
        buffer.registerGeneration(generation())
        buffer.append(EncodedSample.keyFrame(0, 1))
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val writer = executor.submit {
                repeat(100) { phase ->
                    barrier.await(5, TimeUnit.SECONDS)
                    repeat(50) { index ->
                        val frame = phase * 50 + index + 1
                        buffer.append(if (frame % 20 == 0) EncodedSample.keyFrame(frame * 1_000L, 1)
                        else EncodedSample.delta(frame * 1_000L, 1))
                    }
                    barrier.await(5, TimeUnit.SECONDS)
                }
            }
            val reader = executor.submit<List<ClipSnapshot>> {
                val snapshots = mutableListOf<ClipSnapshot>()
                repeat(100) { phase ->
                    barrier.await(5, TimeUnit.SECONDS)
                    val snapshot = ready(buffer.mark("parallel-$phase", postRollUs = 0))
                    assertTrue(snapshot.samples.first().isKeyFrame)
                    assertEquals(snapshot.tMarkUs, snapshot.samples.last().ptsUs)
                    assertTrue(snapshot.actualPreRollUs <= 2_000_000)
                    val stats = buffer.stats()
                    assertTrue(stats.durationUs in 0..3_000_000)
                    snapshots.add(snapshot)
                    barrier.await(5, TimeUnit.SECONDS)
                }
                snapshots
            }
            writer.get(15, TimeUnit.SECONDS)
            val snapshots = reader.get(15, TimeUnit.SECONDS)
            assertEquals(100, snapshots.size)
            val first = snapshots.first().samples.map { it.ptsUs }
            buffer.clear()
            assertEquals(first, snapshots.first().samples.map { it.ptsUs })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
