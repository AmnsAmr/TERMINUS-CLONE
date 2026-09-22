package com.necroware.terminusplayer.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Size

class LocalAudioArtFetcher(
    private val data: Uri,
    private val options: Options,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val bitmap = loadThumbnailSafely(context, data, options)
        if (bitmap != null) {
            DrawableResult(
                drawable = BitmapDrawable(context.resources, bitmap),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } else {
            null
        }
    }

    private fun loadThumbnailSafely(context: Context, uri: Uri, options: Options): Bitmap? {
        val sizePx = options.size.run {
            if (this == coil.size.Size.ORIGINAL) {
                512 // fallback size
            } else {
                val width = (this.width as? coil.size.Dimension.Pixels)?.px ?: 512
                val height = (this.height as? coil.size.Dimension.Pixels)?.px ?: 512
                maxOf(width, height)
            }
        }

        try {
            return context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        } catch (_: Exception) {
            try {
                val mmr = MediaMetadataRetriever()
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    mmr.setDataSource(pfd.fileDescriptor)
                    val pic = mmr.embeddedPicture
                    if (pic != null) {
                        return BitmapFactory.decodeByteArray(pic, 0, pic.size)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            return null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            // Check if this is an audio file content URI
            if (data.scheme == "content" && data.toString().contains("audio")) {
                return LocalAudioArtFetcher(data, options, context)
            }
            return null
        }
    }
}
