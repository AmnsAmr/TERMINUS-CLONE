package com.necroware.terminusplayer.ui.screens.home

import com.necroware.terminusplayer.ui.components.TerminalActionText

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.model.GenreMix
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.ui.components.SongArt
import com.necroware.terminusplayer.ui.components.DeviceStatusCard
import com.necroware.terminusplayer.ui.components.SystemInfoCard
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalHeader
import com.necroware.terminusplayer.ui.components.TerminalState

@Composable
fun HomeScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeviceStatus by rememberSaveable { mutableStateOf(false) }

    if (state.isSyncing && state.songCount == 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                TerminalActionText(
                    text = "[ scanning library... ]",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                TerminalHeader(title = "TERMINUS")
                Text(
                    text = "YOUR MUSIC, READY WHEN YOU ARE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.syncError != null) {
            item {
                TerminalActionText(
                    text = "[ sync warning: ${state.syncError} ]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        if (state.songCount > 0) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "> SMART MIXES · BY GENRE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    if (state.genreMixes.isEmpty()) {
                        Text(
                            text = if (state.isLoadingGenreMixes) {
                                "Finding genre playlists..."
                            } else {
                                "No genre has at least three playable tracks yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
            }
            if (state.genreMixes.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.genreMixes, key = { it.genre }) { mix ->
                            GenreMixCard(mix) {
                                val queue = mix.songs.shuffled()
                                queue.firstOrNull()?.let { onSongClick(it, queue) }
                            }
                        }
                    }
                }
            }
        }

        if (state.songCount > 0) {
            item {
                YourMixCard(
                    mix = state.yourMix,
                    onPlayMix = {
                        if (state.yourMix.isNotEmpty()) {
                            val shuffled = state.yourMix.shuffled()
                            onSongClick(shuffled.first(), shuffled)
                        }
                    }
                )
            }

            item {
                SystemInfoCard(
                    trackCount = state.songCount,
                    likedCount = state.likedCount,
                    weekPlays = state.weekPlays,
                    weekMsPlayed = state.weekMsPlayed,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        if (state.songCount == 0 && !state.isSyncing) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TerminalState(
                        title = "YOUR LIBRARY IS EMPTY",
                        detail = "Scan your device or connect a music server to get started."
                    )
                    TerminalActionText(
                        text = "[ SCAN LIBRARY ]",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { viewModel.refreshLibrary() }
                    )
                }
            }
        }

        if (state.recentlyPlayed.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "> RECENTLY PLAYED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${state.recentlyPlayed.size} TRACKS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                val listState = rememberLazyListState()
                
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.recentlyPlayed, key = { it.id }) { song ->
                        RecentlyPlayedChip(
                            song = song,
                            onClick = { onSongClick(song, state.recentlyPlayed) }
                        )
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.songCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TerminalActionText(
                            text = "[ ${state.songCount} tracks indexed ]",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TerminalActionText(
                            text = if (state.isSyncing) "[ SCANNING ]" else "[ RESCAN ]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(enabled = !state.isSyncing) { viewModel.refreshLibrary() }
                        )
                    }
                }

                TerminalActionText(
                    text = if (showDeviceStatus) "[ HIDE DEVICE STATUS ]" else "[ SHOW DEVICE STATUS ]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { showDeviceStatus = !showDeviceStatus }
                )
                if (showDeviceStatus) DeviceStatusCard()
            }
        }
    }
}

@Composable
private fun GenreMixCard(mix: GenreMix, onPlay: () -> Unit) {
    TerminalBorder(modifier = Modifier.width(212.dp).clickable { onPlay() }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "> ${mix.genre.uppercase()}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${mix.songs.size} TRACKS · SMART SELECTION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mix.songs.take(3).forEach { song ->
                    SongArt(uriString = song.artworkUri?.takeIf { it.isNotBlank() } ?: song.uriString, size = 48.dp)
                }
            }
            TerminalActionText(
                text = "[ PLAY MIX ]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun YourMixCard(mix: List<Song>, onPlayMix: () -> Unit) {
    TerminalBorder(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "> YOUR MIX_",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (mix.isEmpty()) "BUILDING A MIX FROM YOUR LISTENING" else "${mix.size} TRACKS PICKED FOR YOU",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (mix.isNotEmpty()) {
                    TerminalActionText(
                        text = "[ SHUFFLE ]",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onPlayMix() }.padding(start = 8.dp, top = 10.dp, bottom = 10.dp)
                    )
                }
            }

            if (mix.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(mix.take(10), key = { it.id }) { song ->
                        SongArt(uriString = song.artworkUri?.takeIf { it.isNotBlank() } ?: song.uriString, size = 72.dp)
                    }
                }
            } else {
                Text(
                    text = "Play more tracks to personalize this selection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecentlyPlayedChip(song: Song, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(144.dp)
            .clickable { onClick() }
    ) {
        TerminalBorder(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SongArt(uriString = song.artworkUri?.takeIf { it.isNotBlank() } ?: song.uriString, size = 112.dp)
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
