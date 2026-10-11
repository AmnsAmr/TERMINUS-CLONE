package com.necroware.terminusplayer.util

import okio.Buffer
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressRequestBodyTest {
    @Test
    fun reportsActualBytesAndKnownTotal() {
        val payload = ByteArray(700_000) { (it % 251).toByte() }
        val events = mutableListOf<TransferProgress>()
        val body = ProgressRequestBody(byteArrayBody(payload), events::add)
        val sink = Buffer()

        body.writeTo(sink)

        assertEquals(payload.size.toLong(), sink.size)
        assertEquals(payload.size.toLong(), events.last().bytesTransferred)
        assertEquals(payload.size.toLong(), events.last().totalBytes)
        assertEquals(true, events.any { it.bytesTransferred in 1L until payload.size.toLong() })
    }

    @Test
    fun reportsByteCountsWhenTotalIsUnknown() {
        val events = mutableListOf<TransferProgress>()
        val body = ProgressRequestBody(byteArrayBody(byteArrayOf(1, 2, 3), knownLength = false), events::add)

        body.writeTo(Buffer())

        assertEquals(3L, events.last().bytesTransferred)
        assertNull(events.last().totalBytes)
    }

    private fun byteArrayBody(bytes: ByteArray, knownLength: Boolean = true) = object : RequestBody() {
        override fun contentType() = null
        override fun contentLength() = if (knownLength) bytes.size.toLong() else -1L
        override fun writeTo(sink: okio.BufferedSink) {
            sink.write(bytes)
        }
    }
}
