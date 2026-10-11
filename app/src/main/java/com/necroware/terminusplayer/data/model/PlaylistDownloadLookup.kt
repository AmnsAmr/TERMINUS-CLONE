package com.necroware.terminusplayer.data.model

/** Bounded lookup inputs for the batched Room queries used by playlist availability checks. */
data class PlaylistDownloadLookupKeys(
    val songIds: List<String>,
    val navidromeIds: List<String>,
    val titles: List<String>
)

const val PLAYLIST_LOOKUP_BATCH_SIZE = 900

fun playlistDownloadLookupKeys(songs: List<Song>): PlaylistDownloadLookupKeys = PlaylistDownloadLookupKeys(
    songIds = songs.map { it.id }.distinct(),
    navidromeIds = songs.mapNotNull { song ->
        when {
            song.providerId == "navidrome" -> song.providerRemoteId.takeIf(String::isNotBlank)
            else -> song.navidromeId?.takeIf(String::isNotBlank)
        }
    }.distinct(),
    titles = songs.map { it.title.trim() }.filter(String::isNotBlank).distinct()
)

fun <T> sqliteQueryBatches(values: List<T>): List<List<T>> =
    values.distinct().chunked(PLAYLIST_LOOKUP_BATCH_SIZE)
