package com.bugsnap.ai.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.bugsnap.ai.R

@Composable
fun SpikeCaptureScreen(
    modifier: Modifier = Modifier,
    viewModel: SpikeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.onProjectionAccepted(result.resultCode, data)
        } else {
            viewModel.onProjectionDenied()
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        SpikeLog.i("POST_NOTIFICATIONS granted=$granted")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.spike_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.spike_banner),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.spike_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        StatusBlock(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (viewModel.needsNotificationPermission()) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                },
                enabled = state.phase != SpikePhase.Recording && state.phase != SpikePhase.Starting,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.spike_start)) }
            OutlinedButton(
                onClick = viewModel::stop,
                enabled = state.phase == SpikePhase.Recording || state.phase == SpikePhase.Starting,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.spike_stop)) }
        }
        Button(
            onClick = viewModel::mark,
            enabled = state.phase == SpikePhase.Recording,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.spike_mark)) }
        OutlinedButton(
            onClick = { context.startActivity(viewModel.overlaySettingsIntent()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            val overlayLabel = if (viewModel.canDrawOverlays()) {
                stringResource(R.string.spike_overlay_granted)
            } else {
                stringResource(R.string.spike_overlay_request)
            }
            Text(overlayLabel)
        }
        OutlinedButton(
            onClick = viewModel::reuseToken,
            enabled = state.phase == SpikePhase.Recording,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.spike_reuse_token)) }
        OutlinedButton(
            onClick = viewModel::postTestNotification,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.spike_post_test_notification)) }
        if (state.lastTokenReuseResult != null) {
            Text(
                stringResource(R.string.spike_reuse_result, state.lastTokenReuseResult.orEmpty()),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (state.lastError != null) {
            Text(
                state.lastError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(stringResource(R.string.spike_clips_header), style = MaterialTheme.typography.titleMedium)
        if (state.clips.isEmpty()) {
            Text(stringResource(R.string.spike_clips_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            state.clips.forEach { clip ->
                OutlinedButton(
                    onClick = {
                        val intent = viewModel.openClip(clip.clipPath) ?: return@OutlinedButton
                        context.startActivity(Intent.createChooser(intent, clip.id))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            R.string.spike_clip_item,
                            clip.id,
                            clip.durationMs,
                            clip.actualPreRollMs,
                            clip.actualPostRollMs,
                            clip.latencyMs,
                        ),
                    )
                }
            }
        }
        Text(stringResource(R.string.spike_logcat_hint), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatusBlock(state: SpikeUiState) {
    val phaseLabel = when (state.phase) {
        SpikePhase.Idle -> stringResource(R.string.spike_status_idle)
        SpikePhase.Starting -> stringResource(R.string.spike_status_starting)
        SpikePhase.Recording -> stringResource(R.string.spike_status_recording)
        SpikePhase.ProjectionLost -> stringResource(R.string.spike_status_projection_lost)
        SpikePhase.Stopped -> stringResource(R.string.spike_status_stopped)
    }
    Text(
        stringResource(
            R.string.spike_metrics,
            phaseLabel,
            state.recordingElapsedMs / 1000L,
            state.bufferDurationMs / 1000L,
            state.bufferRamBytes / 1024L,
            state.javaHeapBytes / 1024L,
            state.encodedFps,
            state.lastKeyframeIntervalMs ?: -1L,
            state.keyframeCount,
            state.generation,
            state.encodeWidth,
            state.encodeHeight,
            state.exportingCount,
            state.overlayActive.toString(),
            state.thermalStatus?.toString() ?: "-",
            state.batteryPct?.toString() ?: "-",
            state.lastMarkLatencyMs ?: -1L,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
