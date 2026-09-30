package com.bookrio.library

import com.bookrio.core.domain.model.LibrarySortMode
import com.bookrio.core.domain.model.SortDirection
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.designsystem.components.BookVisual
import com.bookrio.library.mapper.BookVisualMapper
import com.bookrio.library.sort.LibrarySorter
import com.bookrio.library.sort.ResumeSelector
import com.bookrio.library.sort.SortBook

/**
 * Which shelf the shared library chrome is showing. Books and Audiobooks use the
 * exact same chrome (header, search, sort rail, resume strip, grid/list); only the
 * media filter, the title and the persisted sort settings differ.
 *
 * Locked per destination, like the Android `LibraryMode`: no cross-mode filtering.
 */
enum class BookrioLibraryMode { BOOKS, AUDIOBOOKS }

/** Grid entries: a full-width section label (HYLLE only) or one book cover. */
sealed interface BookrioLibraryEntry {
    data class SectionLabel(val text: String) : BookrioLibraryEntry
    data class BookEntry(val book: BookVisual) : BookrioLibraryEntry
}

/** One candidate in the thin "Fortsett" strip / the +N bottom sheet. */
data class BookrioResumeItem(
    val bookId: Long,
    val title: String,
    val author: String,
    val detail: String,
    val coverPath: String? = null
)

/**
 * Everything the shared library screen renders for the current tab, derived
 * from Room + the persisted sort/view preferences. Pure data, no Android types.
 */
data class BookrioLibraryState(
    val entries: List<BookrioLibraryEntry> = emptyList(),
    val resumeItems: List<BookrioResumeItem> = emptyList(),
    /** Non-deleted ebooks (Android: `type != AUDIOBOOK`), for the Books tab badge. */
    val booksCount: Int = 0,
    /** Non-deleted audiobooks (`type == AUDIOBOOK`), for the Audiobooks tab badge. */
    val audiobooksCount: Int = 0,
    /** Count for the currently visible tab. */
    val tabCount: Int = 0
)

/**
 * Derives the render state the same way `LibraryViewModel.buildStateFlow` does on
 * Android:
 *  1. filter the mode (Books = ebooks, Audiobooks = audiobooks only),
 *  2. filter the search query over title/author/series,
 *  3. sort through [LibrarySorter] (single source of truth),
 *  4. add HYLLE section labels when the query is blank,
 *  5. pick the resume candidates through [ResumeSelector] for the current mode.
 *
 * The Android DAO runs the query in SQL (`BookDao.search`); here it is a
 * case-insensitive `contains` over the same three fields, on the already-collected
 * Room list — observable behaviour is identical.
 */
fun buildBookrioLibraryState(
    books: List<BookEntity>,
    progress: List<ReadingProgressEntity>,
    mode: BookrioLibraryMode,
    query: String,
    sortMode: LibrarySortMode,
    direction: SortDirection
): BookrioLibraryState {
    val active = books.filter { !it.isDeleted }
    val progressById = progress.associateBy { it.bookId }
    val pct: (Long) -> Float = { progressById[it]?.progressPercent ?: 0f }

    val trimmedQuery = query.trim()
    val matching = if (trimmedQuery.isBlank()) active else active.filter { b ->
        b.title.contains(trimmedQuery, ignoreCase = true) ||
            b.author.contains(trimmedQuery, ignoreCase = true) ||
            b.series?.contains(trimmedQuery, ignoreCase = true) == true
    }
    val tabBooks = matching.filter { b ->
        when (mode) {
            BookrioLibraryMode.BOOKS -> b.type != BookTypeEntity.AUDIOBOOK
            BookrioLibraryMode.AUDIOBOOKS -> b.type == BookTypeEntity.AUDIOBOOK
        }
    }

    // ── Sorting: single source of truth (LibrarySorter) ──
    val sortBooks = tabBooks.map { b ->
        SortBook(
            id = b.id,
            title = b.title,
            sortTitle = b.sortTitle,
            author = b.author,
            sortAuthor = b.sortAuthor,
            series = b.series,
            seriesIndex = b.seriesIndex,
            seriesIndexResolved = b.seriesIndex ?: LibrarySorter.parseSeriesIndex(b.series),
            dateAdded = b.dateAdded,
            lastActivity = progressById[b.id]?.updatedAt ?: b.lastOpenedAt ?: 0L,
            updatedAt = progressById[b.id]?.updatedAt ?: b.lastModifiedAt,
            isAudio = b.type == BookTypeEntity.AUDIOBOOK
        )
    }
    val sorted = LibrarySorter.sort(sortBooks, sortMode, direction)
    val byId = tabBooks.associateBy { it.id }
    val sortedEntities = sorted.mapNotNull { byId[it.id] }

    val showLabels = sortMode == LibrarySortMode.HYLLE && trimmedQuery.isBlank()
    val labels = if (showLabels) LibrarySorter.sectionLabels(sorted) else List(sorted.size) { null }

    val entries = buildList {
        sortedEntities.forEachIndexed { i, entity ->
            labels[i]?.let { add(BookrioLibraryEntry.SectionLabel(it)) }
            add(BookrioLibraryEntry.BookEntry(BookVisualMapper.toBookVisual(entity, pct(entity.id))))
        }
    }

    // ── Fortsett-linje: smart multi-bok kandidater per fane ──
    val resumeInputs = active.map { b ->
        ResumeSelector.ResumeBook(
            id = b.id,
            title = b.title,
            author = b.author,
            progressPercent = pct(b.id),
            positionMs = progressById[b.id]?.positionMs ?: 0L,
            durationMs = b.durationMs ?: 0L,
            isAudio = b.type == BookTypeEntity.AUDIOBOOK,
            lastActivity = progressById[b.id]?.updatedAt ?: b.lastOpenedAt ?: 0L,
            updatedAt = progressById[b.id]?.updatedAt ?: b.lastModifiedAt,
            dateFinished = b.dateFinished,
            isDeleted = b.isDeleted
        )
    }
    val remainingLabel: (Long) -> String = { ms -> "${ResumeSelector.formatRemaining(ms)} igjen" }
    val resume = ResumeSelector.select(
        books = resumeInputs,
        wantAudio = mode == BookrioLibraryMode.AUDIOBOOKS,
        remainingLabel = remainingLabel
    )
    val activeById = active.associateBy { it.id }

    val booksCount = active.count { it.type != BookTypeEntity.AUDIOBOOK }
    val audiobooksCount = active.count { it.type == BookTypeEntity.AUDIOBOOK }

    return BookrioLibraryState(
        entries = entries,
        resumeItems = resume.map { r ->
            BookrioResumeItem(
                bookId = r.bookId,
                title = r.title,
                author = r.author,
                detail = r.detail,
                coverPath = activeById[r.bookId]?.coverPath?.takeIf { it.isNotBlank() }
            )
        },
        booksCount = booksCount,
        audiobooksCount = audiobooksCount,
        tabCount = if (mode == BookrioLibraryMode.BOOKS) booksCount else audiobooksCount
    )
}