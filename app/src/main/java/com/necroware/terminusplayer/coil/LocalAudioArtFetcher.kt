package com.necroware.terminusplayer.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalAudioUri(val uriString: String)

class LocalAudioArtFetcher(
    private val data: LocalAudioUri,
    private val options: Options,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val uri = try {
            Uri.parse(data.uriString)
        } catch (e: Exception) {
            return@withContext null
        }
        
        val bitmap = loadThumbnailSafely(context, uri, options)
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
        val reqSize = options.size.run {
            if (this == coil.size.Size.ORIGINAL) {
                512 // fallback size
            } else {
                val width = (this.width as? coil.size.Dimension.Pixels)?.px ?: 512
                val height = (this.height as? coil.size.Dimension.Pixels)?.px ?: 512
                maxOf(width, height)
            }
        }

        try {
            return context.contentResolver.loadThumbnail(uri, Size(reqSize, reqSize), null)
        } catch (_: Exception) {
            try {
                val mmr = MediaMetadataRetriever()
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    mmr.setDataSource(pfd.fileDescriptor)
                    val pic = mmr.embeddedPicture
                    if (pic != null) {
                        val decodeOptions = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeByteArray(pic, 0, pic.size, decodeOptions)
                        decodeOptions.inSampleSize = calculateInSampleSize(decodeOptions, reqSize, reqSize)
                        decodeOptions.inJustDecodeBounds = false
                        return BitmapFactory.decodeByteArray(pic, 0, pic.size, decodeOptions)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            return null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    class Factory(private val context: Context) : Fetcher.Factory<LocalAudioUri> {
        override fun create(data: LocalAudioUri, options: Options, imageLoader: ImageLoader): Fetcher {
            return LocalAudioArtFetcher(data, options, context)
        }
    }
}
