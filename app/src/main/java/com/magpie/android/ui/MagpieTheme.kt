package com.magpie.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemePreference { FollowSystem, Light, Dark }

private val LightColors = lightColorScheme(
    primary = Color(0xFF294E9E),
    onPrimary = Color(0xFFFFF6E7),
    primaryContainer = Color(0xFFC8DCF3),
    onPrimaryContainer = Color(0xFF17264D),
    secondary = Color(0xFF16747D),
    onSecondary = Color(0xFFFFF6E7),
    secondaryContainer = Color(0xFFB9DFDB),
    onSecondaryContainer = Color(0xFF123C45),
    tertiary = Color(0xFFA44F55),
    onTertiary = Color(0xFFFFF6E7),
    background = Color(0xFFE7EEF4),
    onBackground = Color(0xFF17233D),
    surface = Color(0xFFF7EFDF),
    onSurface = Color(0xFF17233D),
    surfaceVariant = Color(0xFFDCE6EE),
    onSurfaceVariant = Color(0xFF4B586E),
    outline = Color(0xFF72819A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82C7FF),
    onPrimary = Color(0xFF102B4D),
    primaryContainer = Color(0xFF2D4E7E),
    onPrimaryContainer = Color(0xFFE2F2FF),
    secondary = Color(0xFF62D0C5),
    onSecondary = Color(0xFF103C40),
    secondaryContainer = Color(0xFF24565C),
    onSecondaryContainer = Color(0xFFC4F3EA),
    tertiary = Color(0xFFF3A18E),
    onTertiary = Color(0xFF4C2026),
    background = Color(0xFF101624),
    onBackground = Color(0xFFF5ECDD),
    surface = Color(0xFF182238),
    onSurface = Color(0xFFF5ECDD),
    surfaceVariant = Color(0xFF24324B),
    onSurfaceVariant = Color(0xFFC6D2E4),
    outline = Color(0xFF7F92AF),
)

@Composable
fun MagpieTheme(
    preference: ThemePreference = ThemePreference.FollowSystem,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (preference) {
        ThemePreference.FollowSystem -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
