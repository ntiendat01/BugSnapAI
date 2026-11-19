package com.bugsnap.ai.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EvidenceEntityTest {
    @Test
    fun acceptsRelativePath() {
        val entity = evidence("sessions/s1/markers/m1/clip.mp4")
        assertEquals("sessions/s1/markers/m1/clip.mp4", entity.relPath)
    }

    @Test
    fun rejectsBlankOrAbsolutePath() {
        for (invalid in listOf("", "/", "/data/clip.mp4")) {
            try {
                evidence(invalid)
                fail("expected relPath=$invalid to be rejected")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    private fun evidence(relPath: String) = EvidenceEntity(
        id = "e1",
        markerId = "m1",
        type = EvidenceType.CLIP,
        relPath = relPath,
        sizeBytes = 1,
    )
}
