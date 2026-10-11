package com.necroware.terminusplayer.ui.screens.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.ui.screens.library.LibrarySongRow
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalState

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var playlistSong by remember { mutableStateOf<Song?>(null) }
    var showDownloadPrompt by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "︿",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 20.dp).clickable { onBack() }
            )
        }

        item {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    text = "[ ${state.title} ]",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "[ ${state.songs.size} tracks ]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (state.isLoading) {
            item { TerminalState("LOADING PLAYLIST", "Preparing your tracks…") }
        }

        actionMessage?.let { message ->
            item {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { viewModel.dismissActionMessage() }.padding(vertical = 4.dp))
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "▶ PLAY ALL",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { viewModel.playAll() }
                )
                Text(
                    text = "↓ DOWNLOAD ALL",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.clickable { showDownloadPrompt = true }
                )
            }
        }

        if (!state.isLoading && state.songs.isEmpty()) {
            item {
                TerminalState("PLAYLIST IS EMPTY", state.errorMessage ?: state.emptyMessage)
            }
        }

        items(state.songs, key = { it.id }) { song ->
            LibrarySongRow(
                song = song,
                isSelected = false,
                onClick = { viewModel.playFrom(song) },
                onLongClick = { actionSong = song },
                onLike = { viewModel.toggleLike(song) },
                onAddToPlaylist = { playlistSong = song },
                onPlayNext = { viewModel.playNext(song) }
            )
        }
    }

    playlistSong?.let { song ->
        Dialog(onDismissRequest = { playlistSong = null }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ADD TO PLAYLIST", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        if (playlists.isEmpty()) {
                            Text("Create a playlist first to save this track.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(240.dp)) {
                                items(playlists, key = { it.id }) { playlist ->
                                    Text(
                                        "[ ${playlist.name} ]",
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            viewModel.addSongToPlaylist(playlist.id, song.id)
                                            playlistSong = null
                                        }.padding(vertical = 10.dp)
                                    )
                                }
                            }
                        }
                        Text("[ CANCEL ]", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).clickable { playlistSong = null }.padding(8.dp))
                    }
                }
            }
        }
    }

    actionSong?.let { song ->
        Dialog(onDismissRequest = { actionSong = null }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(song.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (song.providerId == "local") {
                                Text(
                                    "[ UPLOAD TO SERVER ]",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.clickable { viewModel.uploadSong(song); actionSong = null }
                                )
                            } else if (song.downloadedUri == null) {
                                Text(
                                    "[ DOWNLOAD ]",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.clickable { viewModel.downloadSong(song); actionSong = null }
                                )
                            } else {
                                Text("[ DOWNLOADED ]", color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDownloadPrompt) {
        Dialog(onDismissRequest = { showDownloadPrompt = false }) {
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
                                    viewModel.downloadAll(force = false)
                                    showDownloadPrompt = false
                                }.padding(vertical = 8.dp)
                            )
                            Text(
                                "[ FORCE ALL ]",
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.downloadAll(force = true)
                                    showDownloadPrompt = false
                                }.padding(vertical = 8.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { showDownloadPrompt = false }.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
