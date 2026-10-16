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
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val accentCache = LruCache<String, Color>(32)

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
    val loader = ImageLoader(context)
    val request = ImageRequest.Builder(context)
        .data(uri)
        .size(64, 64)
        .allowHardware(false)
        .build()

    val result = loader.execute(request).drawable as? BitmapDrawable ?: return null
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
        android.graphics.Color.RGBToHSV(r, g, b, hsv)

        val saturation = hsv[1]
        val value = hsv[2]

        // Reject almost black, almost white, or very desaturated pixels
        if (value > 0.35f && saturation > 0.40f) {
            if (saturation > maxSaturation) {
                maxSaturation = saturation
                // Boost brightness slightly so the neon pop glows cleanly against the pitch black terminal
                hsv[2] = maxOf(value, 0.85f)
                val neonInt = android.graphics.Color.HSVToColor(hsv)
                bestColor = Color(neonInt)
            }
        }
    }

    return bestColor
}
