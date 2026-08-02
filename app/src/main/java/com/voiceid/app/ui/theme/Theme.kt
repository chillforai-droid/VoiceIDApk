package com.voiceid.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// VoiceID brand palette (indigo/violet accent, matching the web app's Tailwind theme intent)
private val VoiceIdPrimary = Color(0xFF4F46E5)
private val VoiceIdSecondary = Color(0xFF7C3AED)
private val VoiceIdTertiary = Color(0xFF10B981)

private val LightColors = lightColorScheme(
    primary = VoiceIdPrimary,
    secondary = VoiceIdSecondary,
    tertiary = VoiceIdTertiary,
    background = Color(0xFFF8FAFC),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF818CF8),
    secondary = Color(0xFFA78BFA),
    tertiary = Color(0xFF34D399),
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B)
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun VoiceIdTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Was `true`: on Android 12+ this let Material You override the brand palette with
    // whatever colors it derived from the user's wallpaper, so the app's accent color would
    // randomly shift device to device and never reliably match the indigo/violet VoiceID brand
    // used on the web app — the opposite of the intended "professional, consistent" look.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VoiceIdTypography,
        content = content
    )
}
