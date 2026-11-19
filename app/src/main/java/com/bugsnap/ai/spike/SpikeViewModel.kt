package com.bugsnap.ai.spike

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SpikeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SpikeSession,
) : ViewModel() {
    val state: StateFlow<SpikeUiState> = session.state

    fun onProjectionAccepted(resultCode: Int, data: Intent) {
        session.resetForNewSession()
        val start = Intent(context, SpikeCaptureService::class.java).apply {
            action = SpikeCaptureService.ACTION_START
            putExtra(SpikeCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SpikeCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(context, start)
        SpikeLog.i("requested service start after user consent")
    }

    fun onProjectionDenied() {
        session.setPhase(SpikePhase.Idle, "user denied MediaProjection")
        session.setError("MediaProjection bị từ chối")
    }

    fun mark() = send(SpikeCaptureService.ACTION_MARK)

    fun stop() = send(SpikeCaptureService.ACTION_STOP)

    fun reuseToken() = send(SpikeCaptureService.ACTION_REUSE_TOKEN)

    fun postTestNotification() {
        SpikeNotifications.postTestNotification(context)
    }

    fun overlaySettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun openClip(path: String): Intent? {
        val file = File(path)
        if (!file.exists()) {
            session.setError("clip not found: ${file.name}")
            return null
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= 33
    }

    private fun send(action: String) {
        context.startService(Intent(context, SpikeCaptureService::class.java).setAction(action))
    }
}
