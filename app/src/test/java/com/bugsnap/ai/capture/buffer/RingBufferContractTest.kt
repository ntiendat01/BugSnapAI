package com.bugsnap.ai.capture.buffer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RingBufferContractTest {
    @Test
    fun sampleAndCodecConfigurationOwnTheirBytes() {
        val source = byteArrayOf(7, 8)
        val csd = byteArrayOf(1, 2)
        val sample = EncodedSample(source, 0, EncodedSample.FLAG_KEY_FRAME, 1)
        val gen = Generation(1, 1280, 720, "video/avc", csd, csd, 0)
        source[0] = 99
        csd[0] = 99
        sample.data[0] = 66
        gen.csd0[0] = 66
        gen.csd1!![0] = 66
        assertArrayEquals(byteArrayOf(7, 8), sample.data)
        assertArrayEquals(byteArrayOf(1, 2), gen.csd0)
        assertArrayEquals(byteArrayOf(1, 2), gen.csd1)
        assertEquals(2, sample.sizeBytes)
    }

    @Test
    fun snapshotCopiesAndFreezesListButSharesImmutableSamples() {
        val gen = generation()
        val first = EncodedSample.keyFrame(0, 1)
        val input = mutableListOf(first, EncodedSample.delta(100, 1))
        val snapshot = ClipSnapshot("frozen", input, gen, 100, 100, 0)
        input.clear()
        assertEquals(2, snapshot.samples.size)
        assertSame(first, snapshot.samples.first())
        try {
            (snapshot.samples as MutableList).clear()
            fail("Snapshot list must not be mutable")
        } catch (_: UnsupportedOperationException) {
            // Java/Kotlin callers cannot mutate the list through a cast.
        }
    }

    @Test
    fun overlappingMarkersShareSampleObjectsWithoutSharingMutableLists() {
        val buffer = buffer()
        buffer.feedThrough(2_000_000)
        val a = ready(buffer.mark("a", postRollUs = 0))
        val b = ready(buffer.mark("b", postRollUs = 0))
        a.samples.zip(b.samples).forEach { (left, right) -> assertSame(left, right) }
        assertFalse(a.samples === b.samples)
        buffer.clear()
        assertEquals(21, a.samples.size)
    }

    @Test
    fun ringWaitsForKeyframeAndIgnoresUnknownStaleEmptyAndUnorderedFrames() {
        val buffer = RingBuffer()
        buffer.append(EncodedSample.keyFrame(0, 1))
        assertEquals(0, buffer.stats().sampleCount)
        buffer.registerGeneration(generation())
        buffer.append(EncodedSample.delta(0, 1))
        assertTrue(buffer.mark("too-early") is MarkResult.Rejected)
        buffer.append(EncodedSample.keyFrame(100, 1))
        buffer.append(EncodedSample.delta(200, 1))
        buffer.append(EncodedSample.delta(200, 1))
        buffer.append(EncodedSample.delta(150, 1))
        buffer.append(EncodedSample.keyFrame(300, 2))
        buffer.append(EncodedSample(byteArrayOf(1), 400, EncodedSample.FLAG_CODEC_CONFIG, 1))
        buffer.append(EncodedSample(ByteArray(0), 500, 0, 1))
        assertEquals(listOf(100L, 200L), ready(buffer.mark("valid", postRollUs = 0)).samples.map { it.ptsUs })
        assertEquals(200L, buffer.lastPts())
    }

    @Test
    fun preRollIsCappedEvenWhenTargetFallsBetweenKeyframes() {
        val buffer = buffer()
        buffer.feedThrough(40_500_000)
        val snapshot = ready(buffer.mark("limit", postRollUs = 0))
        assertEquals(11_000_000L, snapshot.samples.first().ptsUs)
        assertEquals(29_500_000L, snapshot.actualPreRollUs)
        assertTrue(buffer.stats().durationUs <= 31_000_000)
    }

    @Test
    fun missingKeyframesCannotGrowRetentionWithoutBound() {
        val buffer = buffer()
        buffer.append(EncodedSample.keyFrame(0, 1))
        for (second in 1..100) buffer.append(EncodedSample.delta(second * 1_000_000L, 1))
        assertEquals(0, buffer.stats().sampleCount)
        assertEquals(0L, buffer.stats().ramBytes)
        assertTrue(buffer.mark("no-sync") is MarkResult.Rejected)
        buffer.append(EncodedSample.keyFrame(101_000_000, 1))
        assertEquals(1, ready(buffer.mark("recovered", postRollUs = 0)).samples.size)
    }

    @Test
    fun postRollDropsFrameBeyondDeadline() {
        val buffer = buffer()
        buffer.append(EncodedSample.keyFrame(0, 1))
        buffer.append(EncodedSample.delta(100, 1))
        assertTrue(buffer.mark("deadline", postRollUs = 200) is MarkResult.Pending)
        buffer.append(EncodedSample.delta(250, 1))
        val snapshot = buffer.append(EncodedSample.delta(350, 1)).single()
        assertEquals(listOf(0L, 100L, 250L), snapshot.samples.map { it.ptsUs })
        assertEquals(150L, snapshot.actualPostRollUs)
        assertEquals(0, buffer.pendingCount())
    }

    @Test
    fun exactDeadlineIsIncludedAndFinishesOnlyOnce() {
        val buffer = buffer()
        buffer.append(EncodedSample.keyFrame(0, 1))
        buffer.mark("exact", postRollUs = 500)
        assertEquals(500L, buffer.append(EncodedSample.delta(500, 1)).single().actualPostRollUs)
        assertTrue(buffer.append(EncodedSample.delta(600, 1)).isEmpty())
        assertTrue(buffer.forceFinalizePending().isEmpty())
    }

    @Test
    fun historicalMarkCollectsPostRollAlreadyCaptured() {
        val buffer = buffer()
        buffer.feedThrough(5_000_000)
        val snapshot = ready(buffer.mark("late", tMarkUs = 2_000_000, postRollUs = 1_000_000))
        assertEquals(3_000_000L, snapshot.samples.last().ptsUs)
        assertEquals(1_000_000L, snapshot.actualPostRollUs)
        assertEquals(0, buffer.pendingCount())
    }

    @Test
    fun historicalMarkBetweenFramesReportsOnlyActualCapturedDuration() {
        val buffer = buffer()
        buffer.feedThrough(1_000_000)
        val snapshot = ready(buffer.mark("between", tMarkUs = 950_000, postRollUs = 0))
        assertEquals(900_000L, snapshot.actualPreRollUs)
        assertEquals(snapshot.durationUs, snapshot.actualPreRollUs + snapshot.actualPostRollUs)
    }

    @Test
    fun generationSwitchRejectsOldCallbacksAndClosesPendingWithoutMixing() {
        val buffer = buffer()
        buffer.feedThrough(1_000_000)
        buffer.mark("old", postRollUs = 5_000_000)
        assertTrue(buffer.registerGeneration(generation(2)))
        assertEquals(-1L, buffer.lastPts())
        assertTrue(buffer.mark("before-key") is MarkResult.Rejected)
        assertFalse(buffer.registerGeneration(generation(1)))
        assertTrue(buffer.append(EncodedSample.delta(1_100_000, 1)).isEmpty())
        val old = buffer.append(EncodedSample.delta(1_200_000, 2)).single()
        assertEquals(1, old.generation.id)
        assertTrue(old.samples.all { it.generation == 1 })
        assertEquals(0L, old.actualPostRollUs)
        assertTrue(buffer.mark("still-no-key") is MarkResult.Rejected)
        buffer.append(EncodedSample.keyFrame(1_300_000, 2))
        assertEquals(2, ready(buffer.mark("new", postRollUs = 0)).generation.id)
    }

    @Test
    fun repeatedIdenticalGenerationKeepsRingButChangedConfigurationCannotReuseId() {
        val buffer = buffer()
        buffer.feedThrough(1_000_000)
        assertTrue(buffer.registerGeneration(generation()))
        assertEquals(11, buffer.stats().sampleCount)
        assertFalse(buffer.registerGeneration(Generation(1, 720, 1280, "video/avc", byteArrayOf(9), null, 0)))
        assertEquals(1280, ready(buffer.mark("same", postRollUs = 0)).generation.width)
    }

    @Test
    fun invalidMarkInputsAreRejectedWithoutChangingPendingState() {
        val buffer = buffer()
        buffer.feedThrough(1_000_000)
        val requests = listOf(
            buffer.mark("", postRollUs = 0), buffer.mark("pre", preRollUs = -1),
            buffer.mark("pre-cap", preRollUs = 30_000_001), buffer.mark("post", postRollUs = -1),
            buffer.mark("post-cap", postRollUs = 10_000_001), buffer.mark("future", tMarkUs = 1_000_001),
            buffer.mark("negative", tMarkUs = -1),
        )
        assertTrue(requests.all { it is MarkResult.Rejected })
        assertEquals(0, buffer.pendingCount())
        buffer.mark("duplicate")
        assertTrue(buffer.mark("duplicate") is MarkResult.Rejected)
        assertEquals(1, buffer.forceFinalizePending().size)
    }

    @Test
    fun extremeTimestampsDoNotOverflowPostRollOrEviction() {
        val buffer = buffer()
        buffer.append(EncodedSample.keyFrame(Long.MAX_VALUE - 100, 1))
        buffer.append(EncodedSample.delta(Long.MAX_VALUE, 1))
        assertTrue(buffer.mark("overflow", postRollUs = 1) is MarkResult.Rejected)
        assertEquals(100L, ready(buffer.mark("safe", postRollUs = 0)).actualPreRollUs)
    }

    @Test
    fun clearResetsStreamAndForceFinalizeIsIdempotent() {
        val buffer = buffer()
        buffer.feedThrough(1_000_000)
        buffer.mark("stop")
        assertEquals(1, buffer.forceFinalizePending().size)
        assertTrue(buffer.forceFinalizePending().isEmpty())
        buffer.clear()
        assertEquals(-1L, buffer.lastPts())
        assertEquals(-1, buffer.stats().currentGenerationId)
        assertEquals(0L, buffer.stats().ramBytes)
        assertTrue(buffer.mark("cleared") is MarkResult.Rejected)
        assertTrue(buffer.registerGeneration(generation()))
    }

    private fun buffer() = RingBuffer().apply { registerGeneration(generation()) }
}
