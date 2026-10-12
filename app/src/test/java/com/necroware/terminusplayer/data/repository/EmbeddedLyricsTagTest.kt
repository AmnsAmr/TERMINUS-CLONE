package com.necroware.terminusplayer.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EmbeddedLyricsTagTest {
    @Test
    fun readsFlacVorbisCommentLyrics() {
        val file = resourceFile("audio/embedded-lyrics.flac")
        val lyrics = readEmbeddedLyricsTag(file)!!
        assertEquals(1_200L, lyrics.lines.single().startMs)
        assertEquals("Embedded line", lyrics.lines.single().text)
    }

    @Test
    fun readsM4aMp4LyricsAtom() {
        val file = resourceFile("audio/embedded-lyrics.m4a")
        val lyrics = readEmbeddedLyricsTag(file)!!
        assertEquals(1_200L, lyrics.lines.single().startMs)
        assertEquals("Embedded line", lyrics.lines.single().text)
    }

    private fun resourceFile(path: String): File {
        val url = javaClass.classLoader!!.getResource(path) ?: error("Missing test fixture: $path")
        return File(url.toURI())
    }
}
