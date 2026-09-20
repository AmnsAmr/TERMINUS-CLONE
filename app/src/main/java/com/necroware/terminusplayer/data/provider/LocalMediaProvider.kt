package com.necroware.terminusplayer.data.provider

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.necroware.terminusplayer.data.database.entity.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaProvider {

    override val providerId: String = "local"

    override suspend fun syncLibrary(): List<SongEntity> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<SongEntity>()

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
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol) ?: ""
                val folderPath = File(path).parent ?: ""
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
                    folderPath = folderPath,
                    sizeBytes = cursor.getLong(sizeCol)
                )
            }
        }

        songs
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
}
