package com.necroware.terminusplayer.data.repository

import com.necroware.terminusplayer.data.api.youtube.YouTubeConstants
import com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter
import com.necroware.terminusplayer.data.api.youtube.YouTubeInnerTubeClient
import com.necroware.terminusplayer.data.api.youtube.YouTubePagedResult
import com.necroware.terminusplayer.data.database.dao.SongDao
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.toSongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeMusicRepository @Inject constructor(
    private val client: YouTubeInnerTubeClient,
    private val songDao: SongDao
) {
    suspend fun searchSongsPaged(
        query: String,
        params: String? = null,
        continuation: String? = null
    ): Result<YouTubePagedResult> = runCatching {
        val searchResult = client.searchSongsPaged(query.trim(), params, continuation)
        YouTubePagedResult(
            songs = searchResult.songs,
            continuationToken = searchResult.continuationToken,
            dynamicFilterChips = searchResult.dynamicFilterChips
        )
    }

    suspend fun searchSongs(query: String): Result<List<Song>> = runCatching {
        client.searchSongs(query.trim())
    }

    suspend fun getSearchSuggestions(input: String): Result<List<String>> = runCatching {
        if (input.isBlank()) emptyList() else client.getSearchSuggestions(input.trim())
    }

    suspend fun getDiscoverySongsPaged(
        filter: YouTubeDiscoveryFilter,
        continuation: String? = null
    ): Result<YouTubePagedResult> = runCatching {
        if (!filter.browseId.isNullOrBlank()) {
            val songs = client.browsePlaylistSongs(filter.browseId)
            YouTubePagedResult(songs = songs, continuationToken = null)
        } else {
            val q = filter.query ?: filter.label
            val res = client.searchSongsPaged(
                query = q,
                params = YouTubeConstants.SEARCH_FILTER_SONGS,
                continuation = continuation
            )
            YouTubePagedResult(
                songs = res.songs,
                continuationToken = res.continuationToken,
                dynamicFilterChips = res.dynamicFilterChips
            )
        }
    }

    suspend fun browsePlaylist(browseId: String): Result<List<Song>> = runCatching {
        client.browsePlaylistSongs(browseId)
    }

    suspend fun getPopularSongs(): Result<List<Song>> = runCatching {
        client.getPopularSongs()
    }

    suspend fun getTrendingSongs(): Result<List<Song>> = runCatching {
        client.getTrendingSongs()
    }

    suspend fun getCharts(): Result<List<Song>> = runCatching {
        client.getCharts()
    }

    suspend fun getRelatedSongs(videoId: String): Result<List<Song>> = runCatching {
        client.getRelatedSongs(videoId.removePrefix("youtube:"))
    }

    suspend fun ensureTrackPersisted(song: Song) = withContext(Dispatchers.IO) {
        if (song.providerId != "youtube") return@withContext
        val existing = songDao.getById(song.id)
        if (existing == null) {
            songDao.insertSong(song.toSongEntity())
        }
    }

    suspend fun ensureTracksPersisted(songs: List<Song>) = withContext(Dispatchers.IO) {
        songs.filter { it.providerId == "youtube" }.forEach { ensureTrackPersisted(it) }
    }
}
