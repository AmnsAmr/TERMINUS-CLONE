package com.necroware.terminusplayer.data.model

data class SongTransferStatus(
    val transferType: String,
    val state: String,
    val phase: String,
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val errorMessage: String?
) {
    val label: String
        get() {
            val action = if (transferType == "DOWNLOAD") "DOWNLOAD" else "UPLOAD"
            return when (state) {
                "WAITING" -> "$action PENDING"
                "FAILED" -> "$action FAILED · RETRY"
                "UPLOADING", "DOWNLOADING", "CONVERTING" -> {
                    val percent = totalBytes?.takeIf { it > 0 }?.let { (bytesTransferred * 100 / it).coerceIn(0, 99) }
                    if (percent == null) "$action IN PROGRESS" else "$action $percent%"
                }
                "SUCCESS" -> if (transferType == "DOWNLOAD") "DOWNLOADED" else "UPLOAD COMPLETE"
                "ALREADY_EXISTS" -> "UPLOAD MATCH NEEDED"
                "PERMANENT_FAILURE", "CONVERSION_FAILED", "QUOTA_EXCEEDED" -> "$action FAILED"
                else -> "$action PENDING"
            }
        }

    val canRetry: Boolean
        get() = state in setOf("FAILED", "PERMANENT_FAILURE", "CONVERSION_FAILED", "QUOTA_EXCEEDED")
}
