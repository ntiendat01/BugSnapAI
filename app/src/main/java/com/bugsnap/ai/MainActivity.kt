package com.bugsnap.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bugsnap.ai.ui.navigation.BugSnapNavHost
import com.bugsnap.ai.ui.theme.BugSnapTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BugSnapTheme {
                BugSnapNavHost()
            }
        }
    }
}
