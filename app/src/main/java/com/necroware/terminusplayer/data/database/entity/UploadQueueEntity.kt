package com.necroware.terminusplayer.data.database.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "upload_queue")
data class UploadQueueEntity(
    @PrimaryKey val songId: String,
    val uriString: String,
    val state: String, // "WAITING", "UPLOADING", "SUCCESS", "FAILED", "PERMANENT_FAILURE"
    val attemptCount: Int,
    val addedAtEpochMs: Long,
    val title: String,
    val errorMessage: String? = null,
    val forceConvert: Boolean = false, // Legacy database column retained for existing installs.
    @ColumnInfo(defaultValue = "'UPLOAD'") val transferType: String = "UPLOAD",
    @ColumnInfo(defaultValue = "NULL") val remoteSongId: String? = null,
    @ColumnInfo(defaultValue = "0") val bytesTransferred: Long = 0L,
    @ColumnInfo(defaultValue = "NULL") val totalBytes: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val startedAtEpochMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val lastProgressAtEpochMs: Long? = null,
    @ColumnInfo(defaultValue = "'QUEUED'") val phase: String = "QUEUED",
    @ColumnInfo(defaultValue = "0") val forceDownload: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val partialUri: String? = null
)
