package com.necroware.terminusplayer.util

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.necroware.terminusplayer.data.model.Song

private const val EXTRA_ALBUM_ID = "com.necroware.terminusplayer.ALBUM_ID"
private const val EXTRA_SIZE_BYTES = "com.necroware.terminusplayer.SIZE_BYTES"
private const val EXTRA_PROVIDER_ID = "com.necroware.terminusplayer.PROVIDER_ID"
private const val EXTRA_FOLDER_PATH = "com.necroware.terminusplayer.FOLDER_PATH"
private const val EXTRA_STREAM_OFFSET_MS = "com.necroware.terminusplayer.STREAM_OFFSET_MS"

fun Song.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putString(EXTRA_ALBUM_ID, albumId)
        putLong(EXTRA_SIZE_BYTES, sizeBytes)
        putString(EXTRA_PROVIDER_ID, providerId)
        putString(EXTRA_FOLDER_PATH, folderPath)
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setDurationMs(duration.coerceAtLeast(0L))
        .setExtras(extras)
        .build()

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.parse(uriString))
        .setMediaMetadata(metadata)
        .build()
}

fun List<Song>.toMediaItems(): List<MediaItem> = map { it.toMediaItem() }

/** Reads the albumId stashed in MediaMetadata.extras, or null if absent/not a Song-derived item. */
fun MediaMetadata.albumIdOrNull(): String? = extras?.getString(EXTRA_ALBUM_ID)

/** Reads the file size (bytes) stashed in MediaMetadata.extras, or 0 if absent. */
fun MediaMetadata.sizeBytesOrZero(): Long = extras?.getLong(EXTRA_SIZE_BYTES, 0L) ?: 0L

fun MediaMetadata.localFolderPathOrNull(): String? {
    if (extras?.getString(EXTRA_PROVIDER_ID) != "local") return null
    return extras?.getString(EXTRA_FOLDER_PATH)
}

fun MediaMetadata.providerIdOrNull(): String? = extras?.getString(EXTRA_PROVIDER_ID)

fun MediaMetadata.streamOffsetMsOrZero(): Long =
    extras?.getLong(EXTRA_STREAM_OFFSET_MS, 0L) ?: 0L

fun MediaItem.withStreamUriAndOffset(uri: String, offsetMs: Long): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
        putLong(EXTRA_STREAM_OFFSET_MS, offsetMs.coerceAtLeast(0L))
    }
    return buildUpon()
        .setUri(Uri.parse(uri))
        .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
        .build()
}
