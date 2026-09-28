package com.bookrio.reader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.dispatchers.DispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookmarkEntity
import com.bookrio.data.local.entity.BookmarkTypeEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.data.prefs.UserPreferencesRepository
import com.bookrio.reader.engine.BookLoaderEngine
import com.bookrio.reader.engine.ReaderBookState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    application: Application,
    private val db: ShelfDatabase,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : AndroidViewModel(application) {

    private val engine = BookLoaderEngine(getApplication(), db)

    private val prefs = UserPreferencesRepository(application.applicationContext)

    private val _currentBookId = MutableStateFlow(0L)

    private val _state = MutableStateFlow(
        ReaderBookState(
            bookTitle        = "",
            author           = "",
            format           = FormatEntity.UNKNOWN,
            type             = BookTypeEntity.EBOOK,
            chapters         = emptyList(),
            currentChapterIndex = 0,
            percent          = 0f,
            scrollPct        = 0f,
            // Page-turn engine state
            currentPage      = 0,
            totalPages       = 0,
            error            = null,
        )
    )
    val state: StateFlow<ReaderBookState> = _state.asStateFlow()

    // ── Book loading ──────────────────────────────────────────────────────────

    fun load(bookId: Long) {
        _currentBookId.value = bookId
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val existing    = runCatching { db.progressDao().getByBook(bookId) }.getOrNull()
                val engineState = engine.loadBook(bookId)

                val safeChapterMax = (engineState.chapters.size - 1).coerceAtLeast(0)
                val restoredChapter = (existing?.chapterIndex ?: 0).coerceIn(0, safeChapterMax)
                val restoredPage = existing?.pageIndex ?: 0
                val mergedError = engineState.error
                    ?: if (engineState.chapters.isEmpty() && engineState.bookTitle.isNotBlank())
                        "Fant ingen lesbare kapitler i boken. Filen kan være skadet, tom, eller ha et støttet format som ikke kunne tolkes."
                    else null

                engineState.copy(
                    currentChapterIndex = restoredChapter,
                    percent   = existing?.progressPercent ?: engineState.percent,
                    scrollPct = existing?.scrollPct ?: 0f,
                    currentPage = restoredPage,
                    totalPages  = 0,   // will be filled in by HtmlPageRenderer via onPageCountKnown
                    error       = mergedError,
                )
            }.onSuccess {
                // Reader appearance is a persisted preference and must survive
                // reopening the book (and app restarts).
                val sp = runCatching { prefs.readerFontSizeSp.first() }.getOrDefault(it.fontSizeSp)
                val theme = runCatching { prefs.readerTheme.first() }.getOrDefault(it.readerTheme)
                _state.value = it.copy(fontSizeSp = sp.coerceIn(12, 36), readerTheme = theme)
            }.onFailure { t ->
                val prior = _state.value
                _state.value = prior.copy(
                    error = t.message ?: "Kan ikke åpne boken (ukjent feil)",
                    bookTitle = prior.bookTitle.ifBlank { "Kan ikke åpne bok" },
                )
            }
        }
    }

    // ── Page-turn engine callbacks ────────────────────────────────────────────

    /** Called by [PageCurlReader] once the renderer knows the total page count. */
    fun onPageCountKnown(count: Int) {
        val prior = _state.value
        val pending = prior.pendingRepositionPct
        if (pending != null && count > 0) {
            val newPage = (pending * count.toFloat()).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
            _state.value = prior.copy(
                totalPages = count,
                currentPage = newPage,
                percent = pending,
                pendingRepositionPct = null,
            )
            persistProgress(pending, newPage)
        } else {
            // If we just loaded and have a restored currentPage, ensure it's within bounds
            val safePage = if (count > 0) prior.currentPage.coerceIn(0, count - 1) else 0
            val pct = if (count > 0) safePage.toFloat() / count.toFloat() else 0f
            _state.value = prior.copy(totalPages = count, currentPage = safePage, percent = pct)
            if (safePage != prior.currentPage) {
                persistProgress(pct, safePage)
            }
        }
    }

    /** Called each time the user completes a page turn. Updates progress. */
    fun onPageTurned(page: Int) {
        val prior = _state.value
        if (page == prior.currentPage) return
        val pct = if (prior.totalPages > 0)
            page.toFloat() / prior.totalPages.toFloat()
        else 0f
        _state.value = prior.copy(currentPage = page, percent = pct)
        persistProgress(pct, page)
    }

    // ── Remaining API (used by BottomBar seek slider) ─────────────────────────

    fun updateProgress(pct: Float) {
        if (pct == _state.value.percent) return
        _state.value = _state.value.copy(percent = pct)
        persistProgress(pct)
    }

    fun seekToPercent(pct: Float) {
        val prior = _state.value
        val page = if (prior.totalPages > 0)
            (pct * prior.totalPages).toInt().coerceIn(0, prior.totalPages - 1)
        else 0
        _state.value = prior.copy(currentPage = page, percent = pct)
        persistProgress(pct, page)
    }

    fun setCurrentChapter(index: Int, targetChapterPct: Float = 0f) {
        if (index < 0 || index >= _state.value.chapters.size) return
        if (index == _state.value.currentChapterIndex) return
        _state.value = _state.value.copy(
            currentChapterIndex = index,
            currentPage = 0,
            percent = 0f,
            pendingRepositionPct = targetChapterPct,
        )
        persistProgress(targetChapterPct, 0, index)
    }

    fun nextChapter() {
        val next = _state.value.currentChapterIndex + 1
        if (next < _state.value.chapters.size) {
            setCurrentChapter(next, targetChapterPct = 0f)
        }
    }

    /**
     * Kapittelbytte utløst av en fullført sidekrøll over kapittelgrensen.
     * Siden krøllen allerede viser målsidens piksler (sentinel), settes siden
     * direkte uten pendingRepositionPct — ingen re-mount, ingen svart blink.
     */
    fun jumpToChapterPage(index: Int, page: Int, chapterPct: Float) {
        if (index < 0 || index >= _state.value.chapters.size) return
        if (index == _state.value.currentChapterIndex) return
        val safePage = page.coerceAtLeast(0)
        _state.value = _state.value.copy(
            currentChapterIndex = index,
            currentPage = safePage,
            percent = chapterPct.coerceIn(0f, 1f),
            pendingRepositionPct = null,
        )
        persistProgress(chapterPct.coerceIn(0f, 1f), safePage, index)
    }

    fun previousChapter() {
        val prev = _state.value.currentChapterIndex - 1
        if (prev >= 0) {
            setCurrentChapter(prev, targetChapterPct = 1f)
        }
    }

    fun setFontSize(sp: Int) {
        val clamped = sp.coerceIn(12, 36)
        viewModelScope.launch(dispatchers.io) { runCatching { prefs.setReaderFontSizeSp(clamped) } }
        val prior = _state.value
        val currentPct = if (prior.totalPages > 0)
            prior.currentPage.toFloat() / prior.totalPages.toFloat()
        else prior.percent
        _state.value = prior.copy(
            fontSizeSp = clamped,
            pendingRepositionPct = currentPct.coerceIn(0f, 1f),
        )
    }

    fun setTheme(theme: String) {
        viewModelScope.launch(dispatchers.io) { runCatching { prefs.setReaderTheme(theme) } }
        val prior = _state.value
        val currentPct = if (prior.totalPages > 0)
            prior.currentPage.toFloat() / prior.totalPages.toFloat()
        else prior.percent
        _state.value = prior.copy(
            readerTheme = theme,
            pendingRepositionPct = currentPct.coerceIn(0f, 1f),
        )
    }

    // ── Bokmerke: enkel side-bokmerke-toggle ─────────────────────────────────

    /** Kompakt HUD-melding ("Bokmerke lagret" / "Bokmerke fjernet") eller null. */
    private val _bookmarkHudMessage = MutableStateFlow<String?>(null)
    val bookmarkHudMessage: StateFlow<String?> = _bookmarkHudMessage.asStateFlow()

    fun clearBookmarkHud() { _bookmarkHudMessage.value = null }

    /**
     * Slår av/på bokmerke for NÅVÆRENDE logiske plassering:
     * bokId + seksjon/kapittel-indeks + lokal sideindeks (type GENERIC).
     * Første trykk = lagre, andre trykk (samme nøkkel) = fjern. Ingen duplikater.
     *
     * Endrer ALDRI sidetilstand: ingen WebView, ingen re-render, ingen cache-tømming,
     * ingen PageCurl/PageWindow-endring, ingen kapittel-/sidebytte.
     */
    fun toggleBookmark() {
        val bookId = _currentBookId.value
        if (bookId == 0L) return
        viewModelScope.launch(dispatchers.io) {
            val state = _state.value
            val chapterIdx = state.currentChapterIndex
            val page = state.currentPage.coerceAtLeast(0)
            val pct = if (state.totalPages > 1) page.toFloat() / (state.totalPages - 1) else 0f
            runCatching {
                val existing = db.bookmarkDao()
                    .getByBookSectionPage(bookId, BookmarkTypeEntity.GENERIC, chapterIdx, page)
                if (existing != null) {
                    db.bookmarkDao().delete(existing)
                    "Bokmerke fjernet"
                } else {
                    val chapterTitle = state.chapters.getOrNull(chapterIdx)?.title
                    db.bookmarkDao().insert(
                        BookmarkEntity(
                            bookId = bookId,
                            type = BookmarkTypeEntity.GENERIC,
                            title = chapterTitle?.let { "Chap ${chapterIdx + 1}: $it" },
                            snippet = "Page ${page + 1}",
                            pageIndex = page,
                            chapterIndex = chapterIdx,
                            positionPercent = pct.coerceIn(0f, 1f),
                        )
                    )
                    "Bokmerke lagret"
                }
            }.onSuccess { _bookmarkHudMessage.value = it }
        }
    }

    fun saveHighlight(
        text: String,
        colorInt: Int,
        pageIndex: Int,
        startOffset: Float,
        endOffset: Float,
    ) {
        val bookId = _currentBookId.value
        if (bookId == 0L) return
        val trimmed = text.trim().ifBlank { return }
        viewModelScope.launch(dispatchers.io) {
            val chapterIdx = _state.value.currentChapterIndex
            val totalInChapter = _state.value.totalPages.coerceAtLeast(1)
            val pct = ((pageIndex + startOffset) / totalInChapter.toFloat()).coerceIn(0f, 1f)
            runCatching {
                db.highlightDao().insert(
                    com.bookrio.data.local.entity.HighlightEntity(
                        bookId = bookId,
                        text = trimmed,
                        note = null,
                        color = colorInt,
                        pageIndex = pageIndex,
                        startPageOffset = startOffset.coerceIn(0f, 1f),
                        endPageOffset = endOffset.coerceIn(0f, 1f),
                        positionPercent = pct,
                    )
                )
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun persistProgress(pct: Float, page: Int? = null, chapter: Int? = null) {
        val bookId = _currentBookId.value
        if (bookId == 0L) return
        val currentChapter = chapter ?: _state.value.currentChapterIndex
        val currentPage = page ?: _state.value.currentPage
        
        viewModelScope.launch(dispatchers.io) {
            val prior = runCatching { db.progressDao().getByBook(bookId) }.getOrNull()
            db.progressDao().insertOrReplace(
                (prior ?: ReadingProgressEntity(bookId = bookId)).copy(
                    progressPercent = pct,
                    chapterIndex = currentChapter,
                    pageIndex = currentPage,
                    updatedAt       = System.currentTimeMillis(),
                )
            )
        }
    }
}
