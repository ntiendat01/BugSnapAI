package com.bugsnap.ai.spike

import org.junit.Assert.*
import org.junit.Test

class SpikeSessionTest {
    @Test fun completionOfAnOlderMarkerKeepsNewerMarkerAndPendingCount() {
        val session = SpikeSession()
        session.acceptMarker("first", 1_000)
        session.acceptMarker("second", 2_000)
        session.markerStage("first", MarkerStage.Exporting)
        session.markerStage("first", MarkerStage.Saved)
        assertEquals("second", session.state.value.markers.last().id)
        assertEquals(1, session.state.value.exportingCount)
        assertEquals(MarkerStage.Collecting, session.state.value.markers.last().stage)
    }

    @Test fun restartRetainsSavedClipsAndMarkerIdsNeverCollide() {
        val session = SpikeSession()
        val id = session.nextMarkerId()
        session.addClip(SpikeClipInfo(id, "clip.mp4", 1000, 500, 500, 500))
        session.resetForNewSession()
        assertEquals(id, session.state.value.clips.single().id)
        assertNotEquals(id, session.nextMarkerId())
        assertTrue(session.state.value.markers.isEmpty())
    }

    @Test fun countdownUsesDeadlineAndNeverBecomesNegative() {
        val marker = SpikeMarker("one", 1, 1000, 6000)
        assertEquals(5, marker.remainingSeconds(1000))
        assertEquals(1, marker.remainingSeconds(5999))
        assertEquals(0, marker.remainingSeconds(7000))
    }
}
