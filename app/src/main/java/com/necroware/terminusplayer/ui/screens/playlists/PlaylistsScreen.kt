package com.necroware.terminusplayer.ui.screens.playlists

import com.necroware.terminusplayer.ui.components.TerminalActionText
import com.necroware.terminusplayer.ui.components.TerminalToggleButton

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalHeader
import com.necroware.terminusplayer.ui.components.TerminalState
import com.necroware.terminusplayer.ui.components.FastScrollRail
import com.necroware.terminusplayer.ui.components.LibrarySortControls
import com.necroware.terminusplayer.ui.components.fastScrollTargets
import com.necroware.terminusplayer.ui.components.stableSortedList
import com.necroware.terminusplayer.ui.components.shouldShowFastScroll

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (PlaylistKind) -> Unit,
    onCustomPlaylistClick: (String) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val customPlaylists by viewModel.customPlaylists.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val publishingPlaylistIds by viewModel.publishingPlaylistIds.collectAsStateWithLifecycle()
    val downloadDialogState by viewModel.downloadDialogState.collectAsStateWithLifecycle()
    val account by viewModel.accountInfo.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showOtherUsers by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var renamePlaylistName by remember { mutableStateOf("") }
    var playlistSortField by rememberSaveable { mutableStateOf("NAME") }
    var playlistSortAscending by rememberSaveable { mutableStateOf(true) }
    var playlistToDelete by remember { androidx.compose.runtime.mutableStateOf<com.necroware.terminusplayer.data.model.Playlist?>(null) }
    var playlistToDownload by remember { androidx.compose.runtime.mutableStateOf<com.necroware.terminusplayer.data.model.Playlist?>(null) }

    val isOtherUserPlaylist: (Playlist) -> Boolean = { playlist ->
        playlist.isRemote && account.username.isNotBlank() && !playlist.ownerUsername.isNullOrBlank() &&
            !playlist.ownerUsername.equals(account.username, ignoreCase = true)
    }
    val visiblePlaylists = if (account.isAdmin && showOtherUsers) customPlaylists
        else customPlaylists.filterNot(isOtherUserPlaylist)
    val sortedPlaylists = remember(visiblePlaylists, playlistSortField, playlistSortAscending) {
        stableSortedList(
            visiblePlaylists,
            descending = !playlistSortAscending,
            comparator = when (playlistSortField) {
                "TRACKS" -> compareBy(Playlist::songCount)
                else -> compareBy(String.CASE_INSENSITIVE_ORDER, Playlist::name)
            }
        )
    }
    val quickMixPairs = remember { PlaylistKind.entries.chunked(2) }
    val listState = rememberLazyListState()
    val leadingPlaylistItems = 3 + (if (actionMessage != null) 1 else 0) + quickMixPairs.size
    val firstVisiblePlaylistIndex = listState.layoutInfo.visibleItemsInfo
        .firstNotNullOfOrNull { info -> sortedPlaylists.indexOfFirst { it.id == info.key }.takeIf { it >= 0 } }
        ?: 0

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
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
                    TerminalHeader(title = "PLAYLISTS")
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
        items(
            items = quickMixPairs,
            key = { pair -> pair.joinToString("-") { it.name } },
            contentType = { "quick_mix_row" }
        ) { pair ->
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
                    Text("${sortedPlaylists.size} playlists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (account.isAdmin) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("OTHER USERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TerminalToggleButton(checked = showOtherUsers, onToggle = { showOtherUsers = !showOtherUsers })
                    }
                }
            }
            if (sortedPlaylists.isNotEmpty()) {
                LibrarySortControls(
                    selectedField = playlistSortField,
                    fields = listOf("NAME", "TRACKS"),
                    ascending = playlistSortAscending,
                    title = "SORT PLAYLISTS",
                    onFieldSelected = { playlistSortField = it },
                    onDirectionSelected = { playlistSortAscending = it }
                )
            }
        }

        if (sortedPlaylists.isEmpty()) {
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
            items(sortedPlaylists, key = { it.id }, contentType = { "playlist_item" }) { playlist ->
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
                                    append(if (playlist.isRemote) "  ·  Navidrome" else "  ·  On device")
                                    if (belongsToOtherUser) append("  ·  by ${playlist.ownerUsername}")
                                    if (playlist.serverPlaylistMissing) append("  ·  SERVER DELETED · KEEP LOCAL")
                                    else if (playlist.hasSyncConflict) append("  ·  SYNC CONFLICT · RESOLVE")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (playlist.hasSyncConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Playlist options", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("Download Options") }, onClick = {
                                    playlistToDownload = playlist
                                    viewModel.inspectPlaylistDownloads(playlist.id, playlist.songCount, playlist.isRemote)
                                    expanded = false
                                })
                                if (!belongsToOtherUser) {
                                    if (!playlist.isRemote) {
                                        DropdownMenuItem(
                                            text = { Text(if (playlist.id in publishingPlaylistIds) "Publishing…" else "Publish to Navidrome") },
                                            enabled = playlist.id !in publishingPlaylistIds,
                                            onClick = {
                                            viewModel.publishPlaylistToServer(playlist.id)
                                            expanded = false
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                        playlistToRename = playlist
                                        renamePlaylistName = playlist.name
                                        expanded = false
                                    })
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
    if (shouldShowFastScroll(sortedPlaylists.size)) {
        FastScrollRail(
            modifier = Modifier.align(Alignment.CenterEnd),
            listState = listState,
            itemCount = sortedPlaylists.size,
            scrollTargets = remember(sortedPlaylists, leadingPlaylistItems) {
                fastScrollTargets(sortedPlaylists.size, leadingPlaylistItems)
            },
            initialIndex = firstVisiblePlaylistIndex
        )
    }
    }

    if (showCreateDialog) {
        Dialog(onDismissRequest = { showCreateDialog = false }) {
            Box(Modifier.widthIn(max = 380.dp).background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("NEW PLAYLIST", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            TerminalActionText(
                                "[ CREATE LOCAL ]",
                                color = if (newPlaylistName.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = newPlaylistName.isNotBlank()) {
                                    viewModel.createPlaylist(newPlaylistName)
                                    newPlaylistName = ""
                                    showCreateDialog = false
                                }.padding(vertical = 10.dp)
                            )
                            TerminalActionText(
                                "[ CREATE ON NAVIDROME ]",
                                color = if (newPlaylistName.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = newPlaylistName.isNotBlank()) {
                                    viewModel.createServerPlaylist(newPlaylistName)
                                    newPlaylistName = ""
                                    showCreateDialog = false
                                }.padding(vertical = 10.dp)
                            )
                            TerminalActionText(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().clickable { showCreateDialog = false }.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    playlistToRename?.let { playlist ->
        Dialog(onDismissRequest = { playlistToRename = null }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("RENAME PLAYLIST", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            value = renamePlaylistName,
                            onValueChange = { renamePlaylistName = it },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TerminalActionText("[ CANCEL ]", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { playlistToRename = null }.padding(8.dp))
                            TerminalActionText("[ SAVE ]", color = if (renamePlaylistName.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable(enabled = renamePlaylistName.isNotBlank()) {
                                    viewModel.renamePlaylist(playlist.id, renamePlaylistName)
                                    playlistToRename = null
                                }.padding(8.dp))
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
                        Text(if (playlist.isRemote) {
                            "${playlist.name} will be deleted from Navidrome when connected."
                        } else {
                            "${playlist.name} will be deleted from this device."
                        })
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TerminalActionText(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { playlistToDelete = null }.padding(8.dp)
                            )
                            TerminalActionText(
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
        val dialogState = downloadDialogState.takeIf { it.playlistId == playlist.id }
        Dialog(onDismissRequest = { playlistToDownload = null }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("PLAYLIST OFFLINE STATUS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        when {
                            dialogState == null || dialogState.isLoading -> {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Text("Checking local files and previous downloads…", style = MaterialTheme.typography.bodySmall)
                            }
                            dialogState.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(dialogState.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                TerminalActionText(
                                    "[ RETRY CHECK ]",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        viewModel.inspectPlaylistDownloads(playlist.id, playlist.songCount, playlist.isRemote)
                                    }.padding(vertical = 8.dp)
                                )
                            }
                            dialogState.plan != null -> {
                                val plan = dialogState.plan
                                if (plan.downloadableSongs.isNotEmpty()) {
                                    Text(
                                        buildString {
                                            append("${plan.downloadableSongs.size} missing tracks will be downloaded. ${plan.alreadyOfflineCount} are already offline.")
                                            if (plan.unresolvedTrackCount > 0) append(" ${plan.unresolvedTrackCount} playlist entries are not represented in the cache; refresh while online to confirm them.")
                                        },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TerminalActionText(
                                        "[ DOWNLOAD MISSING (${plan.downloadableSongs.size}) ]",
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            viewModel.downloadPlaylist(playlist.id, force = false)
                                            playlistToDownload = null
                                            viewModel.dismissDownloadDialog()
                                        }.padding(vertical = 8.dp)
                                    )
                                } else if (plan.unresolvedTrackCount > 0) {
                                    Text("${plan.unresolvedTrackCount} playlist entries are not represented in the cache. Refresh this playlist while online to confirm them.", style = MaterialTheme.typography.bodyMedium)
                                } else if (plan.unavailableCount > 0) {
                                    Text("No tracks can be downloaded right now. ${plan.unavailableCount} tracks have no readable local file or usable server source.", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    Text("All available tracks in this playlist are already offline.", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (plan.serverSongs.isNotEmpty()) {
                                    TerminalActionText(
                                        "[ FORCE RE-DOWNLOAD (${plan.serverSongs.size}) ]",
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            viewModel.downloadPlaylist(playlist.id, force = true)
                                            playlistToDownload = null
                                            viewModel.dismissDownloadDialog()
                                        }.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TerminalActionText(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable {
                                    playlistToDownload = null
                                    viewModel.dismissDownloadDialog()
                                }.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
