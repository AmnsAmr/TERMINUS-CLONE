package com.necroware.terminusplayer.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.necroware.terminusplayer.ui.screens.nowplaying.NowPlayingTransition
import kotlinx.coroutines.flow.drop

internal enum class PlayerPanelValue { Collapsed, Expanded }

/** Share of the travel distance a slow drag must cover before the panel settles at the other end. */
private const val PanelPositionalThreshold = 0.25f

/** A fling faster than this settles in the fling direction regardless of distance. */
private val PanelVelocityThreshold = 125.dp

/** The mini bar is fully faded at this expansion; the full player content starts fading in here. */
internal const val MiniFadeEnd = 0.35f

/** 0 = mini (collapsed), 1 = full. [collapsedOffset] is the panel's top edge when collapsed. */
internal fun panelExpansionFraction(offset: Float, collapsedOffset: Float): Float =
    if (collapsedOffset <= 0f) {
        if (offset <= 0f) 1f else 0f
    } else {
        (1f - offset / collapsedOffset).coerceIn(0f, 1f)
    }

internal fun miniBarAlpha(fraction: Float): Float = (1f - fraction / MiniFadeEnd).coerceIn(0f, 1f)

internal fun fullContentAlpha(fraction: Float): Float =
    ((fraction - MiniFadeEnd) / (1f - MiniFadeEnd)).coerceIn(0f, 1f)

/** The full title crossfades in while the mini bar fades out. */
internal fun sharedTitleAlpha(fraction: Float): Float = (fraction / MiniFadeEnd).coerceIn(0f, 1f)

/**
 * Translation that places an element laid out at [to] onto [from] at fraction 0 and leaves
 * it in place at fraction 1. Pass either the left or the top edges.
 */
internal fun sharedBoundsTranslation(fromEdge: Float, toEdge: Float, fraction: Float): Float =
    (fromEdge - toEdge) * (1f - fraction)

internal fun sharedBoundsScale(startScale: Float, fraction: Float): Float =
    startScale + (1f - startScale) * fraction

@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class PlayerPanelState(
    internal val draggable: AnchoredDraggableState<PlayerPanelValue>
) {
    private var collapsedOffsetPx by mutableFloatStateOf(0f)

    // Element bounds in the panel's own (untranslated) coordinates, written from layout callbacks
    // and read only inside graphicsLayer blocks.
    internal var miniArtBounds by mutableStateOf(Rect.Zero)
    internal var miniTitleBounds by mutableStateOf(Rect.Zero)
    internal var fullArtBounds by mutableStateOf(Rect.Zero)
    internal var fullTitleBounds by mutableStateOf(Rect.Zero)

    /** Not state: only used to resolve child bounds during layout callbacks. */
    internal var panelCoordinates: LayoutCoordinates? = null

    /**
     * Expansion from 0 (mini) to 1 (full). Changes every frame while dragging or settling,
     * so read it only in graphicsLayer/draw blocks or through derivedStateOf.
     */
    val fraction: Float
        get() {
            val offset = draggable.offset
            if (offset.isNaN()) return if (draggable.currentValue == PlayerPanelValue.Expanded) 1f else 0f
            return panelExpansionFraction(offset, collapsedOffsetPx)
        }

    internal fun updateAnchors(collapsedOffset: Float) {
        collapsedOffsetPx = collapsedOffset
        draggable.updateAnchors(
            DraggableAnchors {
                PlayerPanelValue.Collapsed at collapsedOffset
                PlayerPanelValue.Expanded at 0f
            },
            newTarget = draggable.targetValue
        )
    }

    // Programmatic open/close start from rest; the default would reuse the last fling's velocity.
    suspend fun expand() = draggable.animateTo(PlayerPanelValue.Expanded, velocity = 0f)

    suspend fun collapse() = draggable.animateTo(PlayerPanelValue.Collapsed, velocity = 0f)
}

/**
 * Holds the panel's expansion. The spring/snap spec comes from the motion tokens; when it changes
 * the state is rebuilt at the same open/closed position.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberPlayerPanelState(panelMotion: PanelMotionSpec): PlayerPanelState {
    val density = LocalDensity.current
    var expandedSaved by rememberSaveable { mutableStateOf(false) }
    val state = remember(panelMotion, density) {
        PlayerPanelState(
            AnchoredDraggableState(
                initialValue = if (expandedSaved) PlayerPanelValue.Expanded else PlayerPanelValue.Collapsed,
                positionalThreshold = { distance -> distance * PanelPositionalThreshold },
                velocityThreshold = { with(density) { PanelVelocityThreshold.toPx() } },
                animationSpec = panelMotion.floatSpec
            )
        )
    }
    LaunchedEffect(state) {
        snapshotFlow { state.draggable.currentValue }.collect {
            expandedSaved = it == PlayerPanelValue.Expanded
        }
    }
    return state
}

private fun Modifier.trackPanelBounds(state: PlayerPanelState, onBounds: (Rect) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        val panel = state.panelCoordinates ?: return@onGloballyPositioned
        if (panel.isAttached && coordinates.isAttached) {
            onBounds(panel.localBoundingBoxOf(coordinates, clipBounds = false))
        }
    }

/** Moves and scales an element laid out at [to] so it starts on [from]. Origin is the top-left corner. */
private fun GraphicsLayerScope.applySharedBounds(from: Rect, to: Rect, startScale: Float, fraction: Float) {
    transformOrigin = TransformOrigin(0f, 0f)
    val scale = sharedBoundsScale(startScale, fraction)
    scaleX = scale
    scaleY = scale
    translationX = sharedBoundsTranslation(from.left, to.left, fraction)
    translationY = sharedBoundsTranslation(from.top, to.top, fraction)
}

/**
 * Persistent player surface drawn above the NavHost and below the bottom navigation.
 * Collapsed, its top edge sits where the bottom bar reserves room for the mini bar; expanded,
 * it covers the screen. The mini bar and full player are its two faces.
 *
 * @param bottomBarHeight Scaffold bottom padding: reserved mini-bar room plus the navigation bar.
 * @param miniVisible False when nothing is loaded; the collapsed panel then rests fully off-screen.
 * @param collapsedShiftPx Extra downward shift of the collapsed panel, applied in proportion to how
 *   collapsed it is (none when open). Used while the bottom nav is slid away. Read while drawing.
 * @param mini Mini bar; receives modifiers for its artwork, title and whole row.
 * @param full Full player; composed only while the panel is at least partly open.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerPanel(
    state: PlayerPanelState,
    bottomBarHeight: Dp,
    miniVisible: Boolean,
    collapsedShiftPx: () -> Float,
    background: Color,
    onCollapsed: () -> Unit,
    mini: @Composable (artModifier: Modifier, titleModifier: Modifier, rowModifier: Modifier) -> Unit,
    full: @Composable (NowPlayingTransition) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var heightPx by remember { mutableIntStateOf(0) }
    val bottomBarPx = with(density) { bottomBarHeight.toPx() }
    SideEffect {
        if (heightPx > 0) {
            state.updateAnchors(if (miniVisible) heightPx - bottomBarPx else heightPx.toFloat())
        }
    }

    val latestOnCollapsed by rememberUpdatedState(onCollapsed)
    LaunchedEffect(state) {
        snapshotFlow { state.draggable.currentValue }.drop(1).collect {
            if (it == PlayerPanelValue.Collapsed) latestOnCollapsed()
        }
    }

    val isOpen by remember(state) { derivedStateOf { state.fraction > 0f } }
    val isFullyOpen by remember(state) { derivedStateOf { state.fraction >= 1f } }

    // Mini title is bodySmall, full title is titleLarge; scale the full title down to match at fraction 0.
    val typography = MaterialTheme.typography
    val titleStartScale = remember(typography) {
        val mini = typography.bodySmall.fontSize.value
        val full = typography.titleLarge.fontSize.value
        if (mini.isNaN() || full.isNaN() || full <= 0f) 0.5f else mini / full
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height }
            .graphicsLayer {
                val offset = state.draggable.offset
                translationY = when {
                    !offset.isNaN() -> offset + collapsedShiftPx() * (1f - state.fraction)
                    state.draggable.currentValue == PlayerPanelValue.Expanded -> 0f
                    else -> size.height
                }
            }
            .onGloballyPositioned { state.panelCoordinates = it }
            .anchoredDraggable(state.draggable, Orientation.Vertical)
            .background(background)
    ) {
        if (miniVisible && !isFullyOpen) {
            val miniModifiers = remember(state) {
                Triple(
                    Modifier
                        .trackPanelBounds(state) { state.miniArtBounds = it }
                        .graphicsLayer {
                            // The full artwork takes over this exact spot as soon as the panel moves.
                            alpha = if (state.fraction > 0f && !state.fullArtBounds.isEmpty) 0f else 1f
                        },
                    Modifier.trackPanelBounds(state) { state.miniTitleBounds = it },
                    Modifier.graphicsLayer { alpha = miniBarAlpha(state.fraction) }
                )
            }
            mini(miniModifiers.first, miniModifiers.second, miniModifiers.third)
        }
        if (isOpen) {
            val transition = remember(state, titleStartScale) {
                NowPlayingTransition(
                    drag = Modifier.anchoredDraggable(state.draggable, Orientation.Vertical),
                    art = Modifier
                        .trackPanelBounds(state) { state.fullArtBounds = it }
                        .graphicsLayer {
                            val to = state.fullArtBounds
                            if (to.isEmpty) {
                                alpha = 0f
                                return@graphicsLayer
                            }
                            val from = state.miniArtBounds
                            if (!from.isEmpty) applySharedBounds(from, to, from.width / to.width, state.fraction)
                        },
                    title = Modifier
                        .trackPanelBounds(state) { state.fullTitleBounds = it }
                        .graphicsLayer {
                            val fraction = state.fraction
                            alpha = sharedTitleAlpha(fraction)
                            val to = state.fullTitleBounds
                            val from = state.miniTitleBounds
                            if (!to.isEmpty && !from.isEmpty) applySharedBounds(from, to, titleStartScale, fraction)
                        },
                    content = Modifier.graphicsLayer { alpha = fullContentAlpha(state.fraction) }
                )
            }
            // Same insets Scaffold gave the Now Playing route before it moved into the panel.
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                full(transition)
            }
        }
    }
}
