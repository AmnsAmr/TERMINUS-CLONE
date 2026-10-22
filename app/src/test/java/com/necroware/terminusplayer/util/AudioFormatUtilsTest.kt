package com.necroware.terminusplayer.util

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFormatUtilsTest {

    @Test
    fun mimeToCodecLabelMapsCommonMimeTypesCorrectly() {
        // Standard formats
        assertEquals("MP3", mimeToCodecLabel("audio/mpeg"))
        assertEquals("MP3", mimeToCodecLabel("audio/mp3"))
        assertEquals("AAC", mimeToCodecLabel("audio/aac"))
        assertEquals("AAC", mimeToCodecLabel("audio/mp4a-latm"))
        assertEquals("FLAC", mimeToCodecLabel("audio/flac"))
        assertEquals("FLAC", mimeToCodecLabel("audio/x-flac"))
        assertEquals("Opus", mimeToCodecLabel("audio/opus"))
        assertEquals("Opus", mimeToCodecLabel("audio/ogg; codecs=opus"))
        assertEquals("Vorbis", mimeToCodecLabel("audio/vorbis"))
        assertEquals("Vorbis", mimeToCodecLabel("audio/ogg"))
        assertEquals("ALAC", mimeToCodecLabel("audio/alac"))
        assertEquals("WAV", mimeToCodecLabel("audio/wav"))
        assertEquals("WAV", mimeToCodecLabel("audio/x-wav"))
        assertEquals("WAV", mimeToCodecLabel("audio/raw"))
        assertEquals("WAV", mimeToCodecLabel("audio/x-adpcm"))

        // Unrecognized MIME types fall back to uppercase subtype
        assertEquals("AMR-WB", mimeToCodecLabel("audio/amr-wb"))

        // Null, blank, and dash values map gracefully to "—"
        assertEquals("—", mimeToCodecLabel(null))
        assertEquals("—", mimeToCodecLabel(""))
        assertEquals("—", mimeToCodecLabel("   "))
        assertEquals("—", mimeToCodecLabel("—"))
    }

    @Test
    fun buildFormatStringAssemblesAvailableComponentsCleanly() {
        // All fields present
        val full = buildFormatString(
            codec = "FLAC",
            sampleRate = "44.1 kHz",
            channelCount = 2,
            bitDepth = "24"
        )
        assertEquals("FLAC · 44.1 kHz · 2ch · 24-bit", full)

        // Bit depth already has "-bit" suffix
        val withSuffix = buildFormatString(
            codec = "FLAC",
            sampleRate = "96.0 kHz",
            channelCount = 2,
            bitDepth = "24-bit"
        )
        assertEquals("FLAC · 96.0 kHz · 2ch · 24-bit", withSuffix)

        // Mono track without bit depth
        val monoLossy = buildFormatString(
            codec = "MP3",
            sampleRate = "44.1 kHz",
            channelCount = 1,
            bitDepth = null
        )
        assertEquals("MP3 · 44.1 kHz · 1ch", monoLossy)

        // 5.1 surround channel formatting
        val surround = buildFormatString(
            codec = "Vorbis",
            sampleRate = "48.0 kHz",
            channelCount = 6,
            bitDepth = "16"
        )
        assertEquals("Vorbis · 48.0 kHz · 5.1ch · 16-bit", surround)

        // Codec only
        val codecOnly = buildFormatString(codec = "Opus")
        assertEquals("Opus", codecOnly)

        // Missing / placeholder values are omitted without breaking
        val partialWithDashes = buildFormatString(
            codec = "AAC",
            sampleRate = "—",
            channelCount = 0,
            bitDepth = "—"
        )
        assertEquals("AAC", partialWithDashes)

        // Completely empty or unknown values return "—"
        assertEquals("—", buildFormatString(null, null, null, null))
        assertEquals("—", buildFormatString("—", "—", -1, "—"))
        assertEquals("—", buildFormatString("", "", 0, ""))
    }

    @Test
    fun buildFormatStringFromMedia3Format() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_OPUS)
            .setSampleRate(48000)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()

        assertEquals("Opus", format.toCodecLabel())
        assertEquals("48.0 kHz", format.toSampleRateLabel())
        assertEquals("Opus · 48.0 kHz · 2ch · 16-bit", buildFormatString(format))

        // Null format produces "—"
        assertEquals("—", buildFormatString(null as Format?))
    }
}
