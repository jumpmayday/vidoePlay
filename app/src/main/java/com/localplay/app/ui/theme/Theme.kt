package com.localplay.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LocalPlayDarkColors = darkColorScheme(
    primary = LpPrimary,
    onPrimary = LpOnPrimary,
    primaryContainer = LpPrimaryDim,
    onPrimaryContainer = LpPrimary,
    secondary = LpPrimary,
    onSecondary = LpOnPrimary,
    background = LpBg,
    onBackground = LpText,
    surface = LpSurface,
    onSurface = LpText,
    surfaceVariant = LpSurface2,
    onSurfaceVariant = LpText2,
    error = LpDanger,
    onError = LpText,
    outline = LpDivider,
    scrim = Color.Black
)

@Composable
fun LocalPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LocalPlayDarkColors,
        typography = LocalPlayTypography,
        content = content
    )
}
