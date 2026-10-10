package com.necroware.terminusplayer.util

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import java.io.IOException

class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (progress: Int) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val countingSink = CountingSink(sink)
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }

    private inner class CountingSink(delegate: Sink) : ForwardingSink(delegate) {
        private var bytesWritten = 0L
        private val totalBytes = contentLength()
        private var lastProgress = 0

        @Throws(IOException::class)
        override fun write(source: okio.Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount
            if (totalBytes > 0) {
                val progress = ((bytesWritten * 100) / totalBytes).toInt()
                if (progress != lastProgress) {
                    lastProgress = progress
                    onProgress(progress)
                }
            }
        }
    }
}
