package com.necroware.terminusplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/** A track next to the current one in playback order, as the swipe-to-skip UI shows it. */
data class QueueNeighbour(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUri: String?
)

/** Timeline indices of the items before and after the current one, or [C.INDEX_UNSET]. */
data class NeighbourIndices(val previous: Int, val next: Int)

/** Direction of a track change: the new track came after (+1) or before (-1) the old one. */
const val TRACK_CHANGE_FORWARD = 1
const val TRACK_CHANGE_BACKWARD = -1

/**
 * Repeat mode as next/previous navigation sees it: REPEAT_MODE_ONE navigates like OFF, which is
 * what Media3's seekToNextMediaItem/seekToPreviousMediaItem do.
 */
internal fun navigationRepeatMode(repeatMode: Int): Int =
    if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else repeatMode

/**
 * The items a skip would land on, honouring shuffle order and repeat. With repeat OFF the ends of
 * the queue have no neighbour; with repeat ALL they wrap.
 */
internal fun Timeline.neighbourIndices(currentIndex: Int, repeatMode: Int, shuffle: Boolean): NeighbourIndices {
    if (currentIndex == C.INDEX_UNSET || currentIndex !in 0 until windowCount) {
        return NeighbourIndices(C.INDEX_UNSET, C.INDEX_UNSET)
    }
    val mode = navigationRepeatMode(repeatMode)
    return NeighbourIndices(
        previous = getPreviousWindowIndex(currentIndex, mode, shuffle),
        next = getNextWindowIndex(currentIndex, mode, shuffle)
    )
}

/**
 * Which way playback moved when the current item changed from [oldIndex] to [newIndex] within this
 * timeline. Reads the actual change, so skips from the notification or a headset count as well as
 * in-app ones.
 *
 * - The item a "next" from [oldIndex] reaches is forward, including repeat-ALL wrapping last -> first.
 * - The item a "previous" from [oldIndex] reaches is backward, including wrapping first -> last.
 * - Any other jump compares positions in playback order (shuffle order when shuffle is on).
 * - Unknown indices, or a two-item repeat-ALL queue where next and previous are the same item,
 *   count as forward.
 */
internal fun Timeline.trackChangeDirection(oldIndex: Int, newIndex: Int, repeatMode: Int, shuffle: Boolean): Int {
    val count = windowCount
    if (oldIndex !in 0 until count || newIndex !in 0 until count || oldIndex == newIndex) return TRACK_CHANGE_FORWARD
    val mode = navigationRepeatMode(repeatMode)
    if (getNextWindowIndex(oldIndex, mode, shuffle) == newIndex) return TRACK_CHANGE_FORWARD
    if (getPreviousWindowIndex(oldIndex, mode, shuffle) == newIndex) return TRACK_CHANGE_BACKWARD

    // A jump: compare positions along playback order.
    var oldPosition = -1
    var newPosition = -1
    var index = getFirstWindowIndex(shuffle)
    var position = 0
    while (index != C.INDEX_UNSET && (oldPosition < 0 || newPosition < 0)) {
        if (index == oldIndex) oldPosition = position
        if (index == newIndex) newPosition = position
        index = getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        position++
    }
    return if (newPosition in 0 until oldPosition) TRACK_CHANGE_BACKWARD else TRACK_CHANGE_FORWARD
}
