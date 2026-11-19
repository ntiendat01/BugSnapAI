package com.bugsnap.ai.ui.navigation

sealed class Route(val path: String) {
    data object Home : Route("home")
    data object Spike : Route("spike")
}
