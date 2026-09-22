package com.necroware.terminusplayer.data.model

data class SyncedLyrics(
    val lines: List<LyricLine>
)

data class LyricLine(
    val startMs: Long,
    val text: String
)
