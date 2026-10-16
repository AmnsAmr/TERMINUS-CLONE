package com.necroware.terminusplayer.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTransferStatusTest {
    @Test
    fun uploadAndDownloadStatesHaveTrackLevelLabels() {
        assertEquals("UPLOAD PENDING", status("UPLOAD", "WAITING").label)
        assertEquals("DOWNLOAD 50%", status("DOWNLOAD", "DOWNLOADING", 50, 100).label)
        assertEquals("UPLOAD COMPLETE", status("UPLOAD", "SUCCESS").label)
        assertEquals("DOWNLOADED", status("DOWNLOAD", "SUCCESS").label)
        assertEquals("UPLOAD FAILED", status("UPLOAD", "PERMANENT_FAILURE").label)
        assertEquals("DOWNLOAD FAILED · RETRY", status("DOWNLOAD", "FAILED").label)
        assertEquals("UPLOAD MATCH NEEDED", status("UPLOAD", "ALREADY_EXISTS").label)
    }

    @Test
    fun retryIsOfferedOnlyForFailedTransferStates() {
        assertTrue(status("DOWNLOAD", "PERMANENT_FAILURE").canRetry)
        assertFalse(status("UPLOAD", "UPLOADING").canRetry)
        assertFalse(status("UPLOAD", "SUCCESS").canRetry)
    }

    private fun status(type: String, state: String, bytes: Long = 0, total: Long? = null) =
        SongTransferStatus(type, state, "TEST", bytes, total, null)
}
