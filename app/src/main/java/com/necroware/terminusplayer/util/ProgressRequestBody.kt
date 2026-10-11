package com.necroware.terminusplayer.util

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import java.io.IOException

data class TransferProgress(val bytesTransferred: Long, val totalBytes: Long?, val partialUri: String? = null)

class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (TransferProgress) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val countingSink = CountingSink(sink)
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
        countingSink.report(force = true)
    }

    private inner class CountingSink(delegate: Sink) : ForwardingSink(delegate) {
        private var bytesWritten = 0L
        private val totalBytes = contentLength()
        private var lastReportedBytes = 0L
        private var lastReportedAt = System.currentTimeMillis()

        @Throws(IOException::class)
        override fun write(source: okio.Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount
            report()
        }

        fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || bytesWritten - lastReportedBytes >= REPORT_BYTES || now - lastReportedAt >= REPORT_INTERVAL_MS) {
                lastReportedBytes = bytesWritten
                lastReportedAt = now
                onProgress(TransferProgress(bytesWritten, totalBytes.takeIf { it >= 0L }))
            }
        }
    }

    private companion object {
        const val REPORT_BYTES = 256L * 1024L
        const val REPORT_INTERVAL_MS = 500L
    }
}
