package com.necroware.terminusplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Terminal-styled hardware toggle button indicating [ ON ] / [ OFF ] state.
 *
 * When checked:
 * - Active terminal accent border (1dp primary)
 * - CRT phosphor glow background fill (primary with 0.18 alpha)
 * - High-contrast monospace label: [ ON ] in primary color
 *
 * When unchecked:
 * - Muted outline border (1dp outline with 0.6 alpha)
 * - Deep terminal background (transparent)
 * - Dimmed monospace label: [ OFF ] in onSurfaceVariant
 */
@Composable
fun TerminalToggleButton(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (() -> Unit)? = null
) {
    val clickModifier = if (onToggle != null) {
        modifier.clickable(onClick = onToggle)
    } else {
        modifier
    }

    Box(
        modifier = clickModifier
            .border(
                width = 1.dp,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
            .background(
                if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (checked) "[ ON ]" else "[ OFF ]",
            style = MaterialTheme.typography.labelSmall,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}
