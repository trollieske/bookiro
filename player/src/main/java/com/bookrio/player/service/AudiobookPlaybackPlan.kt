package com.bookrio.player.service

import com.bookrio.player.engine.AudiobookChapter

/**
 * Pure (Android-free) construction of the playable Media3 timeline for one audiobook.
 *
 * Android Auto (and any other MediaController) selects a browse leaf by its stable
 * media id `book_<id>` (see [AudiobookLibraryTree]). Media3's
 * `MediaSession.Callback.onAddMediaItems` must answer that selection with a list of
 * COMPLETE items (URI + metadata), because:
 *
 *  - the browse leaf carries no URI (it is a library entry, not a media source), and
 *  - Media3 1.4.1's default `onAddMediaItems` fails any item without a
 *    `LocalConfiguration` (`UnsupportedOperationException`), and the legacy
 *    `MediaBrowserCompat` path used by Android Auto swallows that failure silently
 *    (`MediaSessionLegacyStub.onFailure` is a no-op). Result: selecting a book in the
 *    car did nothing at all.
 *
 * This object owns the chapter -> item math that used to live inline in
 * [AudiobookPlaybackService.loadBook], so the in-app load and the Auto/library
 * selection resolve into exactly the same timeline, and so the math is JVM-testable
 * without Media3/Android classes.
 */
data class PlaybackItemPlan(
    /** Stable Media3 media id: `book_<bookId>` leaves resolve to `<bookId>_<index>`. */
    val mediaId: String,
    /** Complete, absolute media URI the player can prepare. */
    val uri: String,
    val bookId: Long,
    /** Chapter index as stored in the book metadata (`ch.index`). */
    val chapterIndex: Int,
    /** Number of chapters in the book timeline (0 for the no-chapter fallback). */
    val chapterCount: Int,
    /** Chapter title; blank for the no-chapter fallback (caller fills the book title). */
    val title: String,
    /** True when this item is a chapter of a real chapter timeline. */
    val isChapter: Boolean,
    /**
     * Global start of this chapter on the book timeline (file position for
     * single-file books, cumulative for one-file-per-chapter folders).
     */
    val globalStartMs: Long,
    /** Clip start inside [uri], or null when the item plays the whole file. */
    val clipStartMs: Long?,
    /** Clip end inside [uri], or null when open-ended. */
    val clipEndMs: Long?
)

object AudiobookPlaybackPlan {

    sealed interface Result {
        /** A complete, playable timeline for [bookId]. */
        data class Planned(
            val bookId: Long,
            val items: List<PlaybackItemPlan>,
            /** True when the items are chapters (clipped when they share one file). */
            val chapterTimeline: Boolean
        ) : Result

        /** The selection cannot be turned into playable items. */
        data class Unresolvable(val bookId: Long, val reason: Reason) : Result
    }

    enum class Reason {
        /** No chapters and no readable source file (missing/deleted file). */
        NO_PLAYABLE_SOURCE
    }

    /**
     * Builds the complete playable item list for one book.
     *
     * Chapters that share the same media URI (embedded M4B chapters, or one file per
     * chapter where chapters fall back to the same source) are clipped to their
     * interval; chapters with their own distinct file keep the whole file unclipped.
     * The math is an extraction of the previous inline `loadBook` implementation — it
     * must not change the produced timeline.
     */
    fun plan(
        bookId: Long,
        chapters: List<AudiobookChapter>,
        fallbackSourceUri: String?
    ): Result {
        val source = fallbackSourceUri?.takeIf { it.isNotBlank() }
        if (chapters.isEmpty()) {
            return source?.let {
                Result.Planned(
                    bookId = bookId,
                    items = listOf(
                        PlaybackItemPlan(
                            mediaId = bookId.toString(),
                            uri = it,
                            bookId = bookId,
                            chapterIndex = 0,
                            chapterCount = 0,
                            title = "",
                            isChapter = false,
                            globalStartMs = 0L,
                            clipStartMs = null,
                            clipEndMs = null
                        )
                    ),
                    chapterTimeline = false
                )
            } ?: Result.Unresolvable(bookId, Reason.NO_PLAYABLE_SOURCE)
        }

        val uris = chapters.map { ch ->
            ch.mediaUri?.takeIf { it.isNotBlank() } ?: source
        }
        // A blank URI would hand the player an item that cannot prepare. Fail instead
        // of silently producing a timeline that never starts (the RC bug class).
        if (uris.any { it.isNullOrBlank() }) {
            return Result.Unresolvable(bookId, Reason.NO_PLAYABLE_SOURCE)
        }

        val uriCounts = HashMap<String, Int>()
        uris.forEach { uri -> uriCounts[uri!!] = (uriCounts[uri] ?: 0) + 1 }
        val uriBases = HashMap<String, Long>()

        val items = chapters.mapIndexed { position, ch ->
            val uri = uris[position]!!
            val sharedFile = (uriCounts[uri] ?: 0) > 1
            val clipStartMs: Long?
            val clipEndMs: Long?
            if (sharedFile) {
                val base = uriBases.getOrPut(uri) { ch.startMs }
                clipStartMs = (ch.startMs - base).coerceAtLeast(0L)
                clipEndMs = ch.endMs?.takeIf { it > ch.startMs }?.let { it - base }
            } else {
                clipStartMs = null
                clipEndMs = null
            }
            PlaybackItemPlan(
                mediaId = AudiobookLibraryTree.timelineMediaId(bookId, ch.index),
                uri = uri,
                bookId = bookId,
                chapterIndex = ch.index,
                chapterCount = chapters.size,
                title = ch.title,
                isChapter = true,
                globalStartMs = ch.startMs,
                clipStartMs = clipStartMs,
                clipEndMs = clipEndMs
            )
        }
        return Result.Planned(bookId, items, chapterTimeline = true)
    }

    /**
     * Resume target mapped onto the planned items, identical to the previous inline
     * `loadBook` math: progress fraction x total duration, resolved to the last
     * chapter starting at or before that global position.
     *
     * @return item index + offset inside it, or null when there is nothing to resume.
     */
    fun resumeTarget(
        planned: Result.Planned,
        progress: Float,
        totalDurationMs: Long
    ): Pair<Int, Long>? {
        if (progress <= 0f || totalDurationMs <= 0L) return null
        if (!planned.chapterTimeline) {
            return 0 to (progress * totalDurationMs).toLong().coerceAtLeast(0L)
        }
        val targetMs = (progress * totalDurationMs).toLong()
        val index = planned.items.indexOfLast { it.globalStartMs <= targetMs }.coerceAtLeast(0)
        val offsetMs = (targetMs - planned.items[index].globalStartMs).coerceAtLeast(0L)
        return index to offsetMs
    }
}