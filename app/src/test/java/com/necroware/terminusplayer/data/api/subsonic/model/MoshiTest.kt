package com.necroware.terminusplayer.data.api.subsonic.model

import com.squareup.moshi.Moshi
import org.junit.Test
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals

class MoshiTest {
    @Test
    fun testParse() {
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(SubsonicResponse::class.java)

        val json = """
        {
            "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "lyricsList": {
                    "error": {
                        "code": 70,
                        "message": "Lyrics not found"
                    }
                }
            }
        }
        """.trimIndent()
        
        val response = adapter.fromJson(json)
        assertNotNull(response)
    }

    @Test
    fun parsesServerPlaylistListAndTrackEntries() {
        val adapter = Moshi.Builder().build().adapter(SubsonicResponse::class.java)
        val response = adapter.fromJson(
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "playlists": { "playlist": [{ "id": "p-1", "name": "Outside", "songCount": 2 }] },
                "playlist": {
                  "id": "p-1",
                  "name": "Outside",
                  "entry": [
                    { "id": "song-1", "title": "Track 1", "artist": "Artist", "duration": 180 },
                    { "id": "song-2", "title": "Track 2" }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(2, response?.response?.playlists?.items?.firstOrNull()?.songCount)
        assertEquals("song-1", response?.response?.playlist?.entries?.firstOrNull()?.id)
        assertEquals("Artist", response?.response?.playlist?.entries?.firstOrNull()?.artist)
    }

    @Test
    fun parsesStarredSongsWithTheirNavidromeIds() {
        val adapter = Moshi.Builder().build().adapter(SubsonicResponse::class.java)
        val response = adapter.fromJson(
            """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "starred2": {
                  "song": [
                    { "id": "server-song-7", "title": "Favorite", "artist": "Artist", "album": "Album" }
                  ],
                  "album": [{ "id": "album-1", "name": "Album" }]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("server-song-7", response?.response?.starred2?.songs?.singleOrNull()?.id)
        assertEquals("Favorite", response?.response?.starred2?.songs?.singleOrNull()?.title)
    }

    @Test
    fun parsesSubsonicApiLevelFailuresReturnedWithJson() {
        val adapter = Moshi.Builder().build().adapter(SubsonicResponse::class.java)
        val response = adapter.fromJson(
            """
            {
              "subsonic-response": {
                "status": "failed",
                "version": "1.16.1",
                "error": { "code": 40, "message": "Wrong username or password" }
              }
            }
            """.trimIndent()
        )

        assertEquals("failed", response?.response?.status)
        assertEquals(40, response?.response?.error?.code)
        assertEquals("Wrong username or password", response?.response?.error?.message)
    }
}
