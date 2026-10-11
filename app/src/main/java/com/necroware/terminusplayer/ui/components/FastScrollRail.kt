package com.necroware.terminusplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import com.necroware.terminusplayer.ui.components.logicalIndexForScrollTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The shared fast-scroll control used by library and playlist lists. */
@Composable
fun FastScrollRail(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    itemCount: Int,
    scrollTargets: List<Int>,
    initialIndex: Int,
    dragLabel: (Int) -> String = { "" }
) {
    if (!shouldShowFastScroll(itemCount) || scrollTargets.size != itemCount) return

    val scope = rememberCoroutineScope()
    var railHeightPx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var activeIndex by remember { mutableIntStateOf(initialIndex.coerceIn(0, itemCount - 1)) }
    val visibleTargetIndex = logicalIndexForScrollTarget(scrollTargets, listState.firstVisibleItemIndex)
    val scrollFraction = (visibleTargetIndex.toFloat() / (itemCount - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
    val thumbHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
    val thumbTopPx = scrollFraction * (railHeightPx - thumbHeightPx).coerceAtLeast(0f)
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "library-fast-scroll-alpha"
    )

    LaunchedEffect(listState.isScrollInProgress, dragging) {
        if (listState.isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(850)
            visible = false
        }
    }
    LaunchedEffect(visibleTargetIndex, dragging, itemCount) {
        if (!dragging) activeIndex = visibleTargetIndex.coerceIn(0, itemCount - 1)
    }

    val currentThumbTop = if (dragging) {
        (activeIndex.toFloat() / (itemCount - 1).coerceAtLeast(1)) * (railHeightPx - thumbHeightPx).coerceAtLeast(0f)
    } else thumbTopPx
    val labelTop = with(androidx.compose.ui.platform.LocalDensity.current) {
        (currentThumbTop - 4.dp.toPx()).coerceIn(0f, (railHeightPx - 28.dp.toPx()).coerceAtLeast(0f)).toDp()
    }
    fun jumpTo(y: Float) {
        val usableHeight = (railHeightPx - thumbHeightPx).coerceAtLeast(1f)
        val index = (((y - thumbHeightPx / 2f).coerceIn(0f, usableHeight) / usableHeight) * (itemCount - 1))
            .roundToInt().coerceIn(0, itemCount - 1)
        activeIndex = index
        scope.launch { listState.scrollToItem(scrollTargets[index]) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { railHeightPx = it.height.coerceAtLeast(1) }
            .graphicsLayer { this.alpha = alpha }
    ) {
        if (visible || dragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(48.dp)
                    .fillMaxSize()
                    .semantics { contentDescription = "Drag to fast scroll the list" }
                    .pointerInput(itemCount, scrollTargets, railHeightPx) {
                        detectTapGestures { position ->
                            jumpTo(position.y)
                            dragging = true
                            scope.launch {
                                delay(800)
                                dragging = false
                            }
                        }
                    }
                    .pointerInput(itemCount, scrollTargets, railHeightPx) {
                        detectDragGestures(
                            onDragStart = { position ->
                                dragging = true
                                jumpTo(position.y)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false }
                        ) { change, _ ->
                            change.consume()
                            jumpTo(change.position.y)
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 10.dp)
                        .width(3.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(50))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp)
                        .offset { IntOffset(0, currentThumbTop.roundToInt()) }
                        .width(4.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        val currentDragLabel = dragLabel(activeIndex)
        if (dragging && currentDragLabel.isNotBlank()) {
            Text(
                text = currentDragLabel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = labelTop)
                    .widthIn(max = 140.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
