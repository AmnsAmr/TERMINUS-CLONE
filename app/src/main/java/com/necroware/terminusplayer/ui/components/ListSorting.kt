package com.necroware.terminusplayer.ui.components

/** Sorts without changing the relative order of items that compare equal. */
fun <T> stableSortedList(
    items: List<T>,
    descending: Boolean,
    comparator: Comparator<T>
): List<T> = items.withIndex()
    .sortedWith { left, right ->
        val valueOrder = if (descending) comparator.compare(right.value, left.value) else comparator.compare(left.value, right.value)
        if (valueOrder != 0) valueOrder else left.index.compareTo(right.index)
    }
    .map { it.value }

/** Converts filtered data-row indexes into their corresponding LazyColumn item indexes. */
fun fastScrollTargets(itemCount: Int, leadingLazyItems: Int = 0): List<Int> =
    offsetFastScrollTargets((0 until itemCount).toList(), leadingLazyItems)

fun offsetFastScrollTargets(targets: List<Int>, leadingLazyItems: Int): List<Int> =
    targets.map { it + leadingLazyItems }

fun shouldShowFastScroll(itemCount: Int): Boolean = itemCount > 40

fun toggleSelection(current: Set<String>, itemId: String): Set<String> =
    if (itemId in current) current - itemId else current + itemId

fun selectAllOrClear(current: Set<String>, allItemIds: List<String>): Set<String> =
    if (current.size == allItemIds.size && allItemIds.all { it in current }) emptySet() else allItemIds.toSet()

fun reconcileSelection(selectedIds: Set<String>, currentItemIds: Set<String>): Set<String> =
    selectedIds intersect currentItemIds

fun selectAllItemIds(currentItemIds: List<String>): Set<String> = currentItemIds.toSet()

fun playlistSelectionIdentity(playlistId: String?, kind: String?): String =
    playlistId?.let { "playlist:$it" } ?: "quick:${kind.orEmpty()}"

/** Maps an absolute LazyColumn row index back to the preceding logical data-row index. */
fun logicalIndexForScrollTarget(scrollTargets: List<Int>, visibleLazyItemIndex: Int): Int {
    if (scrollTargets.isEmpty()) return 0
    var low = 0
    var high = scrollTargets.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (scrollTargets[middle] <= visibleLazyItemIndex) low = middle + 1 else high = middle
    }
    return (low - 1).coerceAtLeast(0)
}
