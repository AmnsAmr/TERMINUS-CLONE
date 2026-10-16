package com.necroware.terminusplayer.ui.screens.search

import com.necroware.terminusplayer.ui.components.TerminalActionText
import com.necroware.terminusplayer.ui.components.TerminalSearchBar
import com.necroware.terminusplayer.ui.components.highlightedText

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.ui.components.SongArt
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalState
import com.necroware.terminusplayer.util.toMinutesSeconds

@Composable
fun SearchScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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

        TerminalSearchBar(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            focusRequester = focusRequester,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
            onSearchAction = { keyboardController?.hide() }
        )

        when {
            query.isBlank() -> TerminalState(
                title = "SEARCH THE LIBRARY",
                detail = "Enter a title, artist, or album name."
            )
            results.isEmpty() -> TerminalState(
                title = "NO MATCHES",
                detail = "Try a different title, artist, or album."
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id }) { song ->
                    SearchResultRow(song = song, query = query) { onSongClick(song, results) }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(song: Song, query: String, onClick: () -> Unit) {
    TerminalBorder(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SongArt(uriString = song.uriString, size = 48.dp)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = highlightedText(song.title, query),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
