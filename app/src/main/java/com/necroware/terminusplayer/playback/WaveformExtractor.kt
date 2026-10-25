package com.necroware.terminusplayer.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

@Singleton
class WaveformExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val cacheDir by lazy {
        File(context.filesDir, "waveforms").apply { mkdirs() }
    }

    /**
     * Checks if a waveform for [cacheKey] is already cached on disk.
     * Returns the cached [WaveformData] or null if not found/invalid.
     */
    fun getCachedWaveform(cacheKey: String): WaveformData? {
        val file = File(cacheDir, "$cacheKey.bin")
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val buckets = WaveformMath.deserializeWaveform(bytes)
            buckets?.let { WaveformData(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes audio from [uri] using [MediaExtractor] and [MediaCodec] to extract
     * ~300 peak amplitude buckets. Caches the result in `filesDir/waveforms/`.
     *
     * Runs strictly on [Dispatchers.IO] in non-blocking hardware decode mode
     * and responds immediately to coroutine cancellation.
     */
    suspend fun extractWaveform(
        uri: Uri,
        cacheKey: String,
        durationMs: Long
    ): WaveformData? = withContext(Dispatchers.IO) {
        // 1. Check disk cache first
        getCachedWaveform(cacheKey)?.let { return@withContext it }

        if (durationMs <= 0L) return@withContext null

        val isNetworkUri = uri.scheme == "http" || uri.scheme == "https"
        val pfd = if (!isNetworkUri) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (_: Exception) {
                null
            } ?: return@withContext null
        } else null

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            if (pfd != null) {
                extractor.setDataSource(pfd.fileDescriptor)
            } else {
                extractor.setDataSource(uri.toString())
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) return@withContext null
            extractor.selectTrack(audioTrackIndex)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } ?: (durationMs * 1000L)
            } else {
                durationMs * 1000L
            }

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

            val sumSquares = DoubleArray(WAVEFORM_BUCKET_COUNT)
            val sampleCounts = IntArray(WAVEFORM_BUCKET_COUNT)
            val peaks = FloatArray(WAVEFORM_BUCKET_COUNT)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            val timeoutUs = 0L // Non-blocking: unthrottled hardware silicon decode (<40ms)
            var emptyCycles = 0

            var sampleRate = 44100
            var channelCount = 2
            if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            }
            if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            }

            while (!sawOutputEOS && coroutineContext.isActive) {
                var progressMade = false

                if (!sawInputEOS) {
                    val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        progressMade = true
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    progressMade = true
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }

                    if (bufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val totalSamples = shortBuffer.remaining()
                            val startUs = bufferInfo.presentationTimeUs

                            // Step 2 audio frames per measurement (e.g. 4 shorts in stereo)
                            val stepFrames = 2
                            val stepSamples = stepFrames * channelCount
                            var sampleIdx = 0

                            while (sampleIdx < totalSamples) {
                                val frameIdx = sampleIdx / channelCount
                                val currentSampleUs = startUs + (frameIdx * 1_000_000L / sampleRate)
                                val bucketIdx = ((currentSampleUs.toDouble() / durationUs) * WAVEFORM_BUCKET_COUNT)
                                    .toInt()
                                    .coerceIn(0, WAVEFORM_BUCKET_COUNT - 1)

                                val left = shortBuffer.get(sampleIdx) / 32768.0f
                                val right = if (channelCount > 1 && sampleIdx + 1 < totalSamples) {
                                    shortBuffer.get(sampleIdx + 1) / 32768.0f
                                } else left
                                val mono = (left + right) * 0.5f
                                val absMono = abs(mono)

                                sumSquares[bucketIdx] += (mono * mono)
                                sampleCounts[bucketIdx]++
                                if (absMono > peaks[bucketIdx]) {
                                    peaks[bucketIdx] = absMono
                                }
                                sampleIdx += stepSamples
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressMade = true
                    val newFormat = decoder.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                }

                if (!progressMade) {
                    emptyCycles++
                    if (emptyCycles > 15) {
                        delay(1L)
                        emptyCycles = 0
                    }
                } else {
                    emptyCycles = 0
                }
            }

            if (!coroutineContext.isActive) {
                return@withContext null
            }

            val buckets = WaveformMath.computeNormalizedBuckets(sumSquares, sampleCounts, peaks)

            // Save to disk cache atomically
            saveToCache(cacheKey, buckets)

            WaveformData(buckets)
        } catch (_: Exception) {
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun saveToCache(cacheKey: String, buckets: FloatArray) {
        try {
            val bytes = WaveformMath.serializeWaveform(buckets)
            val tempFile = File(cacheDir, "$cacheKey.tmp")
            tempFile.writeBytes(bytes)
            val targetFile = File(cacheDir, "$cacheKey.bin")
            tempFile.renameTo(targetFile)
        } catch (_: Exception) {
            // Non-fatal if disk write fails
        }
    }
}
