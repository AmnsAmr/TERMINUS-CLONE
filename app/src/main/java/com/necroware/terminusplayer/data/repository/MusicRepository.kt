package com.necroware.terminusplayer.data.repository

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
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
import com.necroware.terminusplayer.data.api.subsonic.SubsonicApiService
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.provider.buildSubsonicToken
import com.necroware.terminusplayer.data.provider.newSubsonicSalt
import com.necroware.terminusplayer.data.model.Album
import com.necroware.terminusplayer.data.model.Artist
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.data.model.PlaylistAccountInfo
import com.necroware.terminusplayer.data.model.GenreMix
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.PlaylistDownloadPlan
import com.necroware.terminusplayer.data.model.buildPlaylistDownloadPlan
import com.necroware.terminusplayer.data.model.playlistDownloadLookupKeys
import com.necroware.terminusplayer.data.model.sqliteQueryBatches
import com.necroware.terminusplayer.data.model.normalizeGenreKey
import com.necroware.terminusplayer.data.model.parseGenreTags
import com.necroware.terminusplayer.util.matchM3uEntryToSong
import com.necroware.terminusplayer.util.normalizeForMatch
import com.necroware.terminusplayer.util.parseM3u
import com.necroware.terminusplayer.util.TransferProgress
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.necroware.terminusplayer.data.model.lyricsCacheIdentity
import com.necroware.terminusplayer.data.model.normalizedRecordingMetadata
import com.necroware.terminusplayer.data.model.uniqueRecordingMatch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import com.necroware.terminusplayer.sync.PlaylistSyncWorker
import com.necroware.terminusplayer.sync.LyricsCacheWorker
import androidx.work.Constraints
import androidx.work.NetworkType
import java.io.IOException
import android.webkit.MimeTypeMap

private data class ProviderScanResult(
    val providerId: String,
    val songs: List<SongEntity>? = null,
    val error: ProviderSyncException? = null
)

@Singleton
class MusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val likedSongDao: LikedSongDao,
    private val playEventDao: PlayEventDao,
    private val playlistDao: PlaylistDao,
    private val database: TerminusDatabase,
    private val providers: Map<String, @JvmSuppressWildcards MediaProvider>,
    private val subsonicApi: SubsonicApiService,
    private val preferencesRepository: UserPreferencesRepository,
    private val lrclibLyricsRepository: LrclibLyricsRepository,
    private val embeddedLyricsReader: EmbeddedLyricsReader
) : DownloadTransferRepository {

    private val syncMutex = Mutex()
    private val _playlistAccountInfo = MutableStateFlow(PlaylistAccountInfo())
    val playlistAccountInfo = _playlistAccountInfo.asStateFlow()

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
        val scanResults = coroutineScope {
            providers.map { (providerId, provider) ->
                async {
                    try {
                        ProviderScanResult(providerId, songs = provider.syncLibrary())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProviderSyncException) {
                        ProviderScanResult(providerId, error = e)
                    } catch (e: Exception) {
                        ProviderScanResult(
                            providerId,
                            error = ProviderSyncException(e.message ?: "Provider scan failed", retryable = true)
                        )
                    }
                }
            }.awaitAll()
        }
        scanResults.forEach { (providerId, songs, error) ->
            if (songs != null) {
                scans[providerId] = songs
            } else {
                failedProviders += providerId
                if (error?.retryable != false) retryableFailures += providerId
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
        
        val remoteSongsByKey = remoteSongs.groupBy(::normalizedRecordingMetadata)
        val matchedRemoteIds = mutableSetOf<String>()
        val deduplicatedLocalSongs = localSongs.map { local ->
            val key = normalizedRecordingMetadata(local)
            val match = uniqueRecordingMatch(local, remoteSongsByKey[key].orEmpty())
                ?.takeIf { it.providerRemoteId !in matchedRemoteIds }
            if (match != null) {
                matchedRemoteIds += match.providerRemoteId
                val mergedGenres = (parseGenreTags(local.genre) + parseGenreTags(match.genre))
                    .distinctBy(::normalizeGenreKey)
                    .joinToString("; ")
                local.copy(navidromeId = match.providerRemoteId, genre = mergedGenres)
            } else {
                local
            }
        }
        
        val remainingRemoteSongs = remoteSongs.filterNot { it.providerRemoteId in matchedRemoteIds }
        
        val scanned = (deduplicatedLocalSongs + remainingRemoteSongs).map { song ->
            song.copy(downloadedUri = existingSongs[song.remoteId]?.downloadedUri)
        }
        
        // Diffing mechanism to prevent unnecessary UI recompositions
        val scannedIds = scanned.map { it.remoteId }.toSet()
        
        val removedLocalSongs = mutableListOf<SongEntity>()
        database.withTransaction {
            val currentSongs = songDao.getAllSongs().associateBy { it.remoteId }
            val currentToUpsert = scanned.filter { currentSongs[it.remoteId] != it }
            val currentToDelete = currentSongs.values
                .filter { it.providerId !in failedProviders && it.remoteId !in scannedIds }
                .map { it.remoteId }

            removedLocalSongs += currentToDelete.mapNotNull(currentSongs::get).filter { it.providerId == "local" }

            if (currentToUpsert.isNotEmpty()) {
                currentToUpsert.chunked(900).forEach { songDao.upsertAll(it) }
            }
            if (currentToDelete.isNotEmpty()) {
                currentToDelete.chunked(900).forEach { songDao.deleteByIds(it) }
            }
        }

        // A scan omission alone is not proof of deletion: directly re-query each old MediaStore URI.
        // Permission failures, null cursors, and inaccessible files retain their lyric cache.
        removedLocalSongs.forEach { removed ->
            if (embeddedLyricsReader.confirmsPermanentDeletion(removed.uriString)) {
                lrclibLyricsRepository.cleanupConfirmedLocalFile(removed.uriString)
            }
        }

        SyncResult(scans.keys.toSet(), failedProviders, retryableFailures)
        }
    }

    fun observeAllSongs(): Flow<List<Song>> =
        combine(songDao.observeAllSongs(), likedSongDao.observeLikedIds()) { songs, likedIds ->
            val likedSet = likedIds.toHashSet()
            songs.map { it.toSong(isLiked = it.remoteId in likedSet) }
                .groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
                .map { (_, group) -> group.find { it.providerId == "local" } ?: group.first() }
        }.flowOn(Dispatchers.Default)

    fun observeSongCount(): Flow<Int> = songDao.observeSongCount()
    fun observeGenreTags(): Flow<List<String>> = songDao.observeGenreTags()
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
        }.flowOn(Dispatchers.Default)

    fun observeAllFolders(): Flow<List<String>> = songDao.observeAllFolders()

    /** Removes cached local tracks immediately when a source folder is excluded. */
    suspend fun removeLocalSongsInFolder(folderPath: String) {
        syncMutex.withLock {
            songDao.deleteLocalSongsInFolder(folderPath)
        }
    }

    fun searchSongs(query: String): Flow<List<Song>> =
        songDao.searchSongs(query).map { entities -> entities.map { it.toSong() } }
            .flowOn(Dispatchers.Default)

    data class LikeChange(val isLiked: Boolean, val syncQueued: Boolean)

    suspend fun toggleLike(songId: String): LikeChange {
        val song = songDao.getById(songId)
        if (song == null) return LikeChange(isLiked = false, syncQueued = false)
        val newLikedState = likedSongDao.toggle(songId, System.currentTimeMillis())

        // Local-only tracks have no server identity. A local copy that matched a
        // Navidrome track uses that remote identity so the server star stays in sync.
        val providerId = when {
            song.navidromeId != null -> "navidrome"
            song.providerId != "local" -> song.providerId
            else -> return LikeChange(isLiked = newLikedState, syncQueued = false)
        }
        val remoteId = song.navidromeId ?: song.providerRemoteId
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<LikeSyncWorker>()
            .setInputData(workDataOf(
                LikeSyncWorker.KEY_PROVIDER_ID to providerId,
                LikeSyncWorker.KEY_PROVIDER_REMOTE_ID to remoteId,
                LikeSyncWorker.KEY_IS_LIKED to newLikedState
            ))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "like-sync:$songId",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return LikeChange(isLiked = newLikedState, syncQueued = true)
    }

    suspend fun syncLike(providerId: String, providerRemoteId: String, isLiked: Boolean) {
        val provider = providers[providerId] ?: throw IOException("Music provider '$providerId' is unavailable")
        provider.toggleLike(providerRemoteId, isLiked)
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

        val providerToRemoteIds = songsById.values.groupBy({ it.providerId }, { it.providerRemoteId })
        val resolvedByProvider = providerToRemoteIds.mapValues { (providerId, remoteIds) ->
            try {
                val downloadedByRemoteId = songsById.values
                    .filter { it.providerId == providerId && it.downloadedUri != null }
                    .associate { it.providerRemoteId to it.downloadedUri.orEmpty() }
                val unresolvedIds = remoteIds.distinct().filterNot { it in downloadedByRemoteId }
                val liveUrls = if (unresolvedIds.isEmpty()) emptyMap() else {
                    providers[providerId]?.resolveStreamUrls(unresolvedIds) ?: emptyMap()
                }
                downloadedByRemoteId + liveUrls
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
        }

        buildMap {
            songIds.distinct().forEach { songId ->
                val song = songsById[songId] ?: return@forEach
                val resolvedUri = resolvedByProvider[song.providerId]?.get(song.providerRemoteId)
                put(songId, resolvedUri ?: song.uriString)
            }
        }
    }

    private suspend fun resolveSongUri(song: SongEntity): String =
        song.downloadedUri
            ?: providers[song.providerId]?.resolveStreamUrl(song.providerRemoteId)
            ?: song.uriString

    suspend fun getSong(songId: String): Song? {
        val song = songDao.getById(songId) ?: return null
        val isLiked = likedSongDao.isLiked(songId)
        return song.toSong(isLiked = isLiked)
    }

    suspend fun getSongByUri(uri: String): Song? {
        val song = songDao.getByUri(uri) ?: return null
        return song.toSong(isLiked = likedSongDao.isLiked(song.remoteId))
    }

    override suspend fun downloadSong(
        songId: String,
        force: Boolean,
        onProgress: ((TransferProgress) -> Unit)?
    ): String = withContext(Dispatchers.IO) {
        val song = songDao.getById(songId) ?: throw PermanentTransferException("Track is no longer in the library")
        try {
            val serverTrackId = when {
                song.providerId == "navidrome" -> song.providerRemoteId
                !song.navidromeId.isNullOrBlank() -> song.navidromeId
                else -> throw PermanentTransferException("Only server tracks can be downloaded")
            }
            val prefs = preferencesRepository.preferences.first()
            val name = downloadFileName(song)
            
            if (!force) {
                song.downloadedUri?.let(Uri::parse)?.takeIf(::isReadableDownload)?.let { existing ->
                    scheduleLyricsWarmup(songId)
                    return@withContext existing.toString()
                }
                if (song.downloadedUri != null) songDao.setDownloadedUri(songId, null)
                findExistingDownload(name, prefs.downloadFolderUri)?.let { existing ->
                    songDao.setDownloadedUri(songId, existing.toString())
                    scheduleLyricsWarmup(songId)
                    return@withContext existing.toString()
                }
            } else {
                if (song.downloadedUri != null) songDao.setDownloadedUri(songId, null)
            }

            val config = preferencesRepository.awaitServerConnectionConfig()
            if (config.username.isBlank() || config.password.isBlank()) throw PermanentTransferException("Navidrome credentials are missing")
            val salt = newSubsonicSalt()
            val token = buildSubsonicToken(config.password, salt)
            
            val response = subsonicApi.download(serverTrackId, config.username, token, salt)
            if (!response.isSuccessful) {
                response.errorBody()?.close()
                val message = "Download failed with HTTP ${response.code()}"
                if (response.code() in 400..499) throw PermanentTransferException(message)
                throw IOException(message)
            }
            val body = response.body() ?: throw IOException("Server returned an empty download")
            if (body.contentType()?.subtype?.contains("json", ignoreCase = true) == true) {
                body.close()
                throw PermanentTransferException("Server returned an authentication or download error")
            }
            
            val destination = createDownloadDestination(name, prefs.downloadFolderUri)
            try {
                val totalBytes = body.contentLength().takeIf { it >= 0L }
                onProgress?.invoke(TransferProgress(0L, totalBytes, destination.uri.toString()))
                body.use { downloaded ->
                    downloaded.byteStream().use { input ->
                        context.contentResolver.openOutputStream(destination.uri, "w")?.use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesTransferred = 0L
                            var lastReportedBytes = 0L
                            var lastReportedAt = System.currentTimeMillis()
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                bytesTransferred += count
                                val now = System.currentTimeMillis()
                                if (bytesTransferred - lastReportedBytes >= 256L * 1024L || now - lastReportedAt >= 500L) {
                                    onProgress?.invoke(TransferProgress(bytesTransferred, totalBytes))
                                    lastReportedBytes = bytesTransferred
                                    lastReportedAt = now
                                }
                            }
                            onProgress?.invoke(TransferProgress(bytesTransferred, totalBytes))
                        } ?: throw IOException("Cannot open the selected download folder")
                    }
                }
                destination.publish()
                songDao.setDownloadedUri(songId, destination.uri.toString())
                scheduleLyricsWarmup(songId)
                destination.uri.toString()
            } catch (e: CancellationException) {
                destination.delete()
                throw e
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun cleanupInterruptedDownload(partialUri: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(partialUri)
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else {
            context.contentResolver.delete(uri, null, null)
        }
        Unit
    }

    private data class DownloadDestination(
        val uri: Uri,
        val publish: () -> Unit,
        val delete: () -> Unit
    )

    private fun createDownloadDestination(name: String, folderUri: String?): DownloadDestination {
        val resolver = context.contentResolver
        if (!folderUri.isNullOrBlank()) {
            val treeUri = Uri.parse(folderUri)
            val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val documentUri = DocumentsContract.createDocument(
                resolver,
                treeDocumentUri,
                mimeTypeFor(name),
                name
            ) ?: throw IOException("Could not create a file in the selected folder")
            return DownloadDestination(documentUri, {}, { DocumentsContract.deleteDocument(resolver, documentUri) })
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeTypeFor(name))
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Terminus")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the download file")
        return DownloadDestination(
            uri = uri,
            publish = { resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) },
            delete = { resolver.delete(uri, null, null) }
        )
    }

    private fun isReadableDownload(uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length != 0L } ?: false
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun findExistingDownload(name: String, folderUri: String?): Uri? {
        return findExistingDownloads(setOf(name), folderUri)[name]
    }

    private fun findExistingDownloads(names: Set<String>, folderUri: String?): Map<String, Uri> {
        if (names.isEmpty()) return emptyMap()
        val resolver = context.contentResolver
        val matches = mutableMapOf<String, Uri>()
        if (!folderUri.isNullOrBlank()) {
            val treeUri = Uri.parse(folderUri)
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idColumn >= 0 && nameColumn >= 0) {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn)
                        if (name in names && name !in matches) {
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                            if (isReadableDownload(documentUri)) matches[name] = documentUri
                        }
                    }
                }
            }
            return matches
        }

        val relativePath = Environment.DIRECTORY_MUSIC + "/Terminus/"
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val sizeColumn = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val nameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (idColumn >= 0 && sizeColumn >= 0 && nameColumn >= 0 && cursor.getLong(sizeColumn) > 0L) {
                    val name = cursor.getString(nameColumn)
                    if (name in names) {
                        matches.putIfAbsent(name, ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idColumn)
                        ))
                    }
                }
            }
        }
        return matches
    }

    private fun downloadFileName(song: SongEntity): String {
        val extension = song.folderPath.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val base = song.folderPath.substringAfterLast('/').substringBeforeLast('.', "")
            .ifBlank { "${song.artist} - ${song.title}" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(120)
        return "$base.${extension ?: "mp3"}"
    }

    private fun mimeTypeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    suspend fun getLyrics(songId: String): SyncedLyrics? = resolveLyrics(songId, reportCacheErrors = false)

    private suspend fun resolveLyrics(songId: String, reportCacheErrors: Boolean): SyncedLyrics? {
        val song = songDao.getById(songId) ?: return null
        return try {
            val identity = lyricsCacheIdentity(song)
            val prefs = preferencesRepository.preferences.first()
            val fileUri = song.downloadedUri ?: song.uriString.takeIf { song.providerId == "local" }

            if (fileUri != null) {
                val fileLyrics = embeddedLyricsReader.read(fileUri, prefs.downloadFolderUri, reportCacheErrors)
                if (fileLyrics != null) {
                    try {
                        return lrclibLyricsRepository.save(identity, fileLyrics)
                    } catch (e: Exception) {
                        if (reportCacheErrors) throw e
                        return fileLyrics
                    }
                }
            }

            lrclibLyricsRepository.cachedForSong(song, identity)?.let { return it }

            if (lrclibLyricsRepository.isOnline()) {
                val providerId = when {
                    !song.navidromeId.isNullOrBlank() -> "navidrome"
                    else -> song.providerId
                }
                val providerRemoteId = song.navidromeId?.takeIf(String::isNotBlank) ?: song.providerRemoteId
                val providerLyrics = try {
                    providers[providerId]?.getLyrics(providerRemoteId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                providerLyrics?.takeIf { it.lines.any { line -> line.text.isNotBlank() } }?.let {
                    try {
                        return lrclibLyricsRepository.save(identity, it)
                    } catch (e: Exception) {
                        if (reportCacheErrors) throw e
                        return it
                    }
                }
            }

            lrclibLyricsRepository.fetchFromLrclib(song, identity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reportCacheErrors) throw e else null
        }
    }

    suspend fun warmLyricsCache(songId: String) {
        resolveLyrics(songId, reportCacheErrors = true)
    }

    private fun scheduleLyricsWarmup(songId: String) {
        try {
            val request = OneTimeWorkRequestBuilder<LyricsCacheWorker>()
                .setInputData(workDataOf(LyricsCacheWorker.KEY_SONG_ID to songId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "lyrics-cache-$songId",
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            android.util.Log.w("MusicRepository", "Could not schedule lyrics cache warmup for $songId", e)
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

    /** Build genre mixes from cached tags, then fill missing genres from Navidrome's genre API. */
    suspend fun getGenreMixes(limit: Int = 6, songsPerGenre: Int = 30): List<GenreMix> =
        withContext(Dispatchers.Default) {
            val likedIds = likedSongDao.observeLikedIds().first().toHashSet()
            val groupedTags = songDao.getSongsWithGenres()
                .flatMap { entity ->
                    parseGenreTags(entity.genre).map { genre -> genre to entity }
                }
                .groupBy { (genre, _) -> normalizeGenreKey(genre) }
                .mapValues { (_, taggedSongs) ->
                    taggedSongs.first().first to taggedSongs.map { it.second }.distinctBy { it.remoteId }
                }

            val mixesByGenre = linkedMapOf<String, GenreMix>()
            groupedTags.values
                .filter { (_, songs) -> songs.size >= 3 }
                .sortedByDescending { (_, songs) -> songs.size }
                .take(limit)
                .forEach { (genre, entities) ->
                    val songs = entities.map { it.toSong(isLiked = it.remoteId in likedIds) }
                        .shuffled()
                        .take(songsPerGenre)
                    mixesByGenre[normalizeGenreKey(genre)] = GenreMix(genre, songs)
                }

            if (mixesByGenre.size < limit) {
                fetchNavidromeGenreMixes(
                    count = limit - mixesByGenre.size,
                    songsPerGenre = songsPerGenre,
                    excludedGenres = mixesByGenre.keys,
                    likedIds = likedIds
                ).forEach { mix -> mixesByGenre.putIfAbsent(normalizeGenreKey(mix.genre), mix) }
            }
            mixesByGenre.values.take(limit)
        }

    private suspend fun fetchNavidromeGenreMixes(
        count: Int,
        songsPerGenre: Int,
        excludedGenres: Set<String>,
        likedIds: Set<String>
    ): List<GenreMix> {
        if (count <= 0) return emptyList()
        return try {
            val config = preferencesRepository.awaitServerConnectionConfig()
            if (config.serverUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
                return emptyList()
            }
            val salt = newSubsonicSalt()
            val token = buildSubsonicToken(config.password, salt)
            val genresResponse = subsonicApi.getGenres(config.username, token, salt)
            if (genresResponse.response.status != "ok") return emptyList()

            val candidates = genresResponse.response.genres?.genres.orEmpty()
                .filter { (it.songCount ?: 0) >= 3 && normalizeGenreKey(it.value) !in excludedGenres }
                .distinctBy { normalizeGenreKey(it.value) }
                .sortedByDescending { it.songCount ?: 0 }
                .take(count)

            val genreEntries = coroutineScope {
                candidates.map { genre ->
                    async(Dispatchers.IO) {
                        try {
                            val response = subsonicApi.getSongsByGenre(
                                genre = genre.value,
                                count = songsPerGenre.coerceIn(3, 500),
                                user = config.username,
                                token = token,
                                salt = salt
                            )
                            if (response.response.status != "ok") null
                            else genre to response.response.songsByGenre?.songs.orEmpty()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            val entriesByGenre = genreEntries.filter { it.second.isNotEmpty() }
            if (entriesByGenre.isEmpty()) return emptyList()
            val allServerIds = entriesByGenre.flatMap { (_, entries) -> entries.map { it.id } }.distinct()
            val cachedByServerId = allServerIds.chunked(400).flatMap { songDao.getByNavidromeIds(it) }
                .groupBy { if (it.providerId == "navidrome") it.providerRemoteId else it.navidromeId.orEmpty() }
            val resolvedByServerId = mutableMapOf<String, SongEntity>()
            val entitiesToUpsert = linkedMapOf<String, SongEntity>()
            val mixes = entriesByGenre.mapNotNull { (genre, entries) ->
                val songs = entries.mapNotNull { entry ->
                    val existing = resolvedByServerId[entry.id]
                        ?: cachedByServerId[entry.id]?.firstOrNull { it.providerId == "local" }
                        ?: cachedByServerId[entry.id]?.firstOrNull()
                    val baseEntity = existing?.let { entitiesToUpsert[it.remoteId] ?: it }
                        ?: SongEntity(
                            remoteId = namespacedSongId("navidrome", entry.id),
                            providerId = "navidrome",
                            providerRemoteId = entry.id,
                            title = entry.title,
                            artist = entry.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
                            album = entry.album?.takeIf { it.isNotBlank() } ?: "Unknown Album",
                            albumId = entry.albumId.orEmpty(),
                            duration = (entry.duration?.toLong() ?: 0L) * 1_000L,
                            uriString = "terminus://${entry.id}",
                            dateAdded = System.currentTimeMillis(),
                            trackNumber = entry.track ?: 0,
                            year = entry.year ?: 0,
                            folderPath = entry.path.orEmpty(),
                            sizeBytes = entry.size ?: 0L,
                            genre = ""
                        )
                    val mergedGenres = (parseGenreTags(baseEntity.genre) + parseGenreTags(entry.genre) + genre.value)
                        .distinctBy(::normalizeGenreKey)
                        .joinToString("; ")
                    val entity = baseEntity.copy(genre = mergedGenres)
                    resolvedByServerId[entry.id] = entity
                    if (existing == null || entity != baseEntity) {
                        entitiesToUpsert[entity.remoteId] = entity
                    }
                    entity.toSong(isLiked = entity.remoteId in likedIds)
                }.distinctBy { it.id }.shuffled().take(songsPerGenre)
                songs.takeIf { it.size >= 3 }?.let { GenreMix(genre.value, it) }
            }
            entitiesToUpsert.values.toList().chunked(400).forEach { songDao.upsertAll(it) }
            mixes
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- Custom playlists ------------------------------------------------

    fun observeCustomPlaylists(): Flow<List<Playlist>> =
        playlistDao.observePlaylists().map { rows ->
            rows.map { row ->
                Playlist(
                    id = row.id,
                    name = row.name,
                    songCount = row.songCount,
                    ownerUsername = row.ownerUsername,
                    isRemote = row.id.startsWith(SERVER_PLAYLIST_PREFIX)
                )
            }
        }

    suspend fun getSongsForPlaylist(playlistId: String): List<Song> {
        try {
            refreshPlaylistSongsFromServer(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the cached playlist usable when the server is offline.
        }
        return getCachedSongsForPlaylist(playlistId)
    }

    /** Reads the persisted playlist snapshot without waiting for the network. */
    suspend fun getCachedSongsForPlaylist(playlistId: String): List<Song> = withContext(Dispatchers.Default) {
        val likedIds = likedSongDao.observeLikedIds().first().toHashSet()
        playlistDao.getSongsForPlaylist(playlistId).map { it.toSong(isLiked = it.remoteId in likedIds) }
            .groupBy { offlineTrackKey(it.title, it.artist) }
            .map { (_, group) -> group.find { it.providerId == "local" } ?: group.first() }
    }

    /** Refreshes the stored remote snapshot; callers can show cached songs while this runs. */
    suspend fun refreshPlaylistSongsFromServer(playlistId: String) {
        val playlist = playlistDao.getPlaylist(playlistId)
        if (playlist?.serverPlaylistId != null) {
            val refreshWindowMs = 5 * 60 * 1000L
            val snapshotIsFresh = playlist.remoteContentSignature != null &&
                playlist.remoteSnapshotSongCount == playlist.remoteSongCount &&
                System.currentTimeMillis() - playlist.remoteSnapshotCheckedAt < refreshWindowMs
            if (snapshotIsFresh) return
            refreshServerPlaylistSongs(playlist, replaceMembership = playlist.id.startsWith(SERVER_PLAYLIST_PREFIX))
        }
    }

    /** Checks the current playlist files and prior downloads before offering a transfer. */
    suspend fun getPlaylistDownloadPlan(
        songs: List<Song>,
        expectedTrackCount: Int = songs.size
    ): PlaylistDownloadPlan = withContext(Dispatchers.IO) {
        val prefs = preferencesRepository.preferences.first()
        val lookupKeys = playlistDownloadLookupKeys(songs)
        val playlistEntities = sqliteQueryBatches(lookupKeys.songIds).flatMap { songDao.getByIds(it) }
        val mappedLocalEntities = sqliteQueryBatches(lookupKeys.navidromeIds)
            .flatMap { songDao.getLocalSongsByNavidromeIds(it) }
        val titleCandidates = sqliteQueryBatches(lookupKeys.titles)
            .flatMap { songDao.getLocalSongsByTitles(it) }
        val entitiesById = playlistEntities.associateBy { it.remoteId }
        val localEntities = (playlistEntities.filter { it.providerId == "local" } + mappedLocalEntities + titleCandidates)
            .distinctBy { it.remoteId }
        val localsByServerId = localEntities.filter { !it.navidromeId.isNullOrBlank() }
            .groupBy { it.navidromeId.orEmpty() }
        val localsByTrack = localEntities.groupBy { offlineTrackKey(it.title, it.artist) }
        val offlineIds = mutableSetOf<String>()
        val unavailableIds = mutableSetOf<String>()
        val downloadEntitiesBySong = songs.mapNotNull { song ->
            val entity = entitiesById[song.id]
            val serverId = if (song.providerId == "navidrome") song.providerRemoteId else song.navidromeId.orEmpty()
            val matchingLocals = (localsByServerId[serverId].orEmpty() + localsByTrack[offlineTrackKey(song.title, song.artist)].orEmpty())
                .distinctBy { it.remoteId }
            val localMatch = if (song.providerId == "local") entity else matchingLocals.firstOrNull()
            val downloadEntity = entity ?: localMatch
            if (serverId.isNotBlank() && downloadEntity != null &&
                (song.providerId == "navidrome" || !song.navidromeId.isNullOrBlank())
            ) song.id to downloadEntity else null
        }.toMap()
        val existingDownloads = runCatching {
            findExistingDownloads(downloadEntitiesBySong.values.map(::downloadFileName).toSet(), prefs.downloadFolderUri)
        }.getOrDefault(emptyMap())

        songs.forEach { song ->
            val entity = entitiesById[song.id]
            val serverId = when {
                song.providerId == "navidrome" -> song.providerRemoteId
                else -> song.navidromeId.orEmpty()
            }
            val matchingLocals = (localsByServerId[serverId].orEmpty() + localsByTrack[offlineTrackKey(song.title, song.artist)].orEmpty())
                .distinctBy { it.remoteId }
            val localFileAvailable = (matchingLocals + listOfNotNull(entity?.takeIf { it.providerId == "local" }))
                .distinctBy { it.remoteId }
                .any { isReadableUri(it.uriString) }
            if (localFileAvailable) {
                offlineIds += song.id
                return@forEach
            }

            val currentDownload = entity?.downloadedUri?.let(Uri::parse)?.takeIf(::isReadableDownload)
            if (currentDownload != null) {
                offlineIds += song.id
                return@forEach
            }
            if (entity?.downloadedUri != null) songDao.setDownloadedUri(entity.remoteId, null)

            if (song.providerId == "local" && song.navidromeId.isNullOrBlank()) {
                unavailableIds += song.id
                return@forEach
            }
            if (song.providerId != "navidrome" && song.navidromeId.isNullOrBlank()) {
                unavailableIds += song.id
                return@forEach
            }

            val downloadEntity = downloadEntitiesBySong[song.id]
            if (downloadEntity == null || serverId.isBlank()) {
                unavailableIds += song.id
                return@forEach
            }
            val existingFile = existingDownloads[downloadFileName(downloadEntity)]
            if (existingFile != null) {
                songDao.setDownloadedUri(downloadEntity.remoteId, existingFile.toString())
                offlineIds += song.id
            } else if (song.providerId == "navidrome" || !song.navidromeId.isNullOrBlank()) {
                // A valid server identity is enough to queue it; the server will report if it is unavailable.
            } else {
                unavailableIds += song.id
            }
        }

        buildPlaylistDownloadPlan(songs, offlineIds, unavailableIds, expectedTrackCount)
    }

    private fun isReadableUri(uriString: String): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uriString), "r")?.use { it.length != 0L } ?: false
    } catch (_: Exception) {
        false
    }

    private fun offlineTrackKey(title: String, artist: String) =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    suspend fun refreshRemotePlaylists() = withContext(Dispatchers.IO) {
        val (user, token, salt) = subsonicCredentials()
        _playlistAccountInfo.value = PlaylistAccountInfo(username = user)
        val response = subsonicApi.getPlaylists(user, token, salt)
        if (response.response.status != "ok") throw IOException("Could not load server playlists")
        response.response.playlists?.items.orEmpty().forEach { remote ->
            val existing = playlistDao.getPlaylistByServerId(remote.id)
            if (existing == null) {
                playlistDao.insertPlaylist(
                    PlaylistEntity(
                        id = SERVER_PLAYLIST_PREFIX + remote.id,
                        name = remote.name,
                        createdAt = System.currentTimeMillis(),
                        serverPlaylistId = remote.id,
                        remoteSongCount = remote.songCount ?: 0,
                        ownerUsername = remote.owner
                    )
                )
            } else {
                playlistDao.updateRemotePlaylist(
                    remote.id,
                    remote.name,
                    remote.songCount ?: existing.remoteSongCount,
                    remote.owner ?: existing.ownerUsername
                )
            }
        }
        try {
            val accountResponse = subsonicApi.getUser(user, user, token, salt)
            val account = accountResponse.response.user
            if (accountResponse.response.status == "ok" && account != null) {
                _playlistAccountInfo.value = PlaylistAccountInfo(
                    username = account.username?.takeIf { it.isNotBlank() } ?: user,
                    isAdmin = account.adminRole,
                    isLoaded = true
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Playlist browsing remains available if the server lacks getUser support.
            _playlistAccountInfo.value = PlaylistAccountInfo(username = user, isLoaded = true)
        }
        if (!_playlistAccountInfo.value.isLoaded) {
            _playlistAccountInfo.value = PlaylistAccountInfo(username = user, isLoaded = true)
        }
    }

    private suspend fun refreshServerPlaylistSongs(playlist: PlaylistEntity, replaceMembership: Boolean) {
        val serverId = playlist.serverPlaylistId ?: return
        val (user, token, salt) = subsonicCredentials()
        val response = subsonicApi.getPlaylist(serverId, user, token, salt)
        if (response.response.status != "ok") throw IOException("Could not load playlist tracks")
        val remote = response.response.playlist ?: throw IOException("Server returned no playlist data")
        val entries = remote.entries.orEmpty()
        val signature = java.security.MessageDigest.getInstance("SHA-256")
            .digest(entries.joinToString("\u0000") { it.id }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val checkedAt = System.currentTimeMillis()
        if (playlist.remoteContentSignature == signature) {
            playlistDao.updateRemoteSnapshot(playlist.id, entries.size, signature, checkedAt)
            return
        }

        val entryIds = entries.map { it.id }.distinct()
        val existingSongs = entryIds.chunked(400).flatMap { songDao.getByNavidromeIds(it) }
        val byNavidromeId = buildMap {
            existingSongs.forEach { song ->
                if (song.providerId == "navidrome") put(song.providerRemoteId, song)
                song.navidromeId?.let { put(it, song) }
            }
        }
        val missingSongs = entries.filter { it.id !in byNavidromeId }.distinctBy { it.id }.map { entry ->
            SongEntity(
                remoteId = namespacedSongId("navidrome", entry.id),
                providerId = "navidrome",
                providerRemoteId = entry.id,
                title = entry.title?.takeIf { it.isNotBlank() } ?: entry.id,
                artist = entry.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
                album = entry.album?.takeIf { it.isNotBlank() } ?: "Unknown Album",
                albumId = entry.albumId.orEmpty(),
                duration = (entry.duration?.toLong() ?: 0L) * 1000L,
                uriString = "terminus://${entry.id}",
                dateAdded = System.currentTimeMillis(),
                trackNumber = entry.track ?: 0,
                year = entry.year ?: 0,
                folderPath = entry.path.orEmpty(),
                sizeBytes = entry.size ?: 0L
            )
        }
        val mappedIds = entries.mapNotNull { entry ->
            byNavidromeId[entry.id]?.remoteId ?: namespacedSongId("navidrome", entry.id).takeIf { missingSongs.any { song -> song.remoteId == it } }
        }.distinct()
        val currentMemberships = playlistDao.getPlaylistMemberships(playlist.id)
        val currentIds = currentMemberships.map { it.songId }.toSet()
        val newMemberships = if (replaceMembership) {
            mappedIds.mapIndexed { index, id -> PlaylistSongEntity(playlist.id, id, index, syncedToServer = true) }
        } else {
            var position = playlistDao.nextSongPosition(playlist.id)
            mappedIds.filterNot { it in currentIds }.map { id ->
                PlaylistSongEntity(playlist.id, id, position++, syncedToServer = true)
            }
        }

        database.withTransaction {
            missingSongs.chunked(900).forEach { songDao.upsertAll(it) }
            if (replaceMembership) playlistDao.purgePlaylistSongs(playlist.id)
            if (newMemberships.isNotEmpty()) newMemberships.chunked(400).forEach { playlistDao.insertPlaylistSongs(it) }
            playlistDao.updateRemoteSnapshot(playlist.id, entries.size, signature, checkedAt)
        }
    }

    private suspend fun subsonicCredentials(): Triple<String, String, String> {
        val config = preferencesRepository.awaitServerConnectionConfig()
        if (config.username.isBlank() || config.password.isBlank() ||
            com.necroware.terminusplayer.data.provider.parseNavidromeBaseUrl(config.serverUrl) == null
        ) throw IOException("Navidrome server settings are incomplete or invalid")
        val salt = newSubsonicSalt()
        return Triple(config.username, buildSubsonicToken(config.password, salt), salt)
    }

    companion object {
        const val SERVER_PLAYLIST_PREFIX = "server:"
    }

    suspend fun getPlaylistName(playlistId: String): String = playlistDao.getPlaylistName(playlistId) ?: "PLAYLIST"

    suspend fun getRemotePlaylistSongCount(playlistId: String): Int =
        playlistDao.getRemoteSongCount(playlistId) ?: 0

    suspend fun createPlaylist(name: String): String {
        val cleanedName = name.trim()
        require(cleanedName.isNotEmpty()) { "Playlist name cannot be empty" }
        val id = java.util.UUID.randomUUID().toString()
        playlistDao.insertPlaylist(
            PlaylistEntity(id = id, name = cleanedName, createdAt = System.currentTimeMillis(), syncPending = true)
        )
        enqueuePlaylistSync()
        return id
    }

    suspend fun deletePlaylist(playlistId: String) {
        val playlist = playlistDao.getPlaylist(playlistId) ?: return
        if (playlist.serverPlaylistId == null) {
            playlistDao.purgePlaylistSongs(playlistId)
            playlistDao.purgePlaylist(playlistId)
        } else {
            playlistDao.markPlaylistDeletePending(playlistId)
            enqueuePlaylistSync()
        }
    }

    suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean {
        val playlist = playlistDao.getPlaylist(playlistId) ?: return false
        val song = songDao.getById(songId) ?: return false
        if (playlistDao.containsSong(playlistId, songId)) return false
        val position = playlistDao.nextSongPosition(playlistId)
        val localAlreadyOnServer = song.providerId == "local"
        playlistDao.insertPlaylistSongIfMissing(
            PlaylistSongEntity(playlistId, songId, position, syncedToServer = localAlreadyOnServer)
        )
        if (playlist.serverPlaylistId != null || song.providerId == "navidrome") {
            playlistDao.markPlaylistPending(playlistId)
            enqueuePlaylistSync()
        }
        return true
    }

    suspend fun syncPendingPlaylists(): Boolean {
        val config = preferencesRepository.awaitServerConnectionConfig()
        if (config.username.isBlank() || config.password.isBlank() ||
            com.necroware.terminusplayer.data.provider.parseNavidromeBaseUrl(config.serverUrl) == null
        ) return false
        val salt = newSubsonicSalt()
        val token = buildSubsonicToken(config.password, salt)

        for (initial in playlistDao.getPendingPlaylists()) {
            val playlist = playlistDao.getPlaylist(initial.id) ?: continue
            val serverId = playlist.serverPlaylistId
            if (playlist.deletePending) {
                if (serverId != null) {
                    val response = subsonicApi.deletePlaylist(serverId, config.username, token, salt)
                    if (response.response.status != "ok" && response.response.error?.code != 70) return false
                }
                playlistDao.purgePlaylistSongs(playlist.id)
                playlistDao.purgePlaylist(playlist.id)
                continue
            }

            val playlistSongs = playlistDao.getPlaylistSongsForSync(playlist.id)
            if (serverId == null && playlistSongs.none { it.providerId == "navidrome" }) {
                val localSongs = playlistSongs.filter { it.providerId == "local" }.map { it.songId }
                if (localSongs.isNotEmpty()) playlistDao.markPlaylistSongsSynced(playlist.id, localSongs)
                playlistDao.markPlaylistSynced(playlist.id)
                continue
            }

            val resolvedServerId = if (serverId == null) {
                val created = subsonicApi.createPlaylist(
                    name = playlist.name,
                    user = config.username,
                    token = token,
                    salt = salt
                )
                if (created.response.status != "ok") return false
                val newId = created.response.playlist?.id ?: return false
                playlistDao.markPlaylistCreated(playlist.id, newId)
                newId
            } else serverId

            val remotePlaylist = subsonicApi.getPlaylist(resolvedServerId, config.username, token, salt)
            if (remotePlaylist.response.status != "ok") return false
            val remotePlaylistData = remotePlaylist.response.playlist ?: return false
            val existingServerSongIds = remotePlaylistData.entries.orEmpty().map { it.id }.toSet()
            val alreadyPresentLocalIds = playlistSongs.filter { it.providerId == "navidrome" && it.providerRemoteId in existingServerSongIds }.map { it.songId }
            if (alreadyPresentLocalIds.isNotEmpty()) {
                playlistDao.markPlaylistSongsSynced(playlist.id, alreadyPresentLocalIds)
            }
            val localSongs = playlistSongs.filter { it.providerId == "local" && !it.syncedToServer }.map { it.songId }
            if (localSongs.isNotEmpty()) playlistDao.markPlaylistSongsSynced(playlist.id, localSongs)

            val serverSongsToAdd = playlistSongs.filter {
                it.providerId == "navidrome" && !it.syncedToServer && it.providerRemoteId !in existingServerSongIds
            }
            if (serverSongsToAdd.isNotEmpty()) {
                val added = subsonicApi.updatePlaylist(
                    playlistId = resolvedServerId,
                    songIdsToAdd = serverSongsToAdd.map { it.providerRemoteId },
                    user = config.username,
                    token = token,
                    salt = salt
                )
                if (added.response.status != "ok") return false
                playlistDao.markPlaylistSongsSynced(playlist.id, serverSongsToAdd.map { it.songId })
            }
            playlistDao.markPlaylistSynced(playlist.id)
        }
        return true
    }

    private fun enqueuePlaylistSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "playlist-sync",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun retryPendingPlaylistSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "playlist-sync",
            ExistingWorkPolicy.REPLACE,
            request
        )
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
            PlaylistEntity(id = playlistId, name = name, createdAt = System.currentTimeMillis(), syncPending = true),
            distinctMatched.mapIndexed { index, song -> PlaylistSongEntity(playlistId, song.id, index) }
        )
        enqueuePlaylistSync()
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

fun SongEntity.toSong(isLiked: Boolean = false): Song = Song(
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
    isLiked = isLiked,
    downloadedUri = downloadedUri,
    genre = genre,
    navidromeId = navidromeId
)

private fun namespacedSongId(providerId: String, providerRemoteId: String): String =
    "$providerId:$providerRemoteId"
