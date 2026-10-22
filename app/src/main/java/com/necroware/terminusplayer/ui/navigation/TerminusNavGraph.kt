package com.necroware.terminusplayer.ui.navigation

import android.app.Activity
import android.animation.ValueAnimator
import com.necroware.terminusplayer.util.JankStatsHelper
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.prefs.MotionPreference
import com.necroware.terminusplayer.ui.components.MiniPlayerBar
import com.necroware.terminusplayer.ui.components.MiniPlayerHeight
import com.necroware.terminusplayer.ui.components.SoundControl
import com.necroware.terminusplayer.ui.components.TerminalNavIcon
import com.necroware.terminusplayer.ui.components.rememberTerminalHaptics
import com.necroware.terminusplayer.ui.screens.albumdetail.AlbumDetailScreen
import com.necroware.terminusplayer.ui.screens.artistdetail.ArtistDetailScreen
import com.necroware.terminusplayer.ui.screens.home.HomeScreen
import com.necroware.terminusplayer.ui.screens.home.HomeViewModel
import com.necroware.terminusplayer.ui.screens.library.LibraryScreen
import com.necroware.terminusplayer.ui.screens.library.LibraryViewModel
import com.necroware.terminusplayer.ui.screens.nowplaying.NowPlayingScreen
import com.necroware.terminusplayer.ui.screens.nowplaying.PlaybackViewModel
import com.necroware.terminusplayer.ui.screens.playlists.PlaylistDetailScreen
import com.necroware.terminusplayer.ui.screens.playlists.PlaylistsScreen
import com.necroware.terminusplayer.ui.screens.playlists.PlaylistsViewModel
import com.necroware.terminusplayer.ui.screens.search.SearchScreen
import com.necroware.terminusplayer.ui.screens.settings.SettingsScreen
import com.necroware.terminusplayer.ui.screens.settings.SettingsViewModel
import com.necroware.terminusplayer.ui.screens.stats.StatsScreen
import com.necroware.terminusplayer.ui.screens.stats.StatsViewModel
import com.necroware.terminusplayer.ui.theme.LocalAppPalette
import com.necroware.terminusplayer.ui.theme.rememberAnimatedAdaptiveAccent

/** Index within the bottom-nav tab order, used to pick left/right slide direction. -1 if not a tab route. */
private fun tabIndex(route: String?): Int = bottomNavItems.indexOfFirst { it.route == route }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int {
    val fromIndex = tabIndex(initialState.destination.route)
    val toIndex = tabIndex(targetState.destination.route)
    return if (toIndex >= fromIndex) 1 else -1
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlideEnter(
    motion: MotionPreference,
    systemAnimationsEnabled: Boolean
): EnterTransition = tabTransitionSpec(motion, systemAnimationsEnabled).let { spec ->
    spec.slideInHorizontallyOrNone(direction = tabDirection()) + spec.fadeInOrNone()
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlideExit(
    motion: MotionPreference,
    systemAnimationsEnabled: Boolean
): ExitTransition = tabTransitionSpec(motion, systemAnimationsEnabled).let { spec ->
    spec.slideOutHorizontallyOrNone(direction = -tabDirection()) + spec.fadeOutOrNone()
}

private fun detailEnter(motion: MotionPreference, systemAnimationsEnabled: Boolean): EnterTransition =
    motionTransitionSpec(motion, systemAnimationsEnabled).let { it.slideInHorizontallyOrNone() + it.fadeInOrNone() }

private fun detailExit(motion: MotionPreference, systemAnimationsEnabled: Boolean): ExitTransition =
    motionTransitionSpec(motion, systemAnimationsEnabled).fadeOutOrNone()

private fun detailPopEnter(motion: MotionPreference, systemAnimationsEnabled: Boolean): EnterTransition =
    motionTransitionSpec(motion, systemAnimationsEnabled).fadeInOrNone()

private fun detailPopExit(motion: MotionPreference, systemAnimationsEnabled: Boolean): ExitTransition =
    motionTransitionSpec(motion, systemAnimationsEnabled).let { it.slideOutHorizontallyOrNone() + it.fadeOutOrNone() }

@Composable
private fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(readSystemAnimationsEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = readSystemAnimationsEnabled()
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private fun readSystemAnimationsEnabled(): Boolean = try {
    ValueAnimator.areAnimatorsEnabled()
} catch (_: RuntimeException) {
    true
}

@Composable
fun TerminusNavGraph(
    motionPreference: MotionPreference = MotionPreference.FULL,
    onInitialContentReady: () -> Unit = {}
) {
    val navController = rememberNavController()
    val systemAnimationsEnabled = rememberSystemAnimationsEnabled()
    val motion = remember(motionPreference, systemAnimationsEnabled) {
        motionTokens(motionPreference, systemAnimationsEnabled)
    }
    val panelState = rememberPlayerPanelState(motion.panel)
    val scope = rememberCoroutineScope()
    val openPlayer: () -> Unit = { scope.launch { panelState.expand() } }
    val collapsePlayer: () -> Unit = { scope.launch { panelState.collapse() } }
    // These flip only at the ends of the travel, so reading them here does not recompose per frame.
    val panelOpen by remember(panelState) { derivedStateOf { panelState.fraction > 0f } }
    val panelFullyOpen by remember(panelState) { derivedStateOf { panelState.fraction >= 1f } }
    // Single PlaybackViewModel instance (Hilt, Activity-scoped by default when
    // requested at this top-level composable) shared by the mini bar and the
    // full Now Playing screen so both reflect the same MediaController state.
    val playbackViewModel: PlaybackViewModel = hiltViewModel()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val playlistsViewModel: PlaylistsViewModel = hiltViewModel()
    val statsViewModel: StatsViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val nowPlaying by playbackViewModel.nowPlaying.collectAsStateWithLifecycle()
    val currentSongUri by playbackViewModel.currentSongUri.collectAsStateWithLifecycle()
    val prefs by playbackViewModel.preferences.collectAsStateWithLifecycle()
    val pendingSong by playbackViewModel.pendingSong.collectAsStateWithLifecycle()
    val defaultAccent = MaterialTheme.colorScheme.primary
    // Same artwork choice the full player makes, so the mini bar and full player share one accent.
    val targetArtUri = pendingSong?.artworkUri?.takeIf { it.isNotBlank() }
        ?: nowPlaying.artworkUri?.takeIf { it.isNotBlank() }
        ?: (pendingSong?.uriString ?: currentSongUri)?.takeIf { it.isNotBlank() }
        ?: nowPlaying.mediaId?.let { if (it.startsWith("terminus://")) it else "terminus://$it" }
    val accentSpec = remember(motion) { motion.crossfadeSpec<Color>() }
    val playerAccent = rememberAnimatedAdaptiveAccent(
        artworkUri = targetArtUri,
        defaultAccent = defaultAccent,
        enabled = prefs.adaptiveArtAccent,
        performanceMode = prefs.performanceMode,
        animationSpec = accentSpec
    )
    val haptic = rememberTerminalHaptics(prefs.terminalHaptics)
    var soundControlOpen by remember { mutableStateOf(false) }
    var soundControlOnLeft by remember { mutableStateOf(true) }
    var soundVolume by remember { mutableFloatStateOf(playbackViewModel.currentVolume()) }

    LaunchedEffect(soundControlOpen, soundVolume) {
        if (soundControlOpen) {
            kotlinx.coroutines.delay(4000)
            soundControlOpen = false
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Mini player + bottom nav are hidden on the detail screens (they push
    // their own back affordance instead). The full player is a panel above
    // the NavHost, not a route; it slides the chrome away instead.
    val hideChromeRoutes = setOf(
        Destination.AlbumDetail.route,
        Destination.ArtistDetail.route,
        Destination.Search.route,
        Destination.Upload.route,
        Destination.GapFinder.route,
        Destination.ManageSources.route
    )

    val appPalette = LocalAppPalette.current
    val showChrome = currentRoute !in hideChromeRoutes
    val miniVisible = showChrome && nowPlaying.title.isNotBlank()
    val currentScheme = MaterialTheme.colorScheme
    val miniColorScheme = remember(currentScheme, playerAccent) {
        currentScheme.copy(
            primary = playerAccent,
            outline = playerAccent.copy(alpha = 0.5f)
        )
    }
    val routeBackground = when (currentRoute) {
        Destination.Playlists.route, Destination.PlaylistDetail.route -> appPalette.playlistSurface
        else -> appPalette.homeBackground
    }
    // Blend between route backgrounds with the screen transition. Read only while drawing,
    // so the blend does not recompose the Scaffold.
    val routeBackgroundSpec = remember(motion) { motion.transition.tweenSpec<Color>() }
    val animatedRouteBackground = animateColorAsState(routeBackground, routeBackgroundSpec, label = "routeBackground")

    val currentActivity = LocalContext.current as? Activity
    LaunchedEffect(currentRoute, panelOpen, currentActivity) {
        currentActivity?.window?.let { window ->
            val screen = if (panelOpen) "NowPlaying" else (currentRoute ?: "Home")
            JankStatsHelper.setScreen(window, screen)
        }
    }

    Scaffold(
        modifier = Modifier.drawBehind { drawRect(animatedRouteBackground.value) },
        containerColor = Color.Transparent,
        contentColor = contentColorFor(routeBackground),
        bottomBar = {
            if (showChrome) {
                Column {
                    // Transparent room for the mini bar, which the player panel draws
                    // underneath. Reserving it here keeps content padding fixed while the panel moves.
                    if (miniVisible) {
                        Spacer(Modifier.fillMaxWidth().height(MiniPlayerHeight))
                    }
                    NavigationBar(
                        containerColor = appPalette.navigationBackground,
                        modifier = Modifier
                            .graphicsLayer { translationY = size.height * panelState.fraction }
                            .then(if (panelFullyOpen) Modifier.clearAndSetSemantics { } else Modifier)
                    ) {
                        bottomNavItems.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    TerminalNavIcon(
                                        destination = destination,
                                        tint = if (currentRoute == destination.route) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                },
                                label = {
                                    Text(
                                        text = destination.label,
                                        fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                                        letterSpacing = androidx.compose.ui.unit.TextUnit(0f, androidx.compose.ui.unit.TextUnitType.Sp),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Clip
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onDoubleTap = {
                soundVolume = playbackViewModel.currentVolume()
                soundControlOnLeft = it.x < size.width / 2f
                soundControlOpen = true
            })
        }) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Fully covered by the open player: skip drawing it and hide it from accessibility.
                .graphicsLayer { alpha = if (panelState.fraction >= 1f) 0f else 1f }
                .then(if (panelFullyOpen) Modifier.clearAndSetSemantics { } else Modifier)
        ) {
        NavHost(
            navController = navController,
            startDestination = Destination.Library.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { tabSlideEnter(motionPreference, systemAnimationsEnabled) },
            exitTransition = { tabSlideExit(motionPreference, systemAnimationsEnabled) },
            popEnterTransition = { tabSlideEnter(motionPreference, systemAnimationsEnabled) },
            popExitTransition = { tabSlideExit(motionPreference, systemAnimationsEnabled) }
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    viewModel = homeViewModel,
                    onSongClick = { song, queue -> handleSongClick(song, queue, playbackViewModel, openPlayer, prefs.openPlayerOnLibraryTrackSelect) }
                )
            }
            composable(Destination.Library.route) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onSongClick = { song, queue -> handleSongClick(song, queue, playbackViewModel, openPlayer, prefs.openPlayerOnLibraryTrackSelect) },
                    onAlbumClick = { albumTitle -> navController.navigate(Destination.AlbumDetail.createRoute(albumTitle)) },
                    onArtistClick = { artist -> navController.navigate(Destination.ArtistDetail.createRoute(artist)) },
                    onSearchClick = { navController.navigate(Destination.Search.route) },
                    onInitialContentReady = onInitialContentReady
                )
            }
            composable(Destination.Playlists.route) {
                PlaylistsScreen(
                    viewModel = playlistsViewModel,
                    onPlaylistClick = { kind -> navController.navigate(Destination.PlaylistDetail.createRoute(kind.name)) },
                    onCustomPlaylistClick = { id ->
                        val routeId = if (id.startsWith("server:")) {
                            "remote:${id.removePrefix("server:")}"
                        } else {
                            "custom:$id"
                        }
                        navController.navigate(Destination.PlaylistDetail.createRoute(routeId))
                    }
                )
            }
            composable(Destination.Stats.route) { StatsScreen(viewModel = statsViewModel) }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateToUpload = { navController.navigate(Destination.Upload.route) },
                    onNavigateToGapFinder = { navController.navigate(Destination.GapFinder.route) },
                    onNavigateToManageSources = { navController.navigate(Destination.ManageSources.route) }
                )
            }
            composable(Destination.Upload.route) {
                com.necroware.terminusplayer.ui.screens.upload.UploadScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.GapFinder.route) {
                com.necroware.terminusplayer.ui.screens.gapfinder.GapFinderScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.ManageSources.route) {
                com.necroware.terminusplayer.ui.screens.managesources.ManageSourcesScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Destination.AlbumDetail.route,
                arguments = listOf(navArgument("albumTitle") { type = NavType.StringType }),
                enterTransition = { detailEnter(motionPreference, systemAnimationsEnabled) },
                exitTransition = { detailExit(motionPreference, systemAnimationsEnabled) },
                popEnterTransition = { detailPopEnter(motionPreference, systemAnimationsEnabled) },
                popExitTransition = { detailPopExit(motionPreference, systemAnimationsEnabled) }
            ) {
                AlbumDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Destination.ArtistDetail.route,
                arguments = listOf(navArgument("artist") { type = NavType.StringType }),
                enterTransition = { detailEnter(motionPreference, systemAnimationsEnabled) },
                exitTransition = { detailExit(motionPreference, systemAnimationsEnabled) },
                popEnterTransition = { detailPopEnter(motionPreference, systemAnimationsEnabled) },
                popExitTransition = { detailPopExit(motionPreference, systemAnimationsEnabled) }
            ) {
                ArtistDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Destination.PlaylistDetail.route,
                arguments = listOf(navArgument("kind") { type = NavType.StringType }),
                enterTransition = { detailEnter(motionPreference, systemAnimationsEnabled) },
                exitTransition = { detailExit(motionPreference, systemAnimationsEnabled) },
                popEnterTransition = { detailPopEnter(motionPreference, systemAnimationsEnabled) },
                popExitTransition = { detailPopExit(motionPreference, systemAnimationsEnabled) }
            ) {
                PlaylistDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = {
                        if (prefs.openPlayerOnPlaylistTrackSelect) openPlayer()
                    }
                )
            }
            composable(
                route = Destination.Search.route,
                enterTransition = { detailPopEnter(motionPreference, systemAnimationsEnabled) },
                exitTransition = { detailExit(motionPreference, systemAnimationsEnabled) },
                popEnterTransition = { detailPopEnter(motionPreference, systemAnimationsEnabled) },
                popExitTransition = { detailExit(motionPreference, systemAnimationsEnabled) }
            ) {
                SearchScreen(
                    onSongClick = { song, queue -> handleSongClick(song, queue, playbackViewModel, openPlayer, prefs.openPlayerOnLibraryTrackSelect) },
                    onBack = { navController.popBackStack() }
                )
            }
        }
        }
        PlayerPanel(
            state = panelState,
            bottomBarHeight = innerPadding.calculateBottomPadding(),
            miniVisible = miniVisible,
            background = appPalette.playerBackground,
            onCollapsed = { playbackViewModel.clearPendingPlaybackSelection() },
            mini = { artModifier, titleModifier, rowModifier ->
                // Collected only while the mini bar is composed; the flow polls every 500 ms while
                // playing (2 s paused) and stops shortly after the last collector leaves.
                val miniPosition = playbackViewModel.positionMs.collectAsStateWithLifecycle()
                MaterialTheme(colorScheme = miniColorScheme) {
                    MiniPlayerBar(
                        nowPlaying = nowPlaying,
                        currentSongUri = currentSongUri,
                        onTogglePlayPause = { playbackViewModel.togglePlayPause() },
                        onSkipNext = { playbackViewModel.skipToNext() },
                        onSkipPrevious = { playbackViewModel.skipToPrevious() },
                        onClick = openPlayer,
                        modifier = rowModifier,
                        artModifier = artModifier,
                        titleModifier = titleModifier,
                        artCrossfadeMs = motion.crossfadeMs,
                        progress = {
                            val duration = nowPlaying.durationMs
                            if (duration > 0L) miniPosition.value.toFloat() / duration else 0f
                        },
                        onSwipeSkip = { toNext ->
                            haptic(true)
                            if (toNext) playbackViewModel.skipToNext() else playbackViewModel.skipToPrevious()
                        },
                        swipeSettleSpec = motion.panel.floatSpec
                    )
                }
            },
            full = { transition ->
                NowPlayingScreen(
                    viewModel = playbackViewModel,
                    onCollapse = collapsePlayer,
                    accent = playerAccent,
                    motion = motion,
                    motionPreference = motionPreference,
                    transition = transition
                )
            }
        )
        // While the player is open it is the whole screen, so the sound control uses the
        // bounds the full player had as a route (no bottom bar padding).
        Box(
            Modifier
                .fillMaxSize()
                .padding(if (panelOpen) WindowInsets.systemBars.asPaddingValues() else innerPadding)
        ) {
        val soundMotion = motionTransitionSpec(motionPreference, systemAnimationsEnabled)
        AnimatedVisibility(
            visible = soundControlOpen,
            enter = soundMotion.fadeInOrNone(),
            exit = soundMotion.fadeOutOrNone()
        ) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                    .clickable(onClick = { soundControlOpen = false })
            )
        }
        AnimatedVisibility(
            visible = soundControlOpen,
            enter = soundMotion.slideInHorizontallyOrNone(direction = if (soundControlOnLeft) -1 else 1) + soundMotion.fadeInOrNone(),
            exit = soundMotion.slideOutHorizontallyOrNone(direction = if (soundControlOnLeft) -1 else 1) + soundMotion.fadeOutOrNone(),
            modifier = Modifier.align(if (soundControlOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.small,
                tonalElevation = 4.dp
            ) {
                SoundControl(
                    volume = soundVolume,
                    onVolumeChange = { soundVolume = it; playbackViewModel.setVolume(it) },
                    onDismiss = { soundControlOpen = false }
                )
            }
        }
        }
        }
    }
}

private fun handleSongClick(
    song: Song,
    queue: List<Song>,
    playbackViewModel: PlaybackViewModel,
    openPlayer: () -> Unit,
    shouldOpenPlayer: Boolean
) {
    val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
    playbackViewModel.playSongQueue(queue, index) {
        if (shouldOpenPlayer) openPlayer()
    }
}
