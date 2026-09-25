package com.necroware.terminusplayer.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.necroware.terminusplayer.data.database.dao.LikedSongDao
import com.necroware.terminusplayer.data.database.dao.PlayEventDao
import com.necroware.terminusplayer.data.database.dao.PlaylistDao
import com.necroware.terminusplayer.data.database.dao.SongDao
import com.necroware.terminusplayer.data.database.TerminusDatabase
import com.necroware.terminusplayer.data.database.entity.LikedSongEntity
import com.necroware.terminusplayer.data.database.entity.PlaylistEntity
import com.necroware.terminusplayer.data.database.entity.PlaylistSongEntity
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.provider.MediaProvider
import com.necroware.terminusplayer.data.provider.ProviderSyncException
import com.necroware.terminusplayer.data.model.Album
import com.necroware.terminusplayer.data.model.Artist
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.util.matchM3uEntryToSong
import com.necroware.terminusplayer.util.normalizeForMatch
import com.necroware.terminusplayer.util.parseM3u
import com.necroware.terminusplayer.data.model.SyncedLyrics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.necroware.terminusplayer.sync.LikeSyncWorker

@Singleton
class MusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val likedSongDao: LikedSongDao,
    private val playEventDao: PlayEventDao,
    private val playlistDao: PlaylistDao,
    private val database: TerminusDatabase,
    private val providers: Map<String, @JvmSuppressWildcards MediaProvider>
) {

    private val syncMutex = Mutex()

    data class SyncResult(
        val successfulProviders: Set<String>,
        val failedProviders: Set<String>,
        val retryableFailures: Set<String>
    )

    /** Re-scans all providers and syncs the Room cache. Call on app start and pull-to-refresh. */
    suspend fun syncLibrary(): SyncResult = withContext(Dispatchers.Default) {
        syncMutex.withLock {
        val scans = mutableMapOf<String, List<SongEntity>>()
        val failedProviders = mutableSetOf<String>()
        val retryableFailures = mutableSetOf<String>()
        providers.forEach { (providerId, provider) ->
            try {
                scans[providerId] = provider.syncLibrary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderSyncException) {
                failedProviders += providerId
                if (e.retryable) retryableFailures += providerId
            } catch (_: Exception) {
                failedProviders += providerId
                retryableFailures += providerId
            }
        }

        val existingSongs = songDao.getAllSongs().associateBy { it.remoteId }
        val retainedFailedProviderSongs = existingSongs.values.filter { it.providerId in failedProviders }
        val allScanned = scans.values.flatten().map { song ->
            song.copy(
                remoteId = namespacedSongId(song.providerId, song.providerRemoteId),
                providerRemoteId = song.providerRemoteId,
                album = song.album.trim()
            )
        } + retainedFailedProviderSongs
        
        val localSongs = allScanned.filter { it.providerId == "local" }
        val remoteSongs = allScanned.filter { it.providerId != "local" }
        
        val remoteSongsByKey = remoteSongs.associateBy { 
            "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}"
        }
        
        val localKeys = localSongs.map { 
            "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}"
        }.toSet()
        
        val deduplicatedLocalSongs = localSongs.map { local ->
            val key = "${local.title.trim().lowercase()}|${local.artist.trim().lowercase()}"
            val match = remoteSongsByKey[key]
            if (match != null) {
                local.copy(navidromeId = match.providerRemoteId)
            } else {
                local
            }
        }
        
        val remainingRemoteSongs = remoteSongs.filterNot { remote ->
            val key = "${remote.title.trim().lowercase()}|${remote.artist.trim().lowercase()}"
            localKeys.contains(key)
        }
        
        val scanned = deduplicatedLocalSongs + remainingRemoteSongs
        
        // Diffing mechanism to prevent unnecessary UI recompositions
        val scannedIds = scanned.map { it.remoteId }.toSet()
        
        database.withTransaction {
            val currentSongs = songDao.getAllSongs().associateBy { it.remoteId }
            val currentToUpsert = scanned.filter { currentSongs[it.remoteId] != it }
            val currentToDelete = currentSongs.values
                .filter { it.providerId !in failedProviders && it.remoteId !in scannedIds }
                .map { it.remoteId }

            if (currentToUpsert.isNotEmpty()) {
                currentToUpsert.chunked(900).forEach { songDao.upsertAll(it) }
            }
            if (currentToDelete.isNotEmpty()) {
                currentToDelete.chunked(900).forEach { songDao.deleteByIds(it) }
            }
        }

        SyncResult(scans.keys.toSet(), failedProviders, retryableFailures)
        }
    }

    fun observeAllSongs(): Flow<List<Song>> =
        combine(songDao.observeAllSongs(), likedSongDao.observeLikedIds()) { songs, likedIds ->
            val likedSet = likedIds.toHashSet()
            songs.map { it.toSong(isLiked = it.remoteId in likedSet) }
        }

    fun observeSongCount(): Flow<Int> = songDao.observeSongCount()
    fun observeLikedSongCount(): Flow<Int> = likedSongDao.observeLikedSongCount()

    fun observeAllArtists(): Flow<List<Artist>> =
        songDao.observeAllArtistsWithSongCount().map { artists ->
            artists.map { Artist(name = it.artist, songCount = it.songCount) }
        }

    fun observeAllAlbums(): Flow<List<Album>> =
        songDao.observeAllSongs().map { songs ->
            // Group by normalized title, NOT raw albumId — MediaStore assigns
            // a distinct albumId per track when per-track artist/feature tags
            // differ, which otherwise splits one real album into many rows.
            songs.groupBy { it.album.trim().lowercase() }
                .map { (_, songsInAlbum) ->
                    val representative = songsInAlbum.first()
                    Album(
                        id = representative.albumId,
                        title = representative.album,
                        artist = representative.artist,
                        songCount = songsInAlbum.size,
                        representativeUriString = representative.uriString
                    )
                }
                .sortedBy { it.title.lowercase() }
        }

    fun observeAllFolders(): Flow<List<String>> = songDao.observeAllFolders()

    /** Removes cached local tracks immediately when a source folder is excluded. */
    suspend fun removeLocalSongsInFolder(folderPath: String) {
        syncMutex.withLock {
            songDao.deleteLocalSongsInFolder(folderPath)
        }
    }

    fun searchSongs(query: String): Flow<List<Song>> =
        songDao.searchSongs(query).map { entities -> entities.map { it.toSong() } }

    suspend fun toggleLike(songId: String) {
        val song = songDao.getById(songId)
        val newLikedState = likedSongDao.toggle(songId, System.currentTimeMillis())
        if (song == null) return

        val remoteId = if (song.navidromeId != null) song.navidromeId else song.providerRemoteId
        val providerId = if (song.navidromeId != null) "navidrome" else song.providerId
        val request = OneTimeWorkRequestBuilder<LikeSyncWorker>()
            .setInputData(workDataOf(
                LikeSyncWorker.KEY_PROVIDER_ID to providerId,
                LikeSyncWorker.KEY_PROVIDER_REMOTE_ID to remoteId,
                LikeSyncWorker.KEY_IS_LIKED to newLikedState
            ))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "like-sync:$songId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun syncLike(providerId: String, providerRemoteId: String, isLiked: Boolean) {
        providers[providerId]?.toggleLike(providerRemoteId, isLiked)
    }

    /** Used by the Now Playing like-button to reflect the current track's liked state. */
    fun observeLikedIds(): Flow<List<String>> = likedSongDao.observeLikedIds()

    /**
     * Media3's MediaController doesn't preserve a MediaItem's local content
     * URI across the session boundary (MediaItem.LocalConfiguration is
     * intentionally dropped during Bundle serialization), so Now Playing
     * resolves the current track's real file URI via this lookup instead
     * of trying to read it back off the controller.
     */
    suspend fun getSongUri(songId: String, timeOffsetMs: Long? = null): String? {
        val song = songDao.getById(songId) ?: return null
        return try {
            val uri = resolveSongUri(song)
            if (song.providerId == "navidrome" && timeOffsetMs != null && timeOffsetMs > 0L) {
                Uri.parse(uri).buildUpon()
                    .appendQueryParameter("timeOffset", (timeOffsetMs / 1000L).toString())
                    .build()
                    .toString()
            } else {
                uri
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            song.uriString
        }
    }

    suspend fun getSongUris(songIds: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext emptyMap()
        val songsById = songIds.distinct().chunked(800)
            .flatMap { songDao.getByIds(it) }
            .associateBy { it.remoteId }
        buildMap {
            songIds.distinct().forEach { songId ->
                val song = songsById[songId] ?: return@forEach
                try {
                    put(songId, resolveSongUri(song))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    put(songId, song.uriString)
                }
            }
        }
    }

    private suspend fun resolveSongUri(song: SongEntity): String =
        providers[song.providerId]?.resolveStreamUrl(song.providerRemoteId) ?: song.uriString

    suspend fun getSong(songId: String): Song? {
        val song = songDao.getById(songId) ?: return null
        val isLiked = likedSongDao.isLiked(songId)
        return song.toSong(isLiked = isLiked)
    }

    suspend fun getLyrics(songId: String): SyncedLyrics? {
        val song = songDao.getById(songId) ?: return null
        return try {
            if (song.navidromeId != null) {
                providers["navidrome"]?.getLyrics(song.navidromeId)
            } else {
        providers[song.providerId]?.getLyrics(song.providerRemoteId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun scrobble(songId: String) {
        val song = songDao.getById(songId) ?: return
        try {
            if (song.navidromeId != null) {
                providers["navidrome"]?.scrobble(song.navidromeId)
            } else {
                providers[song.providerId]?.scrobble(song.providerRemoteId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ignore scrobble failures
        }
    }

    suspend fun getSongsForAlbum(albumTitle: String): List<Song> = withContext(Dispatchers.Default) {
        val likedIds = likedSongDao.observeLikedIds().first().toHashSet()
        songDao.observeSongsByAlbumTitle(albumTitle).first().map { it.toSong(isLiked = it.remoteId in likedIds) }
    }

    suspend fun getSongsForArtist(artist: String): List<Song> = withContext(Dispatchers.Default) {
        val likedIds = likedSongDao.observeLikedIds().first().toHashSet()
        songDao.observeSongsByArtist(artist).first().map { it.toSong(isLiked = it.remoteId in likedIds) }
    }

    /** Liked songs, most recently liked first — the "Liked Songs" auto-playlist. */
    suspend fun getLikedSongs(): List<Song> = withContext(Dispatchers.Default) {
        val likedIdsOrdered = likedSongDao.getLikedIdsMostRecentFirst()
        if (likedIdsOrdered.isEmpty()) return@withContext emptyList()
        val entitiesById = likedIdsOrdered.chunked(800)
            .flatMap { songDao.getByIds(it) }
            .associateBy { it.remoteId }
        likedIdsOrdered.mapNotNull { id -> entitiesById[id]?.toSong(isLiked = true) }
    }

    /** All-time most-played songs, highest play count first — the "Most Played" auto-playlist. */
    suspend fun getMostPlayed(limit: Int = 100): List<Song> = withContext(Dispatchers.Default) {
        val rows = playEventDao.topPlayedSongIds(limit)
        if (rows.isEmpty()) return@withContext emptyList()
        val likedIds = likedSongDao.observeLikedIds().first().toHashSet()
        val entitiesById = songDao.getByIds(rows.map { it.songId }).associateBy { it.remoteId }
        rows.mapNotNull { row ->
            entitiesById[row.songId]?.toSong(isLiked = row.songId in likedIds)
        }
    }

    /** Most-recently-listened-to distinct songs, most recent first, for the Home screen row. */
    suspend fun getRecentlyPlayed(limit: Int = 20): List<Song> = withContext(Dispatchers.Default) {
        val rows = playEventDao.recentlyPlayedSongIds(limit)
        if (rows.isEmpty()) return@withContext emptyList()
        val likedIds = likedSongDao.observeLikedIds().first().toHashSet()
        val entitiesById = songDao.getByIds(rows.map { it.songId }).associateBy { it.remoteId }
        // Room's IN clause doesn't preserve order, so re-order to match recency.
        rows.mapNotNull { row ->
            entitiesById[row.songId]?.toSong(isLiked = row.songId in likedIds)
        }
    }

    /**
     * "Your Mix" — a lightweight algorithmic mix built from real listening
     * signal rather than a static shuffle:
     *  - Liked songs are always eligible.
     *  - Top-played songs are weighted into the pool proportional to how
     *    often they've been played (capped so one song can't dominate).
     *  - If there isn't enough listening history yet (new install), falls
     *    back to the most recently added songs so the screen never renders
     *    empty on day one.
     * Final pool is shuffled and trimmed to [limit] distinct songs.
     */
    suspend fun getYourMix(limit: Int = 25): List<Song> = withContext(Dispatchers.Default) {
        val likedIds = likedSongDao.observeLikedIds().first()
        val topPlayed = playEventDao.topPlayedSongIds(limit = 50)

        val weightedBag = mutableListOf<String>()
        topPlayed.forEach { row ->
            repeat(row.playCount.coerceIn(1, 5)) { weightedBag += row.songId }
        }
        likedIds.forEach { id ->
            repeat(3) { weightedBag += id }
        }

        val candidateIds: List<String> = if (weightedBag.isEmpty()) {
            songDao.mostRecentlyAdded(limit * 2).map { it.remoteId }
        } else {
            weightedBag.shuffled()
        }

        val distinctOrdered = candidateIds.distinct().take(limit)
        if (distinctOrdered.isEmpty()) return@withContext emptyList()

        val likedSet = likedIds.toHashSet()
        val entitiesById = songDao.getByIds(distinctOrdered).associateBy { it.remoteId }
        distinctOrdered
            .mapNotNull { id -> entitiesById[id]?.toSong(isLiked = id in likedSet) }
            .shuffled()
    }

    // ---- Custom playlists ------------------------------------------------

    fun observeCustomPlaylists(): Flow<List<Playlist>> =
        playlistDao.observePlaylists().map { rows ->
            rows.map { Playlist(id = it.id, name = it.name, songCount = it.songCount) }
        }

    suspend fun getSongsForPlaylist(playlistId: String): List<Song> = withContext(Dispatchers.Default) {
        val likedIds = likedSongDao.observeLikedIds().first().toHashSet()
        playlistDao.getSongsForPlaylist(playlistId).map { it.toSong(isLiked = it.remoteId in likedIds) }
    }

    suspend fun getPlaylistName(playlistId: String): String = playlistDao.getPlaylistName(playlistId) ?: "PLAYLIST"

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylistSongs(playlistId)
        playlistDao.deletePlaylist(playlistId)
    }

    /**
     * Reads an .m3u/.m3u8 file from [uri], matches each entry against the
     * already-scanned library by normalized filename, and stores whatever
     * matched as a new named playlist. Returns the number of tracks that
     * matched (out of the total entries found in the file) so the caller
     * can tell the user "$matched / $total tracks found" rather than a
     * silent partial import.
     */
    suspend fun importPlaylistFromM3u(uri: Uri, name: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val entries = context.contentResolver.openInputStream(uri)?.use { stream ->
            parseM3u(stream.bufferedReader())
        } ?: emptyList()
        if (entries.isEmpty()) return@withContext 0 to 0

        val allSongs = songDao.observeAllSongs().first().map { it.toSong() }
        val byNormalizedTitle = allSongs.groupBy { normalizeForMatch(it.title) }

        val matched = entries.mapNotNull { entry -> matchM3uEntryToSong(entry, byNormalizedTitle) }
        if (matched.isEmpty()) return@withContext 0 to entries.size

        // Playlist membership is unique by (playlistId, songId); repeated M3U rows
        // therefore keep the first occurrence and are not stored a second time.
        val playlistId = java.util.UUID.randomUUID().toString()
        val distinctMatched = matched.distinctBy { it.id }
        playlistDao.insertPlaylistWithSongs(
            PlaylistEntity(id = playlistId, name = name, createdAt = System.currentTimeMillis()),
            distinctMatched.mapIndexed { index, song -> PlaylistSongEntity(playlistId, song.id, index) }
        )
        distinctMatched.size to entries.size
    }

    // ---- Add files ---------------------------------------------------

    /**
     * Copies each picked document into the shared Music/Terminus MediaStore
     * collection (scoped storage — no WRITE_EXTERNAL_STORAGE needed on
     * API 29+ since the app owns what it inserts) and re-syncs the library
     * so the new tracks show up immediately. Returns how many succeeded.
     */
    suspend fun importAudioFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var successCount = 0

        for (sourceUri in uris) {
            val displayName = queryDisplayName(sourceUri) ?: continue
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Terminus")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val destUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: continue

            val copied = try {
                resolver.openInputStream(sourceUri)?.use { input ->
                    resolver.openOutputStream(destUri)?.use { output -> input.copyTo(output) }
                } != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val clearPending = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(destUri, clearPending, null, null)
            }

            if (copied) successCount++ else resolver.delete(destUri, null, null)
        }

        if (successCount > 0) syncLibrary()
        successCount
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}

private fun SongEntity.toSong(isLiked: Boolean = false): Song = Song(
    id = remoteId,
    providerId = providerId,
    providerRemoteId = providerRemoteId,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = duration,
    uriString = uriString,
    trackNumber = trackNumber,
    year = year,
    folderPath = folderPath,
    sizeBytes = sizeBytes,
    dateAdded = dateAdded,
    isLiked = isLiked
)

private fun namespacedSongId(providerId: String, providerRemoteId: String): String =
    "$providerId:$providerRemoteId"
