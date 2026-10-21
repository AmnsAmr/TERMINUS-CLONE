package com.necroware.terminusplayer.util

import android.webkit.MimeTypeMap

object DownloadFormatUtils {

    /**
     * Resolves the actual file extension and MIME type from the response headers.
     * Matches the actual response rather than assuming a requested format.
     */
    fun resolveFormatFromResponse(
        contentTypeHeader: String?,
        contentDispositionHeader: String?,
        fallbackExtension: String = "mp3"
    ): Pair<String, String> {
        val dispositionFilename = contentDispositionHeader?.let { parseContentDispositionFileName(it) }
        val dispositionExt = dispositionFilename?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.matches(Regex("^[a-z0-9]{2,5}$")) }

        val cleanMime = contentTypeHeader?.substringBefore(';')?.trim()?.lowercase()
        val hasKnownMime = cleanMime != null && (cleanMime.startsWith("audio/") || cleanMime == "application/ogg")

        val effectiveExt = dispositionExt ?: if (hasKnownMime) {
            when (cleanMime) {
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
                "audio/webm" -> "webm"
                "audio/ogg", "application/ogg", "audio/opus", "audio/x-opus+ogg" -> "opus"
                "audio/aac", "audio/aacp", "audio/x-aac" -> "aac"
                "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
                else -> runCatching {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime!!)
                }.getOrNull() ?: fallbackExtension
            }
        } else {
            fallbackExtension
        }

        val effectiveMime = when {
            hasKnownMime -> cleanMime!!
            else -> when (effectiveExt.lowercase()) {
                "flac" -> "audio/flac"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "webm" -> "audio/webm"
                "opus", "ogg" -> "audio/ogg"
                "aac" -> "audio/aac"
                "wav" -> "audio/wav"
                else -> runCatching {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(effectiveExt.lowercase())
                }.getOrNull() ?: "audio/mpeg"
            }
        }

        return Pair(effectiveExt, effectiveMime)
    }

    fun parseContentDispositionFileName(header: String): String? {
        val regex = Regex("""filename\*?=['"]?(?:UTF-\d['"]*)?([^'";\n]+)['"]?""", RegexOption.IGNORE_CASE)
        return regex.find(header)?.groupValues?.get(1)?.trim()
    }

    /**
     * Determines whether an error response from Navidrome / Subsonic streaming indicates
     * that transcoding or downsampling failed or is unsupported on the server, rather than
     * auth failure or missing track.
     */
    fun isTranscodingFailure(httpStatusCode: Int, errorBodyStr: String?): Boolean {
        // Auth (401, 403) or Not Found (404) are NOT transcoding failures
        if (httpStatusCode in 401..403 || httpStatusCode == 404) return false

        // 501 Not Implemented specifically denotes unsupported transcoding feature
        if (httpStatusCode == 501) return true

        val body = errorBodyStr.orEmpty().lowercase()

        // Check if error body mentions transcoding/codec/ffmpeg/downsampling
        if (body.contains("transcod") || body.contains("ffmpeg") || body.contains("codec") ||
            body.contains("bitrate") || body.contains("downsampl")) {
            return true
        }

        // Subsonic JSON or XML error checks
        if (body.contains("\"status\":\"failed\"") || body.contains("status=\"failed\"")) {
            // Auth error code 40 or Not Found error code 70
            if (body.contains("\"code\":40") || body.contains("code=\"40\"") ||
                body.contains("\"code\":70") || body.contains("code=\"70\"") ||
                body.contains("not found") || body.contains("unauthorized")) {
                return false
            }
            return true
        }

        return false
    }

    fun buildDownloadFileName(songTitle: String, artist: String, folderPath: String, extension: String): String {
        val base = folderPath.substringAfterLast('/').substringBeforeLast('.', "")
            .ifBlank { "$artist - $songTitle" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(120)
        return "$base.$extension"
    }
}
