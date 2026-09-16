package com.shelf.reader

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
import androidx.compose.ui.res.stringResource
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
import com.shelf.reader.designsystem.theme.OmarchyColors
import com.shelf.reader.designsystem.theme.ShelfColors
import com.shelf.reader.app.ShelfDestinations
import com.shelf.reader.core.net.CalibreContentServerClient
import com.shelf.reader.core.net.DiscoveredSourceCandidate
import com.shelf.reader.core.net.LanSourceDiscovery
import com.shelf.reader.data.prefs.UserPreferencesRepository
import com.shelf.reader.designsystem.theme.ShelfTheme
import com.shelf.reader.library.ui.LibraryScreen
import com.shelf.reader.library.viewmodel.LibraryMode
import com.shelf.reader.library.ui.SampleBooks
import com.shelf.reader.reader.ui.ReaderScreen
import com.shelf.reader.player.ui.PlayerScreen
import com.shelf.reader.podcast.ui.PodcastDetailScreen
import com.shelf.reader.podcast.ui.PodcastDiscoverScreen
import com.shelf.reader.podcast.ui.PodcastPlayerScreen
import com.shelf.reader.podcast.ui.PodcastRootScreen
import com.shelf.reader.podcast.ui.podcastDetailVmFactory
import com.shelf.reader.podcast.ui.podcastDiscoverVmFactory
import com.shelf.reader.podcast.ui.podcastPlayerVmFactory
import com.shelf.reader.podcast.ui.podcastRootVmFactory
import com.shelf.reader.ftp.ui.FtpBrowserScreen
import com.shelf.reader.ftp.ui.FtpConnectionScreen
import com.shelf.reader.ftp.ui.FtpSourceDetailsScreen
import com.shelf.reader.ftp.ui.FtpSourcesScreen
import com.shelf.reader.ftp.ui.FtpTransfersScreen
import com.shelf.reader.smb.ui.SmbScreen
import com.shelf.reader.webdav.ui.WebdavScreen
import com.shelf.reader.torrent.ui.TorrentScreen
import com.shelf.reader.app.BookDetailsScreen
import com.shelf.reader.app.ImportScreen
import com.shelf.reader.app.OnboardingScreen
import com.shelf.reader.app.ui.SettingsScreen
import com.shelf.reader.designsystem.theme.ShelfTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    val icon: ImageVector
) {
    object Books : BottomNavItem(ShelfDestinations.Books.route, R.string.nav_books, Icons.Default.AutoStories)
    object Audiobooks : BottomNavItem(ShelfDestinations.Audiobooks.route, R.string.shelf_audiobooks, Icons.Default.Headphones)
    object Podcasts : BottomNavItem(ShelfDestinations.Podcasts.route, com.shelf.reader.podcast.R.string.pod_nav_title, Icons.Default.Podcasts)
    object Settings : BottomNavItem(ShelfDestinations.Settings.route, R.string.nav_settings, Icons.Filled.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfRoot(prefs: UserPreferencesRepository, initialRoute: String? = null) {
    val navController = rememberNavController()
    val hasSeenOnboardingState by prefs.hasSeenOnboarding.collectAsStateWithLifecycle(initialValue = null)

    if (hasSeenOnboardingState == null) {
        Surface(color = OmarchyColors.Bg, modifier = Modifier.fillMaxSize()) {}
        return
    }

    val defaultStart = if (hasSeenOnboardingState == true) ShelfDestinations.Books.route else ShelfDestinations.Onboarding.route
    val startDest = initialRoute ?: defaultStart
    val items = listOf(BottomNavItem.Books, BottomNavItem.Audiobooks, BottomNavItem.Podcasts, BottomNavItem.Settings)
    val showBottomRoutes = setOf(
        ShelfDestinations.Books.route,
        ShelfDestinations.Audiobooks.route,
        ShelfDestinations.Podcasts.route,
        ShelfDestinations.Settings.route
    )

    // Bibliotek-rutenettet styrer denne via onNavVisibilityChange: skjul ved rulling ned,
    // vis ved rulling opp. Tilbakestilles ved fanebytte.
    var libraryNavVisible by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val activeAudio by com.shelf.reader.data.repository.ActivePlaybackState.state.collectAsStateWithLifecycle()
            val activePodcast by com.shelf.reader.data.repository.PodcastPlaybackState.state.collectAsStateWithLifecycle()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val isPlayerScreen = currentDestination?.route?.startsWith("player") == true
            val isPodcastPlayerScreen = currentDestination?.route?.startsWith("podcasts/player") == true

            Column {
                if (activeAudio != null && !isPlayerScreen) {
                    val active = activeAudio!!
                    Surface(
                        tonalElevation = 8.dp,
                        shadowElevation = 12.dp,
                        color = com.shelf.reader.designsystem.theme.OmarchyColors.Panel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(ShelfDestinations.Player.routeFor(active.bookId))
                            }
                    ) {
                        Column {
                            LinearProgressIndicator(
                                progress = { active.progressPercent },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = com.shelf.reader.designsystem.theme.OmarchyColors.Accent,
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
                                    color = com.shelf.reader.designsystem.theme.OmarchyColors.Hairline,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Headphones, contentDescription = null, tint = com.shelf.reader.designsystem.theme.OmarchyColors.Accent, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        active.title.ifBlank { stringResource(R.string.player_title) },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val subLabel = if (active.sleepTimerRemainingMs > 0L) {
                                        val m = (active.sleepTimerRemainingMs / 60_000L).toInt().coerceAtLeast(1)
                                        "${active.author} • ${stringResource(R.string.main_sleep_remaining, m)}"
                                    } else {
                                        active.author.ifBlank { stringResource(R.string.main_playing) }
                                    }
                                    Text(
                                        subLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = com.shelf.reader.designsystem.theme.OmarchyColors.Dim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = {
                                    com.shelf.reader.data.repository.ActivePlaybackState.clear()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = com.shelf.reader.designsystem.theme.OmarchyColors.Dim)
                                }
                            }
                        }
                    }
                }

                if (activePodcast != null && !isPodcastPlayerScreen) {
                    val active = activePodcast!!
                    Surface(
                        tonalElevation = 8.dp,
                        shadowElevation = 12.dp,
                        color = com.shelf.reader.designsystem.theme.OmarchyColors.Panel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(ShelfDestinations.PodcastPlayer.routeFor(active.episodeId))
                            }
                    ) {
                        Column {
                            LinearProgressIndicator(
                                progress = { active.progressPercent },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = com.shelf.reader.designsystem.theme.OmarchyColors.Accent,
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
                                    color = com.shelf.reader.designsystem.theme.OmarchyColors.Hairline,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Podcasts, contentDescription = null, tint = com.shelf.reader.designsystem.theme.OmarchyColors.Accent, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        active.title.ifBlank { stringResource(com.shelf.reader.podcast.R.string.pod_nav_title) },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        active.podcastTitle.ifBlank { stringResource(com.shelf.reader.podcast.R.string.pod_nav_title) },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = com.shelf.reader.designsystem.theme.OmarchyColors.Dim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = {
                                    com.shelf.reader.data.repository.PodcastPlaybackState.clear()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = com.shelf.reader.designsystem.theme.OmarchyColors.Dim)
                                }
                            }
                        }
                    }
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
                                    icon = { Icon(item.icon, contentDescription = itemLabel) },
                                    label = if (selected) { { Text(itemLabel, style = com.shelf.reader.designsystem.theme.ShelfTypography.LabelMedium) } } else null,
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
        NavHost(
            navController = navController,
            startDestination = startDest,
            modifier = Modifier.padding(innerPadding)
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
                FtpTransfersScreen(onBack = { navController.popBackStack() })
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
                TorrentScreen(
                    onBack = { navController.popBackStack() }
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
                        navController.navigate(ShelfDestinations.Books.route) {
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
                    title = stringResource(R.string.sources_calibre_title),
                    subtitle = stringResource(R.string.sources_calibre_sub),
                    icon = Icons.Default.LocalLibrary,
                    tint = OmarchyColors.Fg,
                    onClick = {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.sources_calibre_toast), android.widget.Toast.LENGTH_LONG).show()
                    }
                )
                SourceCard(
                    title = stringResource(R.string.sources_opds_title),
                    subtitle = stringResource(R.string.sources_opds_sub),
                    icon = Icons.Default.MenuBook,
                    tint = OmarchyColors.Fg,
                    onClick = {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.sources_opds_toast), android.widget.Toast.LENGTH_LONG).show()
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = OmarchyColors.Hairline)
            Spacer(Modifier.height(16.dp))

            LanDiscoverySection()

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
                    title = stringResource(com.shelf.reader.ftp.R.string.ftpu_transfers_title),
                    subtitle = stringResource(R.string.sources_downloads_status),
                    icon = Icons.Default.SwapVert,
                    tint = OmarchyColors.Fg,
                    onClick = onTransfersClick
                )
            }

            Spacer(Modifier.height(20.dp))
            WellKnownCatalogsSection()

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
private fun LanDiscoverySection() {
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
                                        val label = ctx.getString(R.string.lan_on_host, cand.label, cand.host, cand.port)
                                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.lan_preview_toast, label, cand.url), android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                headline = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(cand.label, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(8.dp))
                                        AssistChip(
                                            onClick = {
                                                val typeLabel = when (cand.type) {
                                                    com.shelf.reader.core.net.DiscoveredSourceType.FTP -> ctx.getString(R.string.lan_type_ftp)
                                                    com.shelf.reader.core.net.DiscoveredSourceType.SMB -> ctx.getString(R.string.lan_type_smb)
                                                    com.shelf.reader.core.net.DiscoveredSourceType.WEBDAV -> ctx.getString(R.string.lan_type_webdav)
                                                    com.shelf.reader.core.net.DiscoveredSourceType.CALIBRE -> ctx.getString(R.string.lan_type_calibre)
                                                    com.shelf.reader.core.net.DiscoveredSourceType.HTTP_CANDIDATE -> ctx.getString(R.string.lan_type_http)
                                                }
                                                android.widget.Toast.makeText(
                                                    ctx,
                                                    ctx.getString(R.string.lan_confidence_toast, cand.confidencePct, typeLabel),
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            },
                                            label = { Text(stringResource(R.string.lan_confidence, cand.confidencePct)) },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                            )
                                        )
                                    }
                                },
                                supporting = {
                                    Text(cand.url, style = ShelfTypography.BodySmall)
                                },
                                leadingIcon = {
                                    val tint = when (cand.type) {
                                        com.shelf.reader.core.net.DiscoveredSourceType.FTP -> MaterialTheme.colorScheme.primary
                                        com.shelf.reader.core.net.DiscoveredSourceType.SMB -> MaterialTheme.colorScheme.tertiary
                                        com.shelf.reader.core.net.DiscoveredSourceType.WEBDAV -> MaterialTheme.colorScheme.secondary
                                        com.shelf.reader.core.net.DiscoveredSourceType.CALIBRE -> com.shelf.reader.designsystem.theme.OmarchyColors.Dim
                                        com.shelf.reader.core.net.DiscoveredSourceType.HTTP_CANDIDATE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Icon(
                                        when (cand.type) {
                                            com.shelf.reader.core.net.DiscoveredSourceType.FTP -> Icons.Default.CloudSync
                                            com.shelf.reader.core.net.DiscoveredSourceType.SMB -> Icons.Default.Dns
                                            com.shelf.reader.core.net.DiscoveredSourceType.WEBDAV -> Icons.Default.Cloud
                                            com.shelf.reader.core.net.DiscoveredSourceType.CALIBRE -> Icons.Default.LocalLibrary
                                            com.shelf.reader.core.net.DiscoveredSourceType.HTTP_CANDIDATE -> Icons.Default.Public
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

private data class CatalogSuggestion(
    val title: String,
    val url: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: androidx.compose.ui.graphics.Color
)

@Composable
private fun WellKnownCatalogsSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val catalogs = remember {
        listOf(
            CatalogSuggestion("Standard Ebooks", "https://standardebooks.org/opds", ctx.getString(R.string.wkc_standard_ebooks_sub), Icons.Default.AutoStories, com.shelf.reader.designsystem.theme.OmarchyColors.Dim),
            CatalogSuggestion("Feedbooks", "https://www.feedbooks.com/catalog.atom", ctx.getString(R.string.wkc_feedbooks_sub), Icons.Default.MenuBook, com.shelf.reader.designsystem.theme.OmarchyColors.Dim),
            CatalogSuggestion("Project Gutenberg", "https://www.gutenberg.org/ebooks/opds", ctx.getString(R.string.wkc_gutenberg_sub), Icons.Default.LibraryBooks, com.shelf.reader.designsystem.theme.OmarchyColors.Dim),
            CatalogSuggestion(ctx.getString(R.string.wkc_librivox_title), "https://librivox.org/api/feed/audiobooks/?format=opds", ctx.getString(R.string.wkc_librivox_sub), Icons.Default.Audiotrack, com.shelf.reader.designsystem.theme.OmarchyColors.Dim)
        )
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.wkc_title),
                    style = ShelfTypography.TitleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.wkc_sub),
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            catalogs.forEach { c ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(R.string.wkc_toast, c.title, c.url),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = c.tint.copy(alpha = 0.12f)
                        ) {
                            Icon(
                                c.icon,
                                null,
                                Modifier.padding(8.dp).size(28.dp),
                                tint = c.tint
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = ShelfTypography.BodyLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                c.subtitle,
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                c.url,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null)
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
    var showHistoryDialogFor: com.shelf.reader.data.local.entity.SyncHistoryEntity? by remember { mutableStateOf(null) }
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
            val db = com.shelf.reader.data.local.ShelfDatabase.getInstance(navContext)
            val downloadTasks by db.downloadTaskDao().observeAll()
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val syncHistory by db.syncHistoryDao().observeAll()
                .collectAsStateWithLifecycle(initialValue = emptyList())

            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text(stringResource(R.string.import_tab_downloads)) })
                Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text(stringResource(R.string.import_tab_sync_history)) })
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
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.RUNNING -> MaterialTheme.colorScheme.primary
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.COMPLETED -> MaterialTheme.colorScheme.secondary
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.FAILED -> MaterialTheme.colorScheme.error
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
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
                                                        com.shelf.reader.data.local.entity.DownloadStatusEntity.RUNNING -> {
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    navContext.getString(R.string.ip_running_msg, task.remoteName)
                                                                )
                                                            }
                                                        }
                                                        com.shelf.reader.data.local.entity.DownloadStatusEntity.FAILED -> {
                                                            val err = task.errorMessage ?: navContext.getString(R.string.ip_unknown_error)
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    navContext.getString(R.string.ip_failed_msg, err)
                                                                )
                                                            }
                                                        }
                                                        com.shelf.reader.data.local.entity.DownloadStatusEntity.COMPLETED -> {
                                                            scope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    navContext.getString(R.string.ip_done_msg, task.remoteName, formatBytes(task.downloadedBytes))
                                                                )
                                                            }
                                                        }
                                                        com.shelf.reader.data.local.entity.DownloadStatusEntity.CANCELLED -> {
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
                                } else if (task.status == com.shelf.reader.data.local.entity.DownloadStatusEntity.COMPLETED) {
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
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.RUNNING -> MaterialTheme.colorScheme.primary
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.COMPLETED -> MaterialTheme.colorScheme.secondary
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.FAILED -> MaterialTheme.colorScheme.error
                                    com.shelf.reader.data.local.entity.DownloadStatusEntity.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
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
