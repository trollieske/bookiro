package com.bookrio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfColors
import com.bookrio.app.ShelfDestinations
import com.bookrio.core.net.DiscoveredSourceCandidate
import com.bookrio.core.net.LanSourceDiscovery
import com.bookrio.data.prefs.UserPreferencesRepository
import com.bookrio.designsystem.theme.ShelfTheme
import com.bookrio.library.ui.LibraryScreen
import com.bookrio.library.viewmodel.LibraryMode
import com.bookrio.library.ui.SampleBooks
import com.bookrio.reader.ui.ReaderScreen
import com.bookrio.player.ui.PlayerScreen
import com.bookrio.player.service.AudiobookPlaybackService
import com.bookrio.podcast.ui.PodcastDetailScreen
import com.bookrio.podcast.ui.PodcastDiscoverScreen
import com.bookrio.podcast.ui.PodcastPlayerScreen
import com.bookrio.podcast.playback.PodcastPlaybackService
import com.bookrio.podcast.ui.PodcastRootScreen
import com.bookrio.podcast.ui.podcastDetailVmFactory
import com.bookrio.podcast.ui.podcastDiscoverVmFactory
import com.bookrio.podcast.ui.podcastPlayerVmFactory
import com.bookrio.podcast.ui.podcastRootVmFactory
import com.bookrio.ftp.ui.FtpBrowserScreen
import com.bookrio.ftp.ui.FtpConnectionScreen
import com.bookrio.ftp.ui.FtpSourceDetailsScreen
import com.bookrio.ftp.ui.FtpSourcesScreen
import com.bookrio.app.ui.HomeScreen
import com.bookrio.app.ui.RemoteTransfersScreen
import com.bookrio.smb.ui.SmbScreen
import com.bookrio.webdav.ui.WebdavScreen
import com.bookrio.calibre.ui.CalibreBrowserScreen
import com.bookrio.calibre.ui.CalibreConnectionScreen
import com.bookrio.calibre.ui.CalibreSourcesScreen
import com.bookrio.torrent.ui.TorrentScreen
import com.bookrio.app.BookDetailsScreen
import com.bookrio.app.ImportScreen
import com.bookrio.app.OnboardingScreen
import com.bookrio.app.ui.SettingsScreen
import com.bookrio.designsystem.theme.ShelfTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        prefs = UserPreferencesRepository(this)
        setContent {
            // Tema låst: HUD-mørkt. Ingen dynamicColor / Material You.
            ShelfTheme(darkTheme = true) {
                val targetRoute = intent?.getStringExtra("target_route")
                ShelfRoot(prefs = prefs, initialRoute = targetRoute)
            }
        }
    }
}

private sealed class BottomNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    /** Renders the Bookiro brand mark instead of a Material icon. */
    val brand: Boolean = false
) {
    object Books : BottomNavItem(ShelfDestinations.Books.route, R.string.nav_books, Icons.Default.AutoStories)
    object Audiobooks : BottomNavItem(ShelfDestinations.Audiobooks.route, R.string.shelf_audiobooks, Icons.Default.Headphones)
    object Home : BottomNavItem(ShelfDestinations.Home.route, R.string.nav_home, Icons.Default.Home, brand = true)
    object Podcasts : BottomNavItem(ShelfDestinations.Podcasts.route, com.bookrio.podcast.R.string.pod_nav_title, Icons.Default.Podcasts)
    object Settings : BottomNavItem(ShelfDestinations.Settings.route, R.string.nav_settings, Icons.Filled.Settings)
}

@Composable
private fun NavItemIcon(item: BottomNavItem, label: String) {
    if (item.brand) {
        Image(
            painter = painterResource(com.bookrio.designsystem.R.drawable.bookrio_mark),
            contentDescription = label,
            modifier = Modifier.size(22.dp)
        )
    } else {
        Icon(item.icon, contentDescription = label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfRoot(prefs: UserPreferencesRepository, initialRoute: String? = null) {
    val navController = rememberNavController()
    val hasSeenOnboardingState by prefs.hasSeenOnboarding.collectAsStateWithLifecycle(initialValue = null)

    // Library tab counts (Settings → "Show counts on library tabs").
    val appContextForCounts = LocalContext.current.applicationContext
    val allBooks by remember(appContextForCounts) {
        com.bookrio.data.local.ShelfDatabase.getInstance(appContextForCounts).bookDao().observeAll()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val showTabCounts by prefs.libraryTabCountsEnabled.collectAsStateWithLifecycle(initialValue = true)

    if (hasSeenOnboardingState == null) {
        Surface(color = OmarchyColors.Bg, modifier = Modifier.fillMaxSize()) {}
        return
    }

    val defaultStart = if (hasSeenOnboardingState == true) ShelfDestinations.Home.route else ShelfDestinations.Onboarding.route
    val startDest = initialRoute ?: defaultStart
    val items = listOf(
        BottomNavItem.Books,
        BottomNavItem.Audiobooks,
        BottomNavItem.Home,
        BottomNavItem.Podcasts,
        BottomNavItem.Settings
    )
    val showBottomRoutes = setOf(
        ShelfDestinations.Books.route,
        ShelfDestinations.Audiobooks.route,
        ShelfDestinations.Home.route,
        ShelfDestinations.Podcasts.route,
        ShelfDestinations.Settings.route
    )

    // Bibliotek-rutenettet styrer denne via onNavVisibilityChange: skjul ved rulling ned,
    // vis ved rulling opp. Tilbakestilles ved fanebytte.
    var libraryNavVisible by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val activeAudio by com.bookrio.data.repository.ActivePlaybackState.state.collectAsStateWithLifecycle()
            val activePodcast by com.bookrio.data.repository.PodcastPlaybackState.state.collectAsStateWithLifecycle()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val isPlayerScreen = currentDestination?.route?.startsWith("player") == true
            val isPodcastPlayerScreen = currentDestination?.route?.startsWith("podcasts/player") == true

            Column {
                val context = LocalContext.current
                // Single now-playing slot. NowPlayingOwnership guarantees at most one
                // engine publishes at a time; if both ever race, the playing one wins.
                val mini = listOfNotNull(
                    activePodcast?.takeIf { !isPodcastPlayerScreen }?.let { active ->
                        val podSubtitle = if (active.sleepTimerRemainingMs > 0L) {
                            val m = (active.sleepTimerRemainingMs / 60_000L).toInt().coerceAtLeast(1)
                            "${active.podcastTitle.ifBlank { stringResource(com.bookrio.podcast.R.string.pod_nav_title) }} • ${stringResource(R.string.main_sleep_remaining, m)}"
                        } else {
                            active.podcastTitle.ifBlank { stringResource(com.bookrio.podcast.R.string.pod_nav_title) }
                        }
                        MiniPlayerInfo(
                            isPlaying = active.isPlaying,
                            title = active.title.ifBlank { stringResource(com.bookrio.podcast.R.string.pod_nav_title) },
                            subtitle = podSubtitle,
                            icon = Icons.Default.Podcasts,
                            progress = active.progressPercent,
                            onClick = { navController.navigate(ShelfDestinations.PodcastPlayer.routeFor(active.episodeId)) },
                            onDismiss = {
                                runCatching {
                                    context.startService(
                                        Intent(context, PodcastPlaybackService::class.java)
                                            .setAction(PodcastPlaybackService.ACTION_STOP)
                                    )
                                }
                            }
                        )
                    },
                    activeAudio?.takeIf { !isPlayerScreen }?.let { active ->
                        val subLabel = if (active.sleepTimerRemainingMs > 0L) {
                            val m = (active.sleepTimerRemainingMs / 60_000L).toInt().coerceAtLeast(1)
                            "${active.author} • ${stringResource(R.string.main_sleep_remaining, m)}"
                        } else {
                            active.author.ifBlank { stringResource(R.string.main_playing) }
                        }
                        MiniPlayerInfo(
                            isPlaying = active.isPlaying,
                            title = active.title.ifBlank { stringResource(R.string.player_title) },
                            subtitle = subLabel,
                            icon = Icons.Default.Headphones,
                            progress = active.progressPercent,
                            onClick = { navController.navigate(ShelfDestinations.Player.routeFor(active.bookId)) },
                            onDismiss = {
                                runCatching {
                                    context.startService(
                                        Intent(context, AudiobookPlaybackService::class.java)
                                            .setAction(AudiobookPlaybackService.ACTION_STOP)
                                    )
                                }
                            }
                        )
                    }
                )
                (mini.firstOrNull { it.isPlaying } ?: mini.firstOrNull())?.let { NowPlayingBar(it) }
                // Media notifications are core to this app: ask for POST_NOTIFICATIONS
                // once, the first time playback starts (contextual, not on cold start).
                if (activeAudio != null || activePodcast != null) {
                    RequestNotificationPermissionIfNeeded()
                }

                if (currentDestination?.route in showBottomRoutes) {
                    LaunchedEffect(currentDestination?.route) { libraryNavVisible = true }
                    AnimatedVisibility(
                        visible = libraryNavVisible,
                        enter = fadeIn() + slideInVertically { it },
                        exit = fadeOut() + slideOutVertically { it }
                    ) {
                        NavigationBar(
                            tonalElevation = 0.dp,
                            containerColor = OmarchyColors.Bg
                        ) {
                            items.forEach { item ->
                                val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                                val itemLabel = stringResource(item.labelRes)
                                NavigationBarItem(
                                    icon = {
                                        val tabCount = when (item) {
                                            BottomNavItem.Books ->
                                                if (showTabCounts) allBooks.count { it.type != com.bookrio.data.local.entity.BookTypeEntity.AUDIOBOOK && !it.isDeleted } else 0
                                            BottomNavItem.Audiobooks ->
                                                if (showTabCounts) allBooks.count { it.type == com.bookrio.data.local.entity.BookTypeEntity.AUDIOBOOK && !it.isDeleted } else 0
                                            else -> 0
                                        }
                                        if (tabCount > 0) {
                                            BadgedBox(
                                                badge = {
                                                    Badge(
                                                        containerColor = OmarchyColors.Accent,
                                                        contentColor = Color.Black
                                                    ) {
                                                        Text(if (tabCount > 99) "99+" else tabCount.toString())
                                                    }
                                                }
                                            ) { NavItemIcon(item, itemLabel) }
                                        } else {
                                            NavItemIcon(item, itemLabel)
                                        }
                                    },
                                    label = if (selected) { { Text(itemLabel, style = com.bookrio.designsystem.theme.ShelfTypography.LabelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) } } else null,
                                    selected = selected,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = OmarchyColors.Accent,
                                        selectedTextColor = OmarchyColors.Accent,
                                        unselectedIconColor = OmarchyColors.Dim,
                                        unselectedTextColor = OmarchyColors.Dim,
                                        indicatorColor = Color.Transparent
                                    ),
                                    onClick = {
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // The reader is a full-screen surface that applies its OWN constant insets.
        // The Scaffold's safeDrawing padding changes when the system bars hide/show,
        // which resized the Readium navigator and visibly moved the text every time
        // the in-book menu was toggled.
        val readerEntry by navController.currentBackStackEntryAsState()
        val isReaderRoute = readerEntry?.destination?.route == ShelfDestinations.Reader.route
        NavHost(
            navController = navController,
            startDestination = startDest,
            modifier = if (isReaderRoute) Modifier.fillMaxSize() else Modifier.padding(innerPadding)
        ) {
            composable(ShelfDestinations.Library.route) {
                LibraryScreen(
                    mode = LibraryMode.Books,
                    onBookClick = { id -> navController.navigate(ShelfDestinations.Reader.routeFor(id)) },
                    onBookLongClick = { id -> navController.navigate(ShelfDestinations.BookDetails.routeFor(id)) },
                    onImportClick = { navController.navigate(ShelfDestinations.Import.route) },
                    onFtpClick = { navController.navigate(ShelfDestinations.Sources.route) },
                    onSettingsClick = { navController.navigate(ShelfDestinations.Settings.route) },
                    onNavVisibilityChange = { libraryNavVisible = it }
                )
            }
            composable(ShelfDestinations.Books.route) {
                LibraryScreen(
                    mode = LibraryMode.Books,
                    onBookClick = { id -> navController.navigate(ShelfDestinations.Reader.routeFor(id)) },
                    onBookLongClick = { id -> navController.navigate(ShelfDestinations.BookDetails.routeFor(id)) },
                    onImportClick = { navController.navigate(ShelfDestinations.Import.route) },
                    onFtpClick = { navController.navigate(ShelfDestinations.Sources.route) },
                    onSettingsClick = { navController.navigate(ShelfDestinations.Settings.route) },
                    onNavVisibilityChange = { libraryNavVisible = it }
                )
            }
            composable(ShelfDestinations.Audiobooks.route) {
                LibraryScreen(
                    mode = LibraryMode.Audio,
                    onBookClick = { id -> navController.navigate(ShelfDestinations.Player.routeFor(id)) },
                    onBookLongClick = { id -> navController.navigate(ShelfDestinations.BookDetails.routeFor(id)) },
                    onImportClick = { navController.navigate(ShelfDestinations.Import.route) },
                    onFtpClick = { navController.navigate(ShelfDestinations.Sources.route) },
                    onSettingsClick = { navController.navigate(ShelfDestinations.Settings.route) },
                    onNavVisibilityChange = { libraryNavVisible = it }
                )
            }
            composable(ShelfDestinations.Home.route) {
                HomeScreen(
                    onOpenBook = { id, isAudio ->
                        navController.navigate(
                            if (isAudio) ShelfDestinations.Player.routeFor(id)
                            else ShelfDestinations.Reader.routeFor(id)
                        )
                    },
                    onOpenEpisode = { episodeId ->
                        navController.navigate(ShelfDestinations.PodcastPlayer.routeFor(episodeId))
                    },
                    onOpenEbooks = { navController.navigate(ShelfDestinations.Books.route) },
                    onOpenAudiobooks = { navController.navigate(ShelfDestinations.Audiobooks.route) },
                    onOpenPodcasts = { navController.navigate(ShelfDestinations.Podcasts.route) },
                    onOpenImport = { navController.navigate(ShelfDestinations.Import.route) },
                    onOpenFtp = { navController.navigate(ShelfDestinations.Ftp.route) },
                    onOpenTorrent = { navController.navigate(ShelfDestinations.Torrent.route) },
                    onOpenSources = { navController.navigate(ShelfDestinations.Sources.route) },
                    onOpenTransfers = { navController.navigate(ShelfDestinations.Transfers.route) }
                )
            }
            composable(ShelfDestinations.Podcasts.route) {
                PodcastRootScreen(
                    onOpenDetail = { feedId -> navController.navigate(ShelfDestinations.PodcastDetail.routeFor(feedId)) },
                    onOpenDiscover = { navController.navigate(ShelfDestinations.PodcastDiscover.route) },
                    onOpenPlayer = { episodeId -> navController.navigate(ShelfDestinations.PodcastPlayer.routeFor(episodeId)) },
                    vmFactory = podcastRootVmFactory()
                )
            }
            composable(ShelfDestinations.PodcastDiscover.route) {
                PodcastDiscoverScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { feedId -> navController.navigate(ShelfDestinations.PodcastDetail.routeFor(feedId)) },
                    vmFactory = podcastDiscoverVmFactory()
                )
            }
            composable(
                route = ShelfDestinations.PodcastDetail.route,
                arguments = listOf(
                    androidx.navigation.navArgument("feedId") { type = androidx.navigation.NavType.LongType }
                )
            ) { backStack ->
                val feedId = backStack.arguments?.getLong("feedId") ?: 0L
                PodcastDetailScreen(
                    feedId = feedId,
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = { episodeId -> navController.navigate(ShelfDestinations.PodcastPlayer.routeFor(episodeId)) },
                    vmFactory = podcastDetailVmFactory(feedId)
                )
            }
            composable(
                route = ShelfDestinations.PodcastPlayer.route,
                arguments = listOf(
                    androidx.navigation.navArgument("episodeId") { type = androidx.navigation.NavType.LongType }
                )
            ) { backStack ->
                val episodeId = backStack.arguments?.getLong("episodeId") ?: 0L
                PodcastPlayerScreen(
                    episodeId = episodeId,
                    onBack = { navController.popBackStack() },
                    vmFactory = podcastPlayerVmFactory(episodeId)
                )
            }
            composable(ShelfDestinations.Sources.route) {
                SourcesOverviewScreen(
                    onBack = { navController.popBackStack() },
                    onFtpClick = { navController.navigate(ShelfDestinations.Ftp.route) },
                    onSmbClick = { navController.navigate(ShelfDestinations.Smb.route) },
                    onWebdavClick = { navController.navigate(ShelfDestinations.Webdav.route) },
                    onTorrentClick = { navController.navigate(ShelfDestinations.Torrent.route) },
                    onCalibreClick = { navController.navigate(ShelfDestinations.Calibre.route) },
                    onImportClick = { navController.navigate(ShelfDestinations.Import.route) },
                    onImportProgressClick = { navController.navigate(ShelfDestinations.ImportProgress.route) },
                    onTransfersClick = { navController.navigate(ShelfDestinations.Transfers.route) }
                )
            }
            composable(ShelfDestinations.Ftp.route) {
                RequestNotificationPermissionIfNeeded()
                FtpSourcesScreen(
                    onBack = { navController.popBackStack() },
                    onAddSource = { navController.navigate(ShelfDestinations.FtpAdd.route) },
                    onOpenSource = { id -> navController.navigate(ShelfDestinations.FtpServer.routeFor(id)) },
                    onBrowse = { id -> navController.navigate(ShelfDestinations.FtpBrowse.routeFor(id)) },
                    onOpenTransfers = { navController.navigate(ShelfDestinations.Transfers.route) }
                )
            }
            composable(ShelfDestinations.FtpAdd.route) {
                FtpConnectionScreen(
                    editingId = 0L,
                    onBack = { navController.popBackStack() },
                    onSaved = { id ->
                        navController.popBackStack()
                        navController.navigate(ShelfDestinations.FtpServer.routeFor(id))
                    }
                )
            }
            composable(
                route = ShelfDestinations.FtpEdit.route,
                arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val serverId = backStack.arguments?.getLong("serverId") ?: -1L
                FtpConnectionScreen(
                    editingId = serverId,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }
            composable(
                route = ShelfDestinations.FtpBrowse.route,
                arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val serverId = backStack.arguments?.getLong("serverId") ?: -1L
                RequestNotificationPermissionIfNeeded()
                FtpBrowserScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    onOpenTransfers = { navController.navigate(ShelfDestinations.Transfers.route) }
                )
            }
            composable(ShelfDestinations.Transfers.route) {
                RemoteTransfersScreen(onBack = { navController.popBackStack() })
            }
            composable(ShelfDestinations.Smb.route) {
                SmbScreen(
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
            composable(ShelfDestinations.Webdav.route) {
                WebdavScreen(
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
            composable(ShelfDestinations.Torrent.route) {
                // The torrent worker posts its progress notification; ask for
                // POST_NOTIFICATIONS so it is actually visible on Android 13+.
                RequestNotificationPermissionIfNeeded()
                TorrentScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(ShelfDestinations.Calibre.route) {
                CalibreSourcesScreen(
                    onBack = { navController.popBackStack() },
                    onAddSource = { navController.navigate(ShelfDestinations.CalibreAdd.route) },
                    onOpenSource = { sourceId -> navController.navigate(ShelfDestinations.CalibreBrowse.routeFor(sourceId)) },
                    onOpenTransfers = { navController.navigate(ShelfDestinations.Transfers.route) }
                )
            }
            composable(ShelfDestinations.CalibreAdd.route) {
                CalibreConnectionScreen(
                    editingId = 0L,
                    onBack = { navController.popBackStack() },
                    onSaved = { sourceId ->
                        navController.popBackStack()
                        navController.navigate(ShelfDestinations.CalibreBrowse.routeFor(sourceId))
                    }
                )
            }
            composable(
                route = ShelfDestinations.CalibreBrowse.route,
                arguments = listOf(androidx.navigation.navArgument("sourceId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val sourceId = backStack.arguments?.getLong("sourceId") ?: -1L
                RequestNotificationPermissionIfNeeded()
                CalibreBrowserScreen(
                    sourceId = sourceId,
                    onBack = { navController.popBackStack() },
                    onOpenTransfers = { navController.navigate(ShelfDestinations.Transfers.route) }
                )
            }
            composable(ShelfDestinations.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onSourcesClick = { navController.navigate(ShelfDestinations.Sources.route) }
                )
            }
            composable(
                route = ShelfDestinations.Player.route,
                arguments = listOf(androidx.navigation.navArgument("bookId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val bookId = backStack.arguments?.getLong("bookId") ?: 0L
                PlayerScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = ShelfDestinations.Reader.route,
                arguments = listOf(
                    androidx.navigation.navArgument("bookId") { type = androidx.navigation.NavType.LongType },
                    androidx.navigation.navArgument("positionPercent") {
                        type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null
                    }
                )
            ) { backStack ->
                val bookId = backStack.arguments?.getLong("bookId") ?: 0L
                val positionPercentStr = backStack.arguments?.getString("positionPercent")
                val initialPosition = positionPercentStr?.toFloatOrNull()?.coerceIn(0f, 1f)
                ReaderScreen(
                    bookId = bookId,
                    initialPositionPercent = initialPosition,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = ShelfDestinations.BookDetails.route,
                arguments = listOf(androidx.navigation.navArgument("bookId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val bookId = backStack.arguments?.getLong("bookId") ?: 0L
                BookDetailsScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onOpenReader = { id -> navController.navigate(ShelfDestinations.Reader.routeFor(id)) },
                    onOpenPlayer = { id -> navController.navigate(ShelfDestinations.Player.routeFor(id)) },
                    onOpenBookmark = { bId, posPct ->
                        navController.navigate(ShelfDestinations.Reader.routeFor(bId, posPct))
                    },
                    onDeleted = { navController.popBackStack() }
                )
            }
            composable(ShelfDestinations.Import.route) {
                ImportScreen(onBack = { navController.popBackStack() })
            }
            composable(ShelfDestinations.ImportProgress.route) {
                ImportProgressScreen(onBack = { navController.popBackStack() })
            }
            composable(ShelfDestinations.Onboarding.route) {
                OnboardingScreen(
                    onDone = {
                        navController.navigate(ShelfDestinations.Home.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
            composable(
                route = ShelfDestinations.FtpServer.route,
                arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val serverId = backStack.arguments?.getLong("serverId") ?: -1L
                RequestNotificationPermissionIfNeeded()
                FtpSourceDetailsScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    onBrowse = { navController.navigate(ShelfDestinations.FtpBrowse.routeFor(serverId)) },
                    onEdit = { navController.navigate(ShelfDestinations.FtpEdit.routeFor(serverId)) },
                    onOpenTransfers = { navController.navigate(ShelfDestinations.Transfers.route) }
                )
            }
            composable(
                route = ShelfDestinations.SmbServer.route,
                arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val serverId = backStack.arguments?.getLong("serverId") ?: -1L
                SmbScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
            composable(
                route = ShelfDestinations.WebdavServer.route,
                arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType })
            ) { backStack ->
                val serverId = backStack.arguments?.getLong("serverId") ?: -1L
                WebdavScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    onImport = { navController.navigate(ShelfDestinations.Import.route) }
                )
            }
        }
    }
}

private data class MiniPlayerInfo(
    val isPlaying: Boolean,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val progress: Float,
    val onClick: () -> Unit,
    val onDismiss: () -> Unit
)

/** The app's single now-playing bar (audiobook or podcast). */
@Composable
private fun NowPlayingBar(info: MiniPlayerInfo) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        color = OmarchyColors.Panel,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = info.onClick)
    ) {
        Column {
            LinearProgressIndicator(
                progress = { info.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = OmarchyColors.Accent,
                trackColor = Color(0x33FFFFFF)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = OmarchyColors.Hairline,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(info.icon, contentDescription = null, tint = OmarchyColors.Accent, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        info.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        info.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = OmarchyColors.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = info.onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = OmarchyColors.Dim
                    )
                }
            }
        }
    }
}

/**
 * Requests POST_NOTIFICATIONS once per process the first time the user opens the
 * FTP area, so the foreground sync notification (file/percent/speed + Pause and
 * Cancel) is actually visible on Android 13+.
 */
@Composable
private fun RequestNotificationPermissionIfNeeded() {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (granted) return
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesOverviewScreen(
    onBack: () -> Unit,
    onFtpClick: () -> Unit,
    onSmbClick: () -> Unit,
    onWebdavClick: () -> Unit,
    onTorrentClick: () -> Unit,
    onCalibreClick: () -> Unit,
    onImportClick: () -> Unit,
    onImportProgressClick: () -> Unit,
    onTransfersClick: () -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        containerColor = OmarchyColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sources_title), style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold, color = OmarchyColors.FgBright) },
                navigationIcon = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmarchyColors.Bg)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                stringResource(R.string.sources_connect_prompt),
                style = ShelfTypography.TitleMedium,
                fontWeight = FontWeight.SemiBold,
                color = OmarchyColors.FgBright
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.sources_subtitle),
                style = ShelfTypography.BodyMedium,
                color = OmarchyColors.Dim
            )
            Spacer(Modifier.height(16.dp))

            // Enkel liste over kilder — ikke dashbord
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceCard(
                    title = stringResource(R.string.ftp_title),
                    subtitle = stringResource(R.string.ftp_subtitle),
                    icon = Icons.Default.CloudSync,
                    tint = OmarchyColors.Fg,
                    onClick = onFtpClick
                )
                SourceCard(
                    title = stringResource(R.string.smb_title),
                    subtitle = stringResource(R.string.smb_subtitle),
                    icon = Icons.Default.Dns,
                    tint = OmarchyColors.Fg,
                    onClick = onSmbClick
                )
                SourceCard(
                    title = stringResource(R.string.webdav_title),
                    subtitle = stringResource(R.string.webdav_subtitle),
                    icon = Icons.Default.Cloud,
                    tint = OmarchyColors.Fg,
                    onClick = onWebdavClick
                )
                SourceCard(
                    title = stringResource(R.string.torrent_title),
                    subtitle = stringResource(R.string.torrent_subtitle),
                    icon = Icons.Default.SwapHoriz,
                    tint = OmarchyColors.Fg,
                    onClick = onTorrentClick
                )
                SourceCard(
                    title = stringResource(com.bookrio.calibre.R.string.calibre_title),
                    subtitle = stringResource(com.bookrio.calibre.R.string.calibre_subtitle),
                    icon = Icons.Default.LocalLibrary,
                    tint = OmarchyColors.Fg,
                    onClick = onCalibreClick
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = OmarchyColors.Hairline)
            Spacer(Modifier.height(16.dp))

            LanDiscoverySection(
                onOpenFtp = onFtpClick,
                onOpenSmb = onSmbClick,
                onOpenWebdav = onWebdavClick,
                onOpenCalibre = onCalibreClick
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = OmarchyColors.Hairline)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.sources_tools),
                style = ShelfTypography.TitleMedium,
                fontWeight = FontWeight.SemiBold,
                color = OmarchyColors.FgBright
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceCard(
                    title = stringResource(R.string.menu_import),
                    subtitle = stringResource(R.string.sources_import_files_sub),
                    icon = Icons.Default.FileUpload,
                    tint = OmarchyColors.Fg,
                    onClick = onImportClick
                )
                SourceCard(
                    title = stringResource(R.string.import_tab_downloads),
                    subtitle = stringResource(R.string.sources_downloads_status),
                    icon = Icons.Default.DownloadDone,
                    tint = OmarchyColors.Fg,
                    onClick = onImportProgressClick
                )
                SourceCard(
                    title = stringResource(com.bookrio.ftp.R.string.ftpu_transfers_title),
                    subtitle = stringResource(R.string.sources_downloads_status),
                    icon = Icons.Default.SwapVert,
                    tint = OmarchyColors.Fg,
                    onClick = onTransfersClick
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(OmarchyColors.Panel, RoundedCornerShape(4.dp))
                    .padding(14.dp)
            ) {
                Icon(Icons.Default.Info, null, tint = OmarchyColors.Dim)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.sources_tips_title), fontWeight = FontWeight.SemiBold, style = ShelfTypography.BodyLarge, color = OmarchyColors.FgBright)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.sources_tips),
                        style = ShelfTypography.BodySmall,
                        color = OmarchyColors.Dim
                    )
                }
            }
        }
    }
}

/** Flat kilderekke: panel, 4dp hjørner, ingen heving — enkel liste, ikke dashbord. */
@Composable
private fun SourceCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OmarchyColors.Panel, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = OmarchyColors.Fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ShelfTypography.BodyLarge, fontWeight = FontWeight.Medium, color = OmarchyColors.FgBright)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, style = ShelfTypography.BodySmall, color = OmarchyColors.Dim)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = OmarchyColors.Dim, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LanDiscoverySection(
    onOpenFtp: () -> Unit,
    onOpenSmb: () -> Unit,
    onOpenWebdav: () -> Unit,
    onOpenCalibre: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf(ctx.getString(R.string.lan_idle)) }
    val discovered = remember { mutableStateListOf<DiscoveredSourceCandidate>() }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.lan_title),
                    style = ShelfTypography.TitleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    progressText,
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            AssistChip(
                onClick = {
                    if (scanning) return@AssistChip
                    discovered.clear()
                    scanning = true
                    progressText = ctx.getString(R.string.lan_scanning_progress)
                    scope.launch(Dispatchers.IO) {
                        val discovery = LanSourceDiscovery(ctx)
                        val seen = HashSet<String>()
                        try {
                            discovery.runScan().collect { cand ->
                                val key = cand.host + ":" + cand.port
                                if (seen.add(key)) {
                                    withContext(Dispatchers.Main.immediate) {
                                        discovered.add(cand)
                                        progressText = ctx.getString(R.string.lan_found_progress, discovered.size)
                                    }
                                }
                            }
                        } catch (_: Throwable) { } finally {
                            withContext(Dispatchers.Main.immediate) {
                                scanning = false
                                progressText = if (discovered.isEmpty()) {
                                    ctx.getString(R.string.lan_none_found)
                                } else {
                                    ctx.getString(R.string.lan_done_found, discovered.size)
                                }
                            }
                        }
                    }
                },
                enabled = !scanning,
                label = { Text(if (scanning) stringResource(R.string.lan_scanning) else stringResource(R.string.lan_scan_now)) },
                leadingIcon = {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.WifiTethering, null)
                    }
                }
            )
        }

        if (discovered.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    discovered.forEachIndexed { idx, cand ->
                        key(cand.host + cand.port + idx) {
                            SimpleListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        when (cand.type) {
                                            com.bookrio.core.net.DiscoveredSourceType.FTP -> onOpenFtp()
                                            com.bookrio.core.net.DiscoveredSourceType.SMB -> onOpenSmb()
                                            com.bookrio.core.net.DiscoveredSourceType.WEBDAV -> onOpenWebdav()
                                            com.bookrio.core.net.DiscoveredSourceType.CALIBRE -> onOpenCalibre()
                                            com.bookrio.core.net.DiscoveredSourceType.HTTP_CANDIDATE -> onOpenCalibre()
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                headline = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            when (cand.type) {
                                                com.bookrio.core.net.DiscoveredSourceType.FTP -> stringResource(R.string.lan_type_ftp)
                                                com.bookrio.core.net.DiscoveredSourceType.SMB -> stringResource(R.string.lan_type_smb)
                                                com.bookrio.core.net.DiscoveredSourceType.WEBDAV -> stringResource(R.string.lan_type_webdav)
                                                com.bookrio.core.net.DiscoveredSourceType.CALIBRE -> stringResource(R.string.lan_type_calibre)
                                                com.bookrio.core.net.DiscoveredSourceType.HTTP_CANDIDATE -> stringResource(R.string.lan_type_http)
                                            },
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.lan_confidence, cand.confidencePct),
                                            style = ShelfTypography.BodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                supporting = {
                                    Text(cand.url, style = ShelfTypography.BodySmall)
                                },
                                leadingIcon = {
                                    val tint = when (cand.type) {
                                        com.bookrio.core.net.DiscoveredSourceType.FTP -> MaterialTheme.colorScheme.primary
                                        com.bookrio.core.net.DiscoveredSourceType.SMB -> MaterialTheme.colorScheme.tertiary
                                        com.bookrio.core.net.DiscoveredSourceType.WEBDAV -> MaterialTheme.colorScheme.secondary
                                        com.bookrio.core.net.DiscoveredSourceType.CALIBRE -> com.bookrio.designsystem.theme.OmarchyColors.Dim
                                        com.bookrio.core.net.DiscoveredSourceType.HTTP_CANDIDATE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Icon(
                                        when (cand.type) {
                                            com.bookrio.core.net.DiscoveredSourceType.FTP -> Icons.Default.CloudSync
                                            com.bookrio.core.net.DiscoveredSourceType.SMB -> Icons.Default.Dns
                                            com.bookrio.core.net.DiscoveredSourceType.WEBDAV -> Icons.Default.Cloud
                                            com.bookrio.core.net.DiscoveredSourceType.CALIBRE -> Icons.Default.LocalLibrary
                                            com.bookrio.core.net.DiscoveredSourceType.HTTP_CANDIDATE -> Icons.Default.Public
                                        },
                                        null,
                                        tint = tint
                                    )
                                },
                                trailingIcon = {
                                    Icon(Icons.Default.ChevronRight, null)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleListItem(
    modifier: Modifier = Modifier,
    headline: @Composable () -> Unit,
    supporting: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon?.let {
            it()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            headline()
            supporting?.let {
                Spacer(Modifier.height(2.dp))
                it()
            }
        }
        trailingIcon?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportProgressScreen(onBack: () -> Unit) {
    val navContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var showHistoryDialogFor: com.bookrio.data.local.entity.SyncHistoryEntity? by remember { mutableStateOf(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_progress_title), style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            val db = com.bookrio.data.local.ShelfDatabase.getInstance(navContext)
            val downloadTasks by db.downloadTaskDao().observeAll()
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val syncHistory by db.syncHistoryDao().observeAll()
                .collectAsStateWithLifecycle(initialValue = emptyList())

            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text(stringResource(R.string.import_tab_downloads), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text(stringResource(R.string.import_tab_sync_history), maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTabIndex) {
                0 -> {
                    if (downloadTasks.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.DownloadDone,
                                    null,
                                    Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.empty_no_downloads),
                                    style = ShelfTypography.TitleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(downloadTasks) { task ->
                                val statusColor = when (task.status) {
                                    com.bookrio.data.local.entity.DownloadStatusEntity.RUNNING -> MaterialTheme.colorScheme.primary
                                    com.bookrio.data.local.entity.DownloadStatusEntity.COMPLETED -> MaterialTheme.colorScheme.secondary
                                    com.bookrio.data.local.entity.DownloadStatusEntity.FAILED -> MaterialTheme.colorScheme.error
                                    com.bookrio.data.local.entity.DownloadStatusEntity.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                                val sourceLabel = if (task.serverId != null) stringResource(R.string.ip_source_sync) else stringResource(R.string.ip_source_import)
                                Card {
                                    Column(Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(task.remoteName, style = ShelfTypography.BodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AssistChip(
                                                onClick = {
                                                    val sourceDetail = if (task.serverId != null) navContext.getString(R.string.ip_detail_server, task.serverId) else navContext.getString(R.string.ip_detail_local_import)
                                                    val detail = buildString {
                                                        append(navContext.getString(R.string.ip_detail_type, sourceLabel, sourceDetail))
                                                        append("\n").append(navContext.getString(R.string.ip_detail_remote, task.remotePath))
                                                        if (task.localPath != null) append("\n").append(navContext.getString(R.string.ip_detail_local, task.localPath))
                                                        val sizeTxt = if (task.sizeBytes > 0) navContext.getString(R.string.ip_detail_size, formatBytes(task.sizeBytes)) else null
                                                        if (sizeTxt != null) append("\n").append(sizeTxt)
                                                        if (task.retryCount > 0) append("\n").append(navContext.getString(R.string.ip_detail_attempts, task.retryCount + 1))
                                                    }
                                                    scope.launch { snackbarHostState.showSnackbar(detail) }
                                                },
                                                label = { Text(sourceLabel) },
                                                colors = AssistChipDefaults.assistChipColors()
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            AssistChip(
                                                onClick = {
                                                    when (task.status) {
                                                        com.bookrio.data.local.entity.DownloadStatusEntity.RUNNING -> {
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    navContext.getString(R.string.ip_running_msg, task.remoteName)
                                                                )
                                                            }
                                                        }
                                                        com.bookrio.data.local.entity.DownloadStatusEntity.FAILED -> {
                                                            val err = task.errorMessage ?: navContext.getString(R.string.ip_unknown_error)
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    navContext.getString(R.string.ip_failed_msg, err)
                                                                )
                                                            }
                                                        }
                                                        com.bookrio.data.local.entity.DownloadStatusEntity.COMPLETED -> {
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    navContext.getString(R.string.ip_done_msg, task.remoteName, formatBytes(task.downloadedBytes))
                                                                )
                                                            }
                                                        }
                                                        com.bookrio.data.local.entity.DownloadStatusEntity.CANCELLED -> {
                                                            scope.launch { snackbarHostState.showSnackbar(navContext.getString(R.string.ip_cancelled_msg)) }
                                                        }
                                                        else -> {
                                                            scope.launch { snackbarHostState.showSnackbar(navContext.getString(R.string.ip_status_msg, task.status.name)) }
                                                        }
                                                    }
                                                },
                                                label = { Text(task.status.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                                colors = AssistChipDefaults.assistChipColors(labelColor = statusColor)
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        if (task.sizeBytes > 0) {
                                    val pct = task.downloadedBytes.toFloat() / task.sizeBytes
                                    LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(5.dp))
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${(pct * 100).toInt()}% · ${formatBytes(task.downloadedBytes)} / ${formatBytes(task.sizeBytes)}",
                                        style = ShelfTypography.BodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (task.status == com.bookrio.data.local.entity.DownloadStatusEntity.COMPLETED) {
                                            Text(
                                                formatBytes(task.downloadedBytes),
                                                style = ShelfTypography.BodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (task.errorMessage != null) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                navContext.getString(R.string.ip_error, task.errorMessage.orEmpty()),
                                                style = ShelfTypography.BodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (syncHistory.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.History,
                                    null,
                                    Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.empty_no_sync_history),
                                    style = ShelfTypography.TitleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.ip_empty_history_hint),
                                    style = ShelfTypography.BodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(syncHistory) { h ->
                                val statusColor = when (h.status) {
                                    com.bookrio.data.local.entity.DownloadStatusEntity.RUNNING -> MaterialTheme.colorScheme.primary
                                    com.bookrio.data.local.entity.DownloadStatusEntity.COMPLETED -> MaterialTheme.colorScheme.secondary
                                    com.bookrio.data.local.entity.DownloadStatusEntity.FAILED -> MaterialTheme.colorScheme.error
                                    com.bookrio.data.local.entity.DownloadStatusEntity.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                                val dateText = java.text.SimpleDateFormat("dd. MMM yyyy HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(h.startedAt))
                                val durationMs = (h.completedAt ?: System.currentTimeMillis()) - h.startedAt
                                val durationSec = (durationMs / 1000).toInt()
                                val durText = if (h.completedAt != null) {
                                    if (durationSec < 60) "${durationSec}s" else "${durationSec / 60}m ${durationSec % 60}s"
                                } else "—"
                                Card {
                                    Column(Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    stringResource(R.string.ip_server, h.serverId),
                                                    style = ShelfTypography.BodyLarge,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    stringResource(R.string.ip_duration_detail, dateText, durText),
                                                    style = ShelfTypography.BodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            AssistChip(
                                                onClick = { showHistoryDialogFor = h },
                                                label = { Text(h.status.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                                colors = AssistChipDefaults.assistChipColors(labelColor = statusColor)
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            SyncStatChip(stringResource(R.string.ip_stat_found), "${h.filesFound}")
                                            SyncStatChip(stringResource(R.string.ip_stat_new), "${h.filesNew}")
                                            SyncStatChip(stringResource(R.string.ip_stat_downloaded), "${h.filesDownloaded}", MaterialTheme.colorScheme.primary)
                                            SyncStatChip(stringResource(R.string.ip_stat_failed), "${h.filesFailed}", MaterialTheme.colorScheme.error)
                                        }
                                        if (h.errorMessage != null) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                navContext.getString(R.string.ip_error, h.errorMessage.orEmpty()),
                                                style = ShelfTypography.BodySmall,
                                                color = MaterialTheme.colorScheme.error
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
    }
    val hist = showHistoryDialogFor
    if (hist != null) {
        val hCompletedAt = hist.completedAt
        val fmt = java.text.SimpleDateFormat("dd. MMM yyyy HH:mm:ss", java.util.Locale.getDefault())
        val durMs = (hCompletedAt ?: System.currentTimeMillis()) - hist.startedAt
        val durSec = (durMs / 1000).toInt()
        val durTxt = if (hCompletedAt != null) {
            if (durSec < 60) navContext.getString(R.string.app_dur_s, durSec) else "${durSec / 60}m ${durSec % 60}s"
        } else stringResource(R.string.ip_dlg_ongoing)
        AlertDialog(
            onDismissRequest = { showHistoryDialogFor = null },
            confirmButton = {
                TextButton(onClick = { showHistoryDialogFor = null }) { Text(stringResource(R.string.action_close)) }
            },
            title = { Text(stringResource(R.string.ip_dlg_title, hist.serverId)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.ip_dlg_status, hist.status.name.lowercase().replaceFirstChar { it.uppercase() }))
                    Text(stringResource(R.string.ip_dlg_started, fmt.format(java.util.Date(hist.startedAt))))
                    Text(stringResource(R.string.ip_dlg_finished, if (hCompletedAt != null) fmt.format(java.util.Date(hCompletedAt)) else "—"))
                    Text(stringResource(R.string.ip_dlg_duration, durTxt))
                    HorizontalDivider()
                    Text(stringResource(R.string.ip_dlg_results), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ip_dlg_found, hist.filesFound))
                    Text(stringResource(R.string.ip_dlg_new, hist.filesNew))
                    Text(stringResource(R.string.ip_dlg_downloaded, hist.filesDownloaded))
                    Text(stringResource(R.string.ip_dlg_failed, hist.filesFailed))
                    if (hist.errorMessage != null) {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.ip_dlg_error, hist.errorMessage.orEmpty()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun SyncStatChip(label: String, value: String, color: androidx.compose.ui.graphics.Color = LocalContentColor.current) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = ShelfTypography.TitleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = ShelfTypography.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}

object SampleData {
    val demoBooks = SampleBooks.books
}
