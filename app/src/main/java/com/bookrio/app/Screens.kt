package com.bookrio.app

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookrio.R
import com.bookrio.app.ui.LanguagePickerSheet
import com.bookrio.data.prefs.UserPreferencesRepository
import androidx.work.WorkManager
import com.bookrio.app.workers.ImportWorker
import com.bookrio.data.local.entity.ImportSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.bookrio.designsystem.components.BookCoverCard
import com.bookrio.designsystem.components.BookFormat
import com.bookrio.designsystem.components.BookVisual
import com.bookrio.designsystem.theme.ShelfColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.library.ui.SampleBooks
import java.net.HttpURLConnection
import java.net.URL

private val GENERIC_CHAPTER_TITLE =
    Regex("^(kapittel|kapitel|chapter)\\s*\\d+$", RegexOption.IGNORE_CASE)

private fun spineColorFor(seed: String): Color {
    val palette = listOf(
        ShelfColors.SpineBurgundy,
        ShelfColors.SpineNavy,
        ShelfColors.SpineForest,
        ShelfColors.SpineSienna,
        ShelfColors.SpineSlate,
        ShelfColors.SpineDustyRose,
        ShelfColors.SpineMustard,
        ShelfColors.SpineTeal,
        ShelfColors.SpinePlum,
        ShelfColors.SpineRust
    )
    return palette[seed.hashCode().mod(palette.size)]
}

private fun formatEntityToUiFormat(entity: com.bookrio.data.local.entity.FormatEntity): BookFormat =
    try {
        BookFormat.valueOf(entity.name)
    } catch (_: Exception) {
        BookFormat.UNKNOWN
    }

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatDuration(ms: Long, ctx: Context): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) ctx.getString(R.string.app_dur_hm, hours, minutes)
    else if (minutes > 0) ctx.getString(R.string.app_dur_ms, minutes, seconds)
    else ctx.getString(R.string.app_dur_s, seconds)
}

private fun formatRemaining(ms: Long, pct: Float, ctx: Context): String {
    val remainingMs = ((1f - pct.coerceIn(0f, 1f)) * ms).toLong()
    return formatDuration(remainingMs, ctx)
}

private fun parseChapters(json: String?, ctx: Context): List<String> {
    if (json == null || json.isBlank()) return emptyList()
    return try {
        val result = mutableListOf<String>()
        val array = org.json.JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            if (item is org.json.JSONObject) {
                val raw = item.optString("title").takeIf { it.isNotBlank() }
                    ?: item.optString("name").takeIf { it.isNotBlank() }
                // Re-localize generic placeholders stored by older imports ("Kapittel 3").
                val t = if (raw == null || GENERIC_CHAPTER_TITLE.matches(raw)) {
                    ctx.getString(R.string.app_chapter_n, i + 1)
                } else raw
                result.add(t)
            } else if (item != null) {
                val s = item.toString().trim()
                if (s.isNotBlank()) result.add(s)
            }
        }
        result
    } catch (_: Exception) {
        json.split("\",\"")
            .map { it.replace("\"", "").replace("[", "").replace("]", "").trim() }
            .filter { it.isNotBlank() }
    }
}

private fun dbgUi(location: String, hypothesisId: String, msg: String, data: String) {
    Thread {
        try {
            val safeMsg = msg.replace("\\", "/").replace("\"", "'").replace("\n", " ")
            val safeData = data.replace("\\", "/").replace("\"", "'").replace("\n", " ")
            val body = """{"sessionId":"ebook-audio-crash","runId":"pre-fix","hypothesisId":"$hypothesisId","location":"$location","msg":"[DEBUG] $safeMsg","data":{"info":"$safeData"},"ts":${System.currentTimeMillis()}}"""
            val conn = (URL("http://192.168.1.10:7777/event").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            runCatching { conn.inputStream.close() }
            conn.disconnect()
        } catch (_: Throwable) {
        }
    }.start()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailsScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenReader: (Long) -> Unit,
    onOpenPlayer: (Long) -> Unit,
    onOpenBookmark: (Long, Float) -> Unit = { _, _ -> },
    onDeleted: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val db = remember { com.bookrio.data.local.ShelfDatabase.getInstance(ctx) }
    val bookData by produceState<Pair<com.bookrio.data.local.entity.BookEntity?, com.bookrio.data.local.entity.ReadingProgressEntity?>>(
        initialValue = null to null,
        key1 = bookId
    ) {
        value = (db.bookDao().getById(bookId) to db.progressDao().getByBook(bookId))
    }

    val bookmarks by produceState<List<com.bookrio.data.local.entity.BookmarkEntity>>(
        initialValue = emptyList(),
        key1 = bookId
    ) {
        db.bookmarkDao().observeByBook(bookId).collect { value = it }
    }
    val highlights by produceState<List<com.bookrio.data.local.entity.HighlightEntity>>(
        initialValue = emptyList(),
        key1 = bookId
    ) {
        db.highlightDao().observeByBook(bookId).collect { value = it }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCoverDialog by remember { mutableStateOf(false) }
    var isDescriptionExpanded by remember { mutableStateOf(false) }
    var chaptersExpanded by remember { mutableStateOf(true) }
    var bookmarksExpanded by remember { mutableStateOf(true) }
    var highlightsExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val book = bookData.first
    val progress = bookData.second
    val metaCleaned = remember(book?.title, book?.author, book?.fileUri, book?.filePath) {
        com.bookrio.core.parse.MetadataCleaner.clean(
            rawTitle = book?.title,
            rawAuthor = book?.author,
            filename = book?.filePath ?: book?.fileUri
        )
    }
    val pct = progress?.progressPercent ?: 0f
    val isAudio = book?.type == com.bookrio.data.local.entity.BookTypeEntity.AUDIOBOOK ||
            book?.type == com.bookrio.data.local.entity.BookTypeEntity.MIXED
    val chapters = remember(book?.chaptersJson) { parseChapters(book?.chaptersJson, ctx) }

    LaunchedEffect(book?.id, book?.fileUri, book?.filePath, book?.persistableUriPermission) {
        val b = book ?: return@LaunchedEffect
        // #region debug-point UI:book-details-loaded
        dbgUi(
            location = "BookDetailsScreen",
            hypothesisId = "B",
            msg = "book-details-loaded",
            data = "bookId=${b.id} type=${b.type} format=${b.format} fileUri=${b.fileUri ?: ""} filePath=${b.filePath ?: ""} persistable=${b.persistableUriPermission}"
        )
        // #endregion
    }

    fun buildBookVisual(): BookVisual {
        val b = book ?: return BookVisual(
            id = bookId,
            title = ctx.getString(R.string.loading),
            author = "",
            spineColor = ShelfColors.SpineSlate,
            format = BookFormat.EPUB,
            progress = pct
        )
        val spineColor = b.spineColor?.let { Color(it) }
            ?: spineColorFor("${b.title}|${b.author}")
        return BookVisual(
            id = b.id,
            title = b.title,
            author = b.author,
            spineColor = spineColor,
            coverImagePath = b.coverPath,
            format = formatEntityToUiFormat(b.format),
            progress = pct
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.action_add_to_shelf)) }
                    }) { Icon(Icons.AutoMirrored.Filled.Label, null) }
                    IconButton(onClick = {
                        val b = book ?: return@IconButton
                        val title = b.title
                        val author = b.author
                        val filePath = b.filePath
                        val body = ctx.getString(R.string.book_share_body, title, author)
                        val file = filePath?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.canRead() }
                        if (file != null) {
                            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                file
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "*/*"
                                putExtra(android.content.Intent.EXTRA_TITLE, title)
                                putExtra(android.content.Intent.EXTRA_TEXT, body)
                                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(intent, null))
                        } else {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TITLE, title)
                                putExtra(android.content.Intent.EXTRA_TEXT, body)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(intent, null))
                        }
                    }) { Icon(Icons.Default.Share, null) }
                    IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.Delete, null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                BookCoverCard(
                    book = buildBookVisual(),
                    onClick = {
                        showCoverDialog = true
                    },
                    onLongClick = {
                        val b = book ?: return@BookCoverCard
                        val title = b.title
                        val author = b.author
                        val filePath = b.filePath
                        val file = filePath?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.canRead() }
                        if (file != null) {
                            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                file
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "*/*"
                                putExtra(android.content.Intent.EXTRA_TITLE, title)
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "\"$title\" – $author\n(hentet via Bookiro)"
                                )
                                putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(intent, null))
                        } else {
                            scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.book_details_share_snack, title)) }
                        }
                    },
                    modifier = Modifier
                        .width(140.dp)
                        .aspectRatio(1f / 1.55f)
                )
                Spacer(Modifier.width(18.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(top = 8.dp)
                ) {
                    Text(
                        book?.let { metaCleaned.title } ?: stringResource(R.string.loading),
                        style = ShelfTypography.HeadlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        metaCleaned.author,
                        style = ShelfTypography.TitleMedium,
                        color = com.bookrio.designsystem.theme.OmarchyColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    book?.type?.let { type ->
                        if (type == com.bookrio.data.local.entity.BookTypeEntity.AUDIOBOOK ||
                            type == com.bookrio.data.local.entity.BookTypeEntity.MIXED
                        ) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.book_details_narrator),
                                style = ShelfTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    AssistChip(
                        onClick = {
                            scope.launch {
                                val formatName = book?.format?.name ?: ctx.getString(R.string.book_details_unknown)
                                val typeName = book?.type?.name ?: ctx.getString(R.string.book_details_unknown)
                                val fileSize = book?.fileSizeBytes?.takeIf { it > 0 }?.let { formatBytes(it) } ?: "—"
                                snackbarHostState.showSnackbar(
                                    ctx.getString(R.string.book_details_info, formatName, typeName, fileSize)
                                )
                            }
                        },
                        label = { Text(book?.format?.name ?: "—") },
                        leadingIcon = {
                            Icon(
                                if (isAudio) Icons.Default.Audiotrack else Icons.Default.Book,
                                null, Modifier.size(16.dp)
                            )
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    if (pct > 0f) {
                        Text(
                            stringResource(R.string.book_details_percent_read, (pct * 100).toInt()),
                            style = ShelfTypography.LabelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { pct.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = ShelfColors.ProgressFill
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (book?.type != com.bookrio.data.local.entity.BookTypeEntity.AUDIOBOOK) {
                    FilledTonalButton(
                        onClick = {
                            // #region debug-point UI:reader-tap
                            dbgUi(
                                location = "BookDetailsScreen",
                                hypothesisId = "B",
                                msg = "tap-open-reader",
                                data = "bookId=$bookId type=${book?.type} format=${book?.format} fileUri=${book?.fileUri ?: ""} filePath=${book?.filePath ?: ""} persistable=${book?.persistableUriPermission}"
                            )
                            // #endregion
                            onOpenReader(bookId)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pct > 0f) stringResource(R.string.book_details_continue_reading) else stringResource(R.string.book_details_read_now),
                            style = ShelfTypography.LabelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (isAudio) {
                    FilledTonalButton(
                        onClick = {
                            // #region debug-point UI:player-tap
                            dbgUi(
                                location = "BookDetailsScreen",
                                hypothesisId = "E",
                                msg = "tap-open-player",
                                data = "bookId=$bookId type=${book?.type} format=${book?.format} fileUri=${book?.fileUri ?: ""} filePath=${book?.filePath ?: ""}"
                            )
                            // #endregion
                            onOpenPlayer(bookId)
                        },
                        modifier = if (book?.type == com.bookrio.data.local.entity.BookTypeEntity.AUDIOBOOK)
                            Modifier.weight(1f) else Modifier,
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pct > 0f) stringResource(R.string.action_continue) else stringResource(R.string.book_details_listen_now),
                            style = ShelfTypography.LabelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.padding(16.dp)) {
                    val infoRows = mutableListOf<Pair<String, String>>()
                    book?.format?.let { infoRows.add(stringResource(R.string.book_details_format) to it.name) }
                    book?.fileSizeBytes?.takeIf { it > 0 }?.let { infoRows.add(stringResource(R.string.book_details_file_size) to formatBytes(it)) }
                    book?.importSource?.let { infoRows.add(stringResource(R.string.book_details_import_source) to it.name) }
                    book?.pageCount?.let { infoRows.add(stringResource(R.string.book_details_pages_label) to stringResource(R.string.book_details_pages, it)) }
                    book?.durationMs?.let { infoRows.add(stringResource(R.string.book_details_duration) to formatDuration(it, ctx)) }
                    if (pct > 0f) {
                        book?.durationMs?.let {
                            infoRows.add(stringResource(R.string.book_details_remaining) to formatRemaining(it, pct, ctx))
                        } ?: book?.pageCount?.let { pc ->
                            val pagesLeft = ((1f - pct) * pc).toInt().coerceAtLeast(0)
                            infoRows.add(stringResource(R.string.book_details_remaining) to stringResource(R.string.book_details_pages_left, pagesLeft))
                        }
                    }
                    infoRows.forEachIndexed { i, (k, v) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                k,
                                style = ShelfTypography.BodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(v, style = ShelfTypography.BodyMedium)
                        }
                        if (i < infoRows.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            book?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.book_details_about), style = ShelfTypography.TitleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            desc,
                            style = ShelfTypography.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (desc.length > 180) {
                            TextButton(
                                onClick = { isDescriptionExpanded = !isDescriptionExpanded },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    if (isDescriptionExpanded) stringResource(R.string.book_details_show_less) else stringResource(R.string.book_details_read_more),
                                    color = com.bookrio.designsystem.theme.OmarchyColors.Accent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                ElevatedCard(
                    onClick = { chaptersExpanded = !chaptersExpanded },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.book_details_chapters),
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (chapters.isEmpty()) "0" else "${chapters.size}",
                            style = ShelfTypography.LabelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (chaptersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null
                        )
                    }
                    if (chaptersExpanded) {
                        if (chapters.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    stringResource(R.string.book_details_no_chapter_info),
                                    style = ShelfTypography.BodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                            ) {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    items(chapters.size) { idx ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${idx + 1}.",
                                                style = ShelfTypography.LabelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(32.dp)
                                            )
                                            Text(
                                                chapters[idx],
                                                style = ShelfTypography.BodyMedium,
                                                maxLines = 2,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (idx < chapters.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                ElevatedCard(
                    onClick = { bookmarksExpanded = !bookmarksExpanded },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bookmark, null, tint = com.bookrio.designsystem.theme.OmarchyColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.book_details_bookmarks),
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${bookmarks.size}",
                            style = ShelfTypography.LabelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (bookmarksExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null
                        )
                    }
                    if (bookmarksExpanded) {
                        if (bookmarks.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    stringResource(R.string.empty_no_bookmarks),
                                    style = ShelfTypography.BodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                            ) {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    items(bookmarks.size) { idx ->
                                        val bm = bookmarks[idx]
                                        val pct = bm.positionPercent ?: 0f
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val p = bm.positionPercent ?: 0f
                                                    onOpenBookmark(bookId, p)
                                                }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${(pct * 100).toInt()}%",
                                                style = ShelfTypography.LabelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.width(52.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    bm.title ?: stringResource(R.string.book_details_bookmark_n, idx + 1),
                                                    style = ShelfTypography.BodyMedium,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                bm.snippet?.takeIf { it.isNotBlank() }?.let {
                                                    Text(
                                                        it,
                                                        style = ShelfTypography.BodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            IconButton(onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    runCatching { db.bookmarkDao().deleteById(bm.id) }
                                                    snackbarHostState.showSnackbar(ctx.getString(R.string.book_details_bookmark_deleted))
                                                }
                                            }) {
                                                Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                                            }
                                        }
                                        if (idx < bookmarks.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                ElevatedCard(
                    onClick = { highlightsExpanded = !highlightsExpanded },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BorderColor, null, tint = com.bookrio.designsystem.theme.OmarchyColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.book_details_highlights),
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${highlights.size}",
                            style = ShelfTypography.LabelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (highlightsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null
                        )
                    }
                    if (highlightsExpanded) {
                        if (highlights.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    stringResource(R.string.empty_no_highlights),
                                    style = ShelfTypography.BodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                            ) {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 8.dp)
                                ) {
                                    items(highlights.size) { idx ->
                                        val hl = highlights[idx]
                                        val pct = hl.positionPercent ?: 0f
                                        val hlColor = hl.color?.let { Color(it) }
                                            ?: Color(0xFFFFF3B0)
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    Modifier
                                                        .size(12.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(hlColor)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "${(pct * 100).toInt()}%",
                                                    style = ShelfTypography.LabelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.weight(1f))
                                                IconButton(onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        runCatching { db.highlightDao().deleteById(hl.id) }
                                                        snackbarHostState.showSnackbar(ctx.getString(R.string.book_details_highlight_deleted))
                                                    }
                                                }) {
                                                    Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                hl.text,
                                                style = ShelfTypography.BodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        hlColor.copy(alpha = 0.18f),
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(8.dp)
                                            )
                                            hl.note?.takeIf { it.isNotBlank() }?.let { note ->
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    note,
                                                    style = ShelfTypography.BodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (idx < highlights.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.book_details_actions), style = ShelfTypography.TitleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.book_details_add_to_shelf)) } },
                        label = { Text(stringResource(R.string.book_details_add_to_shelf)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = { scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.book_details_marked_finished)) } },
                        label = { Text(stringResource(R.string.action_mark_finished)) },
                        leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.book_details_change_cover_snack)) } },
                        label = { Text(stringResource(R.string.action_change_cover)) },
                        leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = { showDeleteDialog = true },
                        label = { Text(stringResource(R.string.book_details_delete_title)) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp)) }
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showCoverDialog) {
        AlertDialog(
            onDismissRequest = { showCoverDialog = false },
            confirmButton = {
                TextButton(onClick = { showCoverDialog = false }) { Text(stringResource(R.string.action_close)) }
            },
            title = { Text(book?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.book_details_cover_title)) },
            text = {
                val coverPath = book?.coverPath
                if (coverPath != null && java.io.File(coverPath).exists()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f / 1.55f)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        coil.compose.AsyncImage(
                            model = coil.request.ImageRequest.Builder(LocalContext.current)
                                .data(java.io.File(coverPath))
                                .memoryCacheKey(coverPath)
                                .diskCacheKey(coverPath)
                                .crossfade(true)
                                .build(),
                            contentDescription = stringResource(R.string.book_details_cover_desc, book?.title.orEmpty()),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f / 1.55f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ShelfColors.SpineNavy),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AutoStories, null, tint = Color.White, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.book_details_no_cover),
                                color = Color.White,
                                style = ShelfTypography.BodyMedium
                            )
                        }
                    }
                }
            }
        )
    }

    if (showDeleteDialog && book != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        val db = com.bookrio.data.local.ShelfDatabase.getInstance(ctx)
                        db.progressDao().deleteByBook(bookId)
                        db.bookDao().delete(book)
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            title = { Text(stringResource(R.string.book_details_delete_title)) },
            text = {
                Text(stringResource(R.string.book_details_delete_confirm, book.title))
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onFtpClick: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    val pickMultipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        // #region debug-point UI:file-picker-result
        dbgUi(
            location = "ImportScreen",
            hypothesisId = "A",
            msg = "file-picker-result",
            data = "uriCount=${uris.size} first=${uris.firstOrNull()?.toString() ?: ""}"
        )
        // #endregion
        if (uris.isNotEmpty()) {
            ImportWorker.enqueueUris(
                WorkManager.getInstance(ctx),
                uris.map { it.toString() },
                ImportSourceEntity.FILE_PICKER
            )
            snackbarScope.launch {
                snackbarHostState.showSnackbar(ctx.getString(R.string.books_sent_to_import, uris.size))
            }
        }
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { tree: Uri? ->
        // #region debug-point UI:folder-picker-result
        dbgUi(
            location = "ImportScreen",
            hypothesisId = "A",
            msg = "folder-picker-result",
            data = "treeUri=${tree?.toString() ?: ""} persistedBefore=${ctx.contentResolver.persistedUriPermissions.size}"
        )
        // #endregion
        if (tree != null) {
            ctx.contentResolver.takePersistableUriPermission(
                tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            ImportWorker.enqueueFolder(WorkManager.getInstance(ctx), tree.toString())
            snackbarScope.launch {
                snackbarHostState.showSnackbar(ctx.getString(R.string.import_from_folder_started))
            }
        }
    }

    fun launchSamples() {
        ImportWorker.enqueueSamples(WorkManager.getInstance(ctx))
        snackbarScope.launch {
            snackbarHostState.showSnackbar(ctx.getString(R.string.samples_loading_started))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title), style = ShelfTypography.HeadlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImportCard(
                title = stringResource(R.string.import_files),
                subtitle = stringResource(R.string.import_files_sub),
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                color = com.bookrio.designsystem.theme.OmarchyColors.Accent,
                onClick = {
                    // #region debug-point UI:file-picker-launch
                    dbgUi(
                        location = "ImportScreen",
                        hypothesisId = "A",
                        msg = "file-picker-launch",
                        data = "mimeCount=${allImportMimeTypes().size}"
                    )
                    // #endregion
                    pickMultipleLauncher.launch(allImportMimeTypes())
                }
            )
            ImportCard(
                title = stringResource(R.string.import_folder),
                subtitle = stringResource(R.string.import_folder_sub),
                icon = Icons.Default.Folder,
                color = com.bookrio.designsystem.theme.OmarchyColors.Accent,
                onClick = {
                    // #region debug-point UI:folder-picker-launch
                    dbgUi(
                        location = "ImportScreen",
                        hypothesisId = "A",
                        msg = "folder-picker-launch",
                        data = "persistedBefore=${ctx.contentResolver.persistedUriPermissions.size}"
                    )
                    // #endregion
                    openFolderLauncher.launch(null)
                }
            )
            ImportCard(
                title = stringResource(R.string.import_sample),
                subtitle = stringResource(R.string.import_sample_sub),
                icon = Icons.Default.AutoAwesome,
                color = com.bookrio.designsystem.theme.OmarchyColors.Accent,
                onClick = {
                    // #region debug-point UI:samples-launch
                    dbgUi(
                        location = "ImportScreen",
                        hypothesisId = "A",
                        msg = "sample-import-launch",
                        data = "source=samples"
                    )
                    // #endregion
                    launchSamples()
                }
            )
            ImportCard(
                title = stringResource(R.string.import_from_ftp),
                subtitle = stringResource(R.string.import_from_ftp_sub),
                icon = Icons.Default.CloudDownload,
                color = com.bookrio.designsystem.theme.OmarchyColors.Accent,
                onClick = {
                    // #region debug-point UI:ftp-launch
                    dbgUi(
                        location = "ImportScreen",
                        hypothesisId = "A",
                        msg = "ftp-import-launch",
                        data = "source=ftp"
                    )
                    // #endregion
                    onFtpClick()
                }
            )

            Spacer(Modifier.height(16.dp))

            ElevatedCard(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.import_supported_formats),
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.import_formats_list),
                        style = ShelfTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun allImportMimeTypes(): Array<String> = arrayOf(
    "application/epub+zip",
    "application/pdf",
    "application/x-mobipocket-ebook",
    "application/x-fictionbook+xml",
    "application/vnd.comicbook+zip",
    "application/vnd.comicbook-rar",
    "application/zip",
    "text/plain",
    "text/markdown",
    "text/html",
    "text/rtf",
    "application/rtf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/msword",
    "audio/m4b",
    "audio/x-m4b",
    "audio/mp4",
    "audio/mpeg",
    "audio/x-mp3",
    "audio/aac",
    "audio/x-aac",
    "audio/flac",
    "audio/x-flac",
    "audio/ogg",
    "audio/x-ogg",
    "audio/opus",
    "audio/x-opus",
    "audio/wav",
    "audio/x-wav",
    "application/vnd.amazon.mobi8-ebook",
    "application/vnd.amazon.ebook",
    "*/*"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0.15f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = ShelfTypography.TitleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}


@Composable
fun OnboardingScreen(
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { com.bookrio.data.prefs.UserPreferencesRepository(ctx) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    Surface(color = com.bookrio.designsystem.theme.OmarchyColors.Bg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 1px CRT-bezel-ramme rundt innholdet
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, com.bookrio.designsystem.theme.OmarchyColors.Hairline)
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Brand mark + wordmark
                Image(
                    painter = painterResource(com.bookrio.designsystem.R.drawable.bookrio_mark),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(96.dp)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "BOOKRIO",
                    style = ShelfTypography.TitleLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = 8.sp,
                    color = com.bookrio.designsystem.theme.OmarchyColors.Accent
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    stringResource(R.string.onboarding_title),
                    style = ShelfTypography.TitleLarge,
                    fontWeight = FontWeight.Bold,
                    color = com.bookrio.designsystem.theme.OmarchyColors.FgBright,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.onboarding_body),
                    style = ShelfTypography.BodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = com.bookrio.designsystem.theme.OmarchyColors.Dim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(48.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            prefs.setHasSeenOnboarding(true)
                            onDone()
                        }
                    },
                    shape = RoundedCornerShape(0.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, com.bookrio.designsystem.theme.OmarchyColors.Accent),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = com.bookrio.designsystem.theme.OmarchyColors.Accent
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        stringResource(R.string.onboarding_cta),
                        style = ShelfTypography.LabelLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
            }
        }

            // Stille språkhandling øverst til høyre — åpner samme egennavn-velger som Innstillinger.
            TextButton(
                onClick = { showLanguagePicker = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(10.dp)
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = com.bookrio.designsystem.theme.OmarchyColors.Dim,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.welcome_language_label),
                    style = ShelfTypography.LabelMedium,
                    color = com.bookrio.designsystem.theme.OmarchyColors.Dim,
                    maxLines = 1
                )
            }
        }
    }
    if (showLanguagePicker) {
        LanguagePickerSheet(onDismiss = { showLanguagePicker = false })
    }
}
