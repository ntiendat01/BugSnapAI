package com.bugsnap.ai.spike

enum class MarkerStage { Collecting, Queued, Exporting, Saved, Failed }

data class SpikeMarker(
    val id: String,
    val number: Int,
    val acceptedAtMs: Long,
    val deadlineMs: Long,
    val stage: MarkerStage = MarkerStage.Collecting,
    val error: String? = null,
    val acceptedInApp: Boolean = false,
) {
    val pending: Boolean get() = stage in setOf(MarkerStage.Collecting, MarkerStage.Queued, MarkerStage.Exporting)
    fun remainingSeconds(nowMs: Long): Long = ((deadlineMs - nowMs + 999L) / 1000L).coerceAtLeast(0)
}
