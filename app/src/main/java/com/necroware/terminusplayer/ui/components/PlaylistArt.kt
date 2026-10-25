package com.necroware.terminusplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

/**
 * Playlist cover art component:
 * 1. Tries to load the server cover art for remote playlists (e.g. Navidrome /rest/getCoverArt?id=pl-<id>).
 * 2. If it does not exist on the server or fails to load, it displays a Spotify-style collage
 *    of the playlist's song artwork (2x2 grid if >= 4 songs, 1st song's artwork if 1..3 songs,
 *    or retro terminal placeholder if empty).
 */
@Composable
fun PlaylistArt(
    modifier: Modifier = Modifier,
    serverArtUri: String? = null,
    songArtUris: List<String> = emptyList(),
    size: Dp = 56.dp
) {
    val cleanArts = remember(songArtUris) {
        songArtUris.filter { it.isNotBlank() }.distinct()
    }
    var serverLoadFailed by remember(serverArtUri) {
        mutableStateOf(serverArtUri.isNullOrBlank())
    }
    val context = LocalContext.current

    Box(
        modifier = modifier
            .size(size)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Spotify-style fallback collage / song artwork underneath
        PlaylistArtCollage(arts = cleanArts, size = size)

        // Remote cover art from Navidrome
        if (!serverArtUri.isNullOrBlank() && !serverLoadFailed) {
            val request = remember(serverArtUri) {
                ImageRequest.Builder(context)
                    .data(songArtModel(serverArtUri))
                    .crossfade(150)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = "Playlist cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Error -> {
                            serverLoadFailed = true
                        }
                        is AsyncImagePainter.State.Empty -> {
                            serverLoadFailed = true
                        }
                        else -> {}
                    }
                }
            )
        }
    }
}

@Composable
private fun PlaylistArtCollage(
    arts: List<String>,
    size: Dp
) {
    when {
        arts.size >= 4 -> {
            val tileSize = size / 2
            Column(modifier = Modifier.size(size)) {
                Row(modifier = Modifier.size(width = size, height = tileSize)) {
                    SongArt(uriString = arts[0], size = tileSize)
                    SongArt(uriString = arts[1], size = tileSize)
                }
                Row(modifier = Modifier.size(width = size, height = tileSize)) {
                    SongArt(uriString = arts[2], size = tileSize)
                    SongArt(uriString = arts[3], size = tileSize)
                }
            }
        }
        arts.isNotEmpty() -> {
            SongArt(uriString = arts.first(), size = size)
        }
        else -> {
            PlaylistArtFallback(size = size)
        }
    }
}

@Composable
private fun PlaylistArtFallback(size: Dp) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "♪",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
