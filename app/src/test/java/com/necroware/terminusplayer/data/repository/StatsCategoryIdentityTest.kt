package com.necroware.terminusplayer.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StatsCategoryIdentityTest {
    @Test
    fun sameTitledSongsHaveDistinctLazyListKeys() {
        val firstSong = topSongCategoryItem("provider-a:track-1", "Intro", 12)
        val secondSong = topSongCategoryItem("provider-b:track-9", "Intro", 8)

        assertEquals(firstSong.label, secondSong.label)
        assertNotEquals(firstSong.id, secondSong.id)
    }
}
