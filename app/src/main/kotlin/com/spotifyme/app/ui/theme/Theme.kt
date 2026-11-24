package com.spotifyme.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SpotifyGreen = Color(0xFF1DB954)

private val LightColors = lightColorScheme(
    primary = Color(0xFF168D40),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F5D6),
    onPrimaryContainer = Color(0xFF00210B),
)

private val DarkColors = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0E5A2A),
    onPrimaryContainer = Color(0xFFC8F5D6),
)

@Composable
fun SpotifyMeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
