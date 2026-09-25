package com.necroware.terminusplayer.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavidromeUrlTest {
    @Test
    fun acceptsHostAndPreservesServerBasePath() {
        val url = parseNavidromeBaseUrl("music.example.test/navidrome/")

        assertEquals("http://music.example.test/navidrome/", url.toString())
    }

    @Test
    fun rejectsMissingOrUnsafeServerConfiguration() {
        assertNull(parseNavidromeBaseUrl(""))
        assertNull(parseNavidromeBaseUrl("https://user:password@example.test"))
        assertNull(parseNavidromeBaseUrl("https://example.test?redirect=elsewhere"))
        assertNull(parseNavidromeBaseUrl("file:///tmp/music"))
    }
}
