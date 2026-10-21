package com.necroware.terminusplayer.ui.components

import com.necroware.terminusplayer.coil.LocalAudioUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerAndArtTest {
    private val distance = 100f
    private val fling = 1000f

    @Test
    fun swipeCommitsPastTheDistanceInEitherDirection() {
        assertTrue(shouldCommitSwipeSkip(-100f, 0f, distance, fling))
        assertTrue(shouldCommitSwipeSkip(120f, 0f, distance, fling))
    }

    @Test
    fun shortSlowSwipeSnapsBack() {
        assertFalse(shouldCommitSwipeSkip(-60f, -200f, distance, fling))
    }

    @Test
    fun fastFlingCommitsWithLessTravelOnlyInItsOwnDirection() {
        assertTrue(shouldCommitSwipeSkip(-40f, -1500f, distance, fling))
        // Flung back against the drag: no skip.
        assertFalse(shouldCommitSwipeSkip(-40f, 1500f, distance, fling))
        // A tiny flick below a third of the distance is not a skip.
        assertFalse(shouldCommitSwipeSkip(-20f, -1500f, distance, fling))
    }

    @Test
    fun artModelKeepsTheExistingUriMapping() {
        assertNull(songArtModel(""))
        assertNull(songArtModel("https://www.youtube.com/watch?v=abc"))
        assertNull(songArtModel("http://www.youtube.com/watch?v=abc"))
        assertNull(songArtModel("https://youtu.be/abc"))
        assertEquals("https://example.com/a.jpg", songArtModel("https://example.com/a.jpg"))
        assertEquals(
            "https://terminus.invalid/rest/getCoverArt?id=42&v=1.16.1&c=Terminus",
            songArtModel("terminus://42")
        )
        assertEquals(LocalAudioUri("content://media/1"), songArtModel("content://media/1"))
    }
}
