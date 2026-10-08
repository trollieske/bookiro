@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookrio.reader.readium

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.bookrio.data.local.entity.BookmarkEntity
import com.bookrio.data.local.entity.HighlightEntity
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.reader.R

/** True when a stored bookmark has no CFI and can only be approximated by chapter + percent. */
internal fun BookmarkEntity.isLegacyPosition(): Boolean = anchorCfi.isNullOrBlank()

@Composable
internal fun ReadiumReaderTopBar(
    modifier: Modifier,
    title: String,
    chapter: String?,
    percent: Int,
    draggedSeek: Float?,
    onSeekChange: (Float) -> Unit,
    onSeekCommit: (Float) -> Unit,
    onBack: () -> Unit,
) {
    val seekDescription = stringResource(R.string.rdr_seek_progress)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OmarchyColors.Panel),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.rdr_back),
                    tint = OmarchyColors.Accent,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = OmarchyColors.FgBright,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp,
                )
                Text(
                    text = chapter?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.rdr_unnamed_section),
                    color = OmarchyColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = "$percent %",
                color = OmarchyColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Slider(
            value = draggedSeek ?: (percent / 100f),
            onValueChange = onSeekChange,
            onValueChangeFinished = { draggedSeek?.let(onSeekCommit) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = seekDescription },
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
internal fun ReadiumReaderBottomBar(
    modifier: Modifier,
    onContents: () -> Unit,
    onMarks: () -> Unit,
    onSearch: () -> Unit,
    onPreferences: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OmarchyColors.Panel)
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ChromeButton(stringResource(R.string.rdr_contents), Icons.AutoMirrored.Filled.List, onContents)
        ChromeButton(stringResource(R.string.rdr_bookmarks), Icons.Outlined.BookmarkBorder, onMarks)
        ChromeButton(stringResource(R.string.rdr_search), Icons.Default.Search, onSearch)
        ChromeButton(stringResource(R.string.rdr_preferences), Icons.Default.FormatSize, onPreferences)
        ChromeButton(stringResource(R.string.rdr_share), Icons.Default.Share, onShare)
    }
}

@Composable
private fun ChromeButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = OmarchyColors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = OmarchyColors.Dim, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
internal fun ReadiumContentsSheet(
    chapters: BookChapters,
    activeChapterIndex: Int,
    onJump: (ChapterEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OmarchyColors.Panel,
        contentColor = OmarchyColors.Fg,
    ) {
        Text(
            stringResource(R.string.rdr_contents),
            style = ShelfTypography.TitleMedium,
            color = OmarchyColors.FgBright,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (!chapters.fromTableOfContents) {
            Text(
                stringResource(R.string.rdr_no_builtin_toc),
                color = OmarchyColors.Dim,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        LazyColumn(modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(chapters.entries, key = { index, _ -> index }) { index, entry ->
                val active = index == activeChapterIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = entry.title) { onJump(entry) }
                        .padding(
                            start = (20 + entry.depth * 16).dp,
                            end = 20.dp,
                            top = 10.dp,
                            bottom = 10.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title.ifBlank {
                                stringResource(R.string.rdr_section_n, index + 1)
                            },
                            color = if (active) OmarchyColors.Accent else OmarchyColors.FgBright,
                            fontSize = if (entry.depth == 0) 15.sp else 13.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (entry.unnamed) {
                            Text(
                                stringResource(R.string.rdr_unnamed_section),
                                color = OmarchyColors.Dim,
                                fontSize = 10.sp,
                            )
                        }
                    }
                    if (active) Text("•", color = OmarchyColors.Accent, fontSize = 18.sp)
                }
            }
        }
    }
}

/**
 * Bookmarks + highlights sheet. Both lists are real database rows; a legacy bookmark without
 * an `anchor_cfi` is marked and only jumped to as best effort (chapter + fraction).
 */
@Composable
internal fun ReadiumMarksSheet(
    bookmarks: List<BookmarkEntity>,
    highlights: List<HighlightEntity>,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (BookmarkEntity) -> Unit,
    onJumpBookmark: (BookmarkEntity) -> Unit,
    onRemoveHighlight: (HighlightEntity) -> Unit,
    onJumpHighlight: (HighlightEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OmarchyColors.Panel,
        contentColor = OmarchyColors.Fg,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetTab(stringResource(R.string.rdr_bookmarks), selected = tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            SheetTab(stringResource(R.string.rdr_highlights), selected = tab == 1) { tab = 1 }
            Spacer(Modifier.weight(1f))
            if (tab == 0) {
                TextButton(onClick = onAddBookmark) {
                    Text(stringResource(R.string.rdr_add_bookmark), color = OmarchyColors.Accent)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (tab == 0) {
            if (bookmarks.isEmpty()) {
                EmptySheetText(stringResource(R.string.rdr_no_bookmarks))
            } else {
                LazyColumn(modifier = Modifier.navigationBarsPadding()) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        MarkRow(
                            title = bookmark.title
                                ?: stringResource(R.string.rdr_bookmark),
                            detail = buildString {
                                append(bookmark.snippet.orEmpty())
                                if (bookmark.isLegacyPosition()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(stringResource(R.string.rdr_legacy_position))
                                }
                            },
                            onClick = { onJumpBookmark(bookmark) },
                            onDelete = { onRemoveBookmark(bookmark) },
                        )
                    }
                }
            }
        } else {
            if (highlights.isEmpty()) {
                EmptySheetText(stringResource(R.string.rdr_no_highlights))
            } else {
                LazyColumn(modifier = Modifier.navigationBarsPadding()) {
                    items(highlights, key = { it.id }) { highlight ->
                        MarkRow(
                            title = ReadiumHighlights.label(highlight),
                            detail = highlight.note.orEmpty(),
                            onClick = { onJumpHighlight(highlight) },
                            onDelete = { onRemoveHighlight(highlight) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) OmarchyColors.Accent else OmarchyColors.Bg,
    ) {
        Text(
            label,
            color = if (selected) Color.Black else OmarchyColors.Fg,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun EmptySheetText(text: String) {
    Text(
        text,
        color = OmarchyColors.Dim,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
private fun MarkRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = OmarchyColors.FgBright,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    color = OmarchyColors.Dim,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.rdr_delete),
                tint = OmarchyColors.Dim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    HorizontalDivider(color = OmarchyColors.Hairline)
}

@Composable
internal fun ReadiumPreferencesSheet(
    preferences: ReadiumReaderPreferences,
    onPreferencesChange: (ReadiumReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OmarchyColors.Panel,
        contentColor = OmarchyColors.Fg,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.rdr_preferences),
                style = ShelfTypography.TitleMedium,
                color = OmarchyColors.FgBright,
            )

            Text(stringResource(R.string.rdr_font_size), color = OmarchyColors.Dim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OptionPill("A-") {
                    onPreferencesChange(
                        preferences.copy(
                            fontScale = (preferences.fontScale - ReadiumReaderPreferences.FONT_SCALE_STEP)
                                .coerceAtLeast(ReadiumReaderPreferences.MIN_FONT_SCALE),
                        ),
                    )
                }
                Text(
                    "${(preferences.fontScale * 100).toInt()} %",
                    color = OmarchyColors.FgBright,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                OptionPill("A+") {
                    onPreferencesChange(
                        preferences.copy(
                            fontScale = (preferences.fontScale + ReadiumReaderPreferences.FONT_SCALE_STEP)
                                .coerceAtMost(ReadiumReaderPreferences.MAX_FONT_SCALE),
                        ),
                    )
                }
            }

            Text(stringResource(R.string.rdr_theme), color = OmarchyColors.Dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionPill(
                    stringResource(R.string.rdr_theme_light),
                    selected = preferences.themeName == ReadiumReaderPreferences.THEME_LIGHT,
                ) { onPreferencesChange(preferences.copy(themeName = ReadiumReaderPreferences.THEME_LIGHT)) }
                OptionPill(
                    stringResource(R.string.rdr_theme_sepia),
                    selected = preferences.themeName == ReadiumReaderPreferences.THEME_SEPIA,
                ) { onPreferencesChange(preferences.copy(themeName = ReadiumReaderPreferences.THEME_SEPIA)) }
                OptionPill(
                    stringResource(R.string.rdr_theme_dark),
                    selected = preferences.themeName == ReadiumReaderPreferences.THEME_DARK,
                ) { onPreferencesChange(preferences.copy(themeName = ReadiumReaderPreferences.THEME_DARK)) }
            }

            Text(stringResource(R.string.rdr_margins), color = OmarchyColors.Dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionPill(
                    stringResource(R.string.rdr_margin_small),
                    selected = preferences.pageMarginFactor < 0.75,
                ) { onPreferencesChange(preferences.copy(pageMarginFactor = 0.5)) }
                OptionPill(
                    stringResource(R.string.rdr_margin_medium),
                    selected = preferences.pageMarginFactor in 0.75..1.25,
                ) { onPreferencesChange(preferences.copy(pageMarginFactor = 1.0)) }
                OptionPill(
                    stringResource(R.string.rdr_margin_large),
                    selected = preferences.pageMarginFactor > 1.25,
                ) { onPreferencesChange(preferences.copy(pageMarginFactor = 1.5)) }
            }

            Text(stringResource(R.string.rdr_line_spacing), color = OmarchyColors.Dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionPill(
                    stringResource(R.string.rdr_line_spacing_default),
                    selected = preferences.lineHeightFactor == null,
                ) { onPreferencesChange(preferences.copy(lineHeightFactor = null)) }
                listOf(1.2, 1.5, 1.8).forEach { factor ->
                    OptionPill(
                        factor.toString(),
                        selected = preferences.lineHeightFactor == factor,
                    ) { onPreferencesChange(preferences.copy(lineHeightFactor = factor)) }
                }
            }

            Text(stringResource(R.string.rdr_layout_mode), color = OmarchyColors.Dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionPill(
                    stringResource(R.string.rdr_paged),
                    selected = !preferences.scroll,
                ) { onPreferencesChange(preferences.copy(scroll = false)) }
                OptionPill(
                    stringResource(R.string.rdr_scroll),
                    selected = preferences.scroll,
                ) { onPreferencesChange(preferences.copy(scroll = true)) }
            }
        }
    }
}

@Composable
private fun OptionPill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) OmarchyColors.Accent else OmarchyColors.Bg,
    ) {
        Text(
            label,
            color = if (selected) Color.Black else OmarchyColors.Fg,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
internal fun ReadiumSearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    searching: Boolean,
    hasSearched: Boolean,
    results: List<ReadiumSearchHit>,
    onJump: (ReadiumSearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OmarchyColors.Panel,
        contentColor = OmarchyColors.Fg,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.rdr_search_in_book),
                style = ShelfTypography.TitleMedium,
                color = OmarchyColors.FgBright,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.rdr_search_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSearch, enabled = query.isNotBlank() && !searching) {
                    Text(stringResource(R.string.rdr_search))
                }
            }
            when {
                searching -> Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = OmarchyColors.Accent,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.rdr_searching), color = OmarchyColors.Dim, fontSize = 13.sp)
                }

                // No search has run for this query yet: show nothing rather than a
                // misleading "no matches found".
                !hasSearched -> Unit

                results.isEmpty() -> EmptySheetText(
                    stringResource(R.string.rdr_search_no_results),
                )

                else -> LazyColumn {
                    items(results.size) { index ->
                        val hit = results[index]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = hit.snippet) { onJump(hit) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            if (!hit.chapterTitle.isNullOrBlank()) {
                                Text(
                                    hit.chapterTitle,
                                    color = OmarchyColors.Accent,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                hit.snippet,
                                color = OmarchyColors.FgBright,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(color = OmarchyColors.Hairline)
                    }
                }
            }
        }
    }
}

/** Shown when the reader taps a highlighted passage. */
@Composable
internal fun ReadiumHighlightDialog(
    highlight: HighlightEntity,
    onJump: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OmarchyColors.Panel,
        title = {
            Text(
                stringResource(R.string.rdr_highlight),
                color = OmarchyColors.FgBright,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                ReadiumHighlights.label(highlight),
                color = OmarchyColors.Fg,
                fontSize = 14.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = {
            TextButton(onClick = onJump) {
                Text(stringResource(R.string.rdr_go_to), color = OmarchyColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.rdr_delete), color = OmarchyColors.Dim)
            }
        },
    )
}

/** Small transient confirmations ("Bookmark saved", "Highlight removed", ...). */
@Composable
internal fun ReadiumHud(message: String?) {
    if (message == null) return
    Box(
        modifier = Modifier.fillMaxWidth().padding(bottom = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.78f),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 4.dp,
        ) {
            Text(
                message,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

