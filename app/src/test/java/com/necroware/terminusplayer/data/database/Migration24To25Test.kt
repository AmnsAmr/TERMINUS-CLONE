package com.necroware.terminusplayer.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.necroware.terminusplayer.data.database.entity.UploadQueueEntity
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Migration24To25Test {

    @Test
    fun migration24To25ExecutesAlterTableAddMaxBitRate() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        assertEquals(24, TerminusDatabase.MIGRATION_24_25.startVersion)
        assertEquals(25, TerminusDatabase.MIGRATION_24_25.endVersion)

        TerminusDatabase.MIGRATION_24_25.migrate(db)

        verify(exactly = 1) {
            db.execSQL("ALTER TABLE upload_queue ADD COLUMN maxBitRate INTEGER DEFAULT NULL")
        }
    }

    @Test
    fun uploadQueueEntityDefaultsMaxBitRateToNull() {
        val entity = UploadQueueEntity(
            songId = "song_1",
            uriString = "content://music/1",
            state = "WAITING",
            attemptCount = 0,
            addedAtEpochMs = 1000L,
            title = "Test Song"
        )

        assertNull(entity.maxBitRate)
    }

    @Test
    fun uploadQueueEntityPreservesMaxBitRateWhenProvided() {
        val entity = UploadQueueEntity(
            songId = "song_2",
            uriString = "content://music/2",
            state = "WAITING",
            attemptCount = 0,
            addedAtEpochMs = 1000L,
            title = "Test Song",
            maxBitRate = 320
        )

        assertEquals(320, entity.maxBitRate)
    }
}
