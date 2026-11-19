package com.bugsnap.ai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bugsnap.ai.ui.home.HomeScreen

@Composable
fun BugSnapNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Route.Home.path,
        modifier = modifier,
    ) {
        composable(route = Route.Home.path) {
            HomeScreen()
        }
    }
}
