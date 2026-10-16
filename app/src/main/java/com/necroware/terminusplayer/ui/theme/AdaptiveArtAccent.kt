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
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val accentCache = LruCache<String, Color>(64)

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
        val resolved = extracted ?: defaultAccent
        accentCache.put(artworkUri, resolved)
        if (extracted != null) {
            value = extracted
        }
    }

    return accent
}

private suspend fun extractNeonAccent(context: Context, uri: String): Color? = runCatching {
    val request = ImageRequest.Builder(context)
        .data(uri)
        .size(32, 32)
        .allowHardware(false)
        .build()

    val result = context.imageLoader.execute(request).drawable as? BitmapDrawable ?: return null
    val bitmap = result.bitmap ?: return null

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
    var maxSaturation = -1f
    val hsv = FloatArray(3)

    for (pixel in pixels) {
        val r = android.graphics.Color.red(pixel)
        val g = android.graphics.Color.green(pixel)
        val b = android.graphics.Color.blue(pixel)

        // Fast reject neutral or low-contrast pixels without expensive RGBToHSV conversion
        val maxVal = maxOf(r, maxOf(g, b))
        val minVal = minOf(r, minOf(g, b))
        if (maxVal - minVal < 36 || maxVal < 60) continue

        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val saturation = hsv[1]
        val value = hsv[2]

        // Reject dim or washed out colors
        if (value > 0.35f && saturation > 0.40f) {
            if (saturation > maxSaturation) {
                maxSaturation = saturation
                // Boost brightness slightly so neon pop glows cleanly against pitch black terminal
                hsv[2] = maxOf(value, 0.85f)
                bestColor = Color(android.graphics.Color.HSVToColor(hsv))
            }
        }
    }

    return bestColor
}

