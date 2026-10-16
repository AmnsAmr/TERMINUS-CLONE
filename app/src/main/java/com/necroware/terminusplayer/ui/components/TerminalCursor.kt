package com.necroware.terminusplayer.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.necroware.terminusplayer.data.prefs.MotionPreference
import com.necroware.terminusplayer.data.prefs.TerminalCursorBlink
import com.necroware.terminusplayer.data.prefs.TerminalCursorStyle
import kotlinx.coroutines.delay

data class TerminalCursorConfig(
    val style: TerminalCursorStyle = TerminalCursorStyle.UNDERSCORE,
    val blinkCadence: TerminalCursorBlink = TerminalCursorBlink.SLOW,
    val motionPreference: MotionPreference = MotionPreference.FULL
)

val LocalTerminalCursorConfig = staticCompositionLocalOf { TerminalCursorConfig() }

/**
 * Computes a blinking or solid terminal cursor glyph respecting motion policy.
 * When motion is OFF or cadence is SOLID, no clock/delay loop runs.
 */
@Composable
fun rememberTerminalCursor(
    style: TerminalCursorStyle = LocalTerminalCursorConfig.current.style,
    cadence: TerminalCursorBlink = LocalTerminalCursorConfig.current.blinkCadence,
    motionPreference: MotionPreference = LocalTerminalCursorConfig.current.motionPreference
): String {
    val interval = cadence.intervalMs
    if (interval == null || motionPreference == MotionPreference.OFF) {
        return style.glyph
    }

    val visible by produceState(initialValue = true, key1 = cadence, key2 = motionPreference) {
        while (true) {
            delay(interval)
            value = !value
        }
    }

    return if (visible) style.glyph else " "
}

/**
 * Standardized terminal screen header with dynamic cursor.
 * Example: "> SETTINGS_" or "> SETTINGS█"
 */
@Composable
fun TerminalHeader(
    title: String,
    modifier: Modifier = Modifier,
    cursorStyle: TerminalCursorStyle = LocalTerminalCursorConfig.current.style,
    cursorBlink: TerminalCursorBlink = LocalTerminalCursorConfig.current.blinkCadence,
    motionPreference: MotionPreference = LocalTerminalCursorConfig.current.motionPreference,
    color: Color = MaterialTheme.colorScheme.onBackground,
    textStyle: TextStyle = MaterialTheme.typography.headlineMedium
) {
    val cursor = rememberTerminalCursor(cursorStyle, cursorBlink, motionPreference)
    Text(
        text = "> ${title.removePrefix("> ").trimEnd('_', ' ')}$cursor",
        style = textStyle,
        color = color,
        modifier = modifier
    )
}

