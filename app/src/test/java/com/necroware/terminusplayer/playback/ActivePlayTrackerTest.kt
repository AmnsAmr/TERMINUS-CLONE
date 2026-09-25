package com.necroware.terminusplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePlayTrackerTest {
    @Test
    fun normalCompletionRecordsExactlyOnce() {
        val tracker = trackerStartedAt(0L, durationMs = 5_000L)

        val finished = tracker.finish(nowElapsedMs = 5_000L, completedAtEnd = true)

        assertEquals(5_000L, finished?.activeMs)
        assertTrue(finished?.completed == true)
        assertNull(tracker.finish(nowElapsedMs = 5_100L, completedAtEnd = true))
    }

    @Test
    fun pauseTimeDoesNotCountTowardListeningDuration() {
        val tracker = trackerStartedAt(0L, durationMs = 10_000L)
        tracker.onIsPlayingChanged(false, nowElapsedMs = 4_000L, nowEpochMs = 5_000L)
        tracker.onIsPlayingChanged(true, nowElapsedMs = 34_000L, nowEpochMs = 35_000L)

        val finished = tracker.finish(nowElapsedMs = 40_000L, completedAtEnd = true)

        assertEquals(10_000L, finished?.activeMs)
        assertTrue(finished?.completed == true)
        assertEquals(1_000L, finished?.startedAtEpochMs)
    }

    @Test
    fun serviceDestructionFlushIsIncompleteAndIdempotent() {
        val tracker = trackerStartedAt(0L, durationMs = 5_000L)

        val finished = tracker.finish(nowElapsedMs = 4_000L, completedAtEnd = false)

        assertFalse(finished?.completed ?: true)
        assertNull(tracker.finish(nowElapsedMs = 4_100L, completedAtEnd = false))
    }

    @Test
    fun automaticTrackTransitionCanMarkCompletedTrack() {
        val tracker = trackerStartedAt(0L, durationMs = 5_000L)

        val finished = tracker.finish(nowElapsedMs = 5_000L, completedAtEnd = true)

        assertTrue(finished?.completed == true)
    }

    private fun trackerStartedAt(nowElapsedMs: Long, durationMs: Long): ActivePlayTracker =
        ActivePlayTracker().apply {
            start(
                songId = "song-1",
                artist = "Artist",
                album = "Album",
                albumId = "album-1",
                durationMs = durationMs,
                isPlaying = true,
                nowElapsedMs = nowElapsedMs,
                nowEpochMs = 1_000L
            )
        }
}
