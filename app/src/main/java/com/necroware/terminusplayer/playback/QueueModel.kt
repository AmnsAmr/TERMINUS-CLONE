package com.necroware.terminusplayer.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * One row of the queue sheet.
 *
 * @param key Stable identity for lists: the media ID plus which occurrence of it this is, so the same
 *   song queued twice gets two keys, and titles are never used as identity.
 * @param index Position in the player's queue (timeline index), which is what Media3 calls take.
 */
data class QueueEntry(
    val key: String,
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUri: String?
)

/** The queue in playback order (shuffle order when shuffle is on). */
data class QueueState(
    val entries: List<QueueEntry> = emptyList(),
    /** Timeline index of the current item, or [C.INDEX_UNSET]. */
    val currentIndex: Int = C.INDEX_UNSET,
    val shuffle: Boolean = false
) {
    /** Row of the current item, or -1. */
    val currentPosition: Int get() = entries.indexOfFirst { it.index == currentIndex }

    /**
     * Drag-to-reorder is off in shuffle: Media3 moves items in the queue order and then places the
     * moved item at a random spot in the shuffle order, so a drop would not land where it was made.
     */
    val canReorder: Boolean get() = !shuffle
}

/** The fields of a queue item the sheet shows. */
data class QueueItemInfo(val mediaId: String, val title: String, val artist: String, val artworkUri: String?)

/** A snapshot of the player's queue, taken on the main thread and turned into rows elsewhere. */
class QueueSource(
    val items: List<MediaItem>,
    val order: IntArray,
    val currentIndex: Int,
    val shuffle: Boolean
) {
    /** Builds the rows. Pure; safe off the main thread (MediaItem is immutable). */
    fun toState(): QueueState = QueueState(
        entries = buildQueueEntries(
            items.map { item ->
                QueueItemInfo(
                    mediaId = item.mediaId,
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                    artworkUri = item.mediaMetadata.artworkUri?.toString()
                )
            },
            order
        ),
        currentIndex = currentIndex,
        shuffle = shuffle
    )
}

/** Timeline indices in the order playback visits them (repeat ignored). */
internal fun Timeline.playbackOrder(shuffle: Boolean): IntArray {
    val out = IntArray(windowCount)
    var index = if (windowCount == 0) C.INDEX_UNSET else getFirstWindowIndex(shuffle)
    var n = 0
    while (index != C.INDEX_UNSET && n < out.size) {
        out[n++] = index
        index = getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
    }
    return if (n == out.size) out else out.copyOf(n)
}

/** Builds the sheet's rows: [items] are in queue (timeline) order, [order] is the playback order. */
internal fun buildQueueEntries(items: List<QueueItemInfo>, order: IntArray): List<QueueEntry> {
    // Keys count occurrences in queue order, so they do not change when shuffle reorders the rows.
    val seen = HashMap<String, Int>()
    val keys = Array(items.size) { i ->
        val id = items[i].mediaId
        val occurrence = seen.getOrDefault(id, 0)
        seen[id] = occurrence + 1
        "$id#$occurrence"
    }
    return order.filter { it in items.indices }.map { i ->
        val info = items[i]
        QueueEntry(keys[i], i, info.mediaId, info.title, info.artist, info.artworkUri)
    }
}

/**
 * Media3 `moveMediaItem(from, to)` arguments for dragging the row at [fromPosition] to [toPosition],
 * or null when nothing should move (shuffle on, same place, or out of range). Without shuffle a row's
 * position is its queue index, and moveMediaItem has the same remove-then-insert meaning as the drag.
 */
internal fun QueueState.moveFor(fromPosition: Int, toPosition: Int): Pair<Int, Int>? {
    if (!canReorder || fromPosition == toPosition) return null
    if (fromPosition !in entries.indices || toPosition !in entries.indices) return null
    return entries[fromPosition].index to entries[toPosition].index
}

/** What a swipe removed, enough to put it back. */
data class RemovedQueueItem(
    val index: Int,
    val mediaItem: MediaItem,
    val title: String,
    val wasCurrent: Boolean,
    val positionMs: Long,
    val playWhenReady: Boolean
)

/**
 * How an UNDO restores [removed] into a queue that now has [countNow] items: insert back at its old
 * index (clamped, in case the queue shrank meanwhile), and if it was playing, return to it at the
 * same position.
 */
internal data class UndoPlan(val insertIndex: Int, val seekToInserted: Boolean, val positionMs: Long)

internal fun undoPlan(removed: RemovedQueueItem, countNow: Int): UndoPlan = UndoPlan(
    insertIndex = removed.index.coerceIn(0, countNow.coerceAtLeast(0)),
    seekToInserted = removed.wasCurrent,
    positionMs = if (removed.wasCurrent) removed.positionMs.coerceAtLeast(0L) else 0L
)
