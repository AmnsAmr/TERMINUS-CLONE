package com.necroware.terminusplayer.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.necroware.terminusplayer.playback.NowPlayingState
import com.necroware.terminusplayer.ui.TestTags

/** Fixed bar height. The bottom bar reserves exactly this much space for the player panel. */
val MiniPlayerHeight: Dp = 52.dp

/** Horizontal travel that commits a swipe-to-skip. */
private val SwipeSkipDistance = 64.dp

private val ProgressLineHeight = 2.dp

private val MiniArtSize = 34.dp

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
    /**
     * Called with true for a leftward swipe (next) and false for a rightward one (previous), after the
     * outgoing track has slid out and the incoming one has slid in.
     */
    onSwipeSkip: ((toNext: Boolean) -> Unit)? = null,
    /** Tracks a swipe would reach; a side without one rubber-bands instead of skipping. */
    neighbours: SwipeNeighbours = SwipeNeighbours(),
    swipeSettleSpec: AnimationSpec<Float> = spring(),
    /** Play/pause morph duration from the motion tokens; 0 swaps immediately. */
    playPauseMorphMs: Int = 0
) {
    if (nowPlaying.title.isBlank()) return

    val distancePx = with(LocalDensity.current) { SwipeSkipDistance.toPx() }
    val swipeState = rememberSwipeSkipState()
    // Art + title area width: the outgoing track slides this far out while the next one slides in.
    var pageWidthPx by remember { mutableFloatStateOf(0f) }
    val swipeModifier = if (onSwipeSkip == null) Modifier else Modifier.swipeToSkip(
        state = swipeState,
        enabled = pageWidthPx > 0f,
        neighbours = neighbours,
        pageDistancePx = pageWidthPx,
        commitDistancePx = distancePx,
        settleSpec = swipeSettleSpec,
        onCommit = onSwipeSkip
    )
    if (onSwipeSkip != null) PrefetchSwipeArt(neighbours, MiniArtSize)
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
        SwipeSkipPager(
            state = swipeState,
            currentId = nowPlaying.mediaId,
            pageDistancePx = pageWidthPx,
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .onSizeChanged { pageWidthPx = it.width.toFloat() },
            neighbour = { track ->
                MiniTrackInfo(artUri = track.artUri, title = track.title, artist = track.artist)
            }
        ) {
            MiniTrackInfo(
                artUri = nowPlaying.artworkUri?.takeIf { it.isNotBlank() } ?: currentSongUri.orEmpty(),
                title = nowPlaying.title,
                artist = nowPlaying.artist,
                artModifier = artModifier,
                titleModifier = titleModifier,
                artCrossfadeMs = artCrossfadeMs
            )
        }
        CompactTransportControls(
            isPlaying = !nowPlaying.showPlayButton,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            morphMs = playPauseMorphMs
        )
    }
}

/** Art, title and artist of one track: the mini bar's swipeable page. */
@Composable
private fun MiniTrackInfo(
    artUri: String,
    title: String,
    artist: String,
    artModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    artCrossfadeMs: Int = 0
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SongArt(uriString = artUri, size = MiniArtSize, modifier = artModifier, crossfadeMs = artCrossfadeMs)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
