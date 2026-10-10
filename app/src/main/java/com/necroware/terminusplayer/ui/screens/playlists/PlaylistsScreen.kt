package com.necroware.terminusplayer.ui.screens.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalState

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (PlaylistKind) -> Unit,
    onCustomPlaylistClick: (String) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val customPlaylists by viewModel.customPlaylists.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val account by viewModel.accountInfo.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showOtherUsers by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToDelete by remember { androidx.compose.runtime.mutableStateOf<com.necroware.terminusplayer.data.model.Playlist?>(null) }
    var playlistToDownload by remember { androidx.compose.runtime.mutableStateOf<com.necroware.terminusplayer.data.model.Playlist?>(null) }

    val isOtherUserPlaylist: (Playlist) -> Boolean = { playlist ->
        playlist.isRemote && account.username.isNotBlank() && !playlist.ownerUsername.isNullOrBlank() &&
            !playlist.ownerUsername.equals(account.username, ignoreCase = true)
    }
    val visiblePlaylists = if (account.isAdmin && showOtherUsers) customPlaylists
        else customPlaylists.filterNot(isOtherUserPlaylist)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("> PLAYLISTS_", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text("YOUR MUSIC, READY TO PLAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = "+ NEW",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showCreateDialog = true }.padding(12.dp)
                )
            }
        }

        actionMessage?.let { message ->
            item {
                TerminalBorder(Modifier.fillMaxWidth().clickable { viewModel.dismissActionMessage() }) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item {
            Text("QUICK MIXES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(PlaylistKind.entries.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { kind ->
                    TerminalBorder(
                        modifier = Modifier.weight(1f).clickable { onPlaylistClick(kind) }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(">_", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(kind.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            Text("SMART PLAYLIST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(if (account.isAdmin && showOtherUsers) "ALL SERVER PLAYLISTS" else "YOUR PLAYLISTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${visiblePlaylists.size} playlists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (account.isAdmin) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("OTHER USERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Switch(checked = showOtherUsers, onCheckedChange = { showOtherUsers = it })
                    }
                }
            }
        }

        if (visiblePlaylists.isEmpty()) {
            item {
                TerminalState(
                    title = if (showOtherUsers) "NO PLAYLISTS FOUND" else "NO PLAYLISTS YET",
                    detail = when {
                        showOtherUsers -> "No playlists from other accounts are available."
                        customPlaylists.isEmpty() -> "Import an M3U playlist from Settings › Library or create one on the server."
                        else -> "Playlists you create will appear here."
                    }
                )
            }
        } else {
            items(visiblePlaylists, key = { it.id }) { playlist ->
                val belongsToOtherUser = isOtherUserPlaylist(playlist)
                var expanded by remember(playlist.id) { mutableStateOf(false) }
                TerminalBorder(
                    modifier = Modifier.fillMaxWidth().clickable { onCustomPlaylistClick(playlist.id) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            Text(
                                text = buildString {
                                    append("${playlist.songCount} tracks")
                                    if (belongsToOtherUser) append("  ·  by ${playlist.ownerUsername}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Playlist options", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("Download All") }, onClick = {
                                    playlistToDownload = playlist
                                    expanded = false
                                })
                                if (!belongsToOtherUser) {
                                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = {
                                        playlistToDelete = playlist
                                        expanded = false
                                    })
                                }
                            }
                        }
                    }
                }
            }
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
