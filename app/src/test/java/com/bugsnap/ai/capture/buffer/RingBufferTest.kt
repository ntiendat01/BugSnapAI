package com.bugsnap.ai.capture.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RingBufferTest {
    private val fps = 30
    private val frameUs = 1_000_000L / fps
    private val gopFrames = 30

    @Test
    fun evictionAlwaysStartsAtKeyframeAndStaysWithinBufferPlusMargin() {
        val buffer = bufferOf()
        feed(buffer, generation = gen(1), durationUs = 40_000_000L)

        val stats = buffer.stats()
        assertTrue("duration ${stats.durationUs} exceeded cap+margin+gop", stats.durationUs <= 32_000_000L + frameUs)
        val snapshot = readySnapshot(buffer.mark("e1", preRollUs = 30_000_000L, postRollUs = 0L))
        assertTrue(snapshot.samples.first().isKeyFrame)
        assertTrue(snapshot.samples.all { it.generation == 1 })
        assertTrue("preRoll ${snapshot.actualPreRollUs}", snapshot.actualPreRollUs <= 31_000_000L + frameUs)
    }

    @Test
    fun snapshotIsImmutableWhenBufferContinuesAndEvicts() {
        val buffer = bufferOf()
        feed(buffer, generation = gen(1), durationUs = 10_000_000L)
        val tMark = buffer.lastPts()
        assertTrue(buffer.mark("imm", tMarkUs = tMark, preRollUs = 10_000_000L, postRollUs = 5_000_000L) is MarkResult.Pending)

        feed(buffer, generation = gen(1), durationUs = 40_000_000L, startPtsUs = tMark + frameUs)
        // post-roll should have completed; grab by force-finalize leftover or through append return
        val snapshots = buffer.forceFinalizePending()
        // Already completed clips are not pending; feed() after mark returned completed via append.
        // Re-run a fresh buffer to capture append results.
        val buffer2 = bufferOf()
        feed(buffer2, generation = gen(1), durationUs = 10_000_000L)
        val markPts = buffer2.lastPts()
        buffer2.mark("imm2", tMarkUs = markPts, preRollUs = 10_000_000L, postRollUs = 5_000_000L)
        var completed: ClipSnapshot? = null
        val extraFrames = frames(gen(1), startPtsUs = markPts + frameUs, durationUs = 20_000_000L)
        for (frame in extraFrames) {
            val done = buffer2.append(frame)
            if (done.isNotEmpty()) completed = done.first()
        }
        assertNotNull(completed)
        val frozen = completed!!
        val frozenPts = frozen.samples.map { it.ptsUs }
        feed(buffer2, generation = gen(1), durationUs = 20_000_000L, startPtsUs = buffer2.lastPts() + frameUs)
        assertEquals(frozenPts, frozen.samples.map { it.ptsUs })
        assertTrue(buffer2.stats().durationUs <= 31_000_000L + frameUs)
        assertTrue(frozen.samples.first().isKeyFrame)
    }

    @Test
    fun tenMarksInThreeSecondsHaveDistinctIdsAndKeepSamples() {
        val buffer = bufferOf()
        feed(buffer, generation = gen(1), durationUs = 5_000_000L)
        val base = buffer.lastPts()
        val ids = (0 until 10).map { "m$it" }
        val completed = mutableListOf<ClipSnapshot>()
        for ((index, id) in ids.withIndex()) {
            val tMark = base + index * 300_000L
            // Align tMark to an existing or upcoming frame by feeding one frame at tMark
            completed += buffer.append(EncodedSample.delta(tMark, 1))
            val result = buffer.mark(id, tMarkUs = tMark, preRollUs = 5_000_000L, postRollUs = 1_000_000L)
            when (result) {
                is MarkResult.Rejected -> fail("$id rejected: ${result.reason}")
                is MarkResult.Ready -> completed += result.snapshot
                is MarkResult.Pending -> Unit
            }
        }
        val tail = frames(gen(1), startPtsUs = base + 10 * 300_000L + frameUs, durationUs = 3_000_000L)
        for (frame in tail) {
            completed += buffer.append(frame)
        }
        completed += buffer.forceFinalizePending()
        val byId = completed.associateBy { it.id }
        assertEquals(ids.toSet(), byId.keys)
        for (id in ids) {
            val snap = byId.getValue(id)
            assertTrue("$id missing samples", snap.samples.isNotEmpty())
            assertTrue("$id must start on keyframe", snap.samples.first().isKeyFrame)
        }
    }

    @Test
    fun markAtEvictionEdgeStillStartsOnKeyframe() {
        val buffer = bufferOf()
        feed(buffer, generation = gen(1), durationUs = 40_000_000L)
        val tMark = buffer.lastPts()
        val snapshot = readySnapshot(
            buffer.mark("edge", tMarkUs = tMark, preRollUs = 30_000_000L, postRollUs = 0L),
        )
        assertTrue(snapshot.samples.first().isKeyFrame)
        assertTrue(snapshot.samples.first().isKeyFrame)
    }

    @Test
    fun clipDoesNotMixGenerations() {
        val buffer = bufferOf()
        feed(buffer, generation = gen(1), durationUs = 8_000_000L)
        buffer.registerGeneration(gen(2, startPtsUs = 8_000_000L))
        feed(buffer, generation = gen(2, startPtsUs = 8_000_000L), durationUs = 8_000_000L, startPtsUs = 8_000_000L)
        val snapshot = readySnapshot(
            buffer.mark("gen", tMarkUs = buffer.lastPts(), preRollUs = 30_000_000L, postRollUs = 0L),
        )
        assertTrue(snapshot.samples.isNotEmpty())
        assertTrue(snapshot.samples.all { it.generation == 2 })
        assertEquals(2, snapshot.generation.id)
    }

    @Test
    fun shortBufferWhenMarkedBeforeCapacity() {
        val buffer = bufferOf()
        feed(buffer, generation = gen(1), durationUs = 3_000_000L)
        val snapshot = readySnapshot(
            buffer.mark("short", tMarkUs = buffer.lastPts(), preRollUs = 30_000_000L, postRollUs = 0L),
        )
        assertTrue(snapshot.actualPreRollUs <= 3_000_000L + frameUs)
        assertTrue(snapshot.samples.first().isKeyFrame)
    }

    @Test
    fun forceFinalizeEndsPostRollEarly() {
        val buffer = bufferOf()
        feed(buffer, generation = gen(1), durationUs = 5_000_000L)
        val result = buffer.mark("early", tMarkUs = buffer.lastPts(), preRollUs = 5_000_000L, postRollUs = 5_000_000L)
        assertTrue(result is MarkResult.Pending)
        val snapshots = buffer.forceFinalizePending()
        assertEquals(1, snapshots.size)
        assertTrue(snapshots[0].actualPostRollUs < 5_000_000L)
        assertEquals(0, buffer.pendingCount())
    }

    private fun bufferOf(): RingBuffer {
        val buffer = RingBuffer(maxDurationUs = 30_000_000L, marginUs = 1_000_000L)
        buffer.registerGeneration(gen(1))
        return buffer
    }

    private fun gen(id: Int, startPtsUs: Long = 0L) = Generation(
        id = id,
        width = 1280,
        height = 720,
        mime = "video/avc",
        csd0 = byteArrayOf(0, 0, 0, 1, 103),
        csd1 = byteArrayOf(0, 0, 0, 1, 104),
        startPtsUs = startPtsUs,
    )

    private fun feed(
        buffer: RingBuffer,
        generation: Generation,
        durationUs: Long,
        startPtsUs: Long = generation.startPtsUs,
    ) {
        if (generation.id != buffer.stats().currentGenerationId && buffer.stats().currentGenerationId != -1) {
            buffer.registerGeneration(generation)
        }
        for (frame in frames(generation, startPtsUs, durationUs)) {
            buffer.append(frame)
        }
    }

    private fun frames(generation: Generation, startPtsUs: Long, durationUs: Long): List<EncodedSample> {
        val out = ArrayList<EncodedSample>()
        var pts = startPtsUs
        val end = startPtsUs + durationUs
        var index = 0
        while (pts < end) {
            val isKey = index % gopFrames == 0
            out += if (isKey) {
                EncodedSample.keyFrame(pts, generation.id)
            } else {
                EncodedSample.delta(pts, generation.id)
            }
            pts += frameUs
            index += 1
        }
        return out
    }

    private fun readySnapshot(result: MarkResult): ClipSnapshot = when (result) {
        is MarkResult.Ready -> result.snapshot
        is MarkResult.Pending -> error("expected immediate snapshot, got pending ${result.id}")
        is MarkResult.Rejected -> error("rejected: ${result.reason}")
    }
}
