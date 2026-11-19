package com.bugsnap.ai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bugsnap.ai.spike.SpikeCaptureScreen
import com.bugsnap.ai.ui.home.HomeScreen

@Composable
fun BugSnapNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Route.Spike.path,
        modifier = modifier,
    ) {
        composable(route = Route.Spike.path) {
            SpikeCaptureScreen()
        }
        composable(route = Route.Home.path) {
            HomeScreen()
        }
    }
}
