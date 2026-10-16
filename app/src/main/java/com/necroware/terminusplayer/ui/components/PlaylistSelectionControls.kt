package com.necroware.terminusplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared playlist selection controls; the selection count only appears after selection starts. */
@Composable
fun PlaylistSelectionControls(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalCount == 0 || selectedCount <= 0) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "SELECTED $selectedCount / $totalCount",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (selectedCount < totalCount) {
                TerminalActionText(
                    "[ SELECT ALL ]",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable(onClick = onSelectAll).padding(vertical = 8.dp)
                )
            }
            TerminalActionText(
                "[ CLEAR ]",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable(onClick = onClearSelection).padding(vertical = 8.dp)
            )
        }
    }
}

/** Shared bottom bar shown only while at least one playlist track is selected. */
@Composable
fun PlaylistSelectionActionBar(
    selectedCount: Int,
    onActions: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedCount <= 0) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$selectedCount SELECTED", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        TerminalActionText("[ ACTIONS ]", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onActions).padding(8.dp))
        TerminalActionText("[ DONE ]", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onDone).padding(8.dp))
    }
}
