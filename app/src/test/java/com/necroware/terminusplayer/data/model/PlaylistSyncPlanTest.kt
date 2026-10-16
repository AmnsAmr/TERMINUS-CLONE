package com.necroware.terminusplayer.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSyncPlanTest {
    @Test
    fun exactSequenceIsAnIdempotentNoOpIncludingDuplicates() {
        val sequence = listOf("a", "b", "a", "c")
        val plan = planPlaylistSync("Mix", "Mix", sequence, sequence)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun partialFailureRetryReconcilesCurrentSnapshotWithoutDuplicateAppend() {
        val desired = listOf("a", "b", "a")
        val first = planPlaylistSync("Mix", "Mix", listOf("a", "b"), desired)
        assertEquals(listOf(1, 0), first.songIndicesToRemove)
        assertEquals(desired, first.songIdsToAdd)

        // The server applied the request but the client did not observe its response.
        val retry = planPlaylistSync("Mix", "Mix", desired, desired)
        assertTrue(retry.isEmpty)
    }

    @Test
    fun renameDoesNotRewriteTrackMembership() {
        val plan = planPlaylistSync("Old", "New", listOf("a", "a"), listOf("a", "a"))
        assertEquals("New", plan.nameToSet)
        assertTrue(plan.songIdsToAdd.isEmpty())
        assertTrue(plan.songIndicesToRemove.isEmpty())
    }

    @Test
    fun reorderRemovesDescendingIndicesThenAddsTheExactDesiredOrder() {
        val plan = planPlaylistSync("Mix", "Mix", listOf("a", "b", "c"), listOf("c", "a", "b"))
        assertEquals(listOf(2, 1, 0), plan.songIndicesToRemove)
        assertEquals(listOf("c", "a", "b"), plan.songIdsToAdd)
    }
}
