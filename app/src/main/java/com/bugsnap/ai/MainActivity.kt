package com.bugsnap.ai

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.bugsnap.ai.spike.SpikeClipIndex
import com.bugsnap.ai.spike.SpikeIntents
import com.bugsnap.ai.spike.SpikeNotifications
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bugsnap.ai.ui.navigation.BugSnapNavHost
import com.bugsnap.ai.ui.theme.BugSnapTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var clipIndex: SpikeClipIndex
    @Inject lateinit var captureSession: com.bugsnap.ai.spike.SpikeSession
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        openRequestedClip(intent)
        setContent {
            BugSnapTheme {
                BugSnapNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        captureSession.update { it.copy(appVisible = true) }
    }

    override fun onPause() {
        captureSession.update { it.copy(appVisible = false) }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedClip(intent)
    }

    private fun openRequestedClip(intent: Intent) {
        val id = intent.getStringExtra(SpikeNotifications.EXTRA_CLIP_ID) ?: return
        intent.removeExtra(SpikeNotifications.EXTRA_CLIP_ID)
        lifecycleScope.launch {
            val clip = withContext(Dispatchers.IO) { clipIndex.load().find { it.id == id } }
            clip?.let { SpikeIntents.openClip(this@MainActivity, it.clipPath) }
        }
    }
}
