package com.bookrio.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.dao.PodcastFeedSummary
import com.bookrio.data.local.dao.PodcastLatestEpisode
import com.bookrio.data.local.dao.PodcastResumeItem
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.library.sort.ResumeSelector
import com.bookrio.podcast.domain.PodcastResumeCandidate
import com.bookrio.podcast.domain.PodcastResumePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** One card in the Home "Continue" row: ebook + audiobook in-progress, merged. */
data class HomeContinueItem(
    val bookId: Long,
    val title: String,
    val author: String,
    val progressPercent: Float,
    val isAudio: Boolean,
    val remainingMs: Long?,
    val spineColor: Int?,
    val coverPath: String?
)

/** One card in the Home audiobooks row: in-progress first, then recently added. */
data class HomeAudiobookItem(
    val bookId: Long,
    val title: String,
    val author: String,
    val progressPercent: Float,
    val remainingMs: Long?,
    val inProgress: Boolean,
    val spineColor: Int?,
    val coverPath: String?
)

/** One episode row in the Home podcasts section (resume or latest). */
data class HomeEpisodeItem(
    val episodeId: Long,
    val feedId: Long,
    val title: String,
    val feedTitle: String,
    val artworkUrl: String?,
    val positionMs: Long,
    val durationMs: Long?,
    val publishedAt: Long?,
    val isResume: Boolean
)

/** Single immutable snapshot rendered by [HomeScreen]. */
data class HomeUiState(
    val isLoading: Boolean = true,
    val ebookCount: Int = 0,
    val audiobookCount: Int = 0,
    val podcastCount: Int = 0,
    val inProgressCount: Int = 0,
    val streakDays: Int = 0,
    val continueItems: List<HomeContinueItem> = emptyList(),
    val audiobooks: List<HomeAudiobookItem> = emptyList(),
    val resumeEpisodes: List<HomeEpisodeItem> = emptyList(),
    val latestEpisodes: List<HomeEpisodeItem> = emptyList()
) {
    /** The library has books or the user follows at least one podcast. */
    val hasLibrary: Boolean get() = ebookCount > 0 || audiobookCount > 0 || podcastCount > 0

    /** Any section has something to show. */
    val hasSections: Boolean
        get() = continueItems.isNotEmpty() || audiobooks.isNotEmpty() ||
            resumeEpisodes.isNotEmpty() || latestEpisodes.isNotEmpty()
}

/** Remaining time split for localized "3h 12m" style labels. */
internal data class RemainingParts(val hours: Long, val minutes: Long)

/**
 * Splits a remaining duration into hours/minutes, rounding up to whole minutes.
 * Null when nothing meaningful is left (unknown position, missing duration).
 */
internal fun remainingParts(remainingMs: Long?): RemainingParts? {
    if (remainingMs == null || remainingMs <= 0L) return null
    val totalMinutes = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
    return RemainingParts(hours = totalMinutes / 60L, minutes = totalMinutes % 60L)
}

/**
 * Remaining audio time for a started item. `positionMs` may be null or 0 for
 * rows that were never played; `durationMs` may be missing for unknown media.
 */
internal fun audioRemainingMs(positionMs: Long?, durationMs: Long?): Long? {
    if (durationMs == null || durationMs <= 0L) return null
    val position = positionMs ?: 0L
    if (position <= 0L) return null
    return (durationMs - position).coerceAtLeast(0L)
}

/**
 * Merges the ebook and audiobook resume candidates into one activity-ordered
 * list, so Home shows every started book (not a single "+N" strip).
 */
internal fun mergeContinueOrder(
    ebooks: List<ResumeSelector.ResumeCandidate>,
    audiobooks: List<ResumeSelector.ResumeCandidate>
): List<ResumeSelector.ResumeCandidate> =
    (ebooks + audiobooks).sortedWith(
        compareByDescending<ResumeSelector.ResumeCandidate> { it.lastActivity }
            .thenByDescending { it.updatedAt }
            .thenBy { it.id }
    )

class HomeViewModel(
    application: Application,
    private val db: ShelfDatabase
) : AndroidViewModel(application) {

    private val bookDao = db.bookDao()
    private val progressDao = db.progressDao()
    private val feedDao = db.podcastFeedDao()
    private val episodeDao = db.podcastEpisodeDao()
    private val rhythmDao = db.readingRhythmDao()

    private data class BookRows(
        val books: List<BookEntity>,
        val progress: List<ReadingProgressEntity>
    )

    val state: StateFlow<HomeUiState> = combine(
        combine(bookDao.observeAll(), progressDao.observeAll()) { books, progress ->
            BookRows(books = books, progress = progress)
        },
        feedDao.observeSummaries(),
        episodeDao.observeResumeItems(RESUME_QUERY_LIMIT),
        episodeDao.observeLatestEpisodes(LATEST_QUERY_LIMIT),
        rhythmDao.observeProfile()
    ) { rows, feeds, resume, latest, profile ->
        buildState(rows, feeds, resume, latest, profile?.currentStreak ?: 0)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState()
        )

    private fun buildState(
        rows: BookRows,
        feeds: List<PodcastFeedSummary>,
        resumeItems: List<PodcastResumeItem>,
        latestItems: List<PodcastLatestEpisode>,
        streakDays: Int
    ): HomeUiState {
        val books = rows.books.filter { !it.isDeleted }
        val booksById = books.associateBy { it.id }
        val progressByBook = rows.progress.associateBy { it.bookId }
        val filesDir = getApplication<Application>().filesDir

        fun percentOf(bookId: Long): Float =
            (progressByBook[bookId]?.progressPercent ?: 0f).coerceIn(0f, 1f)

        // ── Continue: every started ebook + audiobook, merged via ResumeSelector ──
        val resumeInputs = books.map { b ->
            val p = progressByBook[b.id]
            ResumeSelector.ResumeBook(
                id = b.id,
                title = b.title,
                author = b.author,
                progressPercent = p?.progressPercent ?: 0f,
                positionMs = p?.positionMs ?: 0L,
                durationMs = b.durationMs ?: 0L,
                isAudio = b.type == BookTypeEntity.AUDIOBOOK,
                lastActivity = p?.updatedAt ?: b.lastOpenedAt ?: 0L,
                updatedAt = p?.updatedAt ?: b.lastModifiedAt,
                dateFinished = b.dateFinished,
                isDeleted = b.isDeleted
            )
        }
        val ebookCandidates = ResumeSelector.select(resumeInputs, wantAudio = false, max = Int.MAX_VALUE)
        val audioCandidates = ResumeSelector.select(resumeInputs, wantAudio = true, max = Int.MAX_VALUE)
        val activeCandidates = mergeContinueOrder(ebookCandidates, audioCandidates)

        val continueItems = activeCandidates.take(CONTINUE_LIMIT).mapNotNull { candidate ->
            val book = booksById[candidate.bookId] ?: return@mapNotNull null
            val pct = percentOf(book.id)
            HomeContinueItem(
                bookId = book.id,
                title = book.title.ifBlank { book.sortTitle },
                author = book.author,
                progressPercent = pct,
                isAudio = book.type == BookTypeEntity.AUDIOBOOK,
                remainingMs = audioRemainingMs(progressByBook[book.id]?.positionMs, book.durationMs),
                spineColor = book.spineColor,
                coverPath = coverPathFor(book, filesDir)
            )
        }

        // ── Audiobooks: in-progress first (activity order), then recently added ──
        val audioRank = audioCandidates.withIndex().associate { (index, c) -> c.bookId to index }
        val audiobooks = books.asSequence()
            .filter { it.type == BookTypeEntity.AUDIOBOOK }
            .sortedWith(
                compareBy<BookEntity> { audioRank[it.id] ?: Int.MAX_VALUE }
                    .thenByDescending { it.dateAdded }
                    .thenBy { it.id }
            )
            .take(AUDIOBOOK_LIMIT)
            .map { book ->
                HomeAudiobookItem(
                    bookId = book.id,
                    title = book.title.ifBlank { book.sortTitle },
                    author = book.author,
                    progressPercent = percentOf(book.id),
                    remainingMs = audioRemainingMs(progressByBook[book.id]?.positionMs, book.durationMs),
                    inProgress = audioRank.containsKey(book.id),
                    spineColor = book.spineColor,
                    coverPath = coverPathFor(book, filesDir)
                )
            }
            .toList()

        // ── Podcasts: continue-listening first, then latest episodes ──
        val resumeById = resumeItems.associateBy { it.episodeId }
        val resumeEpisodes = PodcastResumePolicy.active(
            resumeItems.map {
                PodcastResumeCandidate(
                    episodeId = it.episodeId,
                    positionMs = it.positionMs,
                    isCompleted = it.isCompleted,
                    lastPlayedAt = it.lastPlayedAt,
                    feedFollowed = it.feedFollowed
                )
            }
        ).mapNotNull { candidate ->
            val item = resumeById[candidate.episodeId] ?: return@mapNotNull null
            HomeEpisodeItem(
                episodeId = item.episodeId,
                feedId = item.feedId,
                title = item.episodeTitle,
                feedTitle = item.feedTitle,
                artworkUrl = item.artworkUrl,
                positionMs = item.positionMs,
                durationMs = item.durationMs,
                publishedAt = null,
                isResume = true
            )
        }
        val resumeIds = resumeEpisodes.mapTo(mutableSetOf()) { it.episodeId }
        val latestEpisodes = latestItems.asSequence()
            .filter { it.episodeId !in resumeIds }
            .take(LATEST_SECTION_LIMIT)
            .map { episode ->
                HomeEpisodeItem(
                    episodeId = episode.episodeId,
                    feedId = episode.feedId,
                    title = episode.episodeTitle,
                    feedTitle = episode.feedTitle,
                    artworkUrl = episode.artworkUrl,
                    positionMs = episode.positionMs,
                    durationMs = episode.durationMs,
                    publishedAt = episode.publishedAt,
                    isResume = false
                )
            }
            .toList()

        return HomeUiState(
            isLoading = false,
            ebookCount = books.count { it.type != BookTypeEntity.AUDIOBOOK },
            audiobookCount = books.count { it.type == BookTypeEntity.AUDIOBOOK },
            podcastCount = feeds.size,
            inProgressCount = activeCandidates.size,
            streakDays = streakDays,
            continueItems = continueItems,
            audiobooks = audiobooks,
            resumeEpisodes = resumeEpisodes,
            latestEpisodes = latestEpisodes
        )
    }

    private fun coverPathFor(book: BookEntity, filesDir: File): String? {
        val direct = book.coverPath?.takeIf { File(it).exists() }
        if (direct != null) return direct
        return File(filesDir, "covers/book_${book.id}.webp")
            .takeIf { it.exists() }
            ?.absolutePath
    }

    private companion object {
        const val CONTINUE_LIMIT = 12
        const val AUDIOBOOK_LIMIT = 12
        const val LATEST_SECTION_LIMIT = 8
        const val RESUME_QUERY_LIMIT = 25
        const val LATEST_QUERY_LIMIT = 25
    }
}