package com.necroware.terminusplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueModelTest {

    private fun timeline(count: Int, shuffleOrder: IntArray = IntArray(count) { it }): Timeline =
        Timeline.RemotableTimeline(
            ImmutableList.copyOf(List(count) { Timeline.Window() }),
            ImmutableList.of(),
            shuffleOrder
        )

    private fun items(vararg ids: String) = ids.map { QueueItemInfo(it, "t-$it", "a", null) }

    @Test
    fun playbackOrderFollowsShuffleOnlyWhenOn() {
        val t = timeline(5, intArrayOf(3, 0, 4, 1, 2))
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), t.playbackOrder(shuffle = false))
        assertArrayEquals(intArrayOf(3, 0, 4, 1, 2), t.playbackOrder(shuffle = true))
        assertArrayEquals(IntArray(0), timeline(0).playbackOrder(shuffle = true))
    }

    @Test
    fun rowsAreInPlaybackOrderAndKeepTheirQueueIndex() {
        val entries = buildQueueEntries(items("a", "b", "c"), intArrayOf(2, 0, 1))
        assertEquals(listOf("c", "a", "b"), entries.map { it.mediaId })
        assertEquals(listOf(2, 0, 1), entries.map { it.index })
    }

    @Test
    fun duplicateSongsGetDistinctStableKeys() {
        val entries = buildQueueEntries(items("x", "y", "x"), intArrayOf(0, 1, 2))
        assertEquals(listOf("x#0", "y#0", "x#1"), entries.map { it.key })
        // Shuffle changes the row order, not the keys.
        val shuffled = buildQueueEntries(items("x", "y", "x"), intArrayOf(2, 0, 1))
        assertEquals(listOf("x#1", "x#0", "y#0"), shuffled.map { it.key })
    }

    @Test
    fun currentPositionFindsTheCurrentRowInShuffleOrder() {
        val state = QueueState(buildQueueEntries(items("a", "b", "c", "d"), intArrayOf(3, 1, 0, 2)), currentIndex = 0, shuffle = true)
        assertEquals(2, state.currentPosition)
    }

    @Test
    fun reorderMapsRowsToQueueIndicesWithoutShuffle() {
        val state = QueueState(buildQueueEntries(items("a", "b", "c", "d"), intArrayOf(0, 1, 2, 3)), currentIndex = 1)
        assertEquals(0 to 3, state.moveFor(0, 3))
        assertEquals(3 to 1, state.moveFor(3, 1))
        assertNull(state.moveFor(2, 2))
        assertNull(state.moveFor(-1, 2))
        assertNull(state.moveFor(1, 9))
        // Same meaning as MutableList remove-then-add, which is what moveMediaItem does.
        val list = mutableListOf("a", "b", "c", "d")
        val (from, to) = state.moveFor(0, 2)!!
        list.add(to, list.removeAt(from))
        assertEquals(listOf("b", "c", "a", "d"), list)
    }

    @Test
    fun reorderIsDisabledInShuffle() {
        val state = QueueState(buildQueueEntries(items("a", "b", "c"), intArrayOf(2, 0, 1)), currentIndex = 0, shuffle = true)
        assertFalse(state.canReorder)
        assertNull(state.moveFor(0, 2))
    }

    private fun removed(index: Int, current: Boolean, positionMs: Long = 42_000L) = RemovedQueueItem(
        index = index,
        mediaItem = MediaItem.Builder().setMediaId("m$index").build(),
        title = "t",
        wasCurrent = current,
        positionMs = positionMs,
        playWhenReady = true
    )

    @Test
    fun undoPutsAnItemBackWhereItWas() {
        val plan = undoPlan(removed(index = 3, current = false), countNow = 9)
        assertEquals(3, plan.insertIndex)
        assertFalse(plan.seekToInserted)
        assertEquals(0L, plan.positionMs)
    }

    @Test
    fun undoOfTheCurrentTrackReturnsToItAtTheSamePosition() {
        val plan = undoPlan(removed(index = 2, current = true, positionMs = 61_500L), countNow = 4)
        assertEquals(2, plan.insertIndex)
        assertTrue(plan.seekToInserted)
        assertEquals(61_500L, plan.positionMs)
    }

    @Test
    fun undoOfTheLastItemRebuildsAnEmptyQueue() {
        // The only item was removed: the queue is empty, so it goes back at 0 and plays again.
        val plan = undoPlan(removed(index = 0, current = true), countNow = 0)
        assertEquals(0, plan.insertIndex)
        assertTrue(plan.seekToInserted)
    }

    @Test
    fun undoClampsWhenTheQueueShrankMeanwhile() {
        // Removed the last of 6 (index 5); two more were removed before UNDO, 3 remain.
        val plan = undoPlan(removed(index = 5, current = false), countNow = 3)
        assertEquals(3, plan.insertIndex)
    }
}
