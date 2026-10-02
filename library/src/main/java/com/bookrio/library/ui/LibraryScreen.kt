package com.bookrio.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.bookrio.core.domain.model.LibrarySortMode
import com.bookrio.core.domain.model.LibraryViewType
import com.bookrio.core.domain.model.SortDirection
import com.bookrio.designsystem.components.BookCoverCard
import com.bookrio.designsystem.components.BookVisual
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.library.R
import com.bookrio.library.viewmodel.GridEntry
import com.bookrio.library.viewmodel.LibraryMode
import com.bookrio.library.viewmodel.LibraryViewModel

// Omarchy-inspirert bibliotek-krom: svart base, lime-aksent, ingen hevede kort.
private val LibBg = com.bookrio.designsystem.theme.OmarchyColors.Bg
private val LibHeaderBg = com.bookrio.designsystem.theme.OmarchyColors.HeaderBg
private val LibHairline = com.bookrio.designsystem.theme.OmarchyColors.Hairline
private val LibAccent = com.bookrio.designsystem.theme.OmarchyColors.Accent
private val LibDim = com.bookrio.designsystem.theme.OmarchyColors.Dim
private val LibFg = com.bookrio.designsystem.theme.OmarchyColors.Fg
private val LibFgBright = com.bookrio.designsystem.theme.OmarchyColors.FgBright
private val LibPanel = com.bookrio.designsystem.theme.OmarchyColors.Panel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    mode: LibraryMode,
    onBookClick: (Long) -> Unit,
    onBookLongClick: (Long) -> Unit,
    onImportClick: () -> Unit,
    onFtpClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onNavVisibilityChange: (Boolean) -> Unit = {},
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    vm: LibraryViewModel = viewModel(factory = vmFactory ?: defaultLibraryVmFactory())
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(mode) { vm.setMode(mode) }
    var search by rememberSaveable { mutableStateOf("") }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(search) { vm.setQuery(search) }

    // Rulle-retning fra rutenettet: ned = skjul nav, opp = vis nav.
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    LaunchedEffect(gridState, onNavVisibilityChange) {
        var lastIndex = 0
        var lastOffset = 0
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                when {
                    index > lastIndex || (index == lastIndex && offset > lastOffset) ->
                        onNavVisibilityChange(false)
                    index < lastIndex || (index == lastIndex && offset < lastOffset) ->
                        onNavVisibilityChange(true)
                }
                lastIndex = index
                lastOffset = offset
            }
    }
    LaunchedEffect(listState, onNavVisibilityChange) {
        var lastIndex = 0
        var lastOffset = 0
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                when {
                    index > lastIndex || (index == lastIndex && offset > lastOffset) ->
                        onNavVisibilityChange(false)
                    index < lastIndex || (index == lastIndex && offset < lastOffset) ->
                        onNavVisibilityChange(true)
                }
                lastIndex = index
                lastOffset = offset
            }
    }
    val booksToDisplay = remember(ui.flatGridBooks) { ui.flatGridBooks.distinctBy { it.id } }
    LaunchedEffect(booksToDisplay.isEmpty(), search, mode) {
        if (booksToDisplay.isEmpty() || search.isNotBlank()) onNavVisibilityChange(true)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp),
            containerColor = LibBg,
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LibBg)
                    .padding(innerPadding)
            ) {
                // Flat toppband: tittel + minimalt ikoner. Ingen heving, bare hårlinje.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(LibHeaderBg)
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    var showSearchField by rememberSaveable { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Image(
                                painter = painterResource(com.bookrio.designsystem.R.drawable.bookrio_mark),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (mode) {
                                    LibraryMode.Books -> stringResource(R.string.lib_books)
                                    LibraryMode.Audio -> stringResource(R.string.lib_audiobooks)
                                },
                                style = ShelfTypography.TitleLarge,
                                color = LibFgBright,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    vm.setViewType(
                                        if (ui.viewType == LibraryViewType.LIST) LibraryViewType.GRID
                                        else LibraryViewType.LIST
                                    )
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (ui.viewType == LibraryViewType.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                                    contentDescription = stringResource(R.string.lib_view_toggle_a11y),
                                    tint = LibDim,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { showSearchField = !showSearchField }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.lib_search_a11y), tint = if (search.isNotEmpty() || showSearchField) LibAccent else LibDim, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onFtpClick, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.CloudSync, contentDescription = stringResource(R.string.lib_sources_a11y), tint = LibDim, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onImportClick, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.lib_import_a11y), tint = LibFgBright, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onSettingsClick, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.lib_settings_a11y), tint = LibDim, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // Søk: skjult ikon som ekspanderer. Skarpe hjørner, ingen chip-rad.
                    AnimatedVisibility(visible = showSearchField || search.isNotEmpty()) {
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
                                    Icon(Icons.Default.Search, contentDescription = null, tint = LibAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (search.isEmpty()) {
                                            Text(stringResource(R.string.lib_search_hint), color = LibDim, fontSize = 12.sp, maxLines = 1)
                                        }
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = search,
                                            onValueChange = { search = it },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = LibFgBright)
                                        )
                                    }
                                    if (search.isNotEmpty()) {
                                        IconButton(onClick = { search = "" }, modifier = Modifier.size(20.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.lib_clear_a11y), tint = LibDim)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LibHairline)
                )

                // Kompakt sorteringskontroll; selve valgene bor i et sheet.
                SortBar(
                    mode = ui.sortMode,
                    itemCount = booksToDisplay.size,
                    onClick = { showSortSheet = true }
                )

                // Fortsett-linjen er flyttet til Home; bibliotekfanene er rene lister.
                Box(modifier = Modifier.weight(1f)) {
                    if (ui.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LibDim)
                        }
                    } else if (booksToDisplay.isEmpty()) {
                        CleanEmptyState(
                            onImportClick = onImportClick,
                            onFtpClick = onFtpClick
                        )
                    } else if (ui.viewType == LibraryViewType.LIST) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            itemsIndexed(
                                ui.gridEntries,
                                key = { index, entry -> gridEntryKey(index, entry) }
                            ) { _, entry ->
                                when (entry) {
                                    is GridEntry.SectionLabel -> SectionLabel(entry.text)
                                    is GridEntry.BookEntry -> Column {
                                        BookListRow(
                                            book = entry.book,
                                            onClick = { onBookClick(entry.book.id) },
                                            onLongClick = { onBookLongClick(entry.book.id) }
                                        )
                                        HorizontalDivider(thickness = 0.5.dp, color = LibHairline)
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 115.dp),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(
                                ui.gridEntries,
                                key = { index, entry -> gridEntryKey(index, entry) },
                                span = { _, entry ->
                                    GridItemSpan(if (entry is GridEntry.SectionLabel) maxLineSpan else 1)
                                }
                            ) { _, entry ->
                                when (entry) {
                                    is GridEntry.SectionLabel -> SectionLabel(entry.text)
                                    is GridEntry.BookEntry -> {
                                        val b = entry.book
                                        BookCoverCard(
                                            book = b.copy(leanDegrees = 0f),
                                            onClick = { onBookClick(b.id) },
                                            onLongClick = { onBookLongClick(b.id) },
                                            showFormatBadge = false,
                                            showInlineProgress = false,
                                            underCoverContent = {
                                                if (b.progress > 0f) {
                                                    ThinProgressBar(progress = b.progress)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showSortSheet) {
            SortSheet(
                mode = ui.sortMode,
                direction = ui.direction,
                onSelectMode = { vm.setSortMode(it) },
                onSelectDirection = { vm.setSortDirection(it) },
                onDismiss = { showSortSheet = false }
            )
        }
    }
}

@Composable
private fun LibrarySortMode.sortLabel(): String = when (this) {
    LibrarySortMode.HYLLE, LibrarySortMode.SERIE -> stringResource(R.string.lib_sort_series)
    LibrarySortMode.FORFATTER -> stringResource(R.string.lib_sort_author)
    LibrarySortMode.NYLIG -> stringResource(R.string.lib_sort_recent)
    LibrarySortMode.TITTEL -> stringResource(R.string.lib_sort_title)
    LibrarySortMode.LAGT_TIL -> stringResource(R.string.lib_sort_added)
}

/** Stable lazy-list keys: books by id, section labels by position. */
private fun gridEntryKey(index: Int, entry: GridEntry): Any = when (entry) {
    is GridEntry.SectionLabel -> "l:$index"
    is GridEntry.BookEntry -> "b:${entry.book.id}"
}

/** Collapsed sort control: current mode + item count; opens [SortSheet]. */
@Composable
private fun SortBar(
    mode: LibrarySortMode,
    itemCount: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = LibPanel,
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Sort, contentDescription = null, tint = LibAccent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text(mode.sortLabel(), style = ShelfTypography.LabelMedium, color = LibFgBright, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = LibDim, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.lib_item_count, itemCount),
            style = ShelfTypography.LabelSmall.copy(fontFamily = FontFamily.Monospace),
            color = LibDim,
            maxLines = 1
        )
    }
}

@Composable
private fun directionLabels(mode: LibrarySortMode): Pair<String, String> = when (mode) {
    LibrarySortMode.NYLIG, LibrarySortMode.LAGT_TIL ->
        stringResource(R.string.lib_dir_oldest) to stringResource(R.string.lib_dir_newest)
    else -> stringResource(R.string.lib_dir_az) to stringResource(R.string.lib_dir_za)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    mode: LibrarySortMode,
    direction: SortDirection,
    onSelectMode: (LibrarySortMode) -> Unit,
    onSelectDirection: (SortDirection) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LibPanel,
        contentColor = LibFg,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = LibHairline) }
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                stringResource(R.string.lib_sort_sheet_title),
                style = ShelfTypography.TitleMedium,
                color = LibFgBright,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            LibrarySortMode.visible.forEach { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelectMode(m) }
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (m == mode) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (m == mode) LibAccent else LibDim,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        m.sortLabel(),
                        style = ShelfTypography.BodyLarge,
                        color = if (m == mode) LibFgBright else LibFg
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = LibHairline)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.lib_sort_direction).uppercase(),
                style = ShelfTypography.LabelSmall,
                color = LibDim
            )
            Spacer(Modifier.height(10.dp))
            val (ascLabel, descLabel) = directionLabels(mode)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectionChip(ascLabel, direction == SortDirection.ASC) { onSelectDirection(SortDirection.ASC) }
                DirectionChip(descLabel, direction == SortDirection.DESC) { onSelectDirection(SortDirection.DESC) }
            }
            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) LibAccent.copy(alpha = 0.16f) else LibBg,
        border = BorderStroke(1.dp, if (selected) LibAccent else LibHairline),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            style = ShelfTypography.LabelMedium,
            color = if (selected) LibAccent else LibDim,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

/** Full-bredde HYLLE-seksjonsetikett: monospace, lav kontrast, 1px skille. */
@Composable
private fun SectionLabel(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LibHairline)
        )
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = LibDim,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp, bottom = 2.dp)
        )
    }
}

/** List-view row, podcast-quality: cover, title/author, format + progress. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListRow(
    book: BookVisual,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 54.dp, height = 80.dp)
                .background(LibHairline, RoundedCornerShape(4.dp))
        ) {
            val cp = book.coverImagePath
            if (!cp.isNullOrBlank()) {
                AsyncImage(
                    model = cp,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = ShelfTypography.BodyLarge,
                color = LibFgBright,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val author = book.author.takeIf { it.isNotBlank() }
            if (author != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    author,
                    style = ShelfTypography.BodySmall,
                    color = LibDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    book.format.name,
                    style = ShelfTypography.LabelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = LibDim,
                    maxLines = 1
                )
                if (book.progress > 0f) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${(book.progress * 100).toInt()}%",
                        style = ShelfTypography.LabelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = LibAccent,
                        maxLines = 1
                    )
                }
            }
            if (book.progress > 0f) {
                Spacer(Modifier.height(6.dp))
                ThinProgressBar(progress = book.progress)
            }
        }
    }
}

/** 2px fremdriftslinje under omslaget — aksent på LibHairline-spor. Ingen etiketter. */
@Composable
private fun ThinProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .height(2.dp)
            .background(LibHairline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0.01f, 1f))
                .background(LibAccent)
        )
    }
}

@Composable
private fun CleanEmptyState(
    onImportClick: () -> Unit,
    onFtpClick: () -> Unit
) {
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
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = LibPanel,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = LibDim,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                stringResource(R.string.lib_empty_title),
                style = ShelfTypography.TitleLarge,
                color = LibFgBright,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.lib_empty_desc),
                style = ShelfTypography.BodyMedium,
                color = LibDim,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onFtpClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LibPanel,
                    contentColor = LibFg
                ),
                shape = RoundedCornerShape(4.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lib_sync_button), fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onImportClick,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LibFg),
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lib_import_button))
            }
        }
    }
}

private fun defaultLibraryVmFactory(): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = (this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application)
        val db = com.bookrio.data.local.ShelfDatabase.getInstance(app)
        val prefs = com.bookrio.data.prefs.UserPreferencesRepository(app)
        LibraryViewModel(app, prefs)
    }
}

object SampleBooks {
    val books: List<com.bookrio.designsystem.components.BookVisual> = emptyList()
}
