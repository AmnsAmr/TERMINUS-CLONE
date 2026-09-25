package com.necroware.terminusplayer.playback

internal data class FinishedPlay(
    val songId: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val startedAtEpochMs: Long,
    val activeMs: Long,
    val completed: Boolean
)

/** Tracks only time for which the player reports actively playing. */
internal class ActivePlayTracker {
    private data class CurrentPlay(
        val songId: String,
        val artist: String,
        val album: String,
        val albumId: String,
        val durationMs: Long,
        var startedAtEpochMs: Long,
        var activeStartedAtElapsedMs: Long?,
        var activeMs: Long = 0L
    )

    private var current: CurrentPlay? = null

    fun start(
        songId: String?,
        artist: String,
        album: String,
        albumId: String,
        durationMs: Long,
        isPlaying: Boolean,
        nowElapsedMs: Long,
        nowEpochMs: Long
    ) {
        current = songId?.let {
            CurrentPlay(
                songId = it,
                artist = artist,
                album = album,
                albumId = albumId,
                durationMs = durationMs.coerceAtLeast(0L),
                startedAtEpochMs = if (isPlaying) nowEpochMs else 0L,
                activeStartedAtElapsedMs = nowElapsedMs.takeIf { isPlaying }
            )
        }
    }

    fun onIsPlayingChanged(isPlaying: Boolean, nowElapsedMs: Long, nowEpochMs: Long) {
        val play = current ?: return
        if (isPlaying && play.activeStartedAtElapsedMs == null) {
            play.activeStartedAtElapsedMs = nowElapsedMs
            if (play.activeMs == 0L) play.startedAtEpochMs = nowEpochMs
        } else if (!isPlaying) {
            play.activeStartedAtElapsedMs?.let { startedAt ->
                play.activeMs += (nowElapsedMs - startedAt).coerceAtLeast(0L)
                play.activeStartedAtElapsedMs = null
            }
        }
    }

    fun finish(nowElapsedMs: Long, completedAtEnd: Boolean): FinishedPlay? {
        val play = current ?: return null
        current = null
        val activeMs = play.activeMs + (play.activeStartedAtElapsedMs
            ?.let { (nowElapsedMs - it).coerceAtLeast(0L) } ?: 0L)
        if (activeMs < 3_000L) return null
        return FinishedPlay(
            songId = play.songId,
            artist = play.artist,
            album = play.album,
            albumId = play.albumId,
            startedAtEpochMs = play.startedAtEpochMs,
            activeMs = activeMs,
            completed = completedAtEnd && play.durationMs > 0L && activeMs >= (play.durationMs * 0.9).toLong()
        )
    }
}
