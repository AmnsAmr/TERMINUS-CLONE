package com.necroware.terminusplayer.data.provider

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.normalizeGenreKey
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.necroware.terminusplayer.data.model.SyncedLyrics

@Singleton
class LocalMediaProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefsRepo: UserPreferencesRepository
) : MediaProvider {

    override val providerId: String = "local"

    override suspend fun syncLibrary(): List<SongEntity> = withContext(Dispatchers.IO) {
        val prefs = userPrefsRepo.preferences.first()
        var excludedFolders = prefs.excludedFolders
        val hasSetupDefaults = prefs.hasSetupDefaultExcludes
        val defaultExcludedFound = mutableSetOf<String>()

        val songs = mutableListOf<SongEntity>()
        val genresByAudioId = loadGenresByAudioId()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.SIZE
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val relativePathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            if (dataCol < 0 && relativePathCol < 0) {
                throw ProviderSyncException("MediaStore exposes neither DATA nor RELATIVE_PATH", retryable = false)
            }
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                val folderPath = path?.let { File(it).parent }
                    ?: (if (relativePathCol >= 0) cursor.getString(relativePathCol)?.trimEnd('/') else null)
                    ?: ""
                
                if (!hasSetupDefaults) {
                    if (folderPath.contains("WhatsApp", ignoreCase = true) || folderPath.contains("Voice Recorder", ignoreCase = true)) {
                        defaultExcludedFound.add(folderPath)
                    }
                }

                if (folderPath in excludedFolders || folderPath in defaultExcludedFound) {
                    continue
                }

                val contentUri = ContentUris.withAppendedId(collection, id)

                songs += SongEntity(
                    remoteId = id.toString(),
                    providerId = providerId,
                    title = cursor.getString(titleCol) ?: "Unknown",
                    artist = cursor.getString(artistCol) ?: "Unknown Artist",
                    album = cursor.getString(albumCol) ?: "Unknown Album",
                    albumId = cursor.getLong(albumIdCol).toString(),
                    duration = cursor.getLong(durationCol),
                    uriString = contentUri.toString(),
                    dateAdded = cursor.getLong(dateAddedCol),
                    trackNumber = cursor.getInt(trackCol) % 1000,
                    year = cursor.getInt(yearCol),
                    genre = genresByAudioId[id].orEmpty(),
                    folderPath = folderPath,
                    sizeBytes = cursor.getLong(sizeCol)
                )
            }
        }

        if (!hasSetupDefaults) {
            userPrefsRepo.addExcludedFolders(defaultExcludedFound)
            userPrefsRepo.setHasSetupDefaultExcludes(true)
        }

        songs
    }

    /** MediaStore keeps genres in a separate table, so attach them during the same library scan. */
    private fun loadGenresByAudioId(): Map<Long, String> {
        val genresByAudioId = mutableMapOf<Long, LinkedHashSet<String>>()
        return try {
            val genresUri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
            val genreProjection = arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME)

            context.contentResolver.query(genresUri, genreProjection, null, null, null)?.use { genres ->
                val idColumn = genres.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val nameColumn = genres.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
                while (genres.moveToNext()) {
                    val genreId = genres.getLong(idColumn)
                    val genreName = genres.getString(nameColumn)?.trim().orEmpty()
                    if (genreName.isBlank()) continue

                    val membersUri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                    val memberProjection = arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID)
                    context.contentResolver.query(membersUri, memberProjection, null, null, null)?.use { members ->
                        val audioIdColumn = members.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members.AUDIO_ID)
                        while (members.moveToNext()) {
                            val genres = genresByAudioId.getOrPut(members.getLong(audioIdColumn)) { linkedSetOf() }
                            if (genres.none { normalizeGenreKey(it) == normalizeGenreKey(genreName) }) {
                                genres += genreName
                            }
                        }
                    }
                }
            }
            genresByAudioId.mapValues { (_, genres) -> genres.joinToString("; ") }
        } catch (_: Exception) {
            // Genre support varies across MediaStore implementations; scanning still works without it.
            emptyMap()
        }
    }

    override suspend fun resolveStreamUrl(remoteId: String): String {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        return ContentUris.withAppendedId(collection, remoteId.toLong()).toString()
    }

    override suspend fun scrobble(remoteId: String) {
        // No-op for local provider
    }

    override suspend fun toggleLike(remoteId: String, isLiked: Boolean) {
        // No-op for local provider
    }

    override suspend fun getLyrics(remoteId: String): SyncedLyrics? {
        return null
    }
}
