package com.bugsnap.ai.spike

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bugsnap.ai.R

@Composable
fun SpikeCaptureScreen(modifier: Modifier = Modifier, viewModel: SpikeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var help by remember { mutableStateOf(false) }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) viewModel.onProjectionAccepted(result.resultCode, data)
        else viewModel.onProjectionDenied()
    }
    fun launchProjection() {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchProjection()
    }
    SpikeCaptureContent(
        state = state,
        modifier = modifier,
        onStart = {
            if (viewModel.needsNotificationPermission()) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else launchProjection()
        },
        onMark = viewModel::mark,
        onStop = viewModel::stop,
        onOpenClip = { SpikeIntents.openClip(context, it.clipPath) },
        onOverlay = { context.startActivity(viewModel.overlaySettingsIntent()) },
        onQuickMark = viewModel::setQuickMark,
        onHelp = { help = true },
        diagnostics = { SpikeDiagnostics(state, viewModel::reuseToken, viewModel::postTestNotification) },
    )
    if (help) AlertDialog(
        onDismissRequest = { help = false },
        title = { Text(stringResource(R.string.capture_help_title)) },
        text = { Text(stringResource(R.string.capture_help)) },
        confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.capture_close)) } },
    )
}
