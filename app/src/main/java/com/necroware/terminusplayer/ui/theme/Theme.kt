package com.necroware.terminusplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.necroware.terminusplayer.data.prefs.ThemePresetId

data class AppPalette(
    val background: Color, val surface: Color, val surfaceElevated: Color,
    val accent: Color, val secondaryAccent: Color, val textPrimary: Color,
    val textSecondary: Color, val textMuted: Color, val border: Color,
    val homeBackground: Color, val playerBackground: Color, val navigationBackground: Color,
    val playlistSurface: Color, val visualizerPrimary: Color, val visualizerSecondary: Color,
    val success: Color, val warning: Color, val error: Color
)

val LocalAppPalette = staticCompositionLocalOf {
    AppPalette(Color.Black, Color.DarkGray, Color.Gray, Color.Green, Color.Cyan,
        Color.White, Color.LightGray, Color.Gray, Color.Gray, Color.Black,
        Color.Black, Color.DarkGray, Color.DarkGray, Color.Green, Color.Cyan,
        Color.Green, Color.Yellow, RedAccent)
}

/**
 * Deliberately NOT using dynamicColorScheme / Material You here — TERMINUS
 * always renders one of its own terminal palettes regardless of system
 * wallpaper. Every screen reads colors off MaterialTheme.colorScheme rather
 * than the theme/Color.kt constants directly, so swapping the whole
 * [ThemePreset] here is enough to re-skin the entire app.
 */
private fun terminalColorScheme(preset: ThemePreset) = darkColorScheme(
    primary = preset.accent,
    onPrimary = preset.onAccent,
    secondary = preset.secondaryAccent,
    onSecondary = preset.onAccent,
    tertiary = preset.secondaryAccent,
    onTertiary = preset.onAccent,
    background = preset.background,
    onBackground = preset.textPrimary,
    surface = preset.surface,
    onSurface = preset.textPrimary,
    surfaceVariant = preset.surfaceElevated,
    onSurfaceVariant = preset.textSecondary,
    outline = preset.border,
    error = RedAccent,
    onError = preset.onAccent
)

@Composable
fun TerminusTheme(
    preset: ThemePreset = themePresetById(ThemePresetId.TERMINAL),
    content: @Composable () -> Unit
) {
    val palette = AppPalette(
        preset.background, preset.surface, preset.surfaceElevated, preset.accent, preset.secondaryAccent,
        preset.textPrimary, preset.textSecondary, preset.textMuted, preset.border, preset.homeBackground,
        preset.playerBackground, preset.navigationBackground, preset.playlistSurface, preset.visualizerPrimary,
        preset.visualizerSecondary, preset.secondaryAccent, preset.accent, RedAccent
    )
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(colorScheme = terminalColorScheme(preset), typography = TerminusTypography, content = content)
    }
}
