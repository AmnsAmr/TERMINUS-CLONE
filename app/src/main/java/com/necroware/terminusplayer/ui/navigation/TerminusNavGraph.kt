package com.necroware.terminusplayer.ui.navigation

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
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
import com.necroware.terminusplayer.ui.components.SoundControl
import com.necroware.terminusplayer.ui.components.TerminalNavIcon
import com.necroware.terminusplayer.ui.screens.albumdetail.AlbumDetailScreen
import com.necroware.terminusplayer.ui.screens.artistdetail.ArtistDetailScreen
import com.necroware.terminusplayer.ui.screens.home.HomeScreen
import com.necroware.terminusplayer.ui.screens.library.LibraryScreen
import com.necroware.terminusplayer.ui.screens.nowplaying.NowPlayingScreen
import com.necroware.terminusplayer.ui.screens.nowplaying.PlaybackViewModel
import com.necroware.terminusplayer.ui.screens.playlists.PlaylistDetailScreen
import com.necroware.terminusplayer.ui.screens.playlists.PlaylistsScreen
import com.necroware.terminusplayer.ui.screens.search.SearchScreen
import com.necroware.terminusplayer.ui.screens.settings.SettingsScreen
import com.necroware.terminusplayer.ui.screens.stats.StatsScreen
import com.necroware.terminusplayer.ui.theme.LocalAppPalette
import com.necroware.terminusplayer.ui.theme.rememberAdaptiveAccent

/** Index within the bottom-nav tab order, used to pick left/right slide direction. -1 if not a tab route. */
private fun tabIndex(route: String?): Int = bottomNavItems.indexOfFirst { it.route == route }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlideEnter(
    motion: MotionPreference,
    systemAnimationsEnabled: Boolean
): EnterTransition = motionTransitionSpec(motion, systemAnimationsEnabled).let { spec ->
    if (spec.durationMs == 0) EnterTransition.None else {
        val fromIndex = tabIndex(initialState.destination.route)
        val toIndex = tabIndex(targetState.destination.route)
        val movingForward = toIndex >= fromIndex
        val offset: (Int) -> Int = { width -> (width * spec.slideFraction).toInt() }
        val slide = if (spec.slideFraction == 0f) EnterTransition.None else slideInHorizontally(
            initialOffsetX = { width -> if (movingForward) offset(width) else -offset(width) },
            animationSpec = tween(spec.durationMs)
        )
        val fade = if (spec.fade) fadeIn(animationSpec = tween(spec.durationMs)) else EnterTransition.None
        slide + fade
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlideExit(
    motion: MotionPreference,
    systemAnimationsEnabled: Boolean
): ExitTransition = motionTransitionSpec(motion, systemAnimationsEnabled).let { spec ->
    if (spec.durationMs == 0) ExitTransition.None else {
        val fromIndex = tabIndex(initialState.destination.route)
        val toIndex = tabIndex(targetState.destination.route)
        val movingForward = toIndex >= fromIndex
        val offset: (Int) -> Int = { width -> (width * spec.slideFraction).toInt() }
        val slide = if (spec.slideFraction == 0f) ExitTransition.None else slideOutHorizontally(
            targetOffsetX = { width -> if (movingForward) -offset(width) else offset(width) },
            animationSpec = tween(spec.durationMs)
        )
        val fade = if (spec.fade) fadeOut(animationSpec = tween(spec.durationMs)) else ExitTransition.None
        slide + fade
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailEnter(
    motion: MotionPreference,
    systemAnimationsEnabled: Boolean
): EnterTransition = motionTransitionSpec(motion, systemAnimationsEnabled).let { spec ->
    if (spec.durationMs == 0) EnterTransition.None else {
        val slide = if (spec.slideFraction == 0f) EnterTransition.None else slideInHorizontally(
            initialOffsetX = { (it * spec.slideFraction).toInt() }, animationSpec = tween(spec.durationMs)
        )
        slide + if (spec.fade) fadeIn(tween(spec.durationMs)) else EnterTransition.None
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailExit(
    motion: MotionPreference,
    systemAnimationsEnabled: Boolean
): ExitTransition = motionTransitionSpec(motion, systemAnimationsEnabled).let { spec ->
    if (spec.durationMs == 0 || !spec.fade) ExitTransition.None else fadeOut(tween(spec.durationMs))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailPopEnter(
    motion: MotionPreference,
    systemAnimationsEnabled: Boolean
): EnterTransition = motionTransitionSpec(motion, systemAnimationsEnabled).let { spec ->
    if (spec.durationMs == 0 || !spec.fade) EnterTransition.None else fadeIn(tween(spec.durationMs))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailPopExit(
    motion: MotionPreference,
    systemAnimationsEnabled: Boolean
): ExitTransition = motionTransitionSpec(motion, systemAnimationsEnabled).let { spec ->
    if (spec.durationMs == 0) ExitTransition.None else {
        val slide = if (spec.slideFraction == 0f) ExitTransition.None else slideOutHorizontally(
            targetOffsetX = { (it * spec.slideFraction).toInt() }, animationSpec = tween(spec.durationMs)
        )
        slide + if (spec.fade) fadeOut(tween(spec.durationMs)) else ExitTransition.None
    }
}

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
    // Single PlaybackViewModel instance (Hilt, Activity-scoped by default when
    // requested at this top-level composable) shared by the mini bar and the
    // full Now Playing screen so both reflect the same MediaController state.
    val playbackViewModel: PlaybackViewModel = hiltViewModel()
    val nowPlaying by playbackViewModel.nowPlaying.collectAsStateWithLifecycle()
    val currentSongUri by playbackViewModel.currentSongUri.collectAsStateWithLifecycle()
    val prefs by playbackViewModel.preferences.collectAsStateWithLifecycle()
    val defaultAccent = MaterialTheme.colorScheme.primary
    val targetArtUri = currentSongUri.takeIf { !it.isNullOrBlank() }
        ?: nowPlaying.mediaId?.let { if (it.startsWith("terminus://")) it else "terminus://$it" }
        ?: nowPlaying.artworkUri
    val miniAccent = rememberAdaptiveAccent(
        artworkUri = targetArtUri,
        defaultAccent = defaultAccent,
        enabled = prefs.adaptiveArtAccent
    )
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

    // Mini player + bottom nav are hidden on both the full player and the
    // detail screens (they push their own back affordance instead).
    val hideChromeRoutes = setOf(
        Destination.NowPlaying.route,
        Destination.AlbumDetail.route,
        Destination.ArtistDetail.route,
        Destination.Search.route,
        Destination.Upload.route,
        Destination.GapFinder.route,
        Destination.ManageSources.route
    )

    val appPalette = LocalAppPalette.current
    val routeBackground = when (currentRoute) {
        Destination.NowPlaying.route -> appPalette.playerBackground
        Destination.Playlists.route, Destination.PlaylistDetail.route -> appPalette.playlistSurface
        else -> appPalette.homeBackground
    }
    Scaffold(
        containerColor = routeBackground,
        bottomBar = {
            if (currentRoute !in hideChromeRoutes) {
                Column {
                    val currentScheme = MaterialTheme.colorScheme
                    val miniColorScheme = remember(currentScheme, miniAccent) {
                        currentScheme.copy(
                            primary = miniAccent,
                            outline = miniAccent.copy(alpha = 0.5f)
                        )
                    }
                    MaterialTheme(colorScheme = miniColorScheme) {
                        MiniPlayerBar(
                            nowPlaying = nowPlaying,
                            currentSongUri = currentSongUri,
                            onTogglePlayPause = { playbackViewModel.togglePlayPause() },
                            onSkipNext = { playbackViewModel.skipToNext() },
                            onSkipPrevious = { playbackViewModel.skipToPrevious() },
                            onClick = { navController.navigate(Destination.NowPlaying.route) }
                        )
                    }
                    NavigationBar(containerColor = appPalette.navigationBackground) {
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
        Box(Modifier.fillMaxSize().padding(innerPadding).pointerInput(Unit) {
            detectTapGestures(onDoubleTap = {
                soundVolume = playbackViewModel.currentVolume()
                soundControlOnLeft = it.x < size.width / 2f
                soundControlOpen = true
            })
        }) {
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
                    onSongClick = { song, queue -> handleSongClick(song, queue, playbackViewModel, navController, prefs.openPlayerOnLibraryTrackSelect) }
                )
            }
            composable(Destination.Library.route) {
                LibraryScreen(
                    onSongClick = { song, queue -> handleSongClick(song, queue, playbackViewModel, navController, prefs.openPlayerOnLibraryTrackSelect) },
                    onAlbumClick = { albumTitle -> navController.navigate(Destination.AlbumDetail.createRoute(albumTitle)) },
                    onArtistClick = { artist -> navController.navigate(Destination.ArtistDetail.createRoute(artist)) },
                    onSearchClick = { navController.navigate(Destination.Search.route) },
                    onInitialContentReady = onInitialContentReady
                )
            }
            composable(Destination.Playlists.route) {
                PlaylistsScreen(
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
            composable(Destination.Stats.route) { StatsScreen() }
            composable(Destination.Settings.route) {
                SettingsScreen(
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
                route = Destination.NowPlaying.route,
                enterTransition = {
                    val spec = motionTransitionSpec(motionPreference, systemAnimationsEnabled)
                    if (spec.durationMs == 0) EnterTransition.None else {
                        val slide = if (spec.slideFraction == 0f) EnterTransition.None else slideInVertically(
                            initialOffsetY = { fullHeight -> (fullHeight * spec.slideFraction).toInt() },
                            animationSpec = tween(spec.durationMs)
                        )
                        slide + if (spec.fade) fadeIn(animationSpec = tween(spec.durationMs)) else EnterTransition.None
                    }
                },
                exitTransition = {
                    val spec = motionTransitionSpec(motionPreference, systemAnimationsEnabled)
                    if (spec.durationMs == 0 || !spec.fade) ExitTransition.None
                    else fadeOut(animationSpec = tween(spec.durationMs))
                },
                popEnterTransition = {
                    val spec = motionTransitionSpec(motionPreference, systemAnimationsEnabled)
                    if (spec.durationMs == 0 || !spec.fade) EnterTransition.None
                    else fadeIn(animationSpec = tween(spec.durationMs))
                },
                popExitTransition = {
                    val spec = motionTransitionSpec(motionPreference, systemAnimationsEnabled)
                    if (spec.durationMs == 0) ExitTransition.None else {
                        val slide = if (spec.slideFraction == 0f) ExitTransition.None else slideOutVertically(
                            targetOffsetY = { fullHeight -> (fullHeight * spec.slideFraction).toInt() },
                            animationSpec = tween(spec.durationMs)
                        )
                        slide + if (spec.fade) fadeOut(animationSpec = tween(spec.durationMs)) else ExitTransition.None
                    }
                }
            ) {
                NowPlayingScreen(
                    viewModel = playbackViewModel,
                    onCollapse = { navController.popBackStack() },
                    motionPreference = motionPreference,
                    systemAnimationsEnabled = systemAnimationsEnabled
                )
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
                        if (prefs.openPlayerOnPlaylistTrackSelect) {
                            navController.navigate(Destination.NowPlaying.route) { launchSingleTop = true }
                        }
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
                    onSongClick = { song, queue -> handleSongClick(song, queue, playbackViewModel, navController, prefs.openPlayerOnLibraryTrackSelect) },
                    onBack = { navController.popBackStack() }
                )
            }
        }
        val soundMotion = motionTransitionSpec(motionPreference, systemAnimationsEnabled)
        AnimatedVisibility(
            visible = soundControlOpen,
            enter = if (soundMotion.durationMs == 0 || !soundMotion.fade) EnterTransition.None else fadeIn(tween(soundMotion.durationMs)),
            exit = if (soundMotion.durationMs == 0 || !soundMotion.fade) ExitTransition.None else fadeOut(tween(soundMotion.durationMs))
        ) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                    .clickable(onClick = { soundControlOpen = false })
            )
        }
        AnimatedVisibility(
            visible = soundControlOpen,
            enter = if (soundMotion.durationMs == 0) EnterTransition.None else {
                val slide = if (soundMotion.slideFraction == 0f) EnterTransition.None else slideInHorizontally(
                    initialOffsetX = { ((if (soundControlOnLeft) -it else it) * soundMotion.slideFraction).toInt() },
                    animationSpec = tween(soundMotion.durationMs)
                )
                slide + if (soundMotion.fade) fadeIn(tween(soundMotion.durationMs)) else EnterTransition.None
            },
            exit = if (soundMotion.durationMs == 0) ExitTransition.None else {
                val slide = if (soundMotion.slideFraction == 0f) ExitTransition.None else slideOutHorizontally(
                    targetOffsetX = { ((if (soundControlOnLeft) -it else it) * soundMotion.slideFraction).toInt() },
                    animationSpec = tween(soundMotion.durationMs)
                )
                slide + if (soundMotion.fade) fadeOut(tween(soundMotion.durationMs)) else ExitTransition.None
            },
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

private fun handleSongClick(
    song: Song,
    queue: List<Song>,
    playbackViewModel: PlaybackViewModel,
    navController: androidx.navigation.NavHostController,
    shouldOpenPlayer: Boolean
) {
    val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
    playbackViewModel.playSongQueue(queue, index) {
        if (shouldOpenPlayer) {
            navController.navigate(Destination.NowPlaying.route) { launchSingleTop = true }
        }
    }
}
