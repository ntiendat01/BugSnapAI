package com.bugsnap.ai.spike

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bugsnap.ai.R
import java.io.File

object SpikeIntents {
    fun viewClip(context: Context, path: String): Intent? {
        val file = File(path)
        if (!file.isFile || !file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun openClip(context: Context, path: String) {
        val intent = viewClip(context, path) ?: return
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.capture_view_clip)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, R.string.clip_open_failed, android.widget.Toast.LENGTH_LONG).show()
            SpikeLog.w("clip viewer unavailable", error)
        }
    }
}
