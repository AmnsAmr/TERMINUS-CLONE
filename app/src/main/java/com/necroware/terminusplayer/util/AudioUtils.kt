package com.necroware.terminusplayer.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.FFmpegSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioUtils @Inject constructor() : AudioConverter {

    override fun isOpus(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var foundOpus = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/opus") == true || mime?.contains("opus", ignoreCase = true) == true) {
                    foundOpus = true
                    break
                }
            }
            foundOpus
        } catch (e: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    override suspend fun convertOpusToAac(context: Context, inputUri: Uri, originalName: String): File = withContext(Dispatchers.IO) {
        // Need a real path for FFmpeg, but we have a URI.
        // Easiest is to use FFmpeg's Saf interface if supported, or copy to a temp file first.
        // Let's copy to a temp file.
        val tempInput = File.createTempFile("temp_in", ".opus", context.cacheDir)
        val newNameBase = originalName.substringBeforeLast(".")
        val tempOutput = File.createTempFile("temp_out_${newNameBase}", ".m4a", context.cacheDir)
        var keepOutput = false
        
        try {
            val coroutineContext = currentCoroutineContext()
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                tempInput.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        output.write(buffer, 0, bytesRead)
                    }
                }
            } ?: throw IOException("Could not open input stream")

            // FFmpeg command: convert to aac, 256k bitrate, preserve metadata, drop video
            // -c:a aac -b:a 256k -vn -map_metadata 0
            val command = "-y -i \"${tempInput.absolutePath}\" -c:a aac -b:a 256k -vn -map_metadata 0 \"${tempOutput.absolutePath}\""
            val session = executeCancellable(command)
            
            if (!ReturnCode.isSuccess(session.returnCode)) {
                val logs = session.logsAsString
                throw IOException("FFmpeg conversion failed: $logs")
            }
            keepOutput = true
            tempOutput
        } finally {
            tempInput.delete()
            if (!keepOutput) tempOutput.delete()
        }
    }

    private suspend fun executeCancellable(command: String): FFmpegSession =
        suspendCancellableCoroutine { continuation ->
            val sessionReference = AtomicReference<FFmpegSession?>(null)
            continuation.invokeOnCancellation {
                sessionReference.get()?.let { FFmpegKit.cancel(it.sessionId) }
            }
            val session = FFmpegKit.executeAsync(command) { completedSession ->
                if (continuation.isActive) continuation.resume(completedSession)
            }
            sessionReference.set(session)
            if (!continuation.isActive) FFmpegKit.cancel(session.sessionId)
        }
}
