package com.bookrio.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookrio.core.time.nowMillis
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTheme
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.library.BookrioLibraryMode
import com.bookrio.library.BookrioLibraryScreen
import com.bookrio.player.ui.BookrioPlayerScreen
import com.bookrio.podcast.ui.BookrioPodcastDetailScreen
import com.bookrio.podcast.ui.BookrioPodcastRootScreen
import com.bookrio.settings.BookrioSettingsScreen
import com.bookrio.shared.platform.AppPrefs
import com.bookrio.shared.platform.PrefKeys
import com.bookrio.shared.platform.appDatabase
import com.bookrio.shared.platform.autoOpenEpubPath
import com.bookrio.shared.platform.autoOpenPdfPath
import com.bookrio.shared.platform.importBookWithPicker
import com.bookrio.shared.platform.presentEpubReader
import com.bookrio.shared.platform.presentPdfReader
import com.bookrio.shared.player.AudioOwner
import com.bookrio.shared.player.AudioPlayers
import com.bookrio.shared.player.AudioRequest
import com.bookrio.shared.player.AudiobookPlayback
import com.bookrio.shared.podcast.PodcastPlayback
import com.bookrio.shared.podcast.PodcastRepository
import com.bookrio.shared.podcast.autoSubscribeRssFile
import com.bookrio.shared.podcast.readLocalText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Synthetic canonical feed URL used by the CI RSS-file smoke test. */
private const val CI_FEED_URL = "https://ci.bookrio.local/feed.xml"

/** The destinations the iOS shell can show. Mirrors Android's tab + sub-routes. */
private sealed interface BookrioScreen {
    data object Books : BookrioScreen
    data object Audiobooks : BookrioScreen
    data object Podcasts : BookrioScreen
    data object Settings : BookrioScreen
    data class Player(val bookId: Long) : BookrioScreen
    data class PodcastDetail(val feedId: Long) : BookrioScreen
}

/**
 * iOS entry point. A Material 3 shell that mirrors the Android app: four bottom
 * tabs (Bøker / Lydbøker / Podkaster / Innstillinger), a single now-playing bar
 * over the one shared audio owner, and the Android-parity screens wired from
 * `:library`, `:player`, `:podcast/ui` and `:settings`.
 *
 * PDF and EPUB still open native page-curl readers (Apple `.pageCurl`), and the
 * CI smoke hooks (auto-open PDF/EPUB, auto-subscribe RSS) are preserved.
 */
@Composable
fun App() {
    val db = remember { appDatabase() }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<BookrioScreen>(BookrioScreen.Books) }
    var message by remember { mutableStateOf<String?>(null) }
    var navVisible by remember { mutableStateOf(true) }
    var lastTab by remember { mutableStateOf<BookrioScreen>(BookrioScreen.Books) }
    val books by remember { db.bookDao().observeAll() }
        .collectAsState(initial = emptyList<BookEntity>())

    val openAudio: (Long) -> Unit = { id ->
        if (screen == BookrioScreen.Audiobooks) lastTab = BookrioScreen.Audiobooks
        else lastTab = BookrioScreen.Books
        screen = BookrioScreen.Player(id)
    }

    val onImport: () -> Unit = {
        importBookWithPicker { filePath, fileName ->
            scope.launch {
                val format = formatFromName(fileName)
                if (format == FormatEntity.UNKNOWN) {
                    message = "\"$fileName\" har et format iOS-leseren ikke kjenner ennå."
                } else {
                    db.bookDao().insert(
                        BookEntity(
                            title = fileName.substringBeforeLast('.').ifBlank { fileName },
                            type = if (isAudioFormat(format)) BookTypeEntity.AUDIOBOOK else BookTypeEntity.EBOOK,
                            format = format,
                            filePath = filePath,
                            importSource = ImportSourceEntity.FILE_PICKER,
                        )
                    )
                    message = "\"$fileName\" importert."
                }
            }
        }
    }

    // CI/demo hooks (unchanged): auto-open PDF/EPUB and auto-subscribe to an RSS
    // file so the GitHub Actions simulator smoke tests keep passing.
    LaunchedEffect(Unit) {
        openDemoPath(db, scope, autoOpenPdfPath(), openAudio) { text -> message = text }
        openDemoPath(db, scope, autoOpenEpubPath(), openAudio) { text -> message = text }
        autoSubscribeRssFile()?.takeIf { it.isNotBlank() }?.let { path ->
            val outcome: Result<Long> = runCatching {
                PodcastRepository(db).subscribeXml(CI_FEED_URL, readLocalText(path))
            }.fold(onSuccess = { it }, onFailure = { Result.failure(it) })
            val feedId = outcome.getOrNull()
            println(
                "[bookrio-smoke] podcastSubscribe=${if (feedId != null) "ok" else "fail"} " +
                    "feedId=$feedId err=${outcome.exceptionOrNull()?.message}",
            )
            if (feedId != null) {
                val count = runCatching { db.podcastEpisodeDao().listIdsByFeed(feedId).size }
                    .getOrDefault(0)
                println("[bookrio-smoke] podcastEpisodes=$count")
            }
        }
    }

    val showBottomNav = when (screen) {
        BookrioScreen.Books, BookrioScreen.Audiobooks,
        BookrioScreen.Podcasts, BookrioScreen.Settings -> true
        is BookrioScreen.Player, is BookrioScreen.PodcastDetail -> false
    }
    val selectedTab = when (screen) {
        BookrioScreen.Audiobooks -> 1
        BookrioScreen.Settings -> 3
        BookrioScreen.Podcasts, is BookrioScreen.PodcastDetail -> 2
        else -> 0
    }

    ShelfTheme(darkTheme = true) {
        Surface(color = OmarchyColors.Bg, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = OmarchyColors.Bg,
                bottomBar = {
                    Column {
                        NowPlayingBar(
                            onOpen = { request ->
                                screen = when (request.owner) {
                                    AudioOwner.AUDIOBOOK -> BookrioScreen.Player(request.id)
                                    AudioOwner.PODCAST -> BookrioScreen.Podcasts
                                }
                            },
                        )
                        if (showBottomNav && navVisible) {
                            ShellNavigationBar(
                                selectedTab = selectedTab,
                                books = books,
                                onSelect = { index ->
                                    screen = when (index) {
                                        0 -> BookrioScreen.Books
                                        1 -> BookrioScreen.Audiobooks
                                        2 -> BookrioScreen.Podcasts
                                        else -> BookrioScreen.Settings
                                    }
                                    if (index <= 1) lastTab = screen
                                    navVisible = true
                                },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    when (val current = screen) {
                        BookrioScreen.Books -> BookrioLibraryScreen(
                            mode = BookrioLibraryMode.BOOKS,
                            onBookClick = { id -> openLibraryItem(db, scope, id, openAudio) { message = it } },
                            onAudiobookClick = openAudio,
                            onImportClick = onImport,
                            onSettingsClick = { screen = BookrioScreen.Settings },
                            onBookLongClick = { /* Book details are a later phase. */ },
                            onNavVisibilityChange = { navVisible = it },
                        )
                        BookrioScreen.Audiobooks -> BookrioLibraryScreen(
                            mode = BookrioLibraryMode.AUDIOBOOKS,
                            onBookClick = openAudio,
                            onAudiobookClick = openAudio,
                            onImportClick = onImport,
                            onSettingsClick = { screen = BookrioScreen.Settings },
                            onBookLongClick = { /* Book details are a later phase. */ },
                            onNavVisibilityChange = { navVisible = it },
                        )
                        BookrioScreen.Podcasts -> BookrioPodcastRootScreen(
                            onOpenDetail = { feedId -> screen = BookrioScreen.PodcastDetail(feedId) },
                            onOpenEpisode = { /* The bar above owns playback. */ },
                        )
                        is BookrioScreen.PodcastDetail -> BookrioPodcastDetailScreen(
                            feedId = current.feedId,
                            onBack = { screen = BookrioScreen.Podcasts },
                            onOpenEpisode = { /* The bar above owns playback. */ },
                        )
                        BookrioScreen.Settings -> BookrioSettingsScreen(
                            onBack = { screen = BookrioScreen.Books },
                        )
                        is BookrioScreen.Player -> BookrioPlayerScreen(
                            bookId = current.bookId,
                            onBack = { screen = lastTab },
                        )
                    }
                }
            }

            message?.let { text ->
                Text(
                    text = text,
                    color = OmarchyColors.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(OmarchyColors.Panel)
                        .clickable { message = null }
                        .padding(16.dp),
                )
            }
            }
        }
    }
}

@Composable
private fun ShellNavigationBar(
    selectedTab: Int,
    books: List<BookEntity>,
    onSelect: (Int) -> Unit,
) {
    val showCounts = remember(selectedTab) { AppPrefs.getBoolean(PrefKeys.LIB_TAB_COUNTS, true) }
    val ebookCount = remember(books) {
        books.count { it.type != BookTypeEntity.AUDIOBOOK && !it.isDeleted }
    }
    val audioCount = remember(books) {
        books.count { it.type == BookTypeEntity.AUDIOBOOK && !it.isDeleted }
    }

    NavigationBar(tonalElevation = 0.dp, containerColor = OmarchyColors.Bg) {
        val items = listOf(
            Triple("Bøker", 0, Icons.Default.AutoStories),
            Triple("Lydbøker", 1, Icons.Default.Headphones),
            Triple("Podkaster", 2, Icons.Default.Podcasts),
            Triple("Innstillinger", 3, Icons.Default.Settings),
        )
        items.forEach { (label, index, icon) ->
            val selected = selectedTab == index
            val count = when (index) {
                0 -> if (showCounts) ebookCount else 0
                1 -> if (showCounts) audioCount else 0
                else -> 0
            }
            NavigationBarItem(
                icon = {
                    if (count > 0) {
                        BadgedBox(
                            badge = {
                                Badge(containerColor = OmarchyColors.Accent, contentColor = Color.Black) {
                                    Text(if (count > 99) "99+" else count.toString())
                                }
                            },
                        ) { Icon(icon, contentDescription = label) }
                    } else {
                        Icon(icon, contentDescription = label)
                    }
                },
                label = if (selected) {
                    {
                        Text(
                            label,
                            style = ShelfTypography.LabelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    null
                },
                selected = selected,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = OmarchyColors.Accent,
                    selectedTextColor = OmarchyColors.Accent,
                    unselectedIconColor = OmarchyColors.Dim,
                    unselectedTextColor = OmarchyColors.Dim,
                    indicatorColor = Color.Transparent,
                ),
                onClick = { onSelect(index) },
            )
        }
    }
}

/**
 * One now-playing bar for every sound in the app, matching the Android
 * `NowPlayingBar`: 3 dp progress line, icon tile, bold title, dim subtitle and a
 * close button. Both audiobook and podcast flow through the single
 * [AudioPlayers.shared] owner.
 */
@Composable
private fun NowPlayingBar(onOpen: (AudioRequest) -> Unit) {
    val player = remember { AudioPlayers.shared }
    val state by player.state.collectAsState()
    val request = state.request ?: return
    val chapters by AudiobookPlayback.chapters.collectAsState()
    val chapterIndex by AudiobookPlayback.currentChapterIndex.collectAsState()
    val duration = state.durationMs
    val fraction = if (duration > 0L) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        color = OmarchyColors.Panel,
        modifier = Modifier.fillMaxWidth().clickable { onOpen(request) },
    ) {
        Column {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = OmarchyColors.Accent,
                trackColor = Color(0x33FFFFFF),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = OmarchyColors.Hairline,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (request.owner == AudioOwner.AUDIOBOOK) {
                                Icons.Default.Headphones
                            } else {
                                Icons.Default.Podcasts
                            },
                            contentDescription = null,
                            tint = OmarchyColors.Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        request.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        nowPlayingSubtitle(request, chapters.getOrNull(chapterIndex)),
                        style = MaterialTheme.typography.labelSmall,
                        color = OmarchyColors.Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    when (request.owner) {
                        AudioOwner.AUDIOBOOK -> AudiobookPlayback.stop()
                        AudioOwner.PODCAST -> PodcastPlayback.stop()
                    }
                }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Lukk",
                        tint = OmarchyColors.Dim,
                    )
                }
            }
        }
    }
}

private fun nowPlayingSubtitle(
    request: AudioRequest,
    chapter: com.bookrio.shared.player.AudiobookChapter?,
): String = when {
    request.owner == AudioOwner.AUDIOBOOK && chapter != null -> chapter.title
    request.artist != null -> request.artist
    request.owner == AudioOwner.PODCAST -> "Podkast"
    else -> "Lydbok"
}

/**
 * Opens a library row: audio formats go to the player, PDF/EPUB open the native
 * page-curl reader, everything else reports "coming later".
 */
private fun openLibraryItem(
    database: ShelfDatabase,
    scope: CoroutineScope,
    bookId: Long,
    onOpenAudio: (Long) -> Unit,
    onMessage: (String) -> Unit,
) {
    scope.launch {
        val book = runCatching { database.bookDao().getById(bookId) }.getOrNull() ?: return@launch
        openBook(scope, database, book, onOpenAudio, onMessage)
    }
}

/**
 * CI/demo helper: if [demoPath] is set, import it (once) and open it, mirroring
 * BOOKRIO_AUTO_OPEN_PDF / BOOKRIO_AUTO_OPEN_EPUB in the simulator smoke test.
 */
private fun openDemoPath(
    database: ShelfDatabase,
    scope: CoroutineScope,
    demoPath: String?,
    onOpenAudio: (Long) -> Unit,
    onMessage: (String) -> Unit,
) {
    val path = demoPath?.takeIf { it.isNotBlank() } ?: return
    scope.launch {
        runCatching {
            val existing = database.bookDao().getByPath(path)
            val book = existing ?: run {
                val id = database.bookDao().insert(
                    BookEntity(
                        title = "CI sample",
                        type = BookTypeEntity.EBOOK,
                        format = formatFromName(path),
                        filePath = path,
                        importSource = ImportSourceEntity.SAMPLE,
                    )
                )
                database.bookDao().getById(id)
            } ?: return@runCatching
            openBook(scope, database, book, onOpenAudio, onMessage)
        }.onFailure { t ->
            println("[bookrio-smoke] auto-open failed: ${t.message}")
        }
    }
}

private fun openBook(
    scope: CoroutineScope,
    database: ShelfDatabase,
    book: BookEntity,
    onOpenAudio: (Long) -> Unit,
    onMessage: (String) -> Unit,
) {
    val path = book.filePath
    when {
        book.format == FormatEntity.PDF || book.format == FormatEntity.EPUB -> {
            if (path.isNullOrBlank()) {
                onMessage("Boken mangler en lesbar fil på denne enheten.")
                return
            }
            scope.launch {
                runCatching { database.bookDao().update(book.copy(lastOpenedAt = nowMillis())) }
                val progress = runCatching { database.progressDao().getByBook(book.id) }.getOrNull()
                val startPage = progress?.pageIndex ?: 0
                val presented = if (book.format == FormatEntity.PDF) {
                    presentPdfReader(filePath = path, startPage = startPage) { page, totalPages ->
                        saveProgress(scope, database, book.id, page, totalPages)
                    }
                } else {
                    presentEpubReader(filePath = path, startPage = startPage) { page, totalPages ->
                        saveProgress(scope, database, book.id, page, totalPages)
                    }
                }
                if (book.format == FormatEntity.PDF) {
                    println("[bookrio-smoke] presentPdfReader=$presented format=${book.format} path=$path")
                } else {
                    println("[bookrio-smoke] presentEpubReader=$presented format=${book.format} path=$path")
                }
                if (!presented) onMessage("Kunne ikke åpne ${book.format}-en.")
            }
        }
        isAudioFormat(book.format) -> onOpenAudio(book.id)
        else -> onMessage("iOS-leseren støtter PDF, EPUB og lydbøker nå — ${book.format} kommer senere.")
    }
}

private fun isAudioFormat(format: FormatEntity): Boolean = when (format) {
    FormatEntity.M4B, FormatEntity.M4A, FormatEntity.MP3, FormatEntity.AAC,
    FormatEntity.FLAC, FormatEntity.OGG, FormatEntity.OGG_OPUS, FormatEntity.WAV,
    -> true
    else -> false
}

private fun saveProgress(
    scope: CoroutineScope,
    database: ShelfDatabase,
    bookId: Long,
    page: Int,
    totalPages: Int,
) {
    val percent = if (totalPages > 0) page.toFloat() / totalPages.toFloat() else 0f
    scope.launch {
        runCatching {
            val prior = database.progressDao().getByBook(bookId)
            database.progressDao().insertOrReplace(
                (prior ?: ReadingProgressEntity(bookId = bookId)).copy(
                    pageIndex = page,
                    progressPercent = percent,
                    updatedAt = nowMillis(),
                ),
            )
        }
    }
}

private fun formatFromName(name: String): FormatEntity =
    when (name.substringAfterLast('.', "").lowercase()) {
        "epub" -> FormatEntity.EPUB
        "pdf" -> FormatEntity.PDF
        "m4b" -> FormatEntity.M4B
        "m4a" -> FormatEntity.M4A
        "mp3" -> FormatEntity.MP3
        "aac" -> FormatEntity.AAC
        "flac" -> FormatEntity.FLAC
        "ogg" -> FormatEntity.OGG
        "opus" -> FormatEntity.OGG_OPUS
        "wav" -> FormatEntity.WAV
        "mobi", "prc" -> FormatEntity.MOBI
        "azw" -> FormatEntity.AZW
        "azw3", "kf8" -> FormatEntity.AZW3
        "fb2" -> FormatEntity.FB2
        "cbz" -> FormatEntity.CBZ
        "cbr" -> FormatEntity.CBR
        "txt" -> FormatEntity.TXT
        "html", "htm", "xhtml" -> FormatEntity.HTML
        "rtf" -> FormatEntity.RTF
        "docx" -> FormatEntity.DOCX
        "md", "markdown" -> FormatEntity.MD
        "zip" -> FormatEntity.ZIP
        else -> FormatEntity.UNKNOWN
    }