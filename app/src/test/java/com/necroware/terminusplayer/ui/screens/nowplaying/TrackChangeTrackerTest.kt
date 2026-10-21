package com.necroware.terminusplayer.ui.screens.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackChangeTrackerTest {
    @Test
    fun aTrackChangeWithoutABackSkipIsForward() {
        val tracker = TrackChangeTracker(id = "a", backSkipCount = 0)
        assertEquals(1, tracker.update("b", newBackSkipCount = 0))
    }

    @Test
    fun aTrackChangeAfterABackSkipIsBackward() {
        val tracker = TrackChangeTracker(id = "b", backSkipCount = 3)
        assertEquals(-1, tracker.update("a", newBackSkipCount = 4))
    }

    @Test
    fun theDirectionHoldsUntilTheTrackChangesAgain() {
        val tracker = TrackChangeTracker(id = "b", backSkipCount = 0)
        assertEquals(-1, tracker.update("a", newBackSkipCount = 1))
        // Recomposition with the same track keeps the label.
        assertEquals(-1, tracker.update("a", newBackSkipCount = 1))
        // Auto-advance afterwards goes forward again.
        assertEquals(1, tracker.update("c", newBackSkipCount = 1))
    }
}
