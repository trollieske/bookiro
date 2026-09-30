package com.bookrio.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookrio.core.domain.model.LibrarySortMode
import com.bookrio.core.domain.model.LibraryViewType
import com.bookrio.core.domain.model.SortDirection
import com.bookrio.core.domain.model.defaultDirectionFor
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTheme
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.library.sort.ResumeSelector
import com.bookrio.shared.platform.AppPrefs
import com.bookrio.shared.platform.PrefKeys
import com.bookrio.shared.platform.appDatabase

private val LibBg = OmarchyColors.Bg
private val LibHeaderBg = OmarchyColors.HeaderBg
private val LibHairline = OmarchyColors.Hairline
private val LibAccent = OmarchyColors.Accent
private val LibDim = OmarchyColors.Dim
private val LibFg = OmarchyColors.Fg
private val LibFgBright = OmarchyColors.FgBright
private val LibPanel = OmarchyColors.Panel

/**
 * iOS-parity library screen (Books / Audiobooks) — the common-Kotlin port of the
 * Android `com.bookrio.library.ui.LibraryScreen` chrome, fed by the shared Room DB.
 *
 * Layout matches the Android spec 1:1: flat black header band with the Bookrio
 * mark + tab title, grid/list toggle, search expander, import + settings actions,
 * persisted sort rail + direction, live tab count badge, the thin resume strip,
 * the adaptive cover grid and the list rows.
 *
 * Deliberately omitted vs. Android: the Sources/FTP `CloudSync` button and the
 * empty-state "sync from FTP/seedbox" action — sources are not implemented on iOS.
 *
 * @param mode              which shelf to show (ebooks or audiobooks).
 * @param onBookClick       play/open a book (Books tab).
 * @param onAudiobookClick  play/open an audiobook (Audiobooks tab).
 * @param onImportClick     import button.
 * @param onSettingsClick   settings button.
 * @param onBookLongClick   long-press → book details.
 * @param onNavVisibilityChange scroll direction signal for the shell's bottom
 *                          navigation (false = hide on scroll down, true = show).
 */
@Composable
fun BookrioLibraryScreen(
    mode: BookrioLibraryMode,
    onBookClick: (Long) -> Unit,
    onAudiobookClick: (Long) -> Unit,
    onImportClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBookLongClick: (Long) -> Unit = {},
    onNavVisibilityChange: (Boolean) -> Unit = {}
) {
    // HUD theme, locked dark — the same Material colours the Android shell provides.
    ShelfTheme(darkTheme = true) {
        LibraryChrome(
            mode = mode,
            onBookClick = onBookClick,
            onAudiobookClick = onAudiobookClick,
            onBookLongClick = onBookLongClick,
            onImportClick = onImportClick,
            onSettingsClick = onSettingsClick,
            onNavVisibilityChange = onNavVisibilityChange
        )
    }
}

@Composable
private fun LibraryChrome(
    mode: BookrioLibraryMode,
    onBookClick: (Long) -> Unit,
    onAudiobookClick: (Long) -> Unit,
    onBookLongClick: (Long) -> Unit,
    onImportClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavVisibilityChange: (Boolean) -> Unit
) {
    val db = remember { appDatabase() }
    // null = Room has not emitted yet → loading; empty list is a real empty library.
    val books: List<BookEntity>? by remember(db) { db.bookDao().observeAll() }
        .collectAsState(initial = null)
    val progress: List<ReadingProgressEntity> by remember(db) { db.progressDao().observeAll() }
        .collectAsState(initial = emptyList())

    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var resumeSheetVisible by rememberSaveable { mutableStateOf(false) }

    // Persisted view type ("grid" | "list"), shared by both shelves like Android.
    var viewType by remember { mutableStateOf(loadViewType()) }

    // Persisted sort per media tab (PrefKeys mirrors the Android preference surface).
    val sortModeKey = if (mode == BookrioLibraryMode.BOOKS) PrefKeys.BOOKS_SORT_MODE else PrefKeys.AUDIO_SORT_MODE
    val sortDirKey = if (mode == BookrioLibraryMode.BOOKS) PrefKeys.BOOKS_SORT_DIR else PrefKeys.AUDIO_SORT_DIR
    var sortMode by remember(mode) { mutableStateOf(LibrarySortMode.from(AppPrefs.getString(sortModeKey))) }
    var direction by remember(mode) { mutableStateOf(SortDirection.from(AppPrefs.getString(sortDirKey))) }
    val showTabCounts = remember(mode) { AppPrefs.getBoolean(PrefKeys.LIB_TAB_COUNTS, true) }

    val ui = remember(books, progress, mode, query, sortMode, direction) {
        buildBookrioLibraryState(
            books = books.orEmpty(),
            progress = progress,
            mode = mode,
            query = query,
            sortMode = sortMode,
            direction = direction
        )
    }
    val loading = books == null
    val onClickEntry: (Long) -> Unit =
        if (mode == BookrioLibraryMode.AUDIOBOOKS) onAudiobookClick else onBookClick
    val resumeItems = ui.resumeItems

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    LibraryScrollHideEffect(
        gridState = gridState,
        listState = listState,
        onVisibilityChange = onNavVisibilityChange
    )
    LaunchedEffect(ui.entries.isEmpty(), query, mode) {
        if (ui.entries.isEmpty() || query.isNotBlank()) onNavVisibilityChange(true)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(LibBg)
        ) {
            // Flat top band: title + minimal actions, no elevation, just a hairline.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(LibHeaderBg)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        BookrioMark()
                        Spacer(Modifier.width(8.dp))
                        Text(
                            libraryTitle(mode),
                            style = ShelfTypography.TitleLarge,
                            color = LibFgBright,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (showTabCounts && ui.tabCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            TabCountBadge(ui.tabCount)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LibAction(
                            icon = if (viewType == LibraryViewType.LIST) {
                                Icons.Default.GridView
                            } else {
                                Icons.AutoMirrored.Filled.ViewList
                            },
                            contentDescription = "Bytt rutenett eller liste",
                            tint = LibDim,
                            onClick = {
                                val next = if (viewType == LibraryViewType.LIST) {
                                    LibraryViewType.GRID
                                } else {
                                    LibraryViewType.LIST
                                }
                                viewType = next
                                AppPrefs.putString(PrefKeys.LIBRARY_VIEW, next.storageKey)
                            }
                        )
                        LibAction(
                            icon = Icons.Default.Search,
                            contentDescription = "Søk",
                            tint = if (query.isNotEmpty() || searchExpanded) LibAccent else LibDim,
                            onClick = { searchExpanded = !searchExpanded }
                        )
                        LibAction(
                            icon = Icons.Default.Add,
                            contentDescription = "Importer",
                            tint = LibFgBright,
                            onClick = onImportClick
                        )
                        LibAction(
                            icon = Icons.Default.Settings,
                            contentDescription = "Innstillinger",
                            tint = LibDim,
                            onClick = onSettingsClick
                        )
                    }
                }

                // Search: hidden icon that expands. Sharp corners, no chip row.
                AnimatedVisibility(visible = searchExpanded || query.isNotEmpty()) {
                    LibrarySearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClear = { query = "" }
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LibHairline)
            )

            // Sort rail: always visible above the grid, in both tabs.
            SortRail(
                activeMode = sortMode,
                direction = direction,
                onSelect = { selected ->
                    val naturalDirection = defaultDirectionFor(selected)
                    sortMode = selected
                    direction = naturalDirection
                    AppPrefs.putString(sortModeKey, selected.storage)
                    AppPrefs.putString(sortDirKey, naturalDirection.storage)
                },
                onToggleDirection = {
                    val next = if (direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                    direction = next
                    AppPrefs.putString(sortDirKey, next.storage)
                }
            )

            // Continue strip: terminal/waybar style, full width, never a card.
            if (resumeItems.isNotEmpty()) {
                val primary = resumeItems.first()
                ResumeStrip(
                    primary = primary,
                    extraCount = resumeItems.size - 1,
                    onClickPrimary = { onClickEntry(primary.bookId) },
                    onClickMore = { resumeSheetVisible = true }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LibDim)
                    }

                    ui.entries.isEmpty() -> EmptyLibrary(onImportClick = onImportClick)

                    viewType == LibraryViewType.LIST -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(ui.entries) { entry ->
                            when (entry) {
                                is BookrioLibraryEntry.SectionLabel -> SectionLabel(entry.text)
                                is BookrioLibraryEntry.BookEntry -> LibraryListRow(
                                    book = entry.book,
                                    onClick = { onClickEntry(entry.book.id) },
                                    onLongClick = { onBookLongClick(entry.book.id) }
                                )
                            }
                        }
                    }

                    else -> LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 115.dp),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            ui.entries,
                            span = { entry ->
                                GridItemSpan(
                                    if (entry is BookrioLibraryEntry.SectionLabel) maxLineSpan else 1
                                )
                            }
                        ) { entry ->
                            when (entry) {
                                is BookrioLibraryEntry.SectionLabel -> SectionLabel(entry.text)
                                is BookrioLibraryEntry.BookEntry -> LibraryCoverCard(
                                    book = entry.book,
                                    onClick = { onClickEntry(entry.book.id) },
                                    onLongClick = { onBookLongClick(entry.book.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // +N: simple dark bottom sheet with the active continue candidates.
        if (resumeSheetVisible && resumeItems.size > 1) {
            ResumeSheet(
                items = resumeItems.take(ResumeSelector.MAX_CANDIDATES),
                onDismiss = { resumeSheetVisible = false },
                onSelect = { bookId ->
                    resumeSheetVisible = false
                    onClickEntry(bookId)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResumeSheet(
    items: List<BookrioResumeItem>,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LibPanel,
        contentColor = LibFg,
        tonalElevation = 0.dp
    ) {
        Text(
            "Fortsett",
            style = ShelfTypography.TitleMedium,
            color = LibFgBright,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        items.forEach { item ->
            ResumeSheetRow(item = item, onClick = { onSelect(item.bookId) })
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Compact terminal/HUD selector above the grid. No Material chips. */
@Composable
private fun SortRail(
    activeMode: LibrarySortMode,
    direction: SortDirection,
    onSelect: (LibrarySortMode) -> Unit,
    onToggleDirection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LibBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        LibrarySortMode.entries.forEach { mode ->
            val active = mode == activeMode
            Column(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    mode.label,
                    color = if (active) LibAccent else LibDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.drawBehind {
                        // Active mode: thin lime underline, as wide as the label.
                        if (active) {
                            drawRect(
                                color = LibAccent,
                                topLeft = Offset(0f, size.height + 2.dp.toPx()),
                                size = Size(size.width, 1.dp.toPx())
                            )
                        }
                    }
                )
            }
        }
        // Direction: compact, visually separated action.
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(start = 6.dp)
                .background(LibPanel, RoundedCornerShape(4.dp))
                .clickable { onToggleDirection() }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (direction == SortDirection.ASC) "↑" else "↓",
                color = LibAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** Continue strip: small monospace, low contrast, full width, max 2px visual weight. */
@Composable
private fun ResumeStrip(
    primary: BookrioResumeItem,
    extraCount: Int,
    onClickPrimary: () -> Unit,
    onClickMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickPrimary)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▸",
            color = LibAccent,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = "${primary.title} · ${primary.detail}",
            style = ShelfTypography.LabelSmall,
            color = LibDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (extraCount > 0) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(LibPanel, RoundedCornerShape(4.dp))
                    .clickable(onClick = onClickMore)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    "+$extraCount",
                    color = LibAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** Sharp-cornered panel search field that mirrors the Android expander. */
@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    Column {
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = LibPanel,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = LibAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Søk i biblioteket...",
                            color = LibDim,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp, color = LibFgBright),
                        cursorBrush = SolidColor(LibAccent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear, modifier = Modifier.size(20.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Tøm",
                            tint = LibDim,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onImportClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EmptyShelfGlyph()

            Spacer(Modifier.height(20.dp))

            Text(
                "Biblioteket er tomt",
                style = ShelfTypography.TitleLarge,
                color = LibFgBright,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Legg til e-bøker og lydbøker ved å importere filer fra enheten.",
                style = ShelfTypography.BodyMedium,
                color = LibDim,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // Android also shows a "Sync from FTP / seedbox" button here; sources
            // are not implemented on iOS, so only the import action is offered.
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, LibHairline, RoundedCornerShape(4.dp))
                    .clickable(onClick = onImportClick),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = LibFg,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Importer fra enhet", color = LibFg, style = ShelfTypography.BodyMedium)
                }
            }
        }
    }
}

/** One compact header action (36dp hit target, 20dp glyph), Android-sized. */
@Composable
private fun LibAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Scroll-direction → bottom-nav visibility, identical to the Android effect. */
@Composable
private fun LibraryScrollHideEffect(
    gridState: LazyGridState,
    listState: LazyListState,
    onVisibilityChange: (Boolean) -> Unit
) {
    LaunchedEffect(gridState, onVisibilityChange) {
        var lastIndex = 0
        var lastOffset = 0
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                when {
                    index > lastIndex || (index == lastIndex && offset > lastOffset) ->
                        onVisibilityChange(false)
                    index < lastIndex || (index == lastIndex && offset < lastOffset) ->
                        onVisibilityChange(true)
                }
                lastIndex = index
                lastOffset = offset
            }
    }
    LaunchedEffect(listState, onVisibilityChange) {
        var lastIndex = 0
        var lastOffset = 0
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                when {
                    index > lastIndex || (index == lastIndex && offset > lastOffset) ->
                        onVisibilityChange(false)
                    index < lastIndex || (index == lastIndex && offset < lastOffset) ->
                        onVisibilityChange(true)
                }
                lastIndex = index
                lastOffset = offset
            }
    }
}

private fun libraryTitle(mode: BookrioLibraryMode): String = when (mode) {
    BookrioLibraryMode.BOOKS -> "Bøker"
    BookrioLibraryMode.AUDIOBOOKS -> "Lydbøker"
}

/**
 * Persisted view type: `PrefKeys.LIBRARY_VIEW` stores "grid" | "list" (the values
 * `LibraryViewType.storageKey` writes). The legacy "shelf" value renders as grid.
 */
private fun loadViewType(): LibraryViewType = when (AppPrefs.getString(PrefKeys.LIBRARY_VIEW)) {
    LibraryViewType.LIST.storageKey -> LibraryViewType.LIST
    else -> LibraryViewType.GRID
}