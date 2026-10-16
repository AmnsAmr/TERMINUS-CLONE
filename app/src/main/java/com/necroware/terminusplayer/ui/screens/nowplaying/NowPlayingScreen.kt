package com.necroware.terminusplayer.ui.screens.nowplaying

import com.necroware.terminusplayer.ui.components.TerminalActionText

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.ui.components.AudioMonitorCard
import com.necroware.terminusplayer.data.prefs.MotionPreference
import com.necroware.terminusplayer.ui.components.BlockSeekBar
import com.necroware.terminusplayer.ui.components.FullTransportControls
import com.necroware.terminusplayer.ui.components.PlaybackArt
import com.necroware.terminusplayer.ui.components.SongArt
import com.necroware.terminusplayer.util.estimateKbpsFromSize
import com.necroware.terminusplayer.util.sizeLabelFromBytes
import com.necroware.terminusplayer.util.toMinutesSeconds
import com.necroware.terminusplayer.util.EXTERNAL_AUDIO_MEDIA_ID_PREFIX
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.necroware.terminusplayer.ui.components.TerminalBorder
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
private fun rememberSmoothPosition(
    actualPositionMs: Long,
    isPlaying: Boolean,
    animate: Boolean
): androidx.compose.runtime.MutableState<Long> {
    val smooth = remember { mutableStateOf(actualPositionMs) }

    LaunchedEffect(actualPositionMs, animate) {
        smooth.value = actualPositionMs
    }

    LaunchedEffect(isPlaying, animate) {
        if (!isPlaying || !animate) return@LaunchedEffect
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
private fun PlaybackProgressSection(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    motionPreference: MotionPreference,
    enabled: Boolean,
    onSeek: (Long) -> Unit
) {
    val progressState = rememberSmoothPosition(
        actualPositionMs = positionMs,
        isPlaying = isPlaying,
        animate = motionPreference == MotionPreference.FULL
    )
    val displayedPositionMs = progressState.value.coerceIn(0L, durationMs.coerceAtLeast(0L))
    BlockSeekBar(
        positionMs = displayedPositionMs,
        durationMs = durationMs,
        onSeek = { newPositionMs ->
            progressState.value = newPositionMs
            onSeek(newPositionMs)
        },
        modifier = Modifier.padding(top = 20.dp),
        enabled = enabled
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(displayedPositionMs.toMinutesSeconds(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(durationMs.toMinutesSeconds(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SleepTimerControl(
    remainingMs: Long?,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit
) {
    var showOptions by remember { mutableStateOf(false) }
    TerminalBorder(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("SLEEP TIMER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    remainingMs?.let { ms ->
                        val totalSeconds = (ms + 999L) / 1_000L
                        "STOPS IN %02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
                    } ?: "OFF",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TerminalActionText(
                if (remainingMs == null) "[ SET ]" else "[ CANCEL ]",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    if (remainingMs == null) showOptions = true else onCancel()
                }.padding(8.dp)
            )
        }
    }

    if (showOptions) {
        Dialog(onDismissRequest = { showOptions = false }) {
            TerminalBorder(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("STOP PLAYBACK AFTER", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    listOf(15, 30, 45, 60, 90, 120).chunked(2).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { minutes ->
                                TerminalActionText(
                                    "[ $minutes MIN ]",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.clickable {
                                        onStart(minutes)
                                        showOptions = false
                                    }.padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                    TerminalActionText(
                        "[ CANCEL ]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End).clickable { showOptions = false }.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun NowPlayingScreen(
    viewModel: PlaybackViewModel,
    onCollapse: () -> Unit,
    motionPreference: MotionPreference = MotionPreference.FULL,
    systemAnimationsEnabled: Boolean = true
) {
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val pendingSong by viewModel.pendingSong.collectAsStateWithLifecycle()
    val pendingPlaybackError by viewModel.pendingPlaybackError.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val isLiked by viewModel.isCurrentLiked.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistActionMessage by viewModel.playlistActionMessage.collectAsStateWithLifecycle()
    val currentSongUri by viewModel.currentSongUri.collectAsStateWithLifecycle()
    val artStyle by viewModel.artStyle.collectAsStateWithLifecycle()
    val currentLyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val preparingSelection = pendingSong != null
    val displayTitle = pendingSong?.title ?: nowPlaying.title
    val displayArtist = pendingSong?.artist ?: nowPlaying.artist
    val displayDurationMs = pendingSong?.duration ?: nowPlaying.durationMs
    val displaySongUri = pendingSong?.uriString ?: currentSongUri.orEmpty()
    val displayIsPlaying = !preparingSelection && nowPlaying.isPlaying
    val displayPositionMs = if (preparingSelection) 0L else positionMs
    val collapsePlayer = {
        viewModel.clearPendingPlaybackSelection()
        onCollapse()
    }

    BackHandler {
        collapsePlayer()
    }

    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var screenHeightPx by remember { mutableStateOf(1f) }
    var lyricsExpanded by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val selectedSongId = pendingSong?.id ?: nowPlaying.mediaId
    val selectedSongIsExternal = selectedSongId.orEmpty().startsWith(EXTERNAL_AUDIO_MEDIA_ID_PREFIX)
    val dismissThresholdFraction = 0.25f
    val scrollState = rememberScrollState()

    LaunchedEffect(lyricsExpanded) {
        if (lyricsExpanded) {
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    var previousScrollValue by remember { mutableStateOf(0) }
    LaunchedEffect(scrollState.value, lyricsExpanded) {
        if (lyricsExpanded && scrollState.value < previousScrollValue) lyricsExpanded = false
        previousScrollValue = scrollState.value
    }
    val dragState = rememberDraggableState { delta ->
        dragOffsetPx = (dragOffsetPx + delta).coerceAtLeast(0f)
    }
    val dragModifier = Modifier.draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        onDragStopped = {
            if (dragOffsetPx > screenHeightPx * dismissThresholdFraction) {
                dragOffsetPx = 0f
                collapsePlayer()
            } else {
                when (motionPreference) {
                    MotionPreference.FULL -> Animatable(dragOffsetPx).animateTo(0f) { dragOffsetPx = value }
                    MotionPreference.MEDIUM -> Animatable(dragOffsetPx).animateTo(0f, tween(120)) { dragOffsetPx = value }
                    MotionPreference.OFF -> dragOffsetPx = 0f
                    MotionPreference.SYSTEM -> if (systemAnimationsEnabled) {
                        Animatable(dragOffsetPx).animateTo(0f, tween(180)) { dragOffsetPx = value }
                    } else dragOffsetPx = 0f
                }
            }
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
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
                    modifier = Modifier.clickable { collapsePlayer() }
                )
                if (!selectedSongIsExternal) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TerminalActionText(
                            text = "[ + PLAYLIST ]",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(enabled = selectedSongId != null) {
                                viewModel.clearPlaylistActionMessage()
                                showPlaylistPicker = true
                            }
                        )
                        TerminalActionText(
                            text = if (!preparingSelection && isLiked) "[LIKED]" else "[LIKE]",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (!preparingSelection && isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(enabled = !preparingSelection) { viewModel.toggleCurrentLike() }
                        )
                    }
                }
            }

            // Playback Art / Song Art
            if (artStyle == com.necroware.terminusplayer.data.prefs.PlaybackArtStyle.STANDARD) {
                SongArt(
                    uriString = displaySongUri,
                    size = 300.dp,
                    modifier = Modifier.padding(top = 20.dp, bottom = 20.dp).then(dragModifier)
                )
            } else {
                PlaybackArt(
                    style = artStyle,
                    title = displayTitle,
                    artist = displayArtist,
                    isPlaying = displayIsPlaying,
                    size = 300.dp,
                    motionPreference = motionPreference,
                    modifier = Modifier.padding(top = 20.dp, bottom = 20.dp).then(dragModifier)
                )
            }

            Text(
                text = displayTitle.ifBlank { "—" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = displayArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (preparingSelection) {
                    pendingPlaybackError?.let { "UNABLE TO START · $it" } ?: "PREPARING TRACK…"
                } else " ",
                style = MaterialTheme.typography.labelSmall,
                color = if (pendingPlaybackError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(18.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            PlaybackProgressSection(
                positionMs = displayPositionMs,
                durationMs = displayDurationMs,
                isPlaying = displayIsPlaying,
                motionPreference = motionPreference,
                enabled = !preparingSelection,
                onSeek = viewModel::seekTo
            )

            FullTransportControls(
                isPlaying = displayIsPlaying,
                shuffleEnabled = !preparingSelection && nowPlaying.shuffleEnabled,
                repeatMode = if (preparingSelection) androidx.media3.common.Player.REPEAT_MODE_OFF else nowPlaying.repeatMode,
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onSkipNext = { viewModel.skipToNext() },
                onSkipPrevious = { viewModel.skipToPrevious() },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onCycleRepeat = { viewModel.cycleRepeatMode() },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                enabled = !preparingSelection
            )

            SleepTimerControl(
                remainingMs = sleepTimerRemainingMs,
                onStart = viewModel::startSleepTimer,
                onCancel = viewModel::cancelSleepTimer
            )

            val displaySizeBytes = pendingSong?.sizeBytes ?: nowPlaying.sizeBytes
            val kbps = if (preparingSelection) null else nowPlaying.bitrateKbps.takeIf { it > 0 }
                ?: estimateKbpsFromSize(displaySizeBytes, displayDurationMs).takeIf { it > 0 }
            AudioMonitorCard(
                codecLabel = if (preparingSelection) "—" else nowPlaying.codecLabel,
                bitDepthLabel = if (preparingSelection) "—" else nowPlaying.bitDepthLabel,
                kbpsLabel = kbps?.toString() ?: "—",
                sizeLabel = sizeLabelFromBytes(displaySizeBytes),
                isLossless = !preparingSelection && nowPlaying.isLossless,
                repeatMode = if (preparingSelection) androidx.media3.common.Player.REPEAT_MODE_OFF else nowPlaying.repeatMode,
                isPlaying = displayIsPlaying,
                motionPreference = motionPreference,
                modifier = Modifier.padding(top = 24.dp, bottom = 24.dp)
            )

            val lyrics = if (preparingSelection) null else currentLyrics
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                val lyricsScrollConnection = remember(lyricsExpanded, lyrics) {
                    object : NestedScrollConnection {
                        override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                            if (lyrics.lines.any { it.startMs > 0L } && !lyricsExpanded && available.y < 0f) {
                                lyricsExpanded = true
                            }
                            return androidx.compose.ui.geometry.Offset.Zero
                        }
                    }
                }
                TerminalBorder(
                    modifier = Modifier
                        .nestedScroll(lyricsScrollConnection)
                        .fillMaxWidth()
                        .height(if (lyricsExpanded) (screenHeightPx / androidx.compose.ui.platform.LocalDensity.current.density).dp * 0.9f else 300.dp)
                        .animateContentSize(tween(if (motionPreference == MotionPreference.OFF) 0 else 260))
                        .padding(bottom = 24.dp)
                ) {
                androidx.compose.runtime.key(lyrics) {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    val isSynced = remember(lyrics) { lyrics.lines.any { it.startMs > 0L } }
                    val activeIndex = if (isSynced) {
                        lyrics.lines.indexOfLast { it.startMs <= positionMs }
                    } else {
                        -1
                    }
                    
                    LaunchedEffect(activeIndex, motionPreference) {
                        if (activeIndex >= 0 && !listState.isScrollInProgress) {
                            val centerOffset = -(listState.layoutInfo.viewportSize.height / 3)
                            if (motionPreference == MotionPreference.OFF) {
                                listState.scrollToItem(activeIndex.coerceAtLeast(0), centerOffset)
                            } else {
                                listState.animateScrollToItem(activeIndex.coerceAtLeast(0), centerOffset)
                            }
                        }
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).clickable { lyricsExpanded = !lyricsExpanded }.padding(vertical = 6.dp)
                            ) {
                                Text(
                                    text = "> LYRICS",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (isSynced) "SYNCED · TAP A LINE TO SEEK" else "PLAIN TEXT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable(enabled = isSynced) {
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

    if (showPlaylistPicker) {
        Dialog(onDismissRequest = { showPlaylistPicker = false }) {
            Box(Modifier.widthIn(max = 380.dp).background(MaterialTheme.colorScheme.background)) {
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("ADD TO PLAYLIST", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        if (playlists.isEmpty()) {
                            Text("Create a playlist first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            playlists.forEach { playlist ->
                                TerminalActionText(
                                    text = "[ ${playlist.name} · ${if (playlist.isRemote) "NAVIDROME" else "LOCAL"} ]",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedSongId?.let { viewModel.addSongToPlaylist(it, playlist.id) }
                                    }.padding(vertical = 8.dp)
                                )
                            }
                        }
                        playlistActionMessage?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        TerminalActionText(
                            "[ DONE ]",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).clickable { showPlaylistPicker = false }.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
