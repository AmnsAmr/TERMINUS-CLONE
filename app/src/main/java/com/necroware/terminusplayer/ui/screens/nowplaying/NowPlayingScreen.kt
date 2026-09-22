package com.necroware.terminusplayer.ui.screens.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.ui.components.AudioMonitorCard
import com.necroware.terminusplayer.ui.components.BlockSeekBar
import com.necroware.terminusplayer.ui.components.FullTransportControls
import com.necroware.terminusplayer.ui.components.PlaybackArt
import com.necroware.terminusplayer.ui.components.SongArt
import com.necroware.terminusplayer.util.estimateKbpsFromSize
import com.necroware.terminusplayer.util.sizeLabelFromBytes
import com.necroware.terminusplayer.util.toMinutesSeconds
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.necroware.terminusplayer.ui.components.TerminalBorder
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.height

@Composable
private fun rememberSmoothPosition(
    actualPositionMs: Long,
    isPlaying: Boolean
): androidx.compose.runtime.MutableState<Long> {
    val smooth = remember { mutableStateOf(actualPositionMs) }

    LaunchedEffect(actualPositionMs) {
        smooth.value = actualPositionMs
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastFrameNanos = withFrameNanos { it }
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            val deltaMs = (frameNanos - lastFrameNanos) / 1_000_000L
            lastFrameNanos = frameNanos
            smooth.value += deltaMs
        }
    }

    return smooth
}

@Composable
fun NowPlayingScreen(viewModel: PlaybackViewModel, onCollapse: () -> Unit) {
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val isLiked by viewModel.isCurrentLiked.collectAsStateWithLifecycle()
    val currentSongUri by viewModel.currentSongUri.collectAsStateWithLifecycle()
    val artStyle by viewModel.artStyle.collectAsStateWithLifecycle()
    val currentLyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()

    val smoothPositionState = rememberSmoothPosition(positionMs, nowPlaying.isPlaying)
    val smoothPositionMs = smoothPositionState.value.coerceIn(0L, nowPlaying.durationMs.coerceAtLeast(0L))

    val coroutineScope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    var screenHeightPx by remember { mutableStateOf(1f) }
    val dismissThresholdFraction = 0.25f
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "︿",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onCollapse() }
                )
                Text(
                    text = if (isLiked) "[LIKED]" else "[LIKE]",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { viewModel.toggleCurrentLike() }
                )
            }

            // Playback Art / Song Art
            if (artStyle == com.necroware.terminusplayer.data.prefs.PlaybackArtStyle.STANDARD) {
                SongArt(
                    uriString = currentSongUri.orEmpty(),
                    size = 300.dp,
                    modifier = Modifier.padding(top = 20.dp, bottom = 20.dp).pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val newOffset = (offsetY.value + dragAmount).coerceAtLeast(0f)
                                coroutineScope.launch { offsetY.snapTo(newOffset) }
                            },
                            onDragEnd = {
                                coroutineScope.launch {
                                    if (offsetY.value > screenHeightPx * dismissThresholdFraction) {
                                        onCollapse()
                                    } else {
                                        offsetY.animateTo(0f)
                                    }
                                }
                            }
                        )
                    }
                )
            } else {
                PlaybackArt(
                    style = artStyle,
                    title = nowPlaying.title,
                    artist = nowPlaying.artist,
                    isPlaying = nowPlaying.isPlaying,
                    size = 300.dp,
                    modifier = Modifier.padding(top = 20.dp, bottom = 20.dp).pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val newOffset = (offsetY.value + dragAmount).coerceAtLeast(0f)
                                coroutineScope.launch { offsetY.snapTo(newOffset) }
                            },
                            onDragEnd = {
                                coroutineScope.launch {
                                    if (offsetY.value > screenHeightPx * dismissThresholdFraction) {
                                        onCollapse()
                                    } else {
                                        offsetY.animateTo(0f)
                                    }
                                }
                            }
                        )
                    }
                )
            }

            Text(
                text = nowPlaying.title.ifBlank { "—" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = nowPlaying.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            BlockSeekBar(
                positionMs = smoothPositionMs,
                durationMs = nowPlaying.durationMs,
                onSeek = { newPositionMs ->
                    smoothPositionState.value = newPositionMs
                    viewModel.seekTo(newPositionMs)
                },
                modifier = Modifier.padding(top = 20.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(smoothPositionMs.toMinutesSeconds(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(nowPlaying.durationMs.toMinutesSeconds(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            FullTransportControls(
                isPlaying = nowPlaying.isPlaying,
                shuffleEnabled = nowPlaying.shuffleEnabled,
                repeatMode = nowPlaying.repeatMode,
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onSkipNext = { viewModel.skipToNext() },
                onSkipPrevious = { viewModel.skipToPrevious() },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onCycleRepeat = { viewModel.cycleRepeatMode() },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            )

            val kbps = nowPlaying.bitrateKbps.takeIf { it > 0 }
                ?: estimateKbpsFromSize(nowPlaying.sizeBytes, nowPlaying.durationMs).takeIf { it > 0 }
            AudioMonitorCard(
                codecLabel = nowPlaying.codecLabel,
                bitDepthLabel = nowPlaying.bitDepthLabel,
                kbpsLabel = kbps?.toString() ?: "—",
                sizeLabel = sizeLabelFromBytes(nowPlaying.sizeBytes),
                isLossless = nowPlaying.isLossless,
                repeatMode = nowPlaying.repeatMode,
                isPlaying = nowPlaying.isPlaying,
                modifier = Modifier.padding(top = 24.dp, bottom = 24.dp)
            )

            val lyrics = currentLyrics
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                TerminalBorder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(bottom = 24.dp)
                ) {
                androidx.compose.runtime.key(lyrics) {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    val isSynced = remember(lyrics) { lyrics.lines.any { it.startMs > 0L } }
                    val activeIndex = if (isSynced) {
                        lyrics.lines.indexOfLast { it.startMs <= smoothPositionMs }
                    } else {
                        -1
                    }
                    
                    LaunchedEffect(activeIndex) {
                        if (activeIndex >= 0 && !listState.isScrollInProgress) {
                            // Calculate a reasonable offset to center the active item (approximate)
                            listState.animateScrollToItem(activeIndex.coerceAtLeast(0))
                        }
                    }

                    Column {
                        Text(
                            text = if (isSynced) "[SYNCED LYRICS]" else "[LYRICS]",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        androidx.compose.foundation.lazy.LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(lyrics.lines) { index, line ->
                                val isActive = isSynced && index == activeIndex
                                Text(
                                    text = line.text.ifBlank { " " },
                                    style = if (isActive) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = isSynced) {
                                            smoothPositionState.value = line.startMs
                                            viewModel.seekTo(line.startMs)
                                        }
                                        .padding(vertical = 8.dp, horizontal = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
