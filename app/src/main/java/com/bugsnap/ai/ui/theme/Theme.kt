package com.bugsnap.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BugSnapColors.Primary,
    onPrimary = BugSnapColors.OnPrimary,
    background = BugSnapColors.Background,
    onBackground = BugSnapColors.Text,
    surface = BugSnapColors.Surface,
    onSurface = BugSnapColors.Text,
    surfaceVariant = BugSnapColors.SurfaceVariant,
    onSurfaceVariant = BugSnapColors.TextMuted,
    error = BugSnapColors.Danger,
    onError = BugSnapColors.OnPrimary,
    outline = BugSnapColors.TextMuted,
    secondary = BugSnapColors.Primary,
    onSecondary = BugSnapColors.OnPrimary,
    tertiary = BugSnapColors.Success,
    onTertiary = Color(0xFF04221A),
)

@Composable
fun BugSnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = BugSnapTypography,
        content = content,
    )
}
