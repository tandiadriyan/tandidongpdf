package com.tandidong.pdf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFFC62828),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF455A64),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9EC),
    onSecondaryContainer = Color(0xFF1B2327),
    tertiary = Color(0xFF00796B),
    background = Color(0xFFFCFCFC),
    surface = Color(0xFFFCFCFC),
    surfaceVariant = Color(0xFFF1EDEC),
    outline = Color(0xFF857371),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color(0xFF1B2327),
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color(0xFFE3E9EC),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF151313),
    surface = Color(0xFF151313),
    surfaceVariant = Color(0xFF2B2726),
)

@Composable
fun TandidongTheme(themeMode: Int, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
