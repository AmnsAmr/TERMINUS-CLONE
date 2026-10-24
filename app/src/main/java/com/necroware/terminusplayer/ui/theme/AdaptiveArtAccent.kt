package com.necroware.terminusplayer.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.PowerManager
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.necroware.terminusplayer.coil.LocalAudioUri
import com.necroware.terminusplayer.data.prefs.PerformanceMode
import com.necroware.terminusplayer.data.prefs.shouldDeferOptionalWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val accentCache = LruCache<String, Color>(128)

/**
 * Resolves artwork URI into a format understandable by Terminus Coil fetchers.
 */
private fun resolveArtModel(uriString: String): Any? {
    if (uriString.isBlank() || uriString.startsWith("https://www.youtube.com/watch") || uriString.startsWith("http://www.youtube.com/watch") || uriString.startsWith("https://youtu.be/")) return null
    return if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
        uriString
    } else if (uriString.startsWith("terminus://")) {
        val songId = uriString.removePrefix("terminus://")
        "https://terminus.invalid/rest/getCoverArt?id=$songId&v=1.16.1&c=Terminus"
    } else {
        LocalAudioUri(uriString)
    }
}

/**
 * Extracts the accurate majority/dominant accent color from the active album cover art using
 * AndroidX Palette population analysis.
 *
 * Connects with [performanceMode]:
 * - Automatically active in [PerformanceMode.FULL] and [PerformanceMode.BALANCED].
 * - Automatically deferred in [PerformanceMode.BATTERY_SAVER] (and [PerformanceMode.AUTO] under Android Battery Saver).
 *
 * Returns [defaultAccent] immediately if disabled, no artwork exists, or in battery saver mode,
 * and when a cover yields no colour. While a new cover is still being read it returns the
 * previous cover's colour.
 */
@Composable
fun rememberAdaptiveAccent(
    artworkUri: String?,
    defaultAccent: Color,
    enabled: Boolean,
    performanceMode: PerformanceMode = PerformanceMode.FULL
): Color {
    val context = LocalContext.current
    val powerManager = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val isSystemPowerSaving = powerManager?.isPowerSaveMode == true
    val isAllowedByPerformance = !shouldDeferOptionalWork(performanceMode, isSystemPowerSaving)

    val activeUri = artworkUri?.takeIf { enabled && isAllowedByPerformance && it.isNotBlank() }
    val cached = activeUri?.let { accentCache.get(it) }

    // Always composed, so it outlives track changes: while a new cover's colour is being
    // extracted it keeps the previous track's colour instead of dropping to the default,
    // which made the accent blend old -> default -> new on every uncached skip.
    val lastAccent by produceState<Color?>(initialValue = null, key1 = activeUri) {
        when {
            activeUri == null -> value = null
            cached != null -> value = cached
            else -> {
                val extracted = withContext(Dispatchers.IO) {
                    extractDominantAccent(context, activeUri)
                }
                if (extracted != null) accentCache.put(activeUri, extracted)
                value = extracted
            }
        }
    }

    return when {
        activeUri == null -> defaultAccent
        cached != null -> cached
        else -> lastAccent ?: defaultAccent
    }
}

/**
 * [rememberAdaptiveAccent] with color changes blended over [animationSpec] instead of snapping.
 * Compute it once per player surface and share the result.
 */
@Composable
fun rememberAnimatedAdaptiveAccent(
    artworkUri: String?,
    defaultAccent: Color,
    enabled: Boolean,
    performanceMode: PerformanceMode,
    animationSpec: AnimationSpec<Color>
): Color {
    val target = rememberAdaptiveAccent(artworkUri, defaultAccent, enabled, performanceMode)
    return animateColorAsState(target, animationSpec, label = "adaptiveAccent").value
}

private suspend fun extractDominantAccent(context: Context, uri: String): Color? = runCatching {
    val model = resolveArtModel(uri) ?: return null
    val request = ImageRequest.Builder(context)
        .data(model)
        .size(128, 128)
        .allowHardware(false)
        .build()

    val drawable = context.imageLoader.execute(request).drawable ?: return null
    val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap(128, 128)

    withContext(Dispatchers.Default) {
        findDominantArtworkColor(bitmap)
    }
}.getOrNull()

/**
 * Uses AndroidX Palette quantization to find the true majority color in the artwork.
 * Evaluates swatches by population (pixel count) to find the dominant color that represents
 * the artwork while ensuring legibility against the deep terminal black background.
 */
private fun findDominantArtworkColor(bitmap: Bitmap): Color? {
    val palette = Palette.from(bitmap)
        .maximumColorCount(24)
        .clearFilters()
        .generate()

    val swatches = palette.swatches
    if (swatches.isEmpty()) return null

    // Sort swatches by pixel population (majority of the pic)
    val sortedByPopulation = swatches.sortedByDescending { it.population }
    val hsv = FloatArray(3)

    // 1. Look for the most populated chromatic swatch (true majority color)
    for (swatch in sortedByPopulation) {
        android.graphics.Color.colorToHSV(swatch.rgb, hsv)
        val saturation = hsv[1]
        val value = hsv[2]

        // Skip pitch black / near-black backgrounds that would blend into the terminal background
        if (value < 0.18f && saturation < 0.20f) continue

        // Skip near-white/gray if there are colorful swatches with meaningful population
        if (saturation < 0.12f && sortedByPopulation.any {
            val otherHsv = FloatArray(3)
            android.graphics.Color.colorToHSV(it.rgb, otherHsv)
            otherHsv[1] >= 0.25f && it.population > (swatch.population * 0.15f)
        }) {
            continue
        }

        // Preserve the exact hue and natural saturation; ensure brightness is clear against terminal black
        val adjustedValue = value.coerceIn(0.65f, 0.96f)
        val adjustedSaturation = if (saturation > 0.05f) saturation.coerceAtLeast(0.35f) else saturation
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], adjustedSaturation, adjustedValue)))
    }

    // 2. Fallback to dominantSwatch with brightness tuning
    val dominant = palette.dominantSwatch ?: sortedByPopulation.firstOrNull() ?: return null
    android.graphics.Color.colorToHSV(dominant.rgb, hsv)
    val fallbackValue = hsv[2].coerceIn(0.65f, 0.96f)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], fallbackValue)))
}
