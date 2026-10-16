package com.necroware.terminusplayer.ui.screens.playlists

import com.necroware.terminusplayer.ui.components.TerminalActionText

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.PlaylistOfflineAvailability
import com.necroware.terminusplayer.ui.screens.library.LibrarySongRow
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalState
import com.necroware.terminusplayer.ui.components.FastScrollRail
import com.necroware.terminusplayer.ui.components.LibrarySortControls
import com.necroware.terminusplayer.ui.components.fastScrollTargets
import com.necroware.terminusplayer.ui.components.stableSortedList
import com.necroware.terminusplayer.ui.components.reconcileSelection
import com.necroware.terminusplayer.ui.components.selectAllItemIds
import com.necroware.terminusplayer.ui.components.playlistSelectionIdentity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.necroware.terminusplayer.ui.components.shouldShowFastScroll
import com.necroware.terminusplayer.ui.components.toggleSelection
import com.necroware.terminusplayer.ui.components.PlaylistSelectionActionBar
import com.necroware.terminusplayer.ui.components.PlaylistSelectionControls
import com.necroware.terminusplayer.ui.components.TerminalSearchBar

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onOpenPlayer: (() -> Unit)? = null,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val transferStatuses by viewModel.transferStatuses.collectAsStateWithLifecycle()
    val currentPlayingSongId by viewModel.currentPlayingSongId.collectAsStateWithLifecycle()
    val uploadMatchResolution by viewModel.uploadMatchResolution.collectAsStateWithLifecycle()
    var playlistSong by remember { mutableStateOf<Song?>(null) }
    var showDownloadPrompt by remember { mutableStateOf(false) }
    var showBulkActions by remember { mutableStateOf(false) }
    var showBulkPlaylistPicker by remember { mutableStateOf(false) }
    val playlistIdentity = playlistSelectionIdentity(state.currentPlaylistId, state.kind?.name)
    var sortField by rememberSaveable(playlistIdentity) { mutableStateOf("PLAYLIST_ORDER") }
    var sortAscending by rememberSaveable(playlistIdentity) { mutableStateOf(true) }
    var searchQuery by rememberSaveable(playlistIdentity) { mutableStateOf("") }
    var isSearchOpen by rememberSaveable(playlistIdentity) { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler(enabled = isSearchOpen) {
        isSearchOpen = false
        searchQuery = ""
        keyboardController?.hide()
    }

    LaunchedEffect(isSearchOpen) {
        if (isSearchOpen) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    var selectedSongIds by remember(playlistIdentity) { mutableStateOf<Set<String>>(emptySet()) }
    val haptic = LocalHapticFeedback.current
    val sortedSongs = remember(state.songs, sortField, sortAscending) {
        if (sortField == "PLAYLIST_ORDER") state.songs else stableSortedList(
            state.songs,
            descending = !sortAscending,
            comparator = when (sortField) {
                "TITLE" -> compareBy(String.CASE_INSENSITIVE_ORDER, Song::title)
                "ARTIST" -> compareBy(String.CASE_INSENSITIVE_ORDER, Song::artist)
                "ALBUM" -> compareBy(String.CASE_INSENSITIVE_ORDER, Song::album)
                "DURATION" -> compareBy(Song::duration)
                else -> compareBy<Song> { 0 }
            }
        )
    }
    val filteredSongs = remember(sortedSongs, searchQuery, isSearchOpen) {
        if (!isSearchOpen || searchQuery.isBlank()) {
            sortedSongs
        } else {
            val query = searchQuery.trim()
            sortedSongs.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                song.artist.contains(query, ignoreCase = true) ||
                song.album.contains(query, ignoreCase = true)
            }
        }
    }
    val currentSongIds = remember(sortedSongs) { sortedSongs.map { it.id }.toSet() }
    val visibleSongIds = remember(filteredSongs) { filteredSongs.map { it.id }.toSet() }
    val selectedIds = reconcileSelection(selectedSongIds, currentSongIds)
    val currentPlaylist = state.currentPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }
    val downloadableSongIds = (state.offlineAvailability as? PlaylistOfflineAvailability.Ready)
        ?.plan?.downloadableSongs?.mapTo(mutableSetOf()) { it.id }.orEmpty()
    LaunchedEffect(playlistIdentity, currentSongIds) {
        if (selectedSongIds != selectedIds) selectedSongIds = selectedIds
    }
    val listState = rememberLazyListState()
    val playlistHeaderItems = 4 + (if (actionMessage != null) 1 else 0) + (if (currentPlaylist?.hasSyncConflict == true) 1 else 0)
    val songIndexByKey = remember(filteredSongs) {
        filteredSongs.mapIndexed { index, song -> "track-$index-${song.id}" to index }.toMap()
    }
    val firstVisibleSongIndex = listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
        (item.key as? String)?.let(songIndexByKey::get)
    } ?: 0

    Box(Modifier.fillMaxSize()) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = if (selectedIds.isNotEmpty()) 120.dp else 28.dp),
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
                TerminalActionText(
                    text = "[ ${state.title} ]",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                TerminalActionText(
                    text = if (isSearchOpen && searchQuery.isNotBlank()) {
                        "[ ${filteredSongs.size} / ${state.songs.size} tracks ]"
                    } else {
                        "[ ${state.songs.size} tracks ]"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (currentPlaylist?.hasSyncConflict == true) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (currentPlaylist.serverPlaylistMissing) {
                            "NAVIDROME NO LONGER HAS THIS PLAYLIST. DEVICE TRACKS AND EDITS ARE PRESERVED."
                        } else {
                            "NAVIDROME CHANGED THIS PLAYLIST WHILE DEVICE EDITS WERE PENDING. BOTH VERSIONS ARE PRESERVED."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (currentPlaylist.serverPlaylistMissing) {
                        TerminalActionText(
                            "[ KEEP AS LOCAL PLAYLIST ]",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.keepMissingServerPlaylistLocally() }
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TerminalActionText(
                                "[ KEEP DEVICE EDITS ]",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { viewModel.resolvePlaylistConflict(keepLocalEdits = true) }
                            )
                            TerminalActionText(
                                "[ USE NAVIDROME VERSION ]",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { viewModel.resolvePlaylistConflict(keepLocalEdits = false) }
                            )
                        }
                    }
                }
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
                    modifier = Modifier.clickable {
                        viewModel.playAll(filteredSongs)
                        onOpenPlayer?.invoke()
                    }
                )
                when (val availability = state.offlineAvailability) {
                    PlaylistOfflineAvailability.Checking -> Text(
                        "… CHECKING OFFLINE FILES",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is PlaylistOfflineAvailability.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("! ${availability.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        TerminalActionText("[ RETRY ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.retryOfflineAvailability() }.padding(start = 8.dp))
                    }
                    is PlaylistOfflineAvailability.Ready -> {
                        val plan = availability.plan
                        if (plan.downloadableSongs.isNotEmpty()) {
                            Text(
                                text = "↓ DOWNLOAD MISSING (${plan.downloadableSongs.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.clickable { showDownloadPrompt = true }
                            )
                        } else if (plan.serverSongs.isNotEmpty()) {
                            Text(
                                text = if (plan.unresolvedTrackCount > 0 || plan.unavailableCount > 0) {
                                    "! SERVER ENTRIES NEED CHECKING"
                                } else "✓ OFFLINE · OPTIONS",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (plan.unresolvedTrackCount > 0 || plan.unavailableCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                modifier = if (plan.unresolvedTrackCount > 0 || plan.unavailableCount > 0) {
                                    Modifier
                                } else Modifier.clickable { showDownloadPrompt = true }
                            )
                        } else if (plan.unresolvedTrackCount > 0) {
                            Text("! ENTRIES NEED VERIFYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        } else if (plan.unavailableCount > 0) {
                            Text("! ${plan.unavailableCount} TRACKS UNAVAILABLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        } else if (sortedSongs.isNotEmpty()) {
                            Text("✓ AVAILABLE OFFLINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }

        item {
            Column {
                LibrarySortControls(
                    selectedField = sortField,
                    fields = listOf("PLAYLIST_ORDER", "TITLE", "ARTIST", "ALBUM", "DURATION"),
                    ascending = sortAscending,
                    title = "SORT PLAYLIST TRACKS",
                    onFieldSelected = { sortField = it },
                    onDirectionSelected = { sortAscending = it },
                    showDirectionControl = sortField != "PLAYLIST_ORDER",
                    actionText = if (isSearchOpen) "[ CLOSE ]" else "[ SEARCH ]",
                    actionColor = if (isSearchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onActionClick = {
                        isSearchOpen = !isSearchOpen
                        if (!isSearchOpen) {
                            searchQuery = ""
                            keyboardController?.hide()
                        }
                    }
                )
                if (isSearchOpen) {
                    TerminalSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = "FILTER TRACKS...",
                        focusRequester = searchFocusRequester,
                        modifier = Modifier.padding(bottom = 8.dp),
                        onClear = { searchQuery = "" },
                        onSearchAction = { keyboardController?.hide() }
                    )
                }
                PlaylistSelectionControls(
                    selectedCount = selectedIds.size,
                    totalCount = filteredSongs.size,
                    onSelectAll = { selectedSongIds = selectedSongIds + visibleSongIds },
                    onClearSelection = { selectedSongIds = emptySet() }
                )
            }
        }

        if (!state.isLoading && filteredSongs.isEmpty()) {
            item {
                if (isSearchOpen && searchQuery.isNotBlank()) {
                    TerminalState("NO MATCHES", "No tracks matching \"$searchQuery\" in this playlist.")
                } else {
                    TerminalState("PLAYLIST IS EMPTY", state.errorMessage ?: state.emptyMessage)
                }
            }
        }

        itemsIndexed(filteredSongs, key = { index, song -> "track-$index-${song.id}" }) { index, song ->
            LibrarySongRow(
                        song = song,
                        isSelected = song.id in selectedIds,
                        isCurrentlyPlaying = song.id == currentPlayingSongId,
                        searchQuery = searchQuery,
                        onClick = {
                            selectedSongIds = if (selectedIds.isNotEmpty()) {
                                toggleSelection(selectedIds, song.id)
                            } else {
                                viewModel.playFrom(song, filteredSongs, index)
                                onOpenPlayer?.invoke()
                                selectedSongIds
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedSongIds = selectedIds + song.id
                        },
                        onLike = { viewModel.toggleLike(song) },
                        onAddToPlaylist = { playlistSong = song },
                        onPlayNext = { viewModel.playNext(song) },
                        transferStatus = transferStatuses[song.id],
                        onUpload = if (
                            song.providerId == "local" && song.navidromeId.isNullOrBlank() && transferStatuses[song.id] == null
                        ) {
                            { viewModel.uploadSong(song) }
                        } else null,
                        onDownload = if (song.id in downloadableSongIds && transferStatuses[song.id] == null) {
                            { viewModel.downloadSong(song) }
                        } else null,
                        onRetryTransfer = if (transferStatuses[song.id]?.canRetry == true) {
                            { viewModel.retryTransfer(song) }
                        } else null,
                        onResolveUploadMatch = if (
                            song.providerId == "local" && song.navidromeId.isNullOrBlank() &&
                            transferStatuses[song.id]?.state in setOf("SUCCESS", "ALREADY_EXISTS")
                        ) {
                            { viewModel.requestUploadMatchResolution(song) }
                        } else null
                    )
        }
      }
      if (shouldShowFastScroll(filteredSongs.size)) {
          FastScrollRail(
              modifier = Modifier.align(Alignment.CenterEnd),
              listState = listState,
              itemCount = filteredSongs.size,
              scrollTargets = remember(filteredSongs, playlistHeaderItems) { fastScrollTargets(filteredSongs.size, playlistHeaderItems) },
              initialIndex = firstVisibleSongIndex
          )
      }
      if (selectedIds.isNotEmpty()) {
          PlaylistSelectionActionBar(
              selectedCount = selectedIds.size,
              onActions = { showBulkActions = true },
              onDone = { selectedSongIds = emptySet() },
              modifier = Modifier.align(Alignment.BottomCenter)
          )
      }
    }

    playlistSong?.let { song ->
        val availablePlaylists = playlists.filter { it.id != state.currentPlaylistId }
        Dialog(onDismissRequest = { playlistSong = null }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ADD TO PLAYLIST", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        if (availablePlaylists.isEmpty()) {
                            Text("No other playlists are available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(240.dp)) {
                                items(availablePlaylists, key = { it.id }) { playlist ->
                                    TerminalActionText(
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
                        TerminalActionText("[ CANCEL ]", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).clickable { playlistSong = null }.padding(8.dp))
                    }
                }
            }
        }
    }

    if (showBulkActions) {
        val selectedSongs = sortedSongs.filter { it.id in selectedIds }
        val readyPlan = (state.offlineAvailability as? PlaylistOfflineAvailability.Ready)?.plan
        val missingSelectedCount = readyPlan?.downloadableSongs?.count { it.id in selectedIds } ?: 0
        val unavailableSelectedCount = readyPlan?.unavailableSongIds?.count { it in selectedIds } ?: 0
        val serverSelectedCount = selectedSongs.count { it.providerId == "navidrome" || !it.navidromeId.isNullOrBlank() }
        val localSelectedCount = selectedSongs.count { it.providerId == "local" }
        Dialog(onDismissRequest = { showBulkActions = false }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("BULK ACTIONS (${selectedSongs.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (readyPlan == null && serverSelectedCount > 0) {
                                val availabilityMessage = when (val availability = state.offlineAvailability) {
                                    PlaylistOfflineAvailability.Checking -> "Checking selected tracks' offline availability…"
                                    is PlaylistOfflineAvailability.Failed -> "Offline check failed: ${availability.message}"
                                    is PlaylistOfflineAvailability.Ready -> ""
                                }
                                Text(availabilityMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.offlineAvailability is PlaylistOfflineAvailability.Failed) {
                                    TerminalActionText("[ RETRY CHECK ]", color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { viewModel.retryOfflineAvailability() })
                                }
                            } else if (readyPlan != null && missingSelectedCount > 0) {
                                TerminalActionText(
                                    "[ DOWNLOAD SELECTED ($missingSelectedCount MISSING) ]",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        viewModel.downloadSelected(selectedSongs)
                                        selectedSongIds = emptySet()
                                        showBulkActions = false
                                    }
                                )
                            } else if (readyPlan != null && serverSelectedCount > 0 && unavailableSelectedCount > 0) {
                                TerminalActionText(
                                    "[ $unavailableSelectedCount SELECTED TRACKS UNAVAILABLE ]",
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (readyPlan != null && serverSelectedCount > 0) {
                                TerminalActionText(
                                    "[ SELECTED SERVER TRACKS ARE OFFLINE ]",
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            if (localSelectedCount > 0) {
                                TerminalActionText(
                                    "[ UPLOAD SELECTED ($localSelectedCount LOCAL) ]",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.clickable {
                                        viewModel.uploadSelected(selectedSongs)
                                        selectedSongIds = emptySet()
                                        showBulkActions = false
                                    }
                                )
                            }
                            if (playlists.any { it.id != state.currentPlaylistId }) {
                                TerminalActionText(
                                    "[ ADD SELECTED TO PLAYLIST ]",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showBulkPlaylistPicker = true; showBulkActions = false }
                                )
                            }
                            if (state.currentPlaylistId != null) {
                                TerminalActionText(
                                    "[ REMOVE SELECTED FROM PLAYLIST ]",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.clickable {
                                        viewModel.removeSongsFromCurrentPlaylist(selectedSongs.map { it.id })
                                        selectedSongIds = emptySet()
                                        showBulkActions = false
                                    }
                                )
                            }
                        }
                        TerminalActionText("[ CANCEL ]", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).clickable { showBulkActions = false }.padding(8.dp))
                    }
                }
            }
        }
    }

    if (showBulkPlaylistPicker) {
        val availablePlaylists = playlists.filter { it.id != state.currentPlaylistId }
        Dialog(onDismissRequest = { showBulkPlaylistPicker = false }) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ADD ${selectedIds.size} TRACKS TO PLAYLIST", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        if (availablePlaylists.isEmpty()) {
                            Text("No other playlists are available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(240.dp)) {
                                items(availablePlaylists, key = { it.id }) { playlist ->
                                    TerminalActionText(
                                        "[ ${playlist.name} ]",
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            viewModel.addSongsToPlaylist(playlist.id, sortedSongs.filter { it.id in selectedIds }.map { it.id })
                                            selectedSongIds = emptySet()
                                            showBulkPlaylistPicker = false
                                        }.padding(vertical = 10.dp)
                                    )
                                }
                            }
                        }
                        TerminalActionText("[ CANCEL ]", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).clickable { showBulkPlaylistPicker = false }.padding(8.dp))
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
                        Text("DOWNLOAD OPTIONS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        when (val availability = state.offlineAvailability) {
                            PlaylistOfflineAvailability.Checking -> Text("Checking offline availability…", style = MaterialTheme.typography.bodyMedium)
                            is PlaylistOfflineAvailability.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Could not check offline availability: ${availability.message}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                TerminalActionText("[ RETRY CHECK ]", color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { viewModel.retryOfflineAvailability() }.padding(vertical = 8.dp))
                            }
                            is PlaylistOfflineAvailability.Ready -> {
                                val plan = availability.plan
                                Text(
                                    when {
                                        plan.downloadableSongs.isNotEmpty() -> "${plan.downloadableSongs.size} tracks are missing offline; ${plan.alreadyOfflineCount} are already available."
                                        plan.unresolvedTrackCount > 0 -> "${plan.unresolvedTrackCount} playlist entries are not represented in the cache. Refresh while online to confirm them."
                                        plan.unavailableCount > 0 -> "${plan.unavailableCount} tracks have no readable local file or usable server source."
                                        else -> "All available tracks are already offline."
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (plan.downloadableSongs.isNotEmpty()) {
                                        TerminalActionText(
                                            "[ DOWNLOAD MISSING (${plan.downloadableSongs.size}) ]",
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                viewModel.downloadAll(force = false)
                                                showDownloadPrompt = false
                                            }.padding(vertical = 8.dp)
                                        )
                                    }
                                    if (plan.serverSongs.isNotEmpty()) {
                                        TerminalActionText(
                                            "[ FORCE RE-DOWNLOAD (${plan.serverSongs.size}) ]",
                                            color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                viewModel.downloadAll(force = true)
                                                showDownloadPrompt = false
                                            }.padding(vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TerminalActionText(
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

    uploadMatchResolution?.let { resolution ->
        Dialog(onDismissRequest = viewModel::dismissUploadMatchResolution) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("LINK ${resolution.localSong.title}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Choose the exact Navidrome recording. No match will be applied until you select one.", style = MaterialTheme.typography.bodySmall)
                        if (resolution.isLoading) {
                            Text("Checking cached Navidrome matches…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (resolution.candidates.isEmpty()) {
                            Text(resolution.errorMessage ?: "No unlinked candidates found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(240.dp)) {
                                items(resolution.candidates, key = { it.id }) { candidate ->
                                    TerminalActionText(
                                        "[ ${candidate.title} · ${candidate.artist} · ${candidate.album} ]",
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            viewModel.confirmUploadMatch(candidate)
                                        }.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }
                        resolution.errorMessage?.takeIf { resolution.candidates.isNotEmpty() }?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        TerminalActionText(
                            "[ CANCEL ]",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).clickable(onClick = viewModel::dismissUploadMatchResolution).padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
