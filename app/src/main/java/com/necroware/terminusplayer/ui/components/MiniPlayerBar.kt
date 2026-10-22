package com.necroware.terminusplayer.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.necroware.terminusplayer.playback.NowPlayingState
import com.necroware.terminusplayer.ui.TestTags
import kotlin.math.abs

/** Fixed bar height. The bottom bar reserves exactly this much space for the player panel. */
val MiniPlayerHeight: Dp = 52.dp

/** Horizontal travel that commits a swipe-to-skip. */
private val SwipeSkipDistance = 64.dp

/** A fling at least this fast in the swipe direction commits it with less travel. */
private val SwipeSkipFlingVelocity = 900.dp

private val ProgressLineHeight = 2.dp

/** Whether a released horizontal swipe should skip. Negative offsets are leftward (next track). */
internal fun shouldCommitSwipeSkip(offsetPx: Float, velocityPx: Float, distancePx: Float, flingVelocityPx: Float): Boolean {
    if (abs(offsetPx) >= distancePx) return true
    val sameDirection = offsetPx != 0f && (offsetPx < 0f) == (velocityPx < 0f)
    return sameDirection && abs(velocityPx) >= flingVelocityPx && abs(offsetPx) >= distancePx / 3f
}

/**
 * Edge-to-edge mini player bar sitting directly above the bottom nav — NOT
 * a floating rounded pill (that clipped the transport buttons and looked
 * broken). Plain rectangular surface, thin top border to separate it from
 * content above.
 */
@Composable
fun MiniPlayerBar(
    nowPlaying: NowPlayingState,
    currentSongUri: String?,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    /** Artwork blend when the track changes; 0 swaps immediately. */
    artCrossfadeMs: Int = 0,
    /** Playback progress 0..1, read while drawing so position updates only redraw the line. */
    progress: (() -> Float)? = null,
    /** Called with true for a leftward swipe (next) and false for a rightward one (previous). */
    onSwipeSkip: ((toNext: Boolean) -> Unit)? = null,
    swipeSettleSpec: AnimationSpec<Float> = spring()
) {
    if (nowPlaying.title.isBlank()) return

    val density = LocalDensity.current
    val distancePx = with(density) { SwipeSkipDistance.toPx() }
    val flingVelocityPx = with(density) { SwipeSkipFlingVelocity.toPx() }
    var swipeOffsetPx by remember { mutableFloatStateOf(0f) }
    val swipeState = rememberDraggableState { delta -> swipeOffsetPx += delta }
    val swipeModifier = if (onSwipeSkip == null) Modifier else Modifier.draggable(
        state = swipeState,
        orientation = Orientation.Horizontal,
        onDragStopped = { velocity ->
            if (shouldCommitSwipeSkip(swipeOffsetPx, velocity, distancePx, flingVelocityPx)) {
                onSwipeSkip(swipeOffsetPx < 0f)
            }
            animate(swipeOffsetPx, 0f, animationSpec = swipeSettleSpec) { value, _ -> swipeOffsetPx = value }
        }
    )
    val progressColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .testTag(TestTags.MINI_PLAYER)
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            )
            .then(
                if (progress == null) Modifier else Modifier.drawWithContent {
                    drawContent()
                    val lineHeight = ProgressLineHeight.toPx()
                    drawRect(
                        color = progressColor,
                        topLeft = Offset(0f, size.height - lineHeight),
                        size = Size(size.width * progress().coerceIn(0f, 1f), lineHeight)
                    )
                }
            )
            .then(swipeModifier)
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f).graphicsLayer { translationX = swipeOffsetPx },
            verticalAlignment = Alignment.CenterVertically
        ) {
            val artUri = nowPlaying.artworkUri?.takeIf { it.isNotBlank() } ?: currentSongUri.orEmpty()
            SongArt(uriString = artUri, size = 34.dp, modifier = artModifier, crossfadeMs = artCrossfadeMs)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = nowPlaying.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = titleModifier
                )
                Text(
                    text = nowPlaying.artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        CompactTransportControls(
            isPlaying = nowPlaying.isPlaying,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious
        )
    }
}
