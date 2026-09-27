package com.necroware.terminusplayer.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.ui.components.SongArt
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.util.toMinutesSeconds

@Composable
fun LibraryScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableStateOf(LibraryTab.SONGS) }
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val localSongs by viewModel.localSongs.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var playlistSong by remember { mutableStateOf<Song?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "> LIBRARY_",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "[SEARCH]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onSearchClick() }
            )
        }

        actionMessage?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { viewModel.dismissActionMessage() }.padding(bottom = 8.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LibraryTab.entries.forEach { tab ->
                Text(
                    text = "[${tab.label}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tab == selectedTab) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.clickable { selectedTab = tab }
                )
            }
        }

        when (selectedTab) {
            LibraryTab.SONGS -> SongList(songs, onSongClick, onSongLongPress = { actionSong = it })
            LibraryTab.LOCAL_MUSIC -> {
                if (localSongs.isEmpty()) {
                    Text(
                        "[ no local music in included folders ]",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else SongList(localSongs, onSongClick, onSongLongPress = { actionSong = it })
            }
            LibraryTab.ALBUMS -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(albums, key = { it.title }) { album ->
                    TerminalBorder(
                        modifier = Modifier.fillMaxWidth().clickable { onAlbumClick(album.title) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongArt(uriString = album.representativeUriString, size = 48.dp)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(album.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text("${album.artist} · ${album.songCount} tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            LibraryTab.ARTISTS -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(artists, key = { it.name }) { artist ->
                    TerminalBorder(
                        modifier = Modifier.fillMaxWidth().clickable { onArtistClick(artist.name) }
                    ) {
                        Column {
                            Text(artist.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("${artist.songCount} tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    actionSong?.let { song ->
        AlertDialog(
            onDismissRequest = { actionSong = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (song.providerId == "local") {
                        Button(onClick = { viewModel.uploadSong(song); actionSong = null }) { Text("UPLOAD TO SERVER") }
                    } else if (song.downloadedUri == null) {
                        Button(onClick = { viewModel.downloadSong(song); actionSong = null }) { Text("DOWNLOAD") }
                    } else {
                        Text("DOWNLOADED FOR OFFLINE PLAYBACK", color = MaterialTheme.colorScheme.tertiary)
                    }
                    Button(onClick = { playlistSong = song; actionSong = null }, enabled = playlists.isNotEmpty()) {
                        Text("ADD TO PLAYLIST")
                    }
                    if (playlists.isEmpty()) Text("Create a playlist first.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { Text("CLOSE", modifier = Modifier.clickable { actionSong = null }.padding(12.dp)) }
        )
    }

    playlistSong?.let { song ->
        AlertDialog(
            onDismissRequest = { playlistSong = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("ADD TO PLAYLIST") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    playlists.forEach { playlist ->
                        Text(
                            "[ ${playlist.name} ]",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.addSongToPlaylist(playlist.id, song.id)
                                playlistSong = null
                            }.padding(vertical = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { Text("CLOSE", modifier = Modifier.clickable { playlistSong = null }.padding(12.dp)) }
        )
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongLongPress: (Song) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(songs, key = { it.id }) { song ->
            SongRow(song = song, onClick = { onSongClick(song, songs) }, onLongClick = { onSongLongPress(song) })
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SongRow(song: Song, onClick: () -> Unit, onLongClick: () -> Unit) {
    val local = song.providerId == "local"
    val accent = if (local) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (local) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        TerminalBorder(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongArt(uriString = song.uriString, size = 48.dp)
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${if (local) "LOCAL" else "SERVER"} · ${song.artist} · ${song.duration.toMinutesSeconds()}${if (song.isLiked) "  [L]" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
