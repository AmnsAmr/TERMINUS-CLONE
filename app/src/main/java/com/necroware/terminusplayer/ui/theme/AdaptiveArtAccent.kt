package com.necroware.terminusplayer.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.necroware.terminusplayer.coil.LocalAudioUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val accentCache = LruCache<String, Color>(64)

/**
 * Resolves artwork URI into a format understandable by Terminus Coil fetchers.
 */
private fun resolveArtModel(uriString: String): Any? {
    if (uriString.isBlank()) return null
    return if (uriString.startsWith("terminus://")) {
        val songId = uriString.removePrefix("terminus://")
        "https://terminus.invalid/rest/getCoverArt?id=$songId&v=1.16.1&c=Terminus"
    } else {
        LocalAudioUri(uriString)
    }
}

/**
 * Extracts a vibrant neon terminal accent from the active album cover art.
 * Returns [defaultAccent] immediately if disabled, no artwork exists, or during loading.
 */
@Composable
fun rememberAdaptiveAccent(
    artworkUri: String?,
    defaultAccent: Color,
    enabled: Boolean
): Color {
    if (!enabled || artworkUri.isNullOrBlank()) return defaultAccent

    val cached = accentCache.get(artworkUri)
    if (cached != null) return cached

    val context = LocalContext.current
    val accent by produceState(initialValue = defaultAccent, key1 = artworkUri, key2 = enabled) {
        val extracted = withContext(Dispatchers.IO) {
            extractNeonAccent(context, artworkUri)
        }
        if (extracted != null) {
            accentCache.put(artworkUri, extracted)
            value = extracted
        }
    }

    return accent
}

private suspend fun extractNeonAccent(context: Context, uri: String): Color? = runCatching {
    val model = resolveArtModel(uri) ?: return null
    val request = ImageRequest.Builder(context)
        .data(model)
        .size(64, 64)
        .allowHardware(false)
        .build()

    val drawable = context.imageLoader.execute(request).drawable ?: return null
    val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap(64, 64)

    withContext(Dispatchers.Default) {
        findMostSaturatedColor(bitmap)
    }
}.getOrNull()

private fun findMostSaturatedColor(bitmap: Bitmap): Color? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    var bestColor: Color? = null
    var maxScore = -1f
    val hsv = FloatArray(3)

    for (pixel in pixels) {
        val r = android.graphics.Color.red(pixel)
        val g = android.graphics.Color.green(pixel)
        val b = android.graphics.Color.blue(pixel)

        val maxVal = maxOf(r, maxOf(g, b))
        val minVal = minOf(r, minOf(g, b))
        // Reject completely neutral or dark black pixels
        if (maxVal - minVal < 14 || maxVal < 36) continue

        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val saturation = hsv[1]
        val value = hsv[2]

        if (value > 0.15f && saturation > 0.18f) {
            val score = saturation * 0.75f + value * 0.25f
            if (score > maxScore) {
                maxScore = score
                // Convert to a punchy cyberpunk neon hue
                hsv[1] = maxOf(saturation, 0.82f)
                hsv[2] = 0.95f
                bestColor = Color(android.graphics.Color.HSVToColor(hsv))
            }
        }
    }

    return bestColor
}

