package com.necroware.terminusplayer.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/** One track as swipe-to-skip draws it. */
@Immutable
data class SwipeTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artUri: String
)

/** The tracks a swipe would reach, in playback order. Null where the queue ends. */
@Immutable
data class SwipeNeighbours(
    val previous: SwipeTrack? = null,
    val next: SwipeTrack? = null
)

/** A fling at least this fast in the swipe direction commits it with less travel. */
internal val SwipeSkipFlingVelocity = 900.dp

/** Furthest a page moves toward a side with no track, as a share of the page distance. */
internal const val RUBBER_BAND_FRACTION = 0.12f

/** How much of the finger's travel the page follows when there is no track on that side, at first. */
private const val RUBBER_BAND_FOLLOW = 0.5f

/** A committed swipe that never sees its track become current (queue edited meanwhile) resets after this. */
private const val PENDING_SKIP_TIMEOUT_MS = 1_500L

/** Whether a released horizontal swipe should skip. Negative offsets are leftward (next track). */
internal fun shouldCommitSwipeSkip(offsetPx: Float, velocityPx: Float, distancePx: Float, flingVelocityPx: Float): Boolean {
    if (abs(offsetPx) >= distancePx) return true
    val sameDirection = offsetPx != 0f && (offsetPx < 0f) == (velocityPx < 0f)
    return sameDirection && abs(velocityPx) >= flingVelocityPx && abs(offsetPx) >= distancePx / 3f
}

/**
 * Resistance toward a side with no track: the page follows at [RUBBER_BAND_FOLLOW] of the finger at
 * first and never moves further than [limit].
 */
internal fun rubberBand(rawOffset: Float, limit: Float): Float {
    if (limit <= 0f) return 0f
    val magnitude = limit * (1f - exp(-RUBBER_BAND_FOLLOW * abs(rawOffset) / limit))
    return if (rawOffset < 0f) -magnitude else magnitude
}

/** Page offset for a raw finger offset: 1:1 toward an existing neighbour, rubber-banded toward none. */
internal fun swipePageOffset(
    rawOffset: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    pageDistance: Float,
    rubberBandLimit: Float
): Float = when {
    rawOffset < 0f && !hasNext -> rubberBand(rawOffset, rubberBandLimit)
    rawOffset > 0f && !hasPrevious -> rubberBand(rawOffset, rubberBandLimit)
    else -> rawOffset.coerceIn(-pageDistance, pageDistance)
}

/** Drag position and commit bookkeeping for one swipe-to-skip surface. */
@Stable
class SwipeSkipState internal constructor() {
    /** Current page offset in px, negative while moving toward the next track. Read it in draw blocks. */
    var offsetPx by mutableFloatStateOf(0f)
        internal set

    /** Neighbours captured when the drag started, so they cannot change under the finger. */
    internal var frozen by mutableStateOf(SwipeNeighbours())

    /** Id of the track a committed swipe is moving to, until that track is current. */
    var pendingTargetId by mutableStateOf<String?>(null)
        internal set

    internal var settleJob: Job? = null

    /** True while [trackId] became current because of this surface's swipe; used to skip canned animations. */
    fun isArrivingBySwipe(trackId: String?): Boolean = trackId != null && pendingTargetId == trackId

    internal fun reset() {
        offsetPx = 0f
        pendingTargetId = null
        frozen = SwipeNeighbours()
    }
}

@Composable
fun rememberSwipeSkipState(): SwipeSkipState = remember { SwipeSkipState() }

/**
 * Horizontal swipe-to-skip that shares the surface with a vertical drag (the player panel).
 *
 * After touch slop the dominant axis decides: horizontal drags are claimed and consumed here, vertical
 * ones are left untouched for the panel. A committed swipe moves the pages to their final positions
 * with [settleSpec] (the panel spring tokens; snap under motion OFF) and only then calls [onCommit].
 */
@Composable
internal fun Modifier.swipeToSkip(
    state: SwipeSkipState,
    enabled: Boolean,
    neighbours: SwipeNeighbours,
    pageDistancePx: Float,
    commitDistancePx: Float,
    settleSpec: AnimationSpec<Float>,
    onCommit: (toNext: Boolean) -> Unit
): Modifier {
    if (!enabled) return this
    val flingVelocityPx = with(LocalDensity.current) { SwipeSkipFlingVelocity.toPx() }
    val latestNeighbours by rememberUpdatedState(neighbours)
    val latestDistance by rememberUpdatedState(pageDistancePx)
    val latestCommitDistance by rememberUpdatedState(commitDistancePx)
    val latestSpec by rememberUpdatedState(settleSpec)
    val latestOnCommit by rememberUpdatedState(onCommit)

    return pointerInput(state, flingVelocityPx) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // A committed swipe is still landing; ignore new drags until it has.
                if (state.pendingTargetId != null) return@awaitEachGesture

                // Slop phase: decide the axis once, by the dominant direction.
                val slop = viewConfiguration.touchSlop
                var total = Offset.Zero
                var claimed: PointerInputChange? = null
                while (claimed == null) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    // Consumed means another detector (the panel's vertical drag) took the gesture.
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    total += change.positionChange()
                    if (total.getDistance() > slop) {
                        if (abs(total.x) <= abs(total.y)) return@awaitEachGesture
                        change.consume()
                        claimed = change
                    }
                }

                state.settleJob?.cancel()
                val frozen = latestNeighbours
                state.frozen = frozen
                val distance = latestDistance
                val rubberLimit = distance * RUBBER_BAND_FRACTION
                fun pageOffset(raw: Float) =
                    swipePageOffset(raw, frozen.previous != null, frozen.next != null, distance, rubberLimit)

                // Continue from wherever an interrupted settle left the page; drop the slop so it does not jump.
                var raw = state.offsetPx + total.x - sign(total.x) * slop
                state.offsetPx = pageOffset(raw)
                val tracker = VelocityTracker()
                tracker.addPosition(claimed.uptimeMillis, claimed.position)

                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) break
                    raw += change.positionChange().x
                    change.consume()
                    state.offsetPx = pageOffset(raw)
                }

                val velocity = tracker.calculateVelocity().x
                val offset = state.offsetPx
                val toNext = offset < 0f
                val target = if (toNext) frozen.next else frozen.previous
                val commit = target != null &&
                    shouldCommitSwipeSkip(offset, velocity, latestCommitDistance, flingVelocityPx)
                val spec = latestSpec
                state.settleJob = launch {
                    if (target != null && commit) {
                        state.pendingTargetId = target.id
                        try {
                            animate(offset, if (toNext) -distance else distance, velocity, spec) { value, _ ->
                                state.offsetPx = value
                            }
                        } catch (e: CancellationException) {
                            state.reset()
                            throw e
                        }
                        latestOnCommit(toNext)
                    } else {
                        animate(offset, 0f, velocity, spec) { value, _ -> state.offsetPx = value }
                    }
                }
            }
        }
    }
}

/**
 * Draws [current] at the swipe offset and, while it is moving, the neighbour entering from the side
 * it moves away from ([pageDistancePx] apart). When a committed swipe's track becomes current the page
 * is centred in that same frame, so the cover that slid in never jumps.
 */
@Composable
internal fun SwipeSkipPager(
    state: SwipeSkipState,
    currentId: String?,
    pageDistancePx: Float,
    modifier: Modifier = Modifier,
    neighbour: @Composable (SwipeTrack) -> Unit,
    current: @Composable () -> Unit
) {
    val latestId by rememberUpdatedState(currentId)
    val arrived by remember(state) { derivedStateOf { state.isArrivingBySwipe(latestId) } }
    // -1 while moving toward the next track (it enters from the right), +1 toward the previous one.
    val side by remember(state) {
        derivedStateOf { if (arrived) 0 else sign(state.offsetPx).toInt() }
    }

    LaunchedEffect(state, currentId) {
        if (state.isArrivingBySwipe(currentId)) state.reset()
    }
    val pendingTarget = state.pendingTargetId
    LaunchedEffect(state, pendingTarget) {
        if (pendingTarget == null) return@LaunchedEffect
        delay(PENDING_SKIP_TIMEOUT_MS)
        if (state.pendingTargetId == pendingTarget) state.reset()
    }

    Box(modifier) {
        Box(Modifier.graphicsLayer { translationX = if (arrived) 0f else state.offsetPx }) {
            current()
        }
        val track = when {
            side < 0 -> state.frozen.next
            side > 0 -> state.frozen.previous
            else -> null
        }
        if (track != null) {
            key(track.id) {
                Box(Modifier.graphicsLayer { translationX = state.offsetPx - side * pageDistancePx }) {
                    neighbour(track)
                }
            }
        }
    }
}

/** Decodes neighbour artwork at its display size ahead of a swipe, into the shared Coil memory cache. */
@Composable
internal fun PrefetchSwipeArt(neighbours: SwipeNeighbours, size: Dp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val uris = listOfNotNull(neighbours.previous?.artUri, neighbours.next?.artUri)
    LaunchedEffect(uris, sizePx) {
        uris.forEach { uri ->
            val model = songArtModel(uri) ?: return@forEach
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(model).size(sizePx).build()
            )
        }
    }
}
