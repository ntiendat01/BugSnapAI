package com.bugsnap.ai.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bugsnap.ai.MainActivity
import com.bugsnap.ai.R

object SpikeNotifications {
    const val CHANNEL_CAPTURE = "spike_capture"
    const val CHANNEL_TEST = "spike_test"
    const val CAPTURE_ID = 1001
    const val RESULT_ID = 1003
    const val CHANNEL_RESULTS = "capture_results"
    const val EXTRA_CLIP_ID = "clip_id"
    const val TEST_ID = 1002
    const val TEST_BODY = "SPIKE-NOTIF-VISIBLE"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                context.getString(R.string.spike_channel_capture),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.capture_results_channel), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TEST,
                context.getString(R.string.spike_channel_test),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun captureNotification(context: Context, recording: Boolean, state: SpikeUiState? = null): Notification {
        val current = state ?: SpikeUiState(phase = if (recording) SpikePhase.Starting else SpikePhase.Stopped)
        val notification = base(context, CHANNEL_CAPTURE)
            .setContentTitle(context.getString(if (current.phase == SpikePhase.Recording) R.string.spike_status_recording else if (current.phase == SpikePhase.Starting) R.string.spike_status_starting else R.string.spike_status_stopped))
            .setContentText(SpikeFeedbackText.title(context, current) + " · " + SpikeFeedbackText.detail(context, current))
            .setContentIntent(openApp(context))
            .setOngoing(recording)
        if (current.phase == SpikePhase.Recording) notification.addAction(0, context.getString(R.string.spike_mark), servicePendingIntent(context, 1, SpikeCaptureService.ACTION_MARK))
        if (recording) notification.addAction(0, context.getString(R.string.capture_stop), servicePendingIntent(context, 2, SpikeCaptureService.ACTION_STOP))
        return notification.build()
    }

    fun update(context: Context, state: SpikeUiState) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(CAPTURE_ID, captureNotification(context,
                state.phase in setOf(SpikePhase.Starting, SpikePhase.Recording), state))
        } catch (error: SecurityException) {
            SpikeLog.w("notification permission changed", error)
        }
    }

    fun result(context: Context, state: SpikeUiState, clip: SpikeClipInfo? = null) {
        if (!canNotify(context)) return
        val notification = base(context, CHANNEL_RESULTS)
            .setContentTitle(SpikeFeedbackText.title(context, state))
            .setContentText(SpikeFeedbackText.detail(context, state))
            .setContentIntent(openApp(context, clip?.id))
            .setAutoCancel(true)
            .addAction(0, context.getString(if (clip != null) R.string.capture_view_clip else R.string.capture_open_app), openApp(context, clip?.id))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(RESULT_ID, notification)
        } catch (error: SecurityException) {
            SpikeLog.w("notification permission changed", error)
        }
    }

    private fun base(context: Context, channel: String): NotificationCompat.Builder {
        val public = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.capture_public_notification)).build()
        return NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher)
            .setOnlyAlertOnce(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
    }

    private fun openApp(context: Context, clipId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = android.net.Uri.Builder().scheme("bugsnap").authority("clip").appendPath(clipId ?: "session").build()
            if (clipId != null) putExtra(EXTRA_CLIP_ID, clipId)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun postTestNotification(context: Context) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_TEST)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.spike_test_notification_title))
            .setContentText(TEST_BODY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            SpikeLog.w("V7 notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        NotificationManagerCompat.from(context).notify(TEST_ID, notification)
        SpikeLog.i("posted V7 test notification body=$TEST_BODY")
    }

    private fun servicePendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, SpikeCaptureService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
