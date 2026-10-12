package com.necroware.terminusplayer.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.necroware.terminusplayer.data.model.parseLyricsText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Reads lyrics from an audio tag and then its exact-name sibling .lrc file. */
@Singleton
class EmbeddedLyricsReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun confirmsPermanentDeletion(uriString: String): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext false
        if (uri.scheme == "file") return@withContext uri.path?.let(::File)?.exists() == false
        if (uri.scheme != "content") return@withContext false
        try {
            val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?: return@withContext false
            cursor.use { !it.moveToFirst() }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun read(
        uriString: String,
        downloadTreeUri: String? = null,
        reportExtractionErrors: Boolean = false
    ): SyncedLyrics? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
        val displayName = queryDisplayName(uri)
        val directFile = findReadableFile(uri)
        var extractionFailure: Exception? = null
        try {
            if (directFile != null) {
                try {
                    readTag(directFile)?.let { return@withContext it }
                } catch (e: Exception) {
                    android.util.Log.w("EmbeddedLyricsReader", "Audio lyric metadata could not be read", e)
                    extractionFailure = e
                }
                siblingLrc(directFile, displayName)?.let { return@withContext it }
            } else {
                val temp = copyToTemporaryAudioFile(uri, displayName) ?: return@withContext findDownloadSidecar(displayName, downloadTreeUri)
                try {
                    try {
                        readTag(temp)?.let { return@withContext it }
                    } catch (e: Exception) {
                        android.util.Log.w("EmbeddedLyricsReader", "Audio lyric metadata could not be read", e)
                        extractionFailure = e
                    }
                } finally {
                    temp.delete()
                }
                findDownloadSidecar(displayName, downloadTreeUri)?.let { return@withContext it }
            }
            if (reportExtractionErrors && extractionFailure != null) throw extractionFailure
            null
        } catch (_: SecurityException) {
            if (reportExtractionErrors) throw SecurityException("Audio lyrics could not be read due to missing file access")
            null
        } catch (e: Exception) {
            if (reportExtractionErrors) throw e
            null
        }
    }

    private fun readTag(file: File): SyncedLyrics? = readEmbeddedLyricsTag(file)

    private fun findReadableFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        val path = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return path?.let(::File)?.takeIf { it.isFile && it.canRead() }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun siblingLrc(audioFile: File, displayName: String?): SyncedLyrics? {
        val base = (displayName ?: audioFile.name).substringBeforeLast('.', displayName ?: audioFile.name)
        val candidate = File(audioFile.parentFile, "$base.lrc")
            .takeIf { it.isFile && it.canRead() }
            ?: File(audioFile.parentFile, "$base.LRC").takeIf { it.isFile && it.canRead() }
            ?: return null
        return parseLyricsText(candidate.readText())
    }

    private fun copyToTemporaryAudioFile(uri: Uri, displayName: String?): File? {
        val extension = displayName?.substringAfterLast('.', "")?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val temp = File.createTempFile("terminus-lyrics-", extension?.let { ".$it" } ?: ".audio", context.cacheDir)
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: run { temp.delete(); return null }
            input.use { source ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_TEMP_AUDIO_BYTES) throw IOException("Audio file is too large for metadata parsing")
                        output.write(buffer, 0, count)
                    }
                }
            }
            temp
        } catch (_: Exception) {
            android.util.Log.w("EmbeddedLyricsReader", "Temporary audio copy failed; trying sidecar lyrics")
            temp.delete()
            null
        }
    }

    private fun findDownloadSidecar(displayName: String?, treeUriString: String?): SyncedLyrics? {
        if (displayName.isNullOrBlank() || treeUriString.isNullOrBlank()) return null
        val base = displayName.substringBeforeLast('.', displayName)
        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return null
        return try {
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                childrenUri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn)
                    if (name.equals("$base.lrc", true)) {
                        val lrcUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                        return@use context.contentResolver.openInputStream(lrcUri)?.bufferedReader()?.use { parseLyricsText(it.readText()) }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_TEMP_AUDIO_BYTES = 256L * 1024L * 1024L
    }
}

internal fun readEmbeddedLyricsTag(file: File): SyncedLyrics? {
    if (!file.isFile || !file.canRead()) return null
    val raw = AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS)
    return parseLyricsText(raw)
}
