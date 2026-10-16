package com.necroware.terminusplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp

/** Shared field/direction picker used by the library and playlist collections. */
@Composable
fun LibrarySortControls(
    selectedField: String,
    fields: List<String>,
    ascending: Boolean,
    title: String,
    onFieldSelected: (String) -> Unit,
    onDirectionSelected: (Boolean) -> Unit,
    showDirectionControl: Boolean = true,
    actionText: String = "[ CHANGE ]",
    actionColor: androidx.compose.ui.graphics.Color? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showDialog = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SORT: ${selectedField.replace('_', ' ')}${if (showDirectionControl) " ${if (ascending) "↑" else "↓"}" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { showDialog.value = true }.padding(vertical = 6.dp)
        )
        TerminalActionText(
            text = actionText,
            style = MaterialTheme.typography.labelSmall,
            color = actionColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable {
                if (onActionClick != null) {
                    onActionClick()
                } else {
                    showDialog.value = true
                }
            }.padding(vertical = 6.dp)
        )
    }

    if (showDialog.value) {
        Dialog(onDismissRequest = { showDialog.value = false }) {
            Box(Modifier.fillMaxWidth(0.9f).background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("FIELD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        fields.forEach { field ->
                            Text(
                                text = "${if (selectedField == field) ">" else " "} ${field.replace('_', ' ')}",
                                color = if (selectedField == field) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().clickable { onFieldSelected(field) }.padding(vertical = 6.dp)
                            )
                        }
                        if (showDirectionControl) {
                            Text("DIRECTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                                TerminalActionText(
                                    "[ ASCENDING ]",
                                    color = if (ascending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.clickable { onDirectionSelected(true) }.padding(vertical = 8.dp)
                                )
                                TerminalActionText(
                                    "[ DESCENDING ]",
                                    color = if (!ascending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.clickable { onDirectionSelected(false) }.padding(vertical = 8.dp)
                                )
                            }
                        }
                        TerminalActionText(
                            "[ DONE ]",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.End).clickable { showDialog.value = false }.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
