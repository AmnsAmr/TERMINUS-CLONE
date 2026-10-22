package com.necroware.terminusplayer.util

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.util.Locale

private val LOSSLESS_MIME_HINTS = listOf("flac", "wav", "x-adpcm", "alac", "raw")

/** Maps common audio MIME types to clean, human-readable labels (e.g. MP3, AAC, FLAC, Opus, Vorbis, ALAC, WAV). */
fun mimeToCodecLabel(mime: String?): String {
    if (mime.isNullOrBlank() || mime == "—") return "—"
    val lower = mime.lowercase()
    return when {
        lower.contains("flac") -> "FLAC"
        lower.contains("wav") || lower.contains("wave") || lower.contains("x-adpcm") || lower.contains("raw") -> "WAV"
        lower.contains("alac") -> "ALAC"
        lower.contains("mpeg") || lower.contains("mp3") -> "MP3"
        lower.contains("aac") || lower.contains("mp4a") -> "AAC"
        lower.contains("opus") -> "Opus"
        lower.contains("vorbis") -> "Vorbis"
        lower.contains("ogg") -> "Vorbis"
        else -> {
            val sub = lower.substringAfterLast('/').trim()
            if (sub.isNotBlank()) sub.uppercase(Locale.US) else "—"
        }
    }
}

/** Builds a readable stream format string from track components (e.g. "FLAC · 44.1 kHz · 2ch · 24-bit"). */
fun buildFormatString(
    codec: String?,
    sampleRate: String? = null,
    channelCount: Int? = null,
    bitDepth: String? = null
): String {
    val parts = mutableListOf<String>()

    val cleanCodec = codec?.trim()?.takeIf { it.isNotEmpty() && it != "—" }
    if (cleanCodec != null) parts += cleanCodec

    val cleanRate = sampleRate?.trim()?.takeIf { it.isNotEmpty() && it != "—" }
    if (cleanRate != null) parts += cleanRate

    if (channelCount != null && channelCount > 0) {
        parts += when (channelCount) {
            1 -> "1ch"
            2 -> "2ch"
            6 -> "5.1ch"
            else -> "${channelCount}ch"
        }
    }

    val cleanDepth = bitDepth?.trim()?.takeIf { it.isNotEmpty() && it != "—" }
    if (cleanDepth != null) {
        parts += if (cleanDepth.endsWith("-bit", ignoreCase = true) || cleanDepth.endsWith("bit", ignoreCase = true)) {
            cleanDepth
        } else {
            "$cleanDepth-bit"
        }
    }

    return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
}

/** Builds a readable stream format string directly from a Media3 [Format]. */
fun buildFormatString(format: Format?): String {
    if (format == null) return "—"
    val codec = format.toCodecLabel().takeIf { it != "—" }
    val rate = if (format.sampleRate > 0) format.toSampleRateLabel() else null
    val channels = format.channelCount.takeIf { it > 0 }
    val depth = format.toBitDepthLabel().takeIf { it != "—" }
    return buildFormatString(codec, rate, channels, depth)
}

/** e.g. "FLAC 96kHz", "MP3 44kHz", or "—" if nothing is resolvable yet. */
fun Format.toAudioFormatLabel(): String {
    val khz = if (sampleRate > 0) "${sampleRate / 1000}kHz" else null
    return listOfNotNull(toCodecLabel(), khz).joinToString(" ").ifBlank { "—" }
}

/** e.g. "MP3", "FLAC", "Opus" — just the codec, no sample rate. */
fun Format.toCodecLabel(): String =
    mimeToCodecLabel(sampleMimeType ?: containerMimeType)

/** e.g. "44.1 kHz" — just the sample rate, no codec. */
fun Format.toSampleRateLabel(): String =
    if (sampleRate > 0) String.format(Locale.US, "%.1f kHz", sampleRate / 1000f) else "—"

fun Format.isLosslessFormat(): Boolean {
    val mime = (sampleMimeType ?: containerMimeType)?.lowercase().orEmpty()
    return LOSSLESS_MIME_HINTS.any { mime.contains(it) }
}

/**
 * e.g. "16" or "24". Format.pcmEncoding is only actually populated for raw
 * PCM sources (WAV) — for every compressed codec (MP3/AAC/OPUS/most FLAC as
 * seen here) Android's decoders output 16-bit PCM regardless of source
 * depth, so that's what's reported rather than a source bit depth we have
 * no reliable way to read from a compressed Format.
 */
@OptIn(UnstableApi::class)
fun Format.toBitDepthLabel(): String = when (pcmEncoding) {
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32"
    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24"
    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16"
    else -> "16"
}

/** Average bitrate in kbps from the container's own Format field, or -1 if unknown. */
@OptIn(UnstableApi::class)
fun Format.bitrateKbpsOrNegative(): Int = if (bitrate != Format.NO_VALUE && bitrate > 0) bitrate / 1000 else -1

/**
 * Falls back to size/duration when the container didn't expose a bitrate
 * (common for some FLAC streams) — same arithmetic any player uses to
 * estimate it: bytes * 8 / seconds.
 */
fun estimateKbpsFromSize(sizeBytes: Long, durationMs: Long): Int {
    if (sizeBytes <= 0L || durationMs <= 0L) return -1
    val seconds = durationMs / 1000.0
    if (seconds <= 0.0) return -1
    return ((sizeBytes * 8L) / seconds / 1000.0).toInt()
}

/** e.g. "5.7mb", or "—" if unknown. */
fun sizeLabelFromBytes(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "—"
    val mb = sizeBytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1fmb", mb)
}

/** Selected audio track's Format from the current Tracks, if any. */
fun androidx.media3.common.Tracks.selectedAudioFormat(): Format? {
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            if (group.isTrackSelected(i)) return group.getTrackFormat(i)
        }
    }
    return null
}
