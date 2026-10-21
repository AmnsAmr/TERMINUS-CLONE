package com.necroware.terminusplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFormatUtilsTest {

    @Test
    fun resolvesAudioFlacMimeAndExtension() {
        val (ext, mime) = DownloadFormatUtils.resolveFormatFromResponse(
            contentTypeHeader = "audio/flac",
            contentDispositionHeader = null
        )
        assertEquals("flac", ext)
        assertEquals("audio/flac", mime)
    }

    @Test
    fun resolvesAudioMpegMimeAndExtension() {
        val (ext, mime) = DownloadFormatUtils.resolveFormatFromResponse(
            contentTypeHeader = "audio/mpeg",
            contentDispositionHeader = null
        )
        assertEquals("mp3", ext)
        assertEquals("audio/mpeg", mime)
    }

    @Test
    fun resolvesAudioWebmMimeAndExtension() {
        val (ext, mime) = DownloadFormatUtils.resolveFormatFromResponse(
            contentTypeHeader = "audio/webm; codecs=\"opus\"",
            contentDispositionHeader = null
        )
        assertEquals("webm", ext)
        assertEquals("audio/webm", mime)
    }

    @Test
    fun resolvesAudioMp4MimeAndExtension() {
        val (ext, mime) = DownloadFormatUtils.resolveFormatFromResponse(
            contentTypeHeader = "audio/mp4",
            contentDispositionHeader = null
        )
        assertEquals("m4a", ext)
        assertEquals("audio/mp4", mime)
    }

    @Test
    fun resolvesAudioOggMimeAndExtension() {
        val (ext, mime) = DownloadFormatUtils.resolveFormatFromResponse(
            contentTypeHeader = "audio/ogg",
            contentDispositionHeader = null
        )
        assertEquals("opus", ext)
        assertEquals("audio/ogg", mime)
    }

    @Test
    fun resolvesExtensionFromContentDisposition() {
        val (ext, mime) = DownloadFormatUtils.resolveFormatFromResponse(
            contentTypeHeader = "application/octet-stream",
            contentDispositionHeader = "attachment; filename=\"custom_recording.flac\""
        )
        assertEquals("flac", ext)
        assertEquals("audio/flac", mime)
    }

    @Test
    fun resolvesExtensionFromContentDispositionUtf8() {
        val (ext, mime) = DownloadFormatUtils.resolveFormatFromResponse(
            contentTypeHeader = "application/octet-stream",
            contentDispositionHeader = "attachment; filename*=UTF-8''Track%20Name.opus"
        )
        assertEquals("opus", ext)
        assertEquals("audio/ogg", mime)
    }

    @Test
    fun fallsBackWhenHeadersAreAmbiguous() {
        val (ext, mime) = DownloadFormatUtils.resolveFormatFromResponse(
            contentTypeHeader = "application/octet-stream",
            contentDispositionHeader = null,
            fallbackExtension = "m4a"
        )
        assertEquals("m4a", ext)
        assertEquals("audio/mp4", mime)
    }

    @Test
    fun detectsTranscodingFailureOn501() {
        assertTrue(DownloadFormatUtils.isTranscodingFailure(501, null))
    }

    @Test
    fun detectsTranscodingFailureOn500WithFfmpegError() {
        assertTrue(DownloadFormatUtils.isTranscodingFailure(500, "Error executing ffmpeg command"))
        assertTrue(DownloadFormatUtils.isTranscodingFailure(500, "Failed to transcode stream"))
        assertTrue(DownloadFormatUtils.isTranscodingFailure(500, "Unsupported bitrate conversion"))
    }

    @Test
    fun detectsTranscodingFailureOnSubsonicJsonError() {
        val body = """{"subsonic-response":{"status":"failed","error":{"code":0,"message":"transcoding failed"}}}"""
        assertTrue(DownloadFormatUtils.isTranscodingFailure(200, body))
    }

    @Test
    fun doesNotTreatAuthErrorsAsTranscodingFailures() {
        assertFalse(DownloadFormatUtils.isTranscodingFailure(401, "Unauthorized"))
        assertFalse(DownloadFormatUtils.isTranscodingFailure(403, "Forbidden"))
        val subsonicAuthError = """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}"""
        assertFalse(DownloadFormatUtils.isTranscodingFailure(200, subsonicAuthError))
    }

    @Test
    fun doesNotTreatNotFoundAsTranscodingFailures() {
        assertFalse(DownloadFormatUtils.isTranscodingFailure(404, "Not Found"))
        val subsonicNotFound = """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"The requested data was not found"}}}"""
        assertFalse(DownloadFormatUtils.isTranscodingFailure(200, subsonicNotFound))
    }

    @Test
    fun buildDownloadFileNameSanitizesInvalidChars() {
        val name = DownloadFormatUtils.buildDownloadFileName(
            songTitle = "Track: Title - Sub? *",
            artist = "Artist <Name>",
            folderPath = "Music/Artist <Name>/Track: Title - Sub? *.flac",
            extension = "mp3"
        )
        assertEquals("Track_ Title - Sub_ _.mp3", name)
        assertFalse(name.contains(":"))
        assertFalse(name.contains("?"))
        assertFalse(name.contains("*"))
    }

    @Test
    fun buildDownloadFileNameFallsBackToArtistAndTitleWhenFolderPathEmpty() {
        val name = DownloadFormatUtils.buildDownloadFileName(
            songTitle = "Great Song: Remastered / 2026",
            artist = "Best / Artist",
            folderPath = "",
            extension = "m4a"
        )
        assertEquals("Best _ Artist - Great Song_ Remastered _ 2026.m4a", name)
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
    }
}
