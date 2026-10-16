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

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment

data class TerminalCursorConfig(
    val style: TerminalCursorStyle = TerminalCursorStyle.UNDERSCORE,
    val blinkCadence: TerminalCursorBlink = TerminalCursorBlink.SLOW,
    val motionPreference: MotionPreference = MotionPreference.FULL
)

val LocalTerminalCursorConfig = staticCompositionLocalOf { TerminalCursorConfig() }

/**
 * Computes a blinking or solid terminal cursor glyph respecting motion policy.
 * When motion is OFF, MEDIUM (Balanced), or cadence is SOLID, no clock/delay loop runs.
 */
@Composable
fun rememberTerminalCursor(
    style: TerminalCursorStyle = LocalTerminalCursorConfig.current.style,
    cadence: TerminalCursorBlink = LocalTerminalCursorConfig.current.blinkCadence,
    motionPreference: MotionPreference = LocalTerminalCursorConfig.current.motionPreference
): String {
    val interval = cadence.intervalMs
    if (interval == null || motionPreference == MotionPreference.OFF || motionPreference == MotionPreference.MEDIUM) {
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
 * Isolated leaf composable that renders only the blinking cursor.
 * Recomposition is localized strictly to this single character node.
 */
@Composable
fun TerminalCursorGlyph(
    style: TerminalCursorStyle = LocalTerminalCursorConfig.current.style,
    cadence: TerminalCursorBlink = LocalTerminalCursorConfig.current.blinkCadence,
    motionPreference: MotionPreference = LocalTerminalCursorConfig.current.motionPreference,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val glyph = rememberTerminalCursor(style, cadence, motionPreference)
    Text(
        text = glyph,
        style = textStyle,
        color = color,
        modifier = modifier
    )
}

/**
 * Standardized terminal screen header with dynamic cursor.
 * Uses TerminalCursorGlyph to ensure blinking never recomposes the header or screen.
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
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "> ${title.removePrefix("> ").trimEnd('_', ' ')}",
            style = textStyle,
            color = color
        )
        TerminalCursorGlyph(
            style = cursorStyle,
            cadence = cursorBlink,
            motionPreference = motionPreference,
            color = color,
            textStyle = textStyle
        )
    }
}


