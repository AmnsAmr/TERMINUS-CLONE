package com.necroware.terminusplayer.ui.components

import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import coil.memory.MemoryCache
import coil.request.ImageRequest

/**
 * Memory-cache key of the last image shown for each artwork URI, at whatever size it was
 * loaded. Lets a new SongArt for the same track (for example the full player opening from
 * the mini bar) show the already-decoded image while the larger one loads.
 */
private val lastLoadedArtKeys = LruCache<String, MemoryCache.Key>(64)

/**
 * Maps an artwork URI to the model Terminus Coil fetchers understand. YouTube watch pages
 * have no artwork and map to null, which shows the fallback.
 */
internal fun songArtModel(uriString: String): Any? =
    if (uriString.isBlank() || uriString.startsWith("https://www.youtube.com/watch") || uriString.startsWith("http://www.youtube.com/watch") || uriString.startsWith("https://youtu.be/")) {
        null
    } else if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
        uriString
    } else if (uriString.startsWith("terminus://")) {
        val songId = uriString.removePrefix("terminus://")
        // NetworkModule replaces this sentinel with the configured server URL.
        // It must never point at device localhost when configuration is absent.
        "https://terminus.invalid/rest/getCoverArt?id=$songId&v=1.16.1&c=Terminus"
    } else {
        com.necroware.terminusplayer.coil.LocalAudioUri(uriString)
    }

/**
 * Unified image caching architecture using Coil for both local and remote artwork.
 * Local artwork fetching is handled by [com.necroware.terminusplayer.coil.LocalAudioArtFetcher].
 *
 * While a new image loads, the previous image stays visible instead of the fallback.
 *
 * @param crossfadeMs Blend from the previous image when a new one arrives from disk or
 *   network. 0 swaps immediately, which lists use.
 */
@Composable
fun SongArt(
    uriString: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    crossfadeMs: Int = 0
) {
    val context = LocalContext.current
    // Key of the image this instance last displayed, used as the placeholder for the next one.
    // Not state: it is only read when the next request is built.
    val shown = remember { ShownArtKey() }
    // Drawn under the image, so starting visible never covers an image that is already cached.
    var showFallback by remember { mutableStateOf(true) }

    val request = remember(uriString, crossfadeMs) {
        val placeholderKey = lastLoadedArtKeys.get(uriString) ?: shown.key
        ImageRequest.Builder(context)
            .data(songArtModel(uriString))
            .placeholderMemoryCacheKey(placeholderKey)
            .crossfade(crossfadeMs)
            .build()
    }

    Box(modifier.size(size)) {
        if (showFallback) SongArtFallback(size)
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onState = { state ->
                showFallback = when (state) {
                    is AsyncImagePainter.State.Success -> {
                        state.result.memoryCacheKey?.let { key ->
                            shown.key = key
                            lastLoadedArtKeys.put(uriString, key)
                        }
                        false
                    }
                    // A placeholder means an earlier image is still on screen.
                    is AsyncImagePainter.State.Loading -> state.painter == null
                    is AsyncImagePainter.State.Error -> true
                    AsyncImagePainter.State.Empty -> true
                }
            }
        )
    }
}

private class ShownArtKey {
    var key: MemoryCache.Key? = null
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
