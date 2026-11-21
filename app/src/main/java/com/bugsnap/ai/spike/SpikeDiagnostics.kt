package com.bugsnap.ai.spike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bugsnap.ai.R

@Composable
fun SpikeDiagnostics(state: SpikeUiState, onReuseToken: () -> Unit, onNotification: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture_diagnostics)) }
        if (expanded) {
            Text(stringResource(R.string.spike_metrics, state.phase.name, state.recordingElapsedMs / 1000,
                state.bufferDurationMs / 1000, state.bufferRamBytes / 1024, state.javaHeapBytes / 1024,
                state.encodedFps, state.lastKeyframeIntervalMs ?: -1L, state.keyframeCount, state.generation,
                state.encodeWidth, state.encodeHeight, state.markers.count { it.pending }, state.overlayActive.toString(),
                state.thermalStatus?.toString() ?: "-", state.batteryPct?.toString() ?: "-", state.lastMarkLatencyMs ?: -1))
            OutlinedButton(onClick = onReuseToken, enabled = state.phase == SpikePhase.Recording) { Text(stringResource(R.string.spike_reuse_token)) }
            OutlinedButton(onClick = onNotification) { Text(stringResource(R.string.spike_post_test_notification)) }
            state.lastTokenReuseResult?.let { Text(it) }
        }
    }
}
