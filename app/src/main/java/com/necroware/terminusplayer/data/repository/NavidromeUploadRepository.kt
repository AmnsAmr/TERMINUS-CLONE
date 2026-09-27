package com.necroware.terminusplayer.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.necroware.terminusplayer.data.api.custom.NavidromeNativeApiService
import com.necroware.terminusplayer.data.api.custom.UploadCheckRequest
import com.necroware.terminusplayer.data.api.custom.UploadQuotaResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okio.source
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavidromeUploadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: NavidromeNativeApiService
) {
    suspend fun quota(): UploadQuotaResponse? = try {
        val response = api.getUploadQuota()
        if (response.isSuccessful) response.body() else {
            response.errorBody()?.close()
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    suspend fun upload(uri: Uri): String {
        val (name, size) = withContext(Dispatchers.IO) { fileMetadataFor(uri) }
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) throw IOException("Unsupported audio format")
        if (size != null && size > MAX_UPLOAD_BYTES) throw IOException("File exceeds the 100 MB limit")

        val check = api.checkUploads(UploadCheckRequest(listOf(name)))
        val checkResult = check.body()
        if (!check.isSuccessful || checkResult == null) {
            check.errorBody()?.close()
            throw IOException("Could not check for duplicate files (${check.code()})")
        }
        if (name !in checkResult.accepted) {
            if (name in checkResult.rejected) throw IOException("$name already exists or is pending")
            throw IOException("Server did not accept $name for upload")
        }

        val contentType = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) }
        val requestBody = object : okhttp3.RequestBody() {
            override fun contentType() = (contentType ?: "application/octet-stream").toMediaTypeOrNull()
            override fun contentLength(): Long = size ?: -1L
            override fun writeTo(sink: okio.BufferedSink) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Selected file is no longer available")
                input.use { stream -> stream.source().use { source -> sink.writeAll(source) } }
            }
        }
        val response = api.uploadFile(MultipartBody.Part.createFormData("file", name, requestBody))
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            val message = when (response.code()) {
                403 -> "Server quota exceeded"
                400, 413 -> "File is too large or invalid"
                else -> "Upload request returned ${response.code()}"
            }
            throw IOException(message)
        }
        return name
    }

    private fun fileMetadataFor(uri: Uri): Pair<String, Long?> {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameColumn >= 0) cursor.getString(nameColumn).orEmpty() else ""
                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    cursor.getLong(sizeColumn).takeIf { it >= 0L }
                } else null
                if (name.isNotBlank()) return name to size
            }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: throw IOException("Could not determine selected filename")
        return fallback to null
    }

    private companion object {
        val ALLOWED_EXTENSIONS = setOf("flac", "mp3", "m4a", "ogg", "opus")
        const val MAX_UPLOAD_BYTES = 100L * 1024L * 1024L
    }
}
