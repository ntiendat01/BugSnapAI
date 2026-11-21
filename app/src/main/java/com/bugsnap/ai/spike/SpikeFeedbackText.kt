package com.bugsnap.ai.spike

import android.content.Context
import com.bugsnap.ai.R

object SpikeFeedbackText {
    fun title(context: Context, state: SpikeUiState): String {
        val marker = state.markers.lastOrNull() ?: return context.getString(
            when (state.phase) {
                SpikePhase.Idle -> R.string.capture_ready
                SpikePhase.Starting -> R.string.spike_status_starting
                SpikePhase.Recording -> R.string.capture_started
                SpikePhase.ProjectionLost -> R.string.spike_status_projection_lost
                SpikePhase.Stopped -> R.string.spike_status_stopped
            },
        )
        return context.getString(
            when (marker.stage) {
                MarkerStage.Collecting -> R.string.marker_accepted
                MarkerStage.Queued -> R.string.marker_queued
                MarkerStage.Exporting -> R.string.marker_exporting
                MarkerStage.Saved -> R.string.marker_saved
                MarkerStage.Failed -> R.string.marker_failed
            }, marker.number,
        )
    }

    fun detail(context: Context, state: SpikeUiState): String {
        val marker = state.markers.lastOrNull() ?: return state.lastError
            ?: context.getString(R.string.capture_other_app)
        return when (marker.stage) {
            MarkerStage.Collecting -> context.getString(R.string.marker_postroll, marker.remainingSeconds(state.nowMs))
            MarkerStage.Queued, MarkerStage.Exporting -> context.getString(R.string.marker_processing, state.markers.count { it.pending })
            MarkerStage.Saved -> {
                val clip = state.clips.find { it.id == marker.id }
                if (clip == null) context.getString(R.string.marker_finalizing)
                else context.getString(R.string.clip_summary, clip.durationMs / 1000, clip.imageCount)
            }
            MarkerStage.Failed -> marker.error ?: context.getString(R.string.marker_error_help)
        }
    }
}
