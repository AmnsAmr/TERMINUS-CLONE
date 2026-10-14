package com.necroware.terminusplayer.ui.theme

import androidx.compose.ui.graphics.Color
import com.necroware.terminusplayer.data.prefs.ThemePresetId
import com.necroware.terminusplayer.data.prefs.ThemeColorRole

/**
 * A full palette, not just an accent swap — background/surface/border/text
 * all move together so each preset reads as a deliberate scheme rather than
 * one color dropped onto the default dark palette.
 */
data class ThemePreset(
    val id: ThemePresetId,
    val label: String,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val secondaryAccent: Color = accent,
    val homeBackground: Color = background,
    val playerBackground: Color = background,
    val navigationBackground: Color = surface,
    val playlistSurface: Color = surface,
    val visualizerPrimary: Color = accent,
    val visualizerSecondary: Color = secondaryAccent
)

fun ThemePreset.color(role: ThemeColorRole): Color = when (role) {
    ThemeColorRole.BACKGROUND -> background
    ThemeColorRole.SURFACE -> surface
    ThemeColorRole.SURFACE_ELEVATED -> surfaceElevated
    ThemeColorRole.ACCENT -> accent
    ThemeColorRole.SECONDARY_ACCENT -> secondaryAccent
    ThemeColorRole.TEXT_PRIMARY -> textPrimary
    ThemeColorRole.TEXT_SECONDARY -> textSecondary
    ThemeColorRole.TEXT_MUTED -> textMuted
    ThemeColorRole.BORDER -> border
    ThemeColorRole.HOME_BACKGROUND -> homeBackground
    ThemeColorRole.PLAYER_BACKGROUND -> playerBackground
    ThemeColorRole.NAVIGATION_BACKGROUND -> navigationBackground
    ThemeColorRole.PLAYLIST_SURFACE -> playlistSurface
    ThemeColorRole.VISUALIZER_PRIMARY -> visualizerPrimary
    ThemeColorRole.VISUALIZER_SECONDARY -> visualizerSecondary
}

fun ThemePreset.withOverrides(overrides: Map<ThemeColorRole, String>): ThemePreset {
    fun overridden(role: ThemeColorRole, current: Color) = overrides[role]?.let(::parseThemeColor) ?: current
    return copy(
        background = overridden(ThemeColorRole.BACKGROUND, background),
        surface = overridden(ThemeColorRole.SURFACE, surface),
        surfaceElevated = overridden(ThemeColorRole.SURFACE_ELEVATED, surfaceElevated),
        accent = overridden(ThemeColorRole.ACCENT, accent),
        secondaryAccent = overridden(ThemeColorRole.SECONDARY_ACCENT, secondaryAccent),
        textPrimary = overridden(ThemeColorRole.TEXT_PRIMARY, textPrimary),
        textSecondary = overridden(ThemeColorRole.TEXT_SECONDARY, textSecondary),
        textMuted = overridden(ThemeColorRole.TEXT_MUTED, textMuted),
        border = overridden(ThemeColorRole.BORDER, border),
        homeBackground = overridden(ThemeColorRole.HOME_BACKGROUND, homeBackground),
        playerBackground = overridden(ThemeColorRole.PLAYER_BACKGROUND, playerBackground),
        navigationBackground = overridden(ThemeColorRole.NAVIGATION_BACKGROUND, navigationBackground),
        playlistSurface = overridden(ThemeColorRole.PLAYLIST_SURFACE, playlistSurface),
        visualizerPrimary = overridden(ThemeColorRole.VISUALIZER_PRIMARY, visualizerPrimary),
        visualizerSecondary = overridden(ThemeColorRole.VISUALIZER_SECONDARY, visualizerSecondary)
    )
}

fun parseThemeColor(value: String): Color? = runCatching {
    val hex = value.removePrefix("#")
    when (hex.length) {
        6 -> Color(0xFF000000L or hex.toLong(16))
        8 -> Color(hex.toLong(16))
        else -> null
    }
}.getOrNull()

val ThemePresets: List<ThemePreset> = listOf(
    ThemePreset(
        id = ThemePresetId.TERMINAL_GREEN, label = "TERMINAL GREEN",
        background = Color(0xFF050B06), surface = Color(0xFF0B140D), surfaceElevated = Color(0xFF122018),
        border = Color(0xFF23472B), textPrimary = Color(0xFFE4F8E7), textSecondary = Color(0xFF8BD894),
        textMuted = Color(0xFF56845D), accent = Color(0xFF39FF14), onAccent = Color(0xFF061006),
        secondaryAccent = Color(0xFF9AFF87)
    ),
    ThemePreset(
        id = ThemePresetId.AMBER_CRT,
        label = "AMBER CRT",
        background = Color(0xFF100B03), surface = Color(0xFF1C1307), surfaceElevated = Color(0xFF2A1D0A),
        border = Color(0xFF51370C), textPrimary = Color(0xFFFFF0C2), textSecondary = Color(0xFFE9B949),
        textMuted = Color(0xFF9C7120), accent = Color(0xFFFFB000), onAccent = Color(0xFF1A1000),
        secondaryAccent = Color(0xFFFFD166)
    ),
    ThemePreset(
        id = ThemePresetId.CYBERPUNK, label = "CYBERPUNK",
        background = Color(0xFF090713), surface = Color(0xFF151024), surfaceElevated = Color(0xFF211936),
        border = Color(0xFF49315B), textPrimary = Color(0xFFF6EEFF), textSecondary = Color(0xFFB8A6D9),
        textMuted = Color(0xFF806C9C), accent = Color(0xFFFF4FD8), onAccent = Color(0xFF21051B),
        secondaryAccent = Color(0xFF42E8F5)
    ),
    ThemePreset(
        id = ThemePresetId.DRACULA, label = "DRACULA",
        background = Color(0xFF282A36), surface = Color(0xFF343746), surfaceElevated = Color(0xFF44475A),
        border = Color(0xFF6272A4), textPrimary = Color(0xFFF8F8F2), textSecondary = Color(0xFFBD93F9),
        textMuted = Color(0xFF9999A8), accent = Color(0xFFFF79C6), onAccent = Color(0xFF282A36),
        secondaryAccent = Color(0xFF8BE9FD)
    ),
    ThemePreset(
        id = ThemePresetId.OCEAN_BLUE, label = "OCEAN BLUE",
        background = Color(0xFF071521), surface = Color(0xFF0D2233), surfaceElevated = Color(0xFF153247),
        border = Color(0xFF28516A), textPrimary = Color(0xFFE6F6FF), textSecondary = Color(0xFF9CCDE5),
        textMuted = Color(0xFF638AA0), accent = Color(0xFF55B8FF), onAccent = Color(0xFF031522),
        secondaryAccent = Color(0xFF55E6D2)
    ),
    ThemePreset(
        id = ThemePresetId.MONOCHROME, label = "MONOCHROME",
        background = Color(0xFF101010), surface = Color(0xFF1C1C1C), surfaceElevated = Color(0xFF292929),
        border = Color(0xFF454545), textPrimary = Color(0xFFF2F2F2), textSecondary = Color(0xFFBDBDBD),
        textMuted = Color(0xFF888888), accent = Color(0xFFE0E0E0), onAccent = Color(0xFF101010),
        secondaryAccent = Color(0xFFAAAAAA)
    ),
    ThemePreset(
        id = ThemePresetId.SOLARIZED_DARK, label = "SOLARIZED DARK",
        background = Color(0xFF002B36), surface = Color(0xFF073642), surfaceElevated = Color(0xFF0B4654),
        border = Color(0xFF315B63), textPrimary = Color(0xFFE8E1C5), textSecondary = Color(0xFF93A1A1),
        textMuted = Color(0xFF657B83), accent = Color(0xFFB58900), onAccent = Color(0xFF002B36),
        secondaryAccent = Color(0xFF2AA198)
    ),
    ThemePreset(
        id = ThemePresetId.TERMINAL,
        label = "TERMINAL",
        background = Color(0xFF1A1A1A), // Dark Grey
        surface = Color(0xFF242424),
        surfaceElevated = Color(0xFF2F2F2F),
        border = Color(0xFF3A3A3A),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFCCCCCC),
        textMuted = Color(0xFF888888),
        accent = Color(0xFFFF9800), // Orange
        onAccent = Color(0xFF000000)
    ),
    ThemePreset(
        id = ThemePresetId.VECTOR,
        label = "VECTOR",
        background = Color(0xFF000000),
        surface = Color(0xFF0A0A0A),
        surfaceElevated = Color(0xFF141414),
        border = Color(0xFF333333),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFAAAAAA),
        textMuted = Color(0xFF555555),
        accent = Color(0xFFFFFFFF),
        onAccent = Color(0xFF000000)
    ),
    ThemePreset(
        id = ThemePresetId.REBECCA,
        label = "REBECCA",
        background = Color(0xFF000000),
        surface = Color(0xFF081010),
        surfaceElevated = Color(0xFF101818),
        border = Color(0xFF1A2F2F),
        textPrimary = Color(0xFFE0FFFF),
        textSecondary = Color(0xFF22D3EE),
        textMuted = Color(0xFF164E63),
        accent = Color(0xFFFDE047), // Yellow
        onAccent = Color(0xFF000000)
    ),
    ThemePreset(
        id = ThemePresetId.DUNE,
        label = "DUNE",
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF5F5F4),
        surfaceElevated = Color(0xFFE7E5E4),
        border = Color(0xFFD6D3D1),
        textPrimary = Color(0xFF1C1917),
        textSecondary = Color(0xFF44403C),
        textMuted = Color(0xFF78716C),
        accent = Color(0xFFEAB308), // Darker Yellow
        onAccent = Color(0xFF000000)
    ),
    ThemePreset(
        id = ThemePresetId.HEX,
        label = "HEX",
        background = Color(0xFF000000),
        surface = Color(0xFF051008),
        surfaceElevated = Color(0xFF0A1A0F),
        border = Color(0xFF14301A),
        textPrimary = Color(0xFFDCFCE7),
        textSecondary = Color(0xFF4ADE80),
        textMuted = Color(0xFF166534),
        accent = Color(0xFF22C55E), // Green
        onAccent = Color(0xFF000000)
    ),
    ThemePreset(
        id = ThemePresetId.LUCY,
        label = "LUCY",
        background = Color(0xFF0F0716),
        surface = Color(0xFF1A0D25),
        surfaceElevated = Color(0xFF261435),
        border = Color(0xFF3B1F50),
        textPrimary = Color(0xFFF5F3FF),
        textSecondary = Color(0xFFA78BFA),
        textMuted = Color(0xFF5B21B6),
        accent = Color(0xFF22D3EE), // Cyan
        onAccent = Color(0xFF000000)
    ),
    ThemePreset(
        id = ThemePresetId.MAINE,
        label = "MAINE",
        background = Color(0xFF100505),
        surface = Color(0xFF1A0A0A),
        surfaceElevated = Color(0xFF261010),
        border = Color(0xFF451010),
        textPrimary = Color(0xFFFEE2E2),
        textSecondary = Color(0xFFEF4444),
        textMuted = Color(0xFF7F1D1D),
        accent = Color(0xFF22D3EE), // Cyan
        onAccent = Color(0xFF000000)
    ),
    ThemePreset(
        id = ThemePresetId.FLATLINE,
        label = "FLATLINE",
        background = Color(0xFF000000),
        surface = Color(0xFF1A0505),
        surfaceElevated = Color(0xFF2D0A0A),
        border = Color(0xFF450A0A),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFF87171),
        textMuted = Color(0xFF7F1D1D),
        accent = Color(0xFFDC2626), // Red
        onAccent = Color(0xFF000000)
    ),
    ThemePreset(
        id = ThemePresetId.WIZ,
        label = "WIZ",
        background = Color(0xFF000000),
        surface = Color(0xFF0F0F05),
        surfaceElevated = Color(0xFF1A1A0A),
        border = Color(0xFF33330A),
        textPrimary = Color(0xFFFDFCEA),
        textSecondary = Color(0xFFEAB308),
        textMuted = Color(0xFF713F12),
        accent = Color(0xFFFACC15), // Yellow
        onAccent = Color(0xFF000000)
    )
)

fun themePresetById(id: ThemePresetId): ThemePreset =
    ThemePresets.firstOrNull { it.id == id } ?: ThemePresets.first { it.id == ThemePresetId.TERMINAL }
