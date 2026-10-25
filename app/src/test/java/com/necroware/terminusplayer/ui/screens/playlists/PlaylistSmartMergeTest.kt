package com.necroware.terminusplayer.ui.screens.playlists

import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.buildPlaylistDownloadPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSmartMergeTest {

    private fun sampleSong(
        id: String,
        provider: String = "local",
        navidromeId: String? = null,
        downloadedUri: String? = null
    ): Song {
        return Song(
            id = id,
            providerId = provider,
            providerRemoteId = id,
            title = "Track $id",
            artist = "Artist A",
            album = "Album B",
            albumId = "alb-$id",
            duration = 180_000L,
            uriString = "content://music/$id",
            navidromeId = navidromeId,
            downloadedUri = downloadedUri
        )
    }

    @Test
    fun mergeServerAndLocalSongs_showsAll700ServerSongsWhenLocalTracksWereDeleted() {
        // User had 700 songs on server, but deleted 50 locally (leaving 650 local)
        val localCached = (1..650).map {
            sampleSong(
                id = "song-$it",
                provider = "navidrome",
                downloadedUri = "/storage/emulated/0/Music/song-$it.mp3"
            )
        }
        val serverSongs = (1..700).map {
            sampleSong(
                id = "song-$it",
                provider = "navidrome"
            )
        }

        val merged = mergeServerAndLocalSongs(localCached, serverSongs)

        // All 700 server songs are present!
        assertEquals(700, merged.size)

        // Matching songs have their local downloadedUri preserved
        val song1 = merged.first { it.id == "song-1" }
        assertEquals("/storage/emulated/0/Music/song-1.mp3", song1.downloadedUri)

        // Deleted local song 680 is still present from server
        val song680 = merged.firstOrNull { it.providerRemoteId == "song-680" }
        assertNotNull(song680)
    }

    @Test
    fun mergeServerAndLocalSongs_preservesLocalOnlyAddedTracks() {
        val serverSongs = listOf(
            sampleSong("srv-1", "navidrome"),
            sampleSong("srv-2", "navidrome")
        )
        val localCached = listOf(
            sampleSong("srv-1", "navidrome"),
            sampleSong("local-track-99", "local")
        )

        val merged = mergeServerAndLocalSongs(localCached, serverSongs)

        assertEquals(3, merged.size)
        assertTrue(merged.any { it.id == "local-track-99" })
    }

    @Test
    fun downloadPlan_hasZeroUnresolvedTracks_preventingServerEntriesError() {
        // Test that passing expectedTrackCount = songs.size produces unresolvedTrackCount = 0
        val songs = (1..700).map { sampleSong("song-$it", "navidrome") }
        val offlineIds = (1..650).map { "song-$it" }.toSet()

        val plan = buildPlaylistDownloadPlan(
            songs = songs,
            offlineSongIds = offlineIds,
            unavailableSongIds = emptySet(),
            expectedTrackCount = songs.size
        )

        assertEquals(0, plan.unresolvedTrackCount)
        assertEquals(50, plan.downloadableSongs.size)
        assertEquals(650, plan.alreadyOfflineCount)
    }
}
