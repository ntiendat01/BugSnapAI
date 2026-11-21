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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bugsnap.ai.R
import com.bugsnap.ai.data.files.EvidenceStore
import com.bugsnap.ai.data.media.VideoGalleryStore
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SpikeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SpikeSession,
    private val clipIndex: SpikeClipIndex,
    private val evidenceStore: EvidenceStore,
    private val gallery: VideoGalleryStore,
) : ViewModel() {
    val state: StateFlow<SpikeUiState> = session.state

    init {
        session.update { it.copy(quickMark = context.getSharedPreferences("capture_controls", Context.MODE_PRIVATE).getBoolean("quick_mark", true)) }
        viewModelScope.launch {
            if (evidenceStore.cleanupOrphanParts().isErr) {
                session.setError(context.getString(R.string.evidence_cleanup_failed))
            }
            if (gallery.cleanupPendingVideos().isErr) {
                session.setError(context.getString(R.string.video_cleanup_failed))
            }
            val saved = withContext(Dispatchers.IO) { clipIndex.load() }
            session.update { current -> current.copy(clips = (current.clips + saved).distinctBy { it.id }.sortedByDescending { it.savedAtMs }) }
        }
    }

    fun setQuickMark(enabled: Boolean) {
        context.getSharedPreferences("capture_controls", Context.MODE_PRIVATE).edit().putBoolean("quick_mark", enabled).apply()
        session.update { it.copy(quickMark = enabled) }
    }

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

    fun openClip(path: String): Intent? = SpikeIntents.viewClip(context, path)

    fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun send(action: String) {
        context.startService(Intent(context, SpikeCaptureService::class.java).setAction(action))
    }
}
