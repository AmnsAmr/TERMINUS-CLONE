package com.necroware.terminusplayer.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.repository.toSong
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Migration23To24Test {

    @Test
    fun migration23To24ExecutesAlterTableAddArtworkUri() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        assertEquals(23, TerminusDatabase.MIGRATION_23_24.startVersion)
        assertEquals(24, TerminusDatabase.MIGRATION_23_24.endVersion)

        TerminusDatabase.MIGRATION_23_24.migrate(db)

        verify(exactly = 1) {
            db.execSQL("ALTER TABLE songs ADD COLUMN artworkUri TEXT DEFAULT NULL")
        }
    }

    @Test
    fun songEntityDefaultsArtworkUriToNull() {
        val entity = SongEntity(
            remoteId = "jamendo:101",
            providerId = "jamendo",
            providerRemoteId = "101",
            title = "Test Track",
            artist = "Test Artist",
            album = "Test Album",
            albumId = "55",
            duration = 180,
            uriString = "https://prod-1.storage.jamendo.com/?trackid=101",
            dateAdded = 1000L
        )

        assertNull(entity.artworkUri)
        assertNull(entity.downloadedUri)
        assertEquals("jamendo", entity.providerId)
        assertEquals("101", entity.providerRemoteId)
    }

    @Test
    fun songEntityPreservesArtworkUriWhenProvided() {
        val artUrl = "https://usercontent.jamendo.com?type=album&id=55&width=300"
        val entity = SongEntity(
            remoteId = "jamendo:101",
            providerId = "jamendo",
            providerRemoteId = "101",
            title = "Test Track",
            artist = "Test Artist",
            album = "Test Album",
            albumId = "55",
            duration = 180,
            uriString = "https://prod-1.storage.jamendo.com/?trackid=101",
            dateAdded = 1000L,
            artworkUri = artUrl
        )

        assertEquals(artUrl, entity.artworkUri)
        val song: Song = entity.toSong()
        assertEquals(artUrl, song.artworkUri)
        assertEquals("https://prod-1.storage.jamendo.com/?trackid=101", song.uriString)
    }

    @Test
    fun songModelDefaultsArtworkUriToNull() {
        val song = Song(
            id = "local:1",
            title = "Local Song",
            artist = "Local Artist",
            album = "Local Album",
            albumId = "local_album",
            duration = 120,
            uriString = "content://media/external/audio/media/1"
        )

        assertNull(song.artworkUri)
    }
}
