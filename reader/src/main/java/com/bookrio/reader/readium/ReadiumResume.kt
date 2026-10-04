package com.bookrio.reader.readium

import com.bookrio.data.local.entity.BookmarkEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Resume intent for an EPUB, derived only from columns that already exist in the
 * Bookiro database. Nothing here mutates storage.
 *
 * Legacy model (pre-Readium reader): positions were `chapterIndex` + chapter-local
 * `pageIndex` + chapter-local `progressPercent`. A page number is **not** a stable text
 * position after reflow, so `pageIndex` is never translated into a page number. What we
 * can defend is the chapter identity plus the chapter-local fraction, which becomes a
 * within-resource `Locator.locations.progression`.
 */
internal sealed interface ResumeIntent {

    /** No usable position at all: open at the start. */
    data object StartFresh : ResumeIntent

    /** A Readium Locator JSON was stored (new reader): restore it exactly. */
    data class StoredLocator(val locatorJson: String) : ResumeIntent

    /** Legacy chapter identity + chapter-local fraction: nearest text, resolved via TOC/spine. */
    data class LegacyChapter(val chapterIndex: Int, val chapterProgression: Double) : ResumeIntent

    /**
     * Legacy percent exists but there is no chapter identity. The global progression alone
     * cannot be mapped to a resource without computing pagination positions, so the book is
     * opened at the start and the legacy values are kept untouched.
     */
    data class LegacyUnresolved(val progressPercent: Double) : ResumeIntent
}

/** Result of resolving a [ResumeIntent] against a real publication. */
internal data class ResolvedPosition(
    val locator: Locator,
    /** True when the locator is a best-effort approximation of an old page-based position. */
    val approximate: Boolean,
)

internal object ReadiumResume {

    /** Below this a stored fraction is treated as "no progress". */
    const val EPSILON = 0.001

    /** True when the legacy row says the book was started (never reset such a row silently). */
    fun wasStarted(saved: ReadingProgressEntity?): Boolean = saved != null && (
        (saved.progressPercent ?: 0f) > EPSILON ||
            (saved.pageIndex ?: 0) > 0 ||
            saved.chapterIndex != null ||
            !saved.anchorCfi.isNullOrBlank()
        )

    /** Resume decision for `reading_progress`. */
    fun progressIntent(saved: ReadingProgressEntity?): ResumeIntent {
        if (saved == null) return ResumeIntent.StartFresh
        saved.anchorCfi?.takeIf { it.isNotBlank() }?.let {
            return ResumeIntent.StoredLocator(it)
        }
        val chapterIndex = saved.chapterIndex
        val chapterPct = (saved.progressPercent ?: 0f).coerceIn(0f, 1f).toDouble()
        if (chapterIndex != null && chapterIndex >= 0 && wasStarted(saved)) {
            return ResumeIntent.LegacyChapter(chapterIndex, chapterPct)
        }
        if (chapterPct > EPSILON) return ResumeIntent.LegacyUnresolved(chapterPct)
        return ResumeIntent.StartFresh
    }

    /**
     * Jump decision for a stored bookmark. New bookmarks carry `anchor_cfi`; legacy bookmarks
     * carry `chapter_index` + chapter-local `position_percent`. `page_index` is deliberately
     * ignored: after reflow the old page number means nothing.
     */
    fun bookmarkIntent(bookmark: BookmarkEntity): ResumeIntent {
        bookmark.anchorCfi?.takeIf { it.isNotBlank() }?.let {
            return ResumeIntent.StoredLocator(it)
        }
        val chapterIndex = bookmark.chapterIndex
        val chapterPct = (bookmark.positionPercent ?: 0f).coerceIn(0f, 1f).toDouble()
        if (chapterIndex != null && chapterIndex >= 0) {
            return ResumeIntent.LegacyChapter(chapterIndex, chapterPct)
        }
        if (chapterPct > EPSILON) return ResumeIntent.LegacyUnresolved(chapterPct)
        return ResumeIntent.StartFresh
    }

    /**
     * Resolves a [ResumeIntent] to a concrete Readium Locator, or null when the publication
     * cannot honour it.
     *
     * For [ResumeIntent.LegacyChapter] the legacy `chapterIndex` is mapped by ordinal onto
     * `publication.tableOfContents` (flattened) or, when the publication has no usable TOC,
     * onto the spine. This is exact when both chapter lists agree — the common flat-nav case.
     * A hierarchical TOC can drift by the number of nested entries; an index past the end is
     * clamped to the *last* chapter, never back to the start. The within-chapter fraction is
     * applied as `Locations.progression`, which Readium's reflowable layout resolves with
     * page-level granularity (R2EpubPageFragment.goToLocator).
     */
    fun resolve(
        publication: Publication,
        chapters: BookChapters,
        intent: ResumeIntent,
    ): ResolvedPosition? = when (intent) {
        ResumeIntent.StartFresh -> null
        is ResumeIntent.LegacyUnresolved -> null
        is ResumeIntent.StoredLocator ->
            runCatching { Locator.fromJSON(JSONObject(intent.locatorJson)) }
                .getOrNull()
                ?.let { ResolvedPosition(it, approximate = false) }

        is ResumeIntent.LegacyChapter -> {
            val entry = chapters.entries.getOrNull(intent.chapterIndex)
                ?: chapters.entries.lastOrNull()
            val link = entry?.link
                ?: publication.readingOrder.getOrNull(intent.chapterIndex)
                ?: publication.readingOrder.lastOrNull()
            link?.let { publication.locatorFromLink(it) }?.let { base ->
                val locator = if (intent.chapterProgression > EPSILON) {
                    base.copy(
                        locations = base.locations.copy(
                            progression = intent.chapterProgression.coerceIn(0.0, 1.0),
                            totalProgression = null,
                        ),
                    )
                } else {
                    base
                }
                ResolvedPosition(locator, approximate = true)
            }
        }
    }

    /**
     * A single Locator JSON written into `anchor_cfi`. Readium 3.0.3 exposes one canonical
     * locator per position/selection (the selection quote itself spans the range), so callers
     * must not expect a separate end locator.
     */
    fun locatorJson(locator: Locator): String? =
        runCatching { locator.toJSON().toString() }.getOrNull()
}