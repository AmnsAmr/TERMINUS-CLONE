package com.necroware.terminusplayer.data.model

data class Song(
    val id: String,
    val providerId: String = "local", // local or remote source
    val providerRemoteId: String = id,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val duration: Long,
    val uriString: String,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val folderPath: String = "",
    val sizeBytes: Long = 0L,
    val dateAdded: Long = 0L,
    val isLiked: Boolean = false,
    val downloadedUri: String? = null,
    val genre: String = "",
    /** Navidrome identity for a local track matched with a server track. */
    val navidromeId: String? = null,
    val artworkUri: String? = null
)

data class PlaylistDownloadPlan(
    val downloadableSongs: List<Song>,
    val serverSongs: List<Song>,
    val alreadyOfflineCount: Int,
    val unavailableCount: Int,
    val offlineSongIds: Set<String>,
    val unavailableSongIds: Set<String>,
    val unresolvedTrackCount: Int = 0
)

fun buildPlaylistDownloadPlan(
    songs: List<Song>,
    offlineSongIds: Set<String>,
    unavailableSongIds: Set<String>,
    expectedTrackCount: Int = songs.size
): PlaylistDownloadPlan {
    val serverSongs = songs.filter { it.providerId == "navidrome" || !it.navidromeId.isNullOrBlank() }
    val downloadable = serverSongs.filter { it.id !in offlineSongIds && it.id !in unavailableSongIds }
    return PlaylistDownloadPlan(
        downloadableSongs = downloadable,
        serverSongs = serverSongs.filterNot { it.id in unavailableSongIds },
        alreadyOfflineCount = songs.count { it.id in offlineSongIds },
        unavailableCount = songs.count { it.id in unavailableSongIds },
        offlineSongIds = offlineSongIds,
        unavailableSongIds = unavailableSongIds,
        unresolvedTrackCount = (expectedTrackCount - songs.size).coerceAtLeast(0)
    )
}

data class GenreMix(val genre: String, val songs: List<Song>)

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val songCount: Int,
    /** URI of one representative song in this (title-merged) album, used
     *  for art lookup — per-file art is reliable; the shared numeric
     *  albumId legacy lookup is not (see SongArt.kt). */
    val representativeUriString: String = ""
)

data class Artist(
    val name: String,
    val songCount: Int
)

data class Folder(
    val path: String,
    val name: String,
    val songCount: Int
)

data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int,
    val ownerUsername: String? = null,
    val isRemote: Boolean = false,
    val hasSyncConflict: Boolean = false,
    val serverPlaylistMissing: Boolean = false
)

data class PlaylistAccountInfo(
    val username: String = "",
    val isAdmin: Boolean = false,
    val isLoaded: Boolean = false
)

fun Song.toSongEntity(): com.necroware.terminusplayer.data.database.entity.SongEntity {
    return com.necroware.terminusplayer.data.database.entity.SongEntity(
        remoteId = id,
        providerId = providerId,
        providerRemoteId = providerRemoteId,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        duration = duration,
        uriString = uriString,
        dateAdded = dateAdded,
        trackNumber = trackNumber,
        year = year,
        folderPath = folderPath,
        sizeBytes = sizeBytes,
        navidromeId = navidromeId,
        downloadedUri = downloadedUri,
        genre = genre,
        artworkUri = artworkUri
    )
}
