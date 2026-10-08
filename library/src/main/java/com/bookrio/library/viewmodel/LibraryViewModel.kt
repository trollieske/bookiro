package com.bookrio.library.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.core.domain.model.DarkModePref
import com.bookrio.core.domain.model.LibrarySortMode
import com.bookrio.core.domain.model.LibraryViewType
import com.bookrio.core.domain.model.SortDirection
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.dispatchers.DispatcherProvider
import com.bookrio.library.cover.CoverRepository
import com.bookrio.library.data.BookImportRepository
import com.bookrio.library.mapper.DomainMappers.toBookVisual
import com.bookrio.library.sort.LibrarySorter
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.prefs.UserPreferencesRepository
import com.bookrio.designsystem.components.BookVisual
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Locked per destination: Books = kun ebøker, Audio = kun lydbøker. Ingen kryssmodus-filter. */
sealed class LibraryMode {
    data object Books : LibraryMode()
    data object Audio : LibraryMode()
}

/** Rutenett-oppføringer: full-bredde seksjonsetikett (kun HYLLE) eller bokomslag. */
sealed interface GridEntry {
    data class SectionLabel(val text: String) : GridEntry
    data class BookEntry(val book: BookVisual) : GridEntry
}

data class LibraryUiState(
    val query: String = "",
    val sortMode: LibrarySortMode = LibrarySortMode.DEFAULT,
    val direction: SortDirection = SortDirection.ASC,
    val isLoading: Boolean = true,
    val viewType: LibraryViewType = LibraryViewType.GRID,
    val gridEntries: List<GridEntry> = emptyList(),
    val flatGridBooks: List<BookVisual> = emptyList(),
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    application: Application,
    val prefs: UserPreferencesRepository = UserPreferencesRepository(application.applicationContext),
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(application) {

    private val db = ShelfDatabase.getInstance(application.applicationContext)

    private val queryFlow = MutableStateFlow("")
    private val modeFlow = MutableStateFlow<LibraryMode>(LibraryMode.Books)

    init {
        viewModelScope.launch(dispatchers.io) {
            val app = getApplication<Application>()
            // Self-heal audiobooks that an older build fragmented into one record per
            // track (online metadata enrichment used to break the per-batch merge),
            // and one-chapter fallbacks left behind by partially imported torrents
            // (BUG A). Both are cheap, idempotent and serialised inside the repository.
            if (!fragmentedAudiobooksRepaired) {
                fragmentedAudiobooksRepaired = true
                runCatching {
                    val repo = BookImportRepository(app, db, dispatchers)
                    // Fix narrator-as-author first: audiobook grouping depends on the author.
                    repo.repairNarratorAuthors()
                    repo.consolidateFragmentedAudiobooks()
                    repo.repairOneChapterAudiobooks()
                    repo.repairTitlesAndAuthors()
                    repo.deduplicateLibrary()
                }
            }
            val coversDir = java.io.File(app.filesDir, "covers")
            val allBooks = runCatching { db.bookDao().getAllOnce() }.getOrElse { emptyList() }
            val missing = allBooks.filter { b ->
                val cp = b.coverPath
                (cp.isNullOrBlank() || !java.io.File(cp).exists()) &&
                        !java.io.File(coversDir, "book_${b.id}.webp").exists()
            }
            if (missing.isNotEmpty()) {
                val coverRepo = CoverRepository(app, db, dispatchers)
                missing.take(10).forEach { b ->
                    runCatching { coverRepo.coverFileFor(b) }
                }
            }
        }
    }

    val state: StateFlow<LibraryUiState> = combine(
        queryFlow,
        modeFlow,
        prefs.booksSortMode,
        prefs.audioSortMode,
        prefs.booksSortDirection,
        prefs.audioSortDirection,
        prefs.libraryViewType
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val mode = args[1] as LibraryMode
        Params(
            query = args[0] as String,
            mode = mode,
            sortMode = if (mode == LibraryMode.Books) args[2] as LibrarySortMode else args[3] as LibrarySortMode,
            direction = if (mode == LibraryMode.Books) args[4] as SortDirection else args[5] as SortDirection,
            viewType = args[6] as LibraryViewType
        )
    }
        .flatMapLatest { p -> buildStateFlow(p) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryUiState()
        )

    private data class Params(
        val query: String,
        val mode: LibraryMode,
        val sortMode: LibrarySortMode,
        val direction: SortDirection,
        val viewType: LibraryViewType
    )

    private fun buildStateFlow(p: Params): Flow<LibraryUiState> =
        combine(
            booksMatching(p.query, p.mode),
            progressRows()
        ) { matching, rows ->
            val filesDir = getApplication<Application>().filesDir
            val pct: (Long) -> Float = { rows[it]?.progressPercent ?: 0f }
            val visual: (BookEntity) -> BookVisual = { toBookVisual(it, pct(it.id), filesDir) }

            // ── Sorting: single source of truth (LibrarySorter) ──
            val sortBooks = matching.map { b ->
                com.bookrio.library.sort.SortBook(
                    id = b.id,
                    title = b.title,
                    sortTitle = b.sortTitle,
                    author = b.author,
                    sortAuthor = b.sortAuthor,
                    series = b.series,
                    seriesIndex = b.seriesIndex,
                    seriesIndexResolved = b.seriesIndex ?: LibrarySorter.parseSeriesIndex(b.series),
                    dateAdded = b.dateAdded,
                    lastActivity = rows[b.id]?.updatedAt ?: b.lastOpenedAt ?: 0L,
                    updatedAt = rows[b.id]?.updatedAt ?: b.lastModifiedAt,
                    isAudio = b.type == BookTypeEntity.AUDIOBOOK
                )
            }
            val sorted = LibrarySorter.sort(sortBooks, p.sortMode, p.direction)
            val byId = matching.associateBy { it.id }
            val sortedEntities = sorted.mapNotNull { byId[it.id] }

            // Group labels for the grouping modes only (Series / Author).
            val showLabels = (p.sortMode == LibrarySortMode.SERIE || p.sortMode == LibrarySortMode.FORFATTER) &&
                p.query.isBlank()
            val labels = if (showLabels) LibrarySorter.sectionLabels(sorted, p.sortMode) else List(sorted.size) { null }

            val gridEntries = buildList {
                sortedEntities.forEachIndexed { i, entity ->
                    labels[i]?.let { add(GridEntry.SectionLabel(it)) }
                    add(GridEntry.BookEntry(visual(entity)))
                }
            }
            val flatGridBooks = sortedEntities.map(visual).distinctBy { it.id }

            LibraryUiState(
                query = p.query,
                sortMode = p.sortMode,
                direction = p.direction,
                isLoading = false,
                viewType = p.viewType,
                gridEntries = gridEntries,
                flatGridBooks = flatGridBooks,
                error = null
            )
        }
            .catch { emit(LibraryUiState(error = it.message, isLoading = false)) }
            .flowOn(dispatchers.default)

    private fun progressRows(): Flow<Map<Long, com.bookrio.data.local.entity.ReadingProgressEntity>> =
        db.progressDao().observeAll().map { rows ->
            rows.associate { it.bookId to it }
        }

    private fun booksMatching(
        query: String,
        mode: LibraryMode
    ): Flow<List<BookEntity>> {
        val base = if (query.isNotBlank()) db.bookDao().search(query) else db.bookDao().observeAll()
        return base.map { list ->
            when (mode) {
                LibraryMode.Books -> list.filter { it.type != BookTypeEntity.AUDIOBOOK }
                LibraryMode.Audio -> list.filter { it.type == BookTypeEntity.AUDIOBOOK }
            }
        }
    }

    // --- UI actions ---

    fun setQuery(q: String) { queryFlow.value = q.trim() }

    fun setMode(m: LibraryMode) { modeFlow.value = m }

    /** Sort sheet: persists per media tab and applies the mode's natural direction. */
    fun setSortMode(mode: LibrarySortMode) {
        // Apply the mode's natural default direction (newest-first for Recent /
        // Date added, A→Z otherwise) so the first tap is never backwards. The ⇅
        // toggle still reverses it afterwards.
        val dir = com.bookrio.core.domain.model.defaultDirectionFor(mode)
        viewModelScope.launch(dispatchers.io) {
            if (modeFlow.value == LibraryMode.Books) {
                prefs.setBooksSortMode(mode)
                prefs.setBooksSortDirection(dir)
            } else {
                prefs.setAudioSortMode(mode)
                prefs.setAudioSortDirection(dir)
            }
        }
    }

    /*
     * Direction is chosen explicitly from the sort sheet; the old ⇅ toggle is
     * gone, so there is no toggleSortDirection() here anymore.
     */

    /** Sets the persisted direction for the current tab explicitly. */
    fun setSortDirection(direction: SortDirection) {
        viewModelScope.launch(dispatchers.io) {
            if (modeFlow.value == LibraryMode.Books) prefs.setBooksSortDirection(direction)
            else prefs.setAudioSortDirection(direction)
        }
    }

    /** Grid ⇄ list view, persisted globally and shared by both tabs. */
    fun setViewType(t: LibraryViewType) {
        viewModelScope.launch(dispatchers.io) { prefs.setLibraryViewType(t) }
    }

    fun delete(bookId: Long) {
        viewModelScope.launch(dispatchers.io) {
            db.bookDao().softDelete(bookId)
        }
    }

    companion object {
        @Volatile
        private var fragmentedAudiobooksRepaired = false
    }
}
