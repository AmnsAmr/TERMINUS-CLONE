package com.necroware.terminusplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uses Media3's RemotableTimeline, the timeline type a MediaController exposes, so shuffle order and
 * repeat wrapping behave exactly as they do against the real session.
 */
class QueueNavigationTest {

    private fun timeline(count: Int, shuffleOrder: IntArray = IntArray(count) { it }): Timeline =
        Timeline.RemotableTimeline(
            ImmutableList.copyOf(List(count) { Timeline.Window() }),
            ImmutableList.of(),
            shuffleOrder
        )

    private val off = Player.REPEAT_MODE_OFF
    private val all = Player.REPEAT_MODE_ALL
    private val one = Player.REPEAT_MODE_ONE

    // Shuffled play order: 3 -> 0 -> 4 -> 1 -> 2
    private val shuffled = timeline(5, intArrayOf(3, 0, 4, 1, 2))

    @Test
    fun neighboursFollowQueueOrderAndStopAtTheEndsWithRepeatOff() {
        val t = timeline(5)
        assertEquals(NeighbourIndices(0, 2), t.neighbourIndices(1, off, shuffle = false))
        assertEquals(NeighbourIndices(C.INDEX_UNSET, 1), t.neighbourIndices(0, off, shuffle = false))
        assertEquals(NeighbourIndices(3, C.INDEX_UNSET), t.neighbourIndices(4, off, shuffle = false))
    }

    @Test
    fun repeatAllWrapsBothEnds() {
        val t = timeline(5)
        assertEquals(NeighbourIndices(4, 1), t.neighbourIndices(0, all, shuffle = false))
        assertEquals(NeighbourIndices(3, 0), t.neighbourIndices(4, all, shuffle = false))
    }

    @Test
    fun repeatOneNavigatesLikeRepeatOff() {
        val t = timeline(5)
        assertEquals(t.neighbourIndices(2, off, shuffle = false), t.neighbourIndices(2, one, shuffle = false))
        assertEquals(NeighbourIndices(3, C.INDEX_UNSET), t.neighbourIndices(4, one, shuffle = false))
    }

    @Test
    fun shuffleUsesShuffleOrderNotIndexOrder() {
        // 4 sits between 0 and 1 in the shuffled order.
        assertEquals(NeighbourIndices(0, 1), shuffled.neighbourIndices(4, off, shuffle = true))
        // 3 is first in shuffle order: nothing before it with repeat off, wraps to the last (2) with repeat all.
        assertEquals(NeighbourIndices(C.INDEX_UNSET, 0), shuffled.neighbourIndices(3, off, shuffle = true))
        assertEquals(NeighbourIndices(2, 0), shuffled.neighbourIndices(3, all, shuffle = true))
        // The same timeline with shuffle off falls back to index order.
        assertEquals(NeighbourIndices(3, C.INDEX_UNSET), shuffled.neighbourIndices(4, off, shuffle = false))
    }

    @Test
    fun noNeighboursForAnUnknownCurrentIndex() {
        val t = timeline(3)
        assertEquals(NeighbourIndices(C.INDEX_UNSET, C.INDEX_UNSET), t.neighbourIndices(C.INDEX_UNSET, off, shuffle = false))
        assertEquals(NeighbourIndices(C.INDEX_UNSET, C.INDEX_UNSET), t.neighbourIndices(7, off, shuffle = false))
    }

    @Test
    fun nextIsForwardAndPreviousIsBackward() {
        val t = timeline(5)
        assertEquals(TRACK_CHANGE_FORWARD, t.trackChangeDirection(1, 2, off, shuffle = false))
        assertEquals(TRACK_CHANGE_BACKWARD, t.trackChangeDirection(2, 1, off, shuffle = false))
    }

    @Test
    fun directionFollowsShuffleOrderNotIndexes() {
        // 4 -> 0 lowers the index but is a "previous" in shuffle order (3, 0, 4, 1, 2).
        assertEquals(TRACK_CHANGE_BACKWARD, shuffled.trackChangeDirection(4, 0, off, shuffle = true))
        // 4 -> 1 lowers the index too, but it is the next item in shuffle order.
        assertEquals(TRACK_CHANGE_FORWARD, shuffled.trackChangeDirection(4, 1, off, shuffle = true))
    }

    @Test
    fun repeatAllWrapKeepsTheSkipDirection() {
        val t = timeline(5)
        // Next from the last item wraps to the first: still forward.
        assertEquals(TRACK_CHANGE_FORWARD, t.trackChangeDirection(4, 0, all, shuffle = false))
        // Previous from the first item wraps to the last: still backward.
        assertEquals(TRACK_CHANGE_BACKWARD, t.trackChangeDirection(0, 4, all, shuffle = false))
        // Same wrap in shuffle order: last (2) -> first (3) forward, first -> last backward.
        assertEquals(TRACK_CHANGE_FORWARD, shuffled.trackChangeDirection(2, 3, all, shuffle = true))
        assertEquals(TRACK_CHANGE_BACKWARD, shuffled.trackChangeDirection(3, 2, all, shuffle = true))
    }

    @Test
    fun jumpsComparePositionsInPlaybackOrder() {
        val t = timeline(10)
        assertEquals(TRACK_CHANGE_FORWARD, t.trackChangeDirection(2, 7, off, shuffle = false))
        assertEquals(TRACK_CHANGE_BACKWARD, t.trackChangeDirection(7, 2, off, shuffle = false))
        // Shuffle positions: 3@0, 0@1, 4@2, 1@3, 2@4. Index 1 -> 3 is a jump back to the start.
        assertEquals(TRACK_CHANGE_BACKWARD, shuffled.trackChangeDirection(1, 3, off, shuffle = true))
        // Index 3 -> 2 lowers the index but jumps to the end of the shuffled order.
        assertEquals(TRACK_CHANGE_FORWARD, shuffled.trackChangeDirection(3, 2, off, shuffle = true))
    }

    @Test
    fun unknownOrUnchangedIndexesCountAsForward() {
        val t = timeline(3)
        assertEquals(TRACK_CHANGE_FORWARD, t.trackChangeDirection(C.INDEX_UNSET, 1, off, shuffle = false))
        assertEquals(TRACK_CHANGE_FORWARD, t.trackChangeDirection(1, C.INDEX_UNSET, off, shuffle = false))
        assertEquals(TRACK_CHANGE_FORWARD, t.trackChangeDirection(1, 1, off, shuffle = false))
        assertEquals(TRACK_CHANGE_FORWARD, t.trackChangeDirection(5, 1, off, shuffle = false))
    }

    @Test
    fun twoItemRepeatAllQueueIsAmbiguousAndCountsAsForward() {
        // Next and previous of item 0 are both item 1, so the change cannot be told apart.
        assertEquals(TRACK_CHANGE_FORWARD, timeline(2).trackChangeDirection(0, 1, all, shuffle = false))
    }
}
