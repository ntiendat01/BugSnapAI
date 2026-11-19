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
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TEST,
                context.getString(R.string.spike_channel_test),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun captureNotification(context: Context, recording: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mark = servicePendingIntent(context, 1, SpikeCaptureService.ACTION_MARK)
        val stop = servicePendingIntent(context, 2, SpikeCaptureService.ACTION_STOP)
        val title = if (recording) {
            context.getString(R.string.spike_notification_recording)
        } else {
            context.getString(R.string.spike_notification_idle)
        }
        return NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.spike_notification_text))
            .setContentIntent(open)
            .setOngoing(recording)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.spike_mark), mark)
            .addAction(0, context.getString(R.string.spike_stop), stop)
            .build()
    }

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
