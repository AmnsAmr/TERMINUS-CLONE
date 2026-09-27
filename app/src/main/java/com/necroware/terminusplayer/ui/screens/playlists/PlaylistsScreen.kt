package com.necroware.terminusplayer.ui.screens.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.ui.components.TerminalBorder

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (PlaylistKind) -> Unit,
    onCustomPlaylistClick: (String) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val customPlaylists by viewModel.customPlaylists.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToDelete by remember { androidx.compose.runtime.mutableStateOf<com.necroware.terminusplayer.data.model.Playlist?>(null) }
    var playlistToDownload by remember { androidx.compose.runtime.mutableStateOf<com.necroware.terminusplayer.data.model.Playlist?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "> PLAYLISTS_",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "[+ NEW]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showCreateDialog = true }
            )
        }

        actionMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { viewModel.dismissActionMessage() }
            )
        }

        PlaylistKind.entries.forEach { kind ->
            TerminalBorder(
                modifier = Modifier.fillMaxWidth().clickable { onPlaylistClick(kind) }
            ) {
                Text(
                    text = "[ ${kind.title} ]",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (customPlaylists.isNotEmpty()) {
            Text(
                text = "MY PLAYLISTS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            customPlaylists.forEach { playlist ->
                TerminalBorder(
                    modifier = Modifier.fillMaxWidth().clickable { onCustomPlaylistClick(playlist.id) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "[ ${playlist.name} ]",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${playlist.songCount} tracks",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { expanded = true }) {
                                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Options", tint = MaterialTheme.colorScheme.onSurface)
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Download All") },
                                        onClick = {
                                            playlistToDownload = playlist
                                            expanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            playlistToDelete = playlist
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Text(
                text = "[ import an m3u playlist from Settings > Library to see it here ]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    if (showCreateDialog) {
        Dialog(onDismissRequest = { showCreateDialog = false }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("NEW PLAYLIST", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { showCreateDialog = false }.padding(8.dp)
                            )
                            Text(
                                "[ CREATE ]",
                                color = if (newPlaylistName.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable(enabled = newPlaylistName.isNotBlank()) {
                                    viewModel.createPlaylist(newPlaylistName)
                                    newPlaylistName = ""
                                    showCreateDialog = false
                                }.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    playlistToDelete?.let { playlist ->
        Dialog(onDismissRequest = { playlistToDelete = null }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("DELETE PLAYLIST?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("${playlist.name} will be removed from this device and the server when connected.")
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { playlistToDelete = null }.padding(8.dp)
                            )
                            Text(
                                "[ CONFIRM DELETE ]",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable {
                                    viewModel.deletePlaylist(playlist.id)
                                    playlistToDelete = null
                                }.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    playlistToDownload?.let { playlist ->
        Dialog(onDismissRequest = { playlistToDownload = null }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("DOWNLOAD PLAYLIST?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Download missing tracks or force re-download all tracks?", style = MaterialTheme.typography.bodyMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "[ MISSING ONLY ]",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.downloadPlaylist(playlist.id, force = false)
                                    playlistToDownload = null
                                }.padding(vertical = 8.dp)
                            )
                            Text(
                                "[ FORCE ALL ]",
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.downloadPlaylist(playlist.id, force = true)
                                    playlistToDownload = null
                                }.padding(vertical = 8.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { playlistToDownload = null }.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
