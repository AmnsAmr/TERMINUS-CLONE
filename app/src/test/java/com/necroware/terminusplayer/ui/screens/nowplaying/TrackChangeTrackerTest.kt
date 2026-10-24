package com.necroware.terminusplayer.ui.screens.nowplaying

import com.necroware.terminusplayer.playback.TRACK_CHANGE_BACKWARD
import com.necroware.terminusplayer.playback.TRACK_CHANGE_FORWARD
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackChangeTrackerTest {
    @Test
    fun aTrackChangeTakesThePlaybackDerivedDirection() {
        val tracker = TrackChangeTracker(id = "a")
        assertEquals(TRACK_CHANGE_FORWARD, tracker.update("b", TRACK_CHANGE_FORWARD))
        assertEquals(TRACK_CHANGE_BACKWARD, tracker.update("a", TRACK_CHANGE_BACKWARD))
    }

    @Test
    fun theDirectionHoldsUntilTheTrackChangesAgain() {
        val tracker = TrackChangeTracker(id = "b")
        assertEquals(TRACK_CHANGE_BACKWARD, tracker.update("a", TRACK_CHANGE_BACKWARD))
        // Recomposition with the same track keeps the label even if the state value moved on.
        assertEquals(TRACK_CHANGE_BACKWARD, tracker.update("a", TRACK_CHANGE_FORWARD))
        // The next change (auto-advance) takes its own direction.
        assertEquals(TRACK_CHANGE_FORWARD, tracker.update("c", TRACK_CHANGE_FORWARD))
    }

    @Test
    fun aBackwardSkipFromOutsideTheAppIsBackward() {
        // Notification/headset "previous" no longer depends on an in-app counter: the controller
        // derives BACKWARD from the timeline and the tracker passes it through.
        val tracker = TrackChangeTracker(id = "c")
        assertEquals(TRACK_CHANGE_BACKWARD, tracker.update("b", TRACK_CHANGE_BACKWARD))
    }
}
