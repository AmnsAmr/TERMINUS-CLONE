package com.necroware.terminusplayer.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistDownloadPlanTest {
    @Test
    fun planSkipsLocalAndPreviouslyDownloadedTracksAndKeepsUnavailableOutOfQueue() {
        val local = song("local:1", provider = "local")
        val previouslyDownloaded = song("navidrome:2", provider = "navidrome")
        val missing = song("navidrome:3", provider = "navidrome")
        val unavailable = song("navidrome:4", provider = "navidrome")

        val plan = buildPlaylistDownloadPlan(
            songs = listOf(local, previouslyDownloaded, missing, unavailable),
            offlineSongIds = setOf(local.id, previouslyDownloaded.id),
            unavailableSongIds = setOf(unavailable.id)
        )

        assertEquals(listOf(missing.id), plan.downloadableSongs.map { it.id })
        assertEquals(listOf(previouslyDownloaded.id, missing.id), plan.serverSongs.map { it.id })
        assertEquals(2, plan.alreadyOfflineCount)
        assertEquals(1, plan.unavailableCount)
    }

    @Test
    fun localTrackMatchedToNavidromeCanBeForcedButDoesNotNeedDownloadNormally() {
        val matchedLocal = song("local:5", provider = "local").copy(navidromeId = "server-5")

        val plan = buildPlaylistDownloadPlan(
            songs = listOf(matchedLocal),
            offlineSongIds = setOf(matchedLocal.id),
            unavailableSongIds = emptySet()
        )

        assertEquals(emptyList<Song>(), plan.downloadableSongs)
        assertEquals(listOf(matchedLocal.id), plan.serverSongs.map { it.id })
        assertEquals(1, plan.alreadyOfflineCount)
    }

    @Test
    fun planReportsServerTracksNotYetPresentInTheCacheAsUnresolved() {
        val cached = song("navidrome:1", provider = "navidrome")

        val plan = buildPlaylistDownloadPlan(
            songs = listOf(cached),
            offlineSongIds = emptySet(),
            unavailableSongIds = emptySet(),
            expectedTrackCount = 3
        )

        assertEquals(1, plan.downloadableSongs.size)
        assertEquals(2, plan.unresolvedTrackCount)
    }

    @Test
    fun failedAvailabilityCheckCannotBecomeAnEmptyReadyPlan() {
        val availability = playlistOfflineAvailabilityFromResult(Result.failure(IllegalStateException("SAF unavailable")))

        assertEquals(PlaylistOfflineAvailability.Failed("SAF unavailable"), availability)
        val emptyPlan = buildPlaylistDownloadPlan(emptyList(), emptySet(), emptySet())
        assertEquals(
            PlaylistOfflineAvailability.Ready(emptyPlan),
            playlistOfflineAvailabilityFromResult(Result.success(emptyPlan))
        )
    }

    private fun song(id: String, provider: String) = Song(
        id = id,
        providerId = provider,
        providerRemoteId = id.substringAfter(':'),
        title = id,
        artist = "artist",
        album = "album",
        albumId = "album",
        duration = 1_000L,
        uriString = "content://music/$id"
    )
}
