package com.necroware.terminusplayer.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.prefs.LibrarySortOrder
import com.necroware.terminusplayer.data.prefs.SortDirection
import com.necroware.terminusplayer.data.prefs.SortField
import com.necroware.terminusplayer.ui.components.SongArt
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalState
import com.necroware.terminusplayer.util.toMinutesSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onInitialContentReady: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.SONGS) }
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val localSongs by viewModel.localSongs.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val isLibraryLoaded by viewModel.isLibraryLoaded.collectAsStateWithLifecycle()
    var initialSongListPrepared by remember { mutableStateOf(false) }

    LaunchedEffect(isLibraryLoaded, songs.isEmpty(), initialSongListPrepared) {
        if (isLibraryLoaded && (songs.isEmpty() || initialSongListPrepared)) {
            onInitialContentReady()
        }
    }

    var playlistSong by remember { mutableStateOf<Song?>(null) }

    var selectedSongs by remember { mutableStateOf<Set<Song>>(emptySet()) }
    var showBulkActionDialog by remember { mutableStateOf(false) }
    var showBulkPlaylistDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    val handleSongClick: (Song, List<Song>) -> Unit = { song, list ->
        if (selectedSongs.isNotEmpty()) {
            selectedSongs = if (selectedSongs.contains(song)) selectedSongs - song else selectedSongs + song
        } else {
            onSongClick(song, list)
        }
    }

    val handleSongLongPress: (Song) -> Unit = { song ->
        if (selectedSongs.isEmpty()) {
            selectedSongs = setOf(song)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "> LIBRARY_",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "[ SEARCH ]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onSearchClick() }.padding(start = 8.dp, top = 10.dp, bottom = 10.dp)
                )
            }

            Text(
                text = when (selectedTab) {
                    LibraryTab.SONGS -> "${songs.size} TRACKS"
                    LibraryTab.LOCAL_MUSIC -> "${localSongs.size} DEVICE TRACKS"
                    LibraryTab.ALBUMS -> "${albums.size} ALBUMS"
                    LibraryTab.ARTISTS -> "${artists.size} ARTISTS"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            actionMessage?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { viewModel.dismissActionMessage() }.padding(bottom = 8.dp)
                )
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(LibraryTab.entries, key = { it.name }) { tab ->
                    val selected = tab == selectedTab
                    Text(
                        text = "[ ${tab.label} ]",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                selectedTab = tab
                                selectedSongs = emptySet()
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (selectedTab == LibraryTab.SONGS || selectedTab == LibraryTab.LOCAL_MUSIC) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SORT: ${sortOrder.field.name.replace('_', ' ')} ${if (sortOrder.direction.name == "ASC") "↑" else "↓"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showSortDialog = true }.padding(vertical = 6.dp)
                    )
                    Text(
                        text = "[ CHANGE ]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { showSortDialog = true }.padding(vertical = 6.dp)
                    )
                }
            }

            when (selectedTab) {
                LibraryTab.SONGS -> Box(Modifier.weight(1f)) {
                    SongList(
                        songs, selectedSongs, sortOrder, handleSongClick, handleSongLongPress,
                        onLike = { viewModel.toggleLike(it.id) },
                        onAddToPlaylist = { playlistSong = it },
                        onPlayNext = viewModel::playNext,
                        onListPrepared = { initialSongListPrepared = true }
                    )
                }
                LibraryTab.LOCAL_MUSIC -> {
                    if (localSongs.isEmpty()) {
                        TerminalState(
                            title = "NO LOCAL MUSIC FOUND",
                            detail = "Check the folders included in Settings, then rescan."
                        )
                    } else Box(Modifier.weight(1f)) {
                        SongList(
                            localSongs, selectedSongs, sortOrder, handleSongClick, handleSongLongPress,
                            onLike = { viewModel.toggleLike(it.id) },
                            onAddToPlaylist = { playlistSong = it },
                            onPlayNext = viewModel::playNext
                        )
                    }
                }
                LibraryTab.ALBUMS -> {
                    if (albums.isEmpty()) {
                        TerminalState(title = "NO ALBUMS FOUND", detail = "Albums will appear after your library is indexed.")
                    } else {
                        val listState = rememberLazyListState()
                        Box(Modifier.weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                            if (albums.size > 40) {
                                FastScrollRail(
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    listState = listState,
                                    itemCount = albums.size,
                                    scrollTargets = remember(albums) { albums.indices.toList() },
                                    initialIndex = listState.firstVisibleItemIndex.coerceIn(0, albums.lastIndex),
                                    dragLabel = { "" }
                                )
                            }
                        }
                    }
                }
                LibraryTab.ARTISTS -> {
                    if (artists.isEmpty()) {
                        TerminalState(title = "NO ARTISTS FOUND", detail = "Artists will appear after your library is indexed.")
                    } else {
                        val listState = rememberLazyListState()
                        Box(Modifier.weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                            if (artists.size > 40) {
                                FastScrollRail(
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    listState = listState,
                                    itemCount = artists.size,
                                    scrollTargets = remember(artists) { artists.indices.toList() },
                                    initialIndex = listState.firstVisibleItemIndex.coerceIn(0, artists.lastIndex),
                                    dragLabel = { "" }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedSongs.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .padding(16.dp)
            ) {
                TerminalBorder(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Selected: ${selectedSongs.size}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { selectedSongs = emptySet() }.padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                            Text(
                                "[ ACTIONS ]",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showBulkActionDialog = true }.padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSortDialog) {
        Dialog(onDismissRequest = { showSortDialog = false }) {
            Box(Modifier.fillMaxWidth(0.9f).background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("SORT LIBRARY", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("FIELD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SortField.entries.forEach { field ->
                            Text(
                                text = "${if (sortOrder.field == field) ">" else " "} ${field.name.replace('_', ' ')}",
                                color = if (sortOrder.field == field) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().clickable { viewModel.setSortField(field) }.padding(vertical = 6.dp)
                            )
                        }
                        Text("DIRECTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "[ ASCENDING ]",
                                color = if (sortOrder.direction == SortDirection.ASC) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clickable { viewModel.setSortDirection(SortDirection.ASC) }.padding(vertical = 8.dp)
                            )
                            Text(
                                "[ DESCENDING ]",
                                color = if (sortOrder.direction == SortDirection.DESC) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clickable { viewModel.setSortDirection(SortDirection.DESC) }.padding(vertical = 8.dp)
                            )
                        }
                        Text(
                            "[ DONE ]",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.End).clickable { showSortDialog = false }.padding(8.dp)
                        )
                    }
                }
            }
        }
    }

    if (showBulkActionDialog) {
        Dialog(onDismissRequest = { showBulkActionDialog = false }) {
            Box(Modifier.fillMaxWidth(0.9f).background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("BULK ACTIONS (${selectedSongs.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        
                        val localSelectedCount = selectedSongs.count { it.providerId == "local" }
                        val serverSelectedCount = selectedSongs.count { it.providerId != "local" && it.downloadedUri == null }
                        if (serverSelectedCount > 0) {
                            Text(
                                "[ ↓ DOWNLOAD ($serverSelectedCount) ]",
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    selectedSongs.filter { it.providerId != "local" && it.downloadedUri == null }.forEach { viewModel.downloadSong(it) }
                                    showBulkActionDialog = false
                                    selectedSongs = emptySet()
                                }.padding(vertical = 8.dp)
                            )
                        }

                        if (localSelectedCount > 0) {
                            Text(
                                "[ ↑ UPLOAD ($localSelectedCount) ]",
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    viewModel.uploadSongs(selectedSongs.filter { it.providerId == "local" })
                                    showBulkActionDialog = false
                                    selectedSongs = emptySet()
                                }.padding(vertical = 8.dp)
                            )
                        }

                        if (playlists.isNotEmpty()) {
                            Text(
                                "[ + ADD TO PLAYLIST ]",
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    showBulkPlaylistDialog = true
                                    showBulkActionDialog = false
                                }.padding(vertical = 8.dp)
                            )
                        } else {
                            Text(
                                "Create a playlist first.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { showBulkActionDialog = false }.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBulkPlaylistDialog) {
        Dialog(onDismissRequest = { showBulkPlaylistDialog = false }) {
            Box(Modifier.fillMaxWidth(0.9f).background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("ADD ${selectedSongs.size} SONGS TO PLAYLIST", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(200.dp)) {
                            items(playlists, key = { it.id }) { playlist ->
                                Text(
                                    "[ ${playlist.name} ]",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.addSongsToPlaylist(playlist.id, selectedSongs.map { it.id })
                                        showBulkPlaylistDialog = false
                                        selectedSongs = emptySet()
                                    }.padding(vertical = 8.dp)
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "[ CANCEL ]",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { showBulkPlaylistDialog = false }.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    playlistSong?.let { song ->
        Dialog(onDismissRequest = { playlistSong = null }) {
            Box(Modifier.fillMaxWidth(0.9f).background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "ADD TO PLAYLIST",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (playlists.isEmpty()) {
                            Text("Create a playlist first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.height(240.dp)
                            ) {
                                items(playlists, key = { it.id }) { playlist ->
                                    Text(
                                        text = "[ ${playlist.name} ]",
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
                        Text(
                            text = "[ CANCEL ]",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).clickable { playlistSong = null }.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SongList(
    songs: List<Song>,
    selectedSongs: Set<Song>,
    sortOrder: LibrarySortOrder,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onLike: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onListPrepared: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val preparedData by produceState<PreparedSongList?>(null, songs, sortOrder.field) {
        value = withContext(Dispatchers.Default) {
            val sections = when (sortOrder.field) {
                SortField.ARTIST -> songs.groupBy { it.artist.ifBlank { "Unknown artist" } }
                    .map { (label, sectionSongs) -> SongListSection(label, sectionSongs) }
                SortField.ALBUM -> songs.groupBy { it.album.ifBlank { "Unknown album" } }
                    .map { (label, sectionSongs) -> SongListSection(label, sectionSongs) }
                else -> listOf(SongListSection(null, songs))
            }
            val scrollTargets = buildList {
                var listIndex = 0
                sections.forEach { section ->
                    if (section.label != null) listIndex++
                    repeat(section.songs.size) { add(listIndex++) }
                }
            }
            PreparedSongList(
                sourceSongs = songs,
                sortField = sortOrder.field,
                sections = sections,
                scrollTargets = scrollTargets,
                songIndexById = songs.withIndex().associate { it.value.id to it.index }
            )
        }
    }
    val prepared = preparedData?.takeIf { it.sourceSongs === songs && it.sortField == sortOrder.field }
    LaunchedEffect(prepared, songs.isEmpty()) {
        if (prepared != null || songs.isEmpty()) onListPrepared()
    }
    val onLongPress: (Song) -> Unit = { song ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onSongLongPress(song)
    }
    val visibleSongIndex = if (songs.isEmpty() || prepared == null) {
        0
    } else {
        listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
            (item.key as? String)?.let(prepared.songIndexById::get)
        }?.coerceIn(0, songs.lastIndex) ?: 0
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (songs.isEmpty()) {
            TerminalState(
                title = "NO TRACKS FOUND",
                detail = "Adjust your library folders or sync a music server.",
                modifier = Modifier.fillMaxWidth()
            )
        } else if (prepared == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                prepared.sections.forEach { section ->
                    section.label?.let { label ->
                        stickyHeader(key = "section:$label") {
                            Text(
                                text = "> $label",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                    items(section.songs, key = { it.id }) { song ->
                        LibrarySongRow(
                            song = song,
                            isSelected = selectedSongs.contains(song),
                            onClick = { onSongClick(song, songs) },
                            onLongClick = { onLongPress(song) },
                            onLike = { onLike(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            onPlayNext = { onPlayNext(song) }
                        )
                    }
                }
            }
        }

        if (prepared != null && songs.size > 40) {
            FastScrollRail(
                modifier = Modifier.align(Alignment.CenterEnd),
                listState = listState,
                itemCount = songs.size,
                scrollTargets = prepared.scrollTargets,
                initialIndex = visibleSongIndex,
                dragLabel = { index ->
                    songs.getOrNull(index)?.dateAdded?.takeIf { it > 0L }?.let { addedAt ->
                        val timestamp = if (addedAt < 10_000_000_000L) addedAt * 1_000L else addedAt
                        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(timestamp))
                    }.orEmpty()
                }
            )
        }
    }
}

private data class SongListSection(val label: String?, val songs: List<Song>)

private data class PreparedSongList(
    val sourceSongs: List<Song>,
    val sortField: SortField,
    val sections: List<SongListSection>,
    val scrollTargets: List<Int>,
    val songIndexById: Map<String, Int>
)

@Composable
private fun FastScrollRail(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    itemCount: Int,
    scrollTargets: List<Int>,
    initialIndex: Int,
    dragLabel: (Int) -> String
) {
    val scope = rememberCoroutineScope()
    var railHeightPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var activeIndex by remember { mutableIntStateOf(initialIndex) }
    val listInfo = listState.layoutInfo
    val maxScrollIndex = (listInfo.totalItemsCount - listInfo.visibleItemsInfo.size).coerceAtLeast(1)
    val scrollFraction = (listState.firstVisibleItemIndex.toFloat() / maxScrollIndex).coerceIn(0f, 1f)
    val thumbHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
    val thumbTopPx = scrollFraction * (railHeightPx - thumbHeightPx).coerceAtLeast(0f)
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "library-fast-scroll-alpha"
    )

    LaunchedEffect(listState.isScrollInProgress, dragging) {
        if (listState.isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(850)
            visible = false
        }
    }
    LaunchedEffect(initialIndex, dragging) {
        if (!dragging) activeIndex = initialIndex.coerceIn(0, itemCount - 1)
    }

    val currentThumbTop = if (dragging) {
        (activeIndex.toFloat() / (itemCount - 1).coerceAtLeast(1)) * (railHeightPx - thumbHeightPx).coerceAtLeast(0f)
    } else thumbTopPx
    val labelTop = with(androidx.compose.ui.platform.LocalDensity.current) {
        (currentThumbTop - 4.dp.toPx()).coerceIn(0f, (railHeightPx - 28.dp.toPx()).coerceAtLeast(0f)).toDp()
    }
    fun jumpTo(y: Float) {
        val usableHeight = (railHeightPx - thumbHeightPx).coerceAtLeast(1f)
        val index = (((y - thumbHeightPx / 2f).coerceIn(0f, usableHeight) / usableHeight) * (itemCount - 1))
            .roundToInt().coerceIn(0, itemCount - 1)
        activeIndex = index
        scope.launch { listState.scrollToItem(scrollTargets[index]) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { railHeightPx = it.height.coerceAtLeast(1) }
            .graphicsLayer { this.alpha = alpha }
    ) {
        if (visible || dragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(48.dp)
                    .fillMaxSize()
                    .semantics { contentDescription = "Drag to fast scroll the library" }
                    .pointerInput(itemCount, scrollTargets, railHeightPx) {
                        detectTapGestures { position ->
                            jumpTo(position.y)
                            dragging = true
                            scope.launch {
                                delay(800)
                                dragging = false
                            }
                        }
                    }
                    .pointerInput(itemCount, scrollTargets, railHeightPx) {
                        detectDragGestures(
                            onDragStart = { position ->
                                dragging = true
                                jumpTo(position.y)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false }
                        ) { change, _ ->
                            change.consume()
                            jumpTo(change.position.y)
                        }
                    }
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 10.dp)
                                .width(3.dp)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(50))
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 4.dp)
                                .offset { IntOffset(0, currentThumbTop.roundToInt()) }
                                .width(4.dp)
                                .height(24.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
            }

        val currentDragLabel = dragLabel(activeIndex)
        if (dragging && currentDragLabel.isNotBlank()) {
            Text(
                text = currentDragLabel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = (-12).dp,
                        y = labelTop
                    )
                    .widthIn(max = 140.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun LibrarySongRow(
    song: Song,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit
) {
    val local = song.providerId == "local"
    val accent = if (isSelected) MaterialTheme.colorScheme.inversePrimary else if (local) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val bgColor = when {
        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f).compositeOver(surfaceColor)
        local -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f).compositeOver(surfaceColor)
        else -> surfaceColor
    }
    val haptic = LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val revealWidth = 168.dp
    val revealWidthPx = with(density) { revealWidth.toPx() }
    val playNextThresholdPx = with(density) { 72.dp.toPx() }
    var offsetPx by remember(song.id) { mutableFloatStateOf(0f) }
    var isDragging by remember(song.id) { mutableStateOf(false) }
    val renderedOffsetPx by animateFloatAsState(
        targetValue = offsetPx,
        animationSpec = if (isDragging) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "song-row-swipe-offset"
    )
    val dragState = rememberDraggableState { delta ->
        offsetPx = (offsetPx + delta).coerceIn(-revealWidthPx, revealWidthPx)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        if (renderedOffsetPx < -1f) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(revealWidth)
                    .height(72.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLike()
                            offsetPx = 0f
                        }
                        .semantics { contentDescription = if (song.isLiked) "Unlike ${song.title}" else "Like ${song.title}" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (song.isLiked) "♥ LIKED" else "♡ LIKE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAddToPlaylist()
                            offsetPx = 0f
                        }
                        .semantics { contentDescription = "Add ${song.title} to playlist" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+ PLAYLIST",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(renderedOffsetPx.roundToInt(), 0) }
                .fillMaxWidth()
                .height(72.dp)
                .background(bgColor)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { isDragging = true },
                    onDragStopped = {
                        when {
                            offsetPx >= playNextThresholdPx -> {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPlayNext()
                                offsetPx = 0f
                            }
                            offsetPx <= -revealWidthPx * 0.45f -> offsetPx = -revealWidthPx
                            else -> offsetPx = 0f
                        }
                        isDragging = false
                    }
                )
                .combinedClickable(
                    onClick = {
                        if (renderedOffsetPx < -playNextThresholdPx) offsetPx = 0f else onClick()
                    },
                    onLongClick = onLongClick
                )
        ) {
            TerminalBorder(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
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
}
