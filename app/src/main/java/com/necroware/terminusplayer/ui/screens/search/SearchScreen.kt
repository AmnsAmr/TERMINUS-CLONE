package com.necroware.terminusplayer.ui.screens.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.api.youtube.YouTubeSearchFilter
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.ui.components.SongArt
import com.necroware.terminusplayer.ui.components.TerminalActionText
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalSearchBar
import com.necroware.terminusplayer.ui.components.TerminalState
import com.necroware.terminusplayer.ui.components.highlightedText
import com.necroware.terminusplayer.util.toMinutesSeconds

@Composable
fun SearchScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchScope by viewModel.searchScope.collectAsStateWithLifecycle()
    val localResults by viewModel.results.collectAsStateWithLifecycle()
    val youTubeResults by viewModel.youTubeResults.collectAsStateWithLifecycle()
    val youTubeFilter by viewModel.youTubeFilter.collectAsStateWithLifecycle()
    val isYouTubeSearching by viewModel.isYouTubeSearching.collectAsStateWithLifecycle()
    val isYouTubeLoadingMore by viewModel.isYouTubeLoadingMore.collectAsStateWithLifecycle()
    val hasMoreYouTubeResults by viewModel.hasMoreYouTubeResults.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val showSuggestions by viewModel.showSuggestions.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header Row: Back button, Title, and Scope Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "︿",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onBack() }
                )
                Text(
                    text = "  SEARCH_",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Scope Selector: LOCAL vs YOUTUBE
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchScope.entries.forEach { scope ->
                    val isSelected = searchScope == scope
                    TerminalActionText(
                        text = if (isSelected) "[ > ${scope.label} < ]" else "[ ${scope.label} ]",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                viewModel.setSearchScope(scope)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Subheader status text
        Text(
            text = when (searchScope) {
                SearchScope.LOCAL -> if (query.isBlank()) "LOCAL LIBRARY SEARCH" else "${localResults.size} LOCAL TRACKS FOUND"
                SearchScope.YOUTUBE -> when {
                    isYouTubeSearching -> "QUERYING YOUTUBE CLOUD..."
                    query.isBlank() -> "YOUTUBE MUSIC EXPLORER"
                    else -> "${youTubeResults.size} YOUTUBE TRACKS (${youTubeFilter.label})"
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        // Search Bar Row with Clear and Execute Action
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalSearchBar(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                focusRequester = focusRequester,
                placeholder = if (searchScope == SearchScope.YOUTUBE) "Search YouTube Music..." else "Search local tracks...",
                modifier = Modifier.weight(1f),
                onClear = viewModel::clearQuery,
                onSearchAction = {
                    keyboardController?.hide()
                    if (searchScope == SearchScope.YOUTUBE) {
                        viewModel.executeYouTubeSearch()
                    }
                }
            )

            if (searchScope == SearchScope.YOUTUBE) {
                Spacer(modifier = Modifier.width(8.dp))
                TerminalActionText(
                    text = "[ EXEC ]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            keyboardController?.hide()
                            viewModel.executeYouTubeSearch()
                        }
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                )
            }
        }

        // Search Suggestions Dropdown (YouTube Scope)
        if (searchScope == SearchScope.YOUTUBE && showSuggestions && suggestions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "// SEARCH SUGGESTIONS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "[ CLOSE ]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { viewModel.dismissSuggestions() }
                        )
                    }
                    suggestions.take(6).forEach { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    keyboardController?.hide()
                                    viewModel.selectSuggestion(suggestion)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "> ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Filter chips (YouTube Scope)
        if (searchScope == SearchScope.YOUTUBE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FILTER:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                YouTubeSearchFilter.entries.forEach { filter ->
                    val isSelected = youTubeFilter == filter
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.clickable {
                            viewModel.setYouTubeFilter(filter)
                        }
                    ) {
                        Text(
                            text = if (isSelected) "[ > ${filter.label} < ]" else "[ ${filter.label} ]",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Main Results Area
        when (searchScope) {
            SearchScope.LOCAL -> {
                when {
                    query.isBlank() -> TerminalState(
                        title = "SEARCH THE LIBRARY",
                        detail = "Enter a title, artist, or album name to search your local library."
                    )
                    localResults.isEmpty() -> TerminalState(
                        title = "NO MATCHES",
                        detail = "Try a different title, artist, or album."
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(localResults, key = { it.id }) { song ->
                            SearchResultRow(song = song, query = query) { onSongClick(song, localResults) }
                        }
                    }
                }
            }

            SearchScope.YOUTUBE -> {
                when {
                    isYouTubeSearching && youTubeResults.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "SEARCHING YOUTUBE CLOUD...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    query.isBlank() && youTubeResults.isEmpty() -> {
                        TerminalState(
                            title = "SEARCH YOUTUBE MUSIC",
                            detail = "Type a query and press Enter or tap [ EXEC ] to discover tracks, artists, and albums."
                        )
                    }
                    youTubeResults.isEmpty() -> {
                        TerminalState(
                            title = "NO YOUTUBE TRACKS FOUND",
                            detail = "Try different keywords or filters."
                        )
                    }
                    else -> {
                        val listState = rememberLazyListState()
                        val shouldLoadMore by remember {
                            derivedStateOf {
                                val total = listState.layoutInfo.totalItemsCount
                                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                total > 0 && lastVisible >= total - 4
                            }
                        }

                        LaunchedEffect(shouldLoadMore) {
                            if (shouldLoadMore && hasMoreYouTubeResults && !isYouTubeSearching && !isYouTubeLoadingMore) {
                                viewModel.loadMoreYouTube()
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(youTubeResults, key = { it.id }) { song ->
                                SearchResultRow(song = song, query = query) { onSongClick(song, youTubeResults) }
                            }

                            if (isYouTubeLoadingMore) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "  FETCHING NEXT CHUNK...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (!hasMoreYouTubeResults && youTubeResults.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "// END OF SEARCH RESULTS //",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(song: Song, query: String, onClick: () -> Unit) {
    TerminalBorder(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SongArt(uriString = song.artworkUri?.takeIf { it.isNotBlank() } ?: song.uriString, size = 48.dp)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = highlightedText(song.title, query),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (song.providerId == "youtube") {
                        Text(
                            text = " [YT]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "${song.artist} · ${song.album} · ${song.duration.toMinutesSeconds()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
