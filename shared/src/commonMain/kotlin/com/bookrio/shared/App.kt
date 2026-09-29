package com.bookrio.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookrio.core.time.nowMillis
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.dao.PodcastFeedSummary
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.shared.platform.appDatabase
import com.bookrio.shared.platform.autoOpenEpubPath
import com.bookrio.shared.platform.autoOpenPdfPath
import com.bookrio.shared.platform.importBookWithPicker
import com.bookrio.shared.platform.presentEpubReader
import com.bookrio.shared.platform.presentPdfReader
import com.bookrio.shared.player.AudioOwner
import com.bookrio.shared.player.AudioPlayers
import com.bookrio.shared.player.AudiobookPlayback
import com.bookrio.shared.podcast.PodcastPlayback
import com.bookrio.shared.podcast.PodcastRepository
import com.bookrio.shared.podcast.autoSubscribeRssUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Bookrio HUD brand colours (mirrors the Android theme; kept local so :shared
// does not have to depend on :designsystem and its Compose-resources Gradle task).
private val Bg = Color(0xFF000000)
private val Panel = Color(0xFF0D0D0D)
private val Accent = Color(0xFFBEF93F)
private val Dim = Color(0xFF8C8C8C)
private val Fg = Color(0xFFF5F5F5)
private val Hairline = Color(0xFF1F1F1F)

/**
 * iOS entry screen. The library UI lives in commonMain (Compose Multiplatform);
 * opening a PDF hands off to the native page-curl reader in iosMain.
 */
@Composable
fun App() {
    val db = remember { appDatabase() }
    val scope = rememberCoroutineScope()
    val books by remember { db.bookDao().observeAll() }.collectAsState(initial = emptyList<BookEntity>())
    var message by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }

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

    // CI/demo hooks: BOOKRIO_AUTO_OPEN_PDF / BOOKRIO_AUTO_OPEN_EPUB import and
    // open a document immediately; BOOKRIO_AUTO_SUBSCRIBE_RSS subscribes to a
    // feed. All are no-ops in normal runs.
    LaunchedEffect(Unit) {
        openDemoPath(db, scope, autoOpenPdfPath()) { text -> message = text }
        openDemoPath(db, scope, autoOpenEpubPath()) { text -> message = text }
        autoSubscribeRssUrl()?.takeIf { it.isNotBlank() }?.let { url ->
            val feedId = runCatching { PodcastRepository(db).subscribe(url).getOrNull() }.getOrNull()
            println("[bookrio-smoke] podcastSubscribe=${if (feedId != null) "ok" else "fail"} feedId=$feedId")
            if (feedId != null) {
                val count = runCatching { db.podcastEpisodeDao().listIdsByFeed(feedId).size }
                    .getOrDefault(0)
                println("[bookrio-smoke] podcastEpisodes=$count")
            }
        }
    }

    MaterialTheme {
        Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(count = books.size, onImport = onImport)
                TabBar(tab = tab, onSelect = { tab = it })

                when (tab) {
                    1 -> PodcastsScreen(db = db, scope = scope) { text -> message = text }
                    else -> if (books.isEmpty()) {
                        EmptyLibrary(onImport = onImport)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(books, key = { it.id }) { book ->
                                BookRow(book = book, onClick = {
                                    openBook(scope, db, book) { text -> message = text }
                                })
                            }
                        }
                    }
                }

                message?.let { text ->
                    Text(
                        text = text,
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Panel)
                            .clickable { message = null }
                            .padding(16.dp),
                    )
                }

                // One now-playing bar for every sound in the app (audiobook or
                // podcast): both flow through the single shared audio owner.
                NowPlayingBar()
            }
        }
    }
}

@Composable
private fun Header(count: Int, onImport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).background(Accent, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("B", color = Bg, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "BOOKRIO",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text("$count bøker", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        TextButton(onClick = onImport) {
            Text("IMPORTER", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TabBar(tab: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabButton("BØKER", selected = tab == 0) { onSelect(0) }
        TabButton("PODKASTER", selected = tab == 1) { onSelect(1) }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) Accent else Dim,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
        )
    }
}

/**
 * Podcasts inside the same Compose shell: subscriptions, an RSS-URL field,
 * episode lists and foreground pull-to-refresh. Playback always goes through the
 * one shared now-playing bar ([PodcastPlayback] -> [AudioPlayers.shared]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodcastsScreen(db: ShelfDatabase, scope: CoroutineScope, onMessage: (String) -> Unit) {
    val repository = remember { PodcastRepository(db) }
    val feeds by remember { db.podcastFeedDao().observeSummaries() }
        .collectAsState(initial = emptyList<PodcastFeedSummary>())
    var selectedFeedId by remember { mutableStateOf<Long?>(null) }
    var url by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    val feedId = selectedFeedId
    if (feedId != null) {
        EpisodesPane(
            db = db,
            scope = scope,
            repository = repository,
            feedId = feedId,
            feed = feeds.firstOrNull { it.feedId == feedId },
            onBack = { selectedFeedId = null },
            onMessage = onMessage,
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RssInput(
            value = url,
            onValueChange = { url = it },
            onSubmit = {
                val candidate = url.trim()
                if (candidate.isNotBlank()) {
                    scope.launch {
                        refreshing = true
                        val result = repository.subscribe(candidate)
                        refreshing = false
                        if (result.isSuccess) {
                            url = ""
                            onMessage("Abonnerte på feeden.")
                        } else {
                            onMessage("Kunne ikke abonnere: ${result.exceptionOrNull()?.message ?: "feil"}")
                        }
                    }
                }
            },
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    val updated = repository.refreshAll()
                    refreshing = false
                    onMessage("Oppdaterte $updated feed(er).")
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (feeds.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Ingen podkaster ennå",
                        color = Fg,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Lim inn en RSS-URL over for å abonnere. Dra ned for å oppdatere.",
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(feeds, key = { it.feedId }) { feed ->
                        FeedRow(feed = feed, onClick = { selectedFeedId = feed.feedId })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodesPane(
    db: ShelfDatabase,
    scope: CoroutineScope,
    repository: PodcastRepository,
    feedId: Long,
    feed: PodcastFeedSummary?,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val episodes by remember(feedId) { db.podcastEpisodeDao().observeByFeed(feedId) }
        .collectAsState(initial = emptyList<PodcastEpisodeEntity>())
    var refreshing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("‹ TILBAKE", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Text(
                feed?.title ?: "Podkast",
                color = Fg,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    val result = repository.refresh(feedId)
                    refreshing = false
                    if (result.isFailure) onMessage("Kunne ikke oppdatere feeden.")
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(episodes, key = { it.id }) { episode ->
                    EpisodeRow(episode = episode, onClick = {
                        scope.launch {
                            val start = PodcastPlayback.resumePosition(db, episode.id)
                            PodcastPlayback.play(db, scope, episode, start)
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun RssInput(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f)
                .border(1.dp, Hairline, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text("RSS-URL", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onSubmit) {
            Text("LEGG TIL", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FeedRow(feed: PodcastFeedSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(width = 34.dp, height = 34.dp)
                .background(Panel, RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("P", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(feed.title, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
            Text(
                "${feed.episodeCount} episoder · ${feed.unplayedCount} uavspilte",
                color = Dim,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
private fun EpisodeRow(episode: PodcastEpisodeEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(episode.title, color = Fg, fontSize = 14.sp, maxLines = 2)
            Text(
                formatPlaybackTime(episode.durationMs ?: 0L),
                color = Dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Text("▶", color = Accent, fontSize = 14.sp)
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Ingen bøker ennå",
            color = Fg,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Importer en PDF for å prøve Apple sin innebygde sidekrøll " +
                "(UIPageViewController .pageCurl).",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onImport) {
            Text("IMPORTER BOK", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BookRow(book: BookEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(width = 34.dp, height = 46.dp)
                .background(Panel, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                book.format.name.take(3),
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                book.title,
                color = Fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            if (book.author.isNotBlank()) {
                Text(book.author, color = Dim, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

/**
 * CI/demo helper: if [demoPath] is set, import it (once) and open it, mirroring
 * BOOKRIO_AUTO_OPEN_PDF / BOOKRIO_AUTO_OPEN_EPUB in the simulator smoke test.
 */
private fun openDemoPath(
    database: ShelfDatabase,
    scope: CoroutineScope,
    demoPath: String?,
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
            openBook(scope, database, book, onMessage)
        }.onFailure { t ->
            println("[bookrio-smoke] auto-open failed: ${t.message}")
        }
    }
}

private fun openBook(
    scope: CoroutineScope,
    database: ShelfDatabase,
    book: BookEntity,
    onMessage: (String) -> Unit,
) {
    val path = book.filePath
    if (path.isNullOrBlank()) {
        onMessage("Boken mangler en lesbar fil på denne enheten.")
        return
    }
    when {
        book.format == FormatEntity.PDF || book.format == FormatEntity.EPUB -> scope.launch {
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
        isAudioFormat(book.format) -> scope.launch {
            runCatching { database.bookDao().update(book.copy(lastOpenedAt = nowMillis())) }
            val tracks = runCatching { database.audioTrackDao().getTracksForBook(book.id) }
                .getOrDefault(emptyList())
            val start = AudiobookPlayback.resumePosition(database, book.id)
            AudiobookPlayback.play(database, scope, book, tracks, start)
            println("[bookrio-smoke] audioPlay=true format=${book.format} path=$path")
        }
        else -> onMessage("iOS-leseren støtter PDF, EPUB og lydbøker nå — ${book.format} kommer senere.")
    }
}

private fun isAudioFormat(format: FormatEntity): Boolean = when (format) {
    FormatEntity.M4B, FormatEntity.M4A, FormatEntity.MP3, FormatEntity.AAC,
    FormatEntity.FLAC, FormatEntity.OGG, FormatEntity.OGG_OPUS, FormatEntity.WAV,
    -> true
    else -> false
}

/**
 * The single now-playing bar. Every sound (audiobook or podcast) plays through
 * [AudioPlayers.shared], so there is never more than one of these.
 */
@Composable
private fun NowPlayingBar() {
    val player = remember { AudioPlayers.shared }
    val state by player.state.collectAsState()
    val request = state.request ?: return
    val chapters by AudiobookPlayback.chapters.collectAsState()
    val chapterIndex by AudiobookPlayback.currentChapterIndex.collectAsState()
    val duration = state.durationMs

    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (duration > 0L) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        val chapter = chapters.getOrNull(chapterIndex)
        val subtitle = when {
            request.owner == AudioOwner.AUDIOBOOK && chapter != null -> chapter.title
            request.artist != null -> request.artist
            else -> request.owner.name.lowercase()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    request.title,
                    color = Fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    "$subtitle · ${formatPlaybackTime(state.positionMs)} / ${formatPlaybackTime(duration)}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            TextButton(onClick = { player.toggle() }) {
                Text(
                    if (state.isPlaying) "PAUSE" else "SPILL",
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = { player.stop() }) {
                Text("LUKK", color = Dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        if (duration > 0L) {
            Slider(
                value = fraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { f -> player.seekTo((f * duration).toLong()) }
                    dragFraction = null
                },
            )
        }
        state.error?.let { error ->
            Text(
                error,
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

private fun formatPlaybackTime(ms: Long): String {
    val safe = if (ms > 0L) ms else 0L
    val totalSeconds = safe / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val mm = if (minutes < 10L) "0$minutes" else "$minutes"
    val ss = if (seconds < 10L) "0$seconds" else "$seconds"
    return "$mm:$ss"
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