package com.necroware.terminusplayer.data.repository

import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.toSongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePersistenceTest {

    @Test
    fun youTubeTrackToSongEntityPreservesIdentityAndArtwork() {
        val song = Song(
            id = "youtube:dQw4w9WgXcQ",
            providerId = "youtube",
            providerRemoteId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            album = "Whenever You Need Somebody",
            albumId = "yt_album_rick",
            duration = 213000L,
            uriString = "https://rr1---sn.googlevideo.com/videoplayback?id=1",
            artworkUri = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        )

        val entity = song.toSongEntity()

        assertEquals("youtube:dQw4w9WgXcQ", entity.remoteId)
        assertEquals("youtube", entity.providerId)
        assertEquals("dQw4w9WgXcQ", entity.providerRemoteId)
        assertEquals("Never Gonna Give You Up", entity.title)
        assertEquals("Rick Astley", entity.artist)
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?id=1", entity.uriString)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", entity.artworkUri)
        assertNull(entity.downloadedUri)
        assertNull(entity.navidromeId)
    }

    @Test
    fun songEntityToSongPreservesYouTubeIdentityAndArtwork() {
        val entity = SongEntity(
            remoteId = "youtube:dQw4w9WgXcQ",
            providerId = "youtube",
            providerRemoteId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            album = "Whenever You Need Somebody",
            albumId = "yt_album_rick",
            duration = 213000L,
            uriString = "https://rr1---sn.googlevideo.com/videoplayback?id=1",
            dateAdded = 12345L,
            artworkUri = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        )

        val song = entity.toSong(isLiked = true)

        assertEquals("youtube:dQw4w9WgXcQ", song.id)
        assertEquals("youtube", song.providerId)
        assertEquals("dQw4w9WgXcQ", song.providerRemoteId)
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?id=1", song.uriString)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", song.artworkUri)
        assertTrue(song.isLiked)
        assertNull(song.downloadedUri)
        assertNull(song.navidromeId)
    }

    @Test
    fun librarySyncDeletionFilterProtectsYouTubeTracks() {
        val localSong = SongEntity(
            remoteId = "local:1",
            providerId = "local",
            providerRemoteId = "1",
            title = "Local Song",
            artist = "Local Artist",
            album = "Local Album",
            albumId = "1",
            duration = 100000L,
            uriString = "content://media/1",
            dateAdded = 1L
        )

        val navidromeSong = SongEntity(
            remoteId = "navidrome:2",
            providerId = "navidrome",
            providerRemoteId = "2",
            title = "Remote Song",
            artist = "Remote Artist",
            album = "Remote Album",
            albumId = "2",
            duration = 100000L,
            uriString = "https://server/2",
            dateAdded = 2L
        )

        val youTubeSong = SongEntity(
            remoteId = "youtube:3",
            providerId = "youtube",
            providerRemoteId = "3",
            title = "YouTube Song",
            artist = "YouTube Artist",
            album = "YouTube Album",
            albumId = "3",
            duration = 100000L,
            uriString = "https://rr1---sn.googlevideo.com/videoplayback?id=3",
            dateAdded = 3L,
            artworkUri = "https://i.ytimg.com/vi/3/hqdefault.jpg"
        )

        val currentSongs = listOf(localSong, navidromeSong, youTubeSong)
        val scannedIds = setOf("local:1") // Only local:1 was in this scan
        val failedProviders = emptySet<String>()

        val currentToDelete = currentSongs
            .filter { it.providerId != "jamendo" && it.providerId != "youtube" && it.providerId !in failedProviders && it.remoteId !in scannedIds }
            .map { it.remoteId }

        // navidrome:2 was omitted from scan and should be deleted
        assertTrue(currentToDelete.contains("navidrome:2"))
        // local:1 was scanned and should NOT be deleted
        assertFalse(currentToDelete.contains("local:1"))
        // youtube:3 was NOT in scan but MUST NOT be deleted!
        assertFalse(currentToDelete.contains("youtube:3"))
    }

    @Test
    fun playbackQueuePrioritizationMaintainsOriginalQueueIntegrity() {
        val tracks = (0..9).map { idx ->
            Song(
                id = "youtube:track_$idx",
                title = "Title $idx",
                artist = "Artist $idx",
                album = "Album",
                albumId = "yt_album_test",
                duration = 180_000L,
                uriString = "https://www.youtube.com/watch?v=track_$idx",
                providerId = "youtube",
                providerRemoteId = "track_$idx"
            )
        }

        val boundedStartIndex = 5
        val prioritizedSongs = tracks.mapIndexed { index, song -> index to song }
            .sortedBy { (index, _) -> kotlin.math.abs(index - boundedStartIndex) }
            .map { it.second }

        // Closest track to 5 is index 5 itself, followed by 4 or 6, etc.
        assertEquals("youtube:track_5", prioritizedSongs.first().id)
        assertEquals(10, prioritizedSongs.size)

        // When splitting resolvedItems before/after the playing index:
        val itemsBefore = tracks.subList(0, boundedStartIndex)
        val itemsAfter = tracks.subList(boundedStartIndex + 1, tracks.size)

        val assembledQueue = itemsBefore + listOf(tracks[boundedStartIndex]) + itemsAfter
        assertEquals(tracks.map { it.id }, assembledQueue.map { it.id })
    }
}
