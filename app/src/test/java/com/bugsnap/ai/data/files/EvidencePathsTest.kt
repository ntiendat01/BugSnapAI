package com.bugsnap.ai.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EvidencePathsTest {
    @Test
    fun buildsSpecLayout() {
        assertEquals("sessions/s1/markers/m1/clip.mp4", EvidencePaths.markerFile("s1", "m1", "clip.mp4"))
        assertEquals("sessions/s1/markers/m1/frames/f2.jpg", EvidencePaths.frame("s1", "m1", 2))
        assertEquals("sessions/s1/exports/report.md", EvidencePaths.export("s1", "report.md"))
    }

    @Test
    fun rejectsUnsafeComponentsAndReservedNames() {
        listOf("", " ", ".", "..", "a/b", "a\\b", "C:", "a\u0000b", "a\nb").forEach { invalid ->
            rejects { EvidencePaths.markerFile(invalid, "m1", "clip.mp4") }
            rejects { EvidencePaths.markerFile("s1", invalid, "clip.mp4") }
            rejects { EvidencePaths.export("s1", invalid) }
        }
        rejects { EvidencePaths.markerFile("s1", "m1", "clip.mp4.part") }
        rejects { EvidencePaths.frame("s1", "m1", 0) }
    }

    @Test
    fun validatesPersistedPathsWithoutNormalizingTraversal() {
        listOf(
            "/sessions/s1/markers/m1/clip.mp4", "sessions/../markers/m1/clip.mp4",
            "sessions/s1//markers/m1/clip.mp4", "sessions/s1/markers/m1/clip.mp4.part",
            "spike/s1/markers/m1/clip.mp4", "sessions/s1/other/clip.mp4",
            "sessions/s1/markers/m1/other/f1.jpg", "C:\\sessions\\clip.mp4",
        ).forEach { rejects { EvidencePaths.components(it) } }
    }

    @Test
    fun storageThresholdsHaveExactBoundaries() {
        val mib = 1024L * 1024
        assertFalse(StorageStatus(200 * mib - 1).canStartSession)
        assertTrue(StorageStatus(200 * mib).canStartSession)
        assertTrue(StorageStatus(500 * mib - 1).shouldWarn)
        assertFalse(StorageStatus(500 * mib).shouldWarn)
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid path to be rejected")
        } catch (_: IllegalArgumentException) {
            // Invalid input is rejected by the pure path builder.
        }
    }
}
