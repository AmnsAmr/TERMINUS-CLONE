package com.necroware.terminusplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * Unified image caching architecture using Coil for both local and remote artwork.
 * Local artwork fetching is handled by [com.necroware.terminusplayer.coil.LocalAudioArtFetcher].
 */
@Composable
fun SongArt(
    uriString: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    val model = if (uriString.isBlank()) {
        null
    } else if (uriString.startsWith("terminus://")) {
        val songId = uriString.removePrefix("terminus://")
        // NetworkModule replaces this sentinel with the configured server URL.
        // It must never point at device localhost when configuration is absent.
        "https://terminus.invalid/rest/getCoverArt?id=$songId&v=1.16.1&c=Terminus"
    } else {
        com.necroware.terminusplayer.coil.LocalAudioUri(uriString)
    }

    SubcomposeAsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size),
        loading = { SongArtFallback(size) },
        error = { SongArtFallback(size) }
    )
}

@Composable
private fun SongArtFallback(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "♪",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
