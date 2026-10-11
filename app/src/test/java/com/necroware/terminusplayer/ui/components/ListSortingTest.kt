package com.necroware.terminusplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ListSortingTest {
    @Test
    fun stableSortKeepsEqualItemsInTheirOriginalOrderInBothDirections() {
        data class Entry(val name: String, val id: Int)
        val entries = listOf(Entry("B", 1), Entry("A", 2), Entry("A", 3))

        val ascending = stableSortedList(entries, descending = false, comparator = compareBy(Entry::name))
        val descending = stableSortedList(entries, descending = true, comparator = compareBy(Entry::name))

        assertEquals(listOf(2, 3, 1), ascending.map { it.id })
        assertEquals(listOf(1, 2, 3), descending.map { it.id })
    }

    @Test
    fun fastScrollTargetsApplyHeaderOffsetToOnlyTheDisplayedFilteredRows() {
        val filteredRows = listOf(1, 4, 9)

        assertEquals(listOf(7, 10, 15), offsetFastScrollTargets(filteredRows, leadingLazyItems = 6))
        assertEquals(emptyList<Int>(), fastScrollTargets(itemCount = 0, leadingLazyItems = 6))
    }

    @Test
    fun fastScrollIsHiddenUntilAListIsLongEnoughToBenefit() {
        assertEquals(false, shouldShowFastScroll(0))
        assertEquals(false, shouldShowFastScroll(40))
        assertEquals(true, shouldShowFastScroll(41))
    }

    @Test
    fun selectionSupportsLongPressToggleAndSelectAllClear() {
        val allIds = listOf("one", "two", "three")
        val first = toggleSelection(emptySet(), "one")
        assertEquals(setOf("one"), first)
        assertEquals(emptySet<String>(), toggleSelection(first, "one"))
        assertEquals(allIds.toSet(), selectAllOrClear(first, allIds))
        assertEquals(emptySet<String>(), selectAllOrClear(allIds.toSet(), allIds))
    }

    @Test
    fun selectionReconcilesAfterRemovalAndSelectAllUsesRefreshedPlaylistIds() {
        val selected = setOf("kept", "removed")
        val refreshedIds = listOf("kept", "new")

        assertEquals(setOf("kept"), reconcileSelection(selected, refreshedIds.toSet()))
        assertEquals(setOf("kept", "new"), selectAllItemIds(refreshedIds))
        assertEquals(emptySet<String>(), reconcileSelection(selected, emptySet()))
    }

    @Test
    fun selectionIdentityUsesPlaylistIdInsteadOfSimilarDisplayNames() {
        assertEquals("playlist:server:one", playlistSelectionIdentity("server:one", null))
        assertEquals("playlist:custom:two", playlistSelectionIdentity("custom:two", null))
        assertEquals("quick:RECENT", playlistSelectionIdentity(null, "RECENT"))
    }

    @Test
    fun logicalFastScrollIndexIgnoresHeadersAndMapsFilteredRowTargets() {
        val targets = listOf(5, 7, 12, 20)

        assertEquals(0, logicalIndexForScrollTarget(targets, 0))
        assertEquals(0, logicalIndexForScrollTarget(targets, 6))
        assertEquals(1, logicalIndexForScrollTarget(targets, 11))
        assertEquals(2, logicalIndexForScrollTarget(targets, 19))
        assertEquals(3, logicalIndexForScrollTarget(targets, 20))
        assertEquals(0, logicalIndexForScrollTarget(emptyList(), 40))
    }

    @Test
    fun sortingEmptyAndLargeListsIsSafeAndStable() {
        assertEquals(emptyList<String>(), stableSortedList(emptyList(), false, compareBy(String::length)))
        val large = (0 until 2_000).map { "track-${it % 12}" }
        val sorted = stableSortedList(large, false, compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        assertEquals(large.size, sorted.size)
        assertEquals(large.sortedWith(String.CASE_INSENSITIVE_ORDER), sorted)
    }
}
