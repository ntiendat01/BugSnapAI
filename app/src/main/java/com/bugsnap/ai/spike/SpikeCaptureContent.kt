package com.bugsnap.ai.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bugsnap.ai.R

@Composable
fun SpikeCaptureContent(
    state: SpikeUiState,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    onMark: () -> Unit = {},
    onStop: () -> Unit = {},
    onOpenClip: (SpikeClipInfo) -> Unit = {},
    onOverlay: () -> Unit = {},
    onQuickMark: (Boolean) -> Unit = {},
    onHelp: () -> Unit = {},
    diagnostics: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val marker = state.markers.lastOrNull()
    var acknowledgedId by remember { mutableStateOf(marker?.id) }
    LaunchedEffect(marker?.id) {
        if (marker != null && marker.id != acknowledgedId) {
            if (marker.acceptedInApp) view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            acknowledgedId = marker.id
        }
    }
    val recording = state.phase == SpikePhase.Recording
    val starting = state.phase == SpikePhase.Starting
    val pending = state.markers.any { it.pending }
    var confirmStop by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onHelp) { Text(stringResource(R.string.capture_help_action)) }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = if (recording) onMark else onStart,
                    enabled = recording || (!starting && !pending),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(stringResource(when {
                        recording -> R.string.spike_mark
                        starting -> R.string.spike_status_starting
                        pending -> R.string.capture_finishing
                        state.phase == SpikePhase.ProjectionLost -> R.string.capture_restart
                        else -> R.string.spike_start
                    }), style = MaterialTheme.typography.titleMedium)
                }
                if (recording || starting) TextButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.capture_stop))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(when (state.phase) {
                        SpikePhase.Idle -> R.string.capture_ready
                        SpikePhase.Starting -> R.string.spike_status_starting
                        SpikePhase.Recording -> R.string.spike_status_recording
                        SpikePhase.ProjectionLost -> R.string.spike_status_projection_lost
                        SpikePhase.Stopped -> R.string.spike_status_stopped
                    }), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    if (recording || starting) {
                        Text(stringResource(R.string.capture_clock, state.recordingElapsedMs / 60_000, state.recordingElapsedMs / 1000 % 60), fontSize = 48.sp, lineHeight = 56.sp)
                        LinearProgressIndicator(progress = { (state.bufferDurationMs / 30_000f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.capture_buffer, state.bufferDurationMs / 1000), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text(stringResource(R.string.capture_intro), style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (marker != null) item {
                CaptureCard {
                    val titleColor = when (marker.stage) {
                        MarkerStage.Saved -> MaterialTheme.colorScheme.tertiary
                        MarkerStage.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Text(SpikeFeedbackText.title(context, state), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = titleColor, style = MaterialTheme.typography.titleMedium)
                    Text(SpikeFeedbackText.detail(context, state), style = MaterialTheme.typography.bodyLarge)
                    if (marker.stage == MarkerStage.Queued || marker.stage == MarkerStage.Exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val clip = state.clips.find { it.id == marker.id }
                    if (clip != null) {
                        if (clip.imageCount < 2) Text(stringResource(R.string.clip_missing_images), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { onOpenClip(clip) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.capture_view_clip)) }
                        Text(stringResource(R.string.capture_clip_location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else item {
                CaptureCard {
                    Text(stringResource(R.string.capture_configuration), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (recording) R.string.capture_other_app else R.string.capture_permission_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.lastError != null) item {
                CaptureCard {
                    Text(stringResource(R.string.capture_error_title), color = MaterialTheme.colorScheme.error)
                    Text(state.lastError, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOverlay, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(if (state.overlayActive) R.string.spike_overlay_granted else R.string.spike_overlay_request)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.capture_quick_mark), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = state.quickMark, onCheckedChange = onQuickMark)
                    }
                    Text(stringResource(R.string.overlay_hold_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.capture_clips), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.capture_saved_count, state.clips.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.clips.isEmpty() && !pending) item { Text(stringResource(R.string.capture_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            items(state.markers.filter { it.pending || it.stage == MarkerStage.Failed }, key = { it.id }) { item ->
                CaptureCard {
                    Text(stringResource(R.string.capture_clip_number, item.number), style = MaterialTheme.typography.titleMedium)
                    Text(SpikeFeedbackText.title(context, state.copy(markers = listOf(item))), style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(state.clips, key = { it.id }) { clip ->
                CaptureCard {
                    Text(stringResource(R.string.capture_clip_number, clip.number), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.clip_summary, clip.durationMs / 1000, clip.imageCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { onOpenClip(clip) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture_view_clip)) }
                }
            }
            item { diagnostics() }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text(stringResource(R.string.capture_stop_question)) },
        text = { Text(stringResource(R.string.capture_stop_help)) },
        confirmButton = { TextButton(onClick = { confirmStop = false; onStop() }) { Text(stringResource(R.string.capture_stop)) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.capture_cancel)) } },
    )
}

@Composable
private fun CaptureCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
