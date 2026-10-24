package com.necroware.terminusplayer.ui.screens.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.playback.QueueEntry
import com.necroware.terminusplayer.playback.QueueState
import com.necroware.terminusplayer.playback.RemovedQueueItem
import com.necroware.terminusplayer.playback.moveFor
import com.necroware.terminusplayer.ui.navigation.MotionTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class QueueSheetValue { Collapsed, Half, Expanded }

/** Height of the collapsed sheet's [ QUEUE ] strip; the player content leaves this much room at the bottom. */
internal val QueuePeekHeight = 44.dp

private val QueueRowHeight = 56.dp
private val SheetVelocityThreshold = 125.dp

/**
 * Queue sheet over the expanded player. Collapsed it is a [ QUEUE ] strip at the bottom: tap it or
 * drag it up. Half and Expanded show the queue in playback order with the current track highlighted.
 * Rows: tap plays, the handle reorders (not in shuffle), a sideways swipe removes with UNDO.
 *
 * Drags inside the list go to the list first; once it is at the top, a downward drag moves the
 * sheet down, and an upward drag on a half-open sheet expands it before the list scrolls. Back
 * closes the sheet before the player.
 *
 * The queue is collected only while the sheet is out of its collapsed position.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QueueSheet(
    queueFlow: StateFlow<QueueState>,
    lastRemoved: RemovedQueueItem?,
    motion: MotionTokens,
    onPlay: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val state = remember(motion.panel, density) {
        AnchoredDraggableState(
            initialValue = QueueSheetValue.Collapsed,
            positionalThreshold = { distance -> distance * 0.5f },
            velocityThreshold = { with(density) { SheetVelocityThreshold.toPx() } },
            animationSpec = motion.panel.floatSpec
        )
    }
    val open by remember(state) {
        derivedStateOf {
            state.currentValue != QueueSheetValue.Collapsed || state.targetValue != QueueSheetValue.Collapsed ||
                (!state.offset.isNaN() && state.anchors.positionOf(QueueSheetValue.Collapsed).let { !it.isNaN() && state.offset < it - 1f })
        }
    }

    BackHandler(enabled = open) {
        scope.launch { state.animateTo(QueueSheetValue.Collapsed) }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val fullPx = constraints.maxHeight.toFloat()
        val peekPx = with(density) { QueuePeekHeight.toPx() }
        SideEffect {
            if (fullPx > 0f) {
                state.updateAnchors(
                    DraggableAnchors {
                        QueueSheetValue.Collapsed at (fullPx - peekPx)
                        QueueSheetValue.Half at fullPx * 0.5f
                        QueueSheetValue.Expanded at 0f
                    },
                    newTarget = state.targetValue
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .height(maxHeight)
                .offset {
                    val offset = state.offset
                    IntOffset(0, (if (offset.isNaN()) fullPx - peekPx else offset).roundToInt())
                }
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(QueuePeekHeight)
                    .anchoredDraggable(state, Orientation.Vertical)
                    .clickable {
                        scope.launch {
                            state.animateTo(
                                if (state.currentValue == QueueSheetValue.Collapsed) QueueSheetValue.Half else QueueSheetValue.Collapsed
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (open) "[ ▼ QUEUE ]" else "[ ▲ QUEUE ]",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (open) {
                val queue by queueFlow.collectAsStateWithLifecycle()
                QueueList(
                    queue = queue,
                    lastRemoved = lastRemoved,
                    motion = motion,
                    sheetScroll = remember(state) { sheetNestedScroll(state) },
                    onPlay = onPlay,
                    onMove = onMove,
                    onRemove = onRemove,
                    onUndo = onUndo
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun QueueList(
    queue: QueueState,
    lastRemoved: RemovedQueueItem?,
    motion: MotionTokens,
    sheetScroll: NestedScrollConnection,
    onPlay: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onUndo: () -> Unit
) {
    val listState = rememberLazyListState()
    val rowPx = with(LocalDensity.current) { QueueRowHeight.toPx() }
    // Gesture callbacks outlive compositions, so they read the latest queue through these.
    val latestQueue by rememberUpdatedState(queue)
    val latestOnMove by rememberUpdatedState(onMove)
    // A drag works on one editable copy of the rows (made once at drag start), and moves a single
    // row within it: no list search or full copy per pointer event, which a 4,887-track queue felt.
    var reordered by remember { mutableStateOf<SnapshotStateList<QueueEntry>?>(null) }
    val rows: List<QueueEntry> = reordered ?: queue.entries
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragStart by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // Back to the player's order once no drag is running (after a drop, the player's update has it).
    LaunchedEffect(queue.entries, dragKey) {
        if (dragKey == null) reordered = null
    }
    val instant = motion.transition.isInstant || motion.transition.slideFraction == 0f
    val placementSpec: FiniteAnimationSpec<IntOffset> = remember(instant) {
        if (instant) snap() else spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
    }

    // Bring the current track into view when the sheet opens and when it changes.
    val current = queue.currentPosition
    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        val target = (current - 1).coerceAtLeast(0)
        if (instant) listState.scrollToItem(target) else listState.animateScrollToItem(target)
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = if (queue.shuffle) "${queue.entries.size} TRACKS · SHUFFLE ORDER · TURN SHUFFLE OFF TO REORDER"
            else "${queue.entries.size} TRACKS · DRAG ≡ TO REORDER · SWIPE TO REMOVE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        // Under the header, so it is visible at any sheet height.
        if (lastRemoved != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REMOVED: ${lastRemoved.title.ifBlank { "track" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "[ UNDO ]",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onUndo).padding(start = 12.dp)
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).nestedScroll(sheetScroll)
        ) {
            itemsIndexed(rows, key = { _, entry -> entry.key }) { position, entry ->
                val dragging = entry.key == dragKey
                val latestEntry by rememberUpdatedState(entry)
                val dismiss = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value -> value == SwipeToDismissBoxValue.Settled || !dragging }
                )
                // Remove once the row has settled off-screen. confirmValueChange can be called more than
                // once per swipe, so it must not remove (it removed two tracks on the S21 FE).
                LaunchedEffect(dismiss.currentValue) {
                    if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) onRemove(latestEntry.index)
                }
                SwipeToDismissBox(
                    state = dismiss,
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f)).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text("[ REMOVE ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier
                        .then(if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset } else Modifier.animateItemPlacement(placementSpec))
                ) {
                    QueueRow(
                        entry = entry,
                        number = position + 1,
                        isCurrent = entry.index == queue.currentIndex,
                        canReorder = queue.canReorder,
                        dragging = dragging,
                        onClick = { onPlay(entry.index) },
                        handle = Modifier.pointerInput(entry.key) {
                            detectDragGestures(
                                onDragStart = {
                                    // Not while the last drop is gliding or waiting for the player to confirm
                                    // it: row positions would not match the player's queue yet.
                                    val busy = reordered != null || settleJob?.isActive == true
                                    val base = latestQueue.entries
                                    val start = if (busy) -1 else base.indexOfFirst { it.key == entry.key }
                                    if (start >= 0) {
                                        reordered = base.toMutableStateList()
                                        dragKey = entry.key
                                        dragStart = start
                                        dragPos = start
                                        dragOffset = 0f
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val list = reordered
                                    if (list != null && dragPos >= 0) {
                                        dragOffset += amount.y
                                        val steps = reorderSteps(dragOffset, rowPx)
                                        if (steps != 0) {
                                            val to = (dragPos + steps).coerceIn(0, list.lastIndex)
                                            if (to != dragPos) {
                                                list.add(to, list.removeAt(dragPos))
                                                dragOffset -= (to - dragPos) * rowPx
                                                dragPos = to
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val move = latestQueue.moveFor(dragStart, dragPos)
                                    dragPos = -1
                                    // Glide into the slot instead of snapping by up to half a row, and only
                                    // then tell the player: applying the move makes Media3 resend the whole
                                    // queue and the sheet rebuild, which stuttered the glide on long queues.
                                    settleJob = scope.launch {
                                        try {
                                            animate(dragOffset, 0f, animationSpec = motion.panel.floatSpec) { value, _ -> dragOffset = value }
                                        } finally {
                                            move?.let { (from, to) -> latestOnMove(from, to) }
                                            dragKey = null
                                        }
                                    }
                                },
                                onDragCancel = {
                                    reordered = null
                                    dragKey = null
                                    dragPos = -1
                                    dragOffset = 0f
                                }
                            )
                        }
                    )
                }
            }
        }

    }
}

/** Whole rows a drag of [offsetPx] has passed, switching at the half-row mark. */
internal fun reorderSteps(offsetPx: Float, rowPx: Float): Int =
    if (rowPx <= 0f) 0 else (offsetPx / rowPx).let { if (it >= 0f) (it + 0.5f).toInt() else -((-it + 0.5f).toInt()) }

@Composable
private fun QueueRow(
    entry: QueueEntry,
    number: Int,
    isCurrent: Boolean,
    canReorder: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    handle: Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            // Opaque first: the swipe-to-remove background sits behind every row.
            .background(MaterialTheme.colorScheme.surface)
            .background(
                when {
                    dragging -> MaterialTheme.colorScheme.surfaceVariant
                    isCurrent -> accent.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .clickable(onClick = onClick)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isCurrent) "▶" else number.toString().padStart(3),
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.title.ifBlank { entry.mediaId },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canReorder) {
            Box(handle.width(52.dp).height(QueueRowHeight), contentAlignment = Alignment.Center) {
                Text("≡", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Shares drags between the queue list and the sheet: an upward drag first expands the sheet, a
 * downward drag the list has no room for (at the top) moves the sheet down; flings settle it.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun sheetNestedScroll(state: AnchoredDraggableState<QueueSheetValue>) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        return if (delta < 0f && source == NestedScrollSource.Drag) Offset(0f, state.dispatchRawDelta(delta)) else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.Drag) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val offset = state.offset
        val top = state.anchors.minAnchor()
        return if (available.y < 0f && !offset.isNaN() && offset > top) {
            state.settle(available.y)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        state.settle(available.y)
        return available
    }
}
