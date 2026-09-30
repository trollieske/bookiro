package com.bookrio.library.data

import com.bookrio.data.local.entity.BookEntity
import com.bookrio.library.util.AudiobookNormalizer

/**
 * Pure planning for the BUG A repair: an audiobook imported while a torrent was
 * still incomplete can be persisted with the importer's one-chapter fallback.
 * The planner decides whether the stored list is genuinely that fallback and,
 * if so, rebuilds the real chapters — without any knowledge of the DB, so the
 * decision is unit-testable on the JVM.
 *
 * Invariants:
 *  - a stored list with >= 2 entries is NEVER replaced;
 *  - a single stored entry is only replaced when it is recognisably the
 *    importer fallback (blank title, or title equal to the book title / file
 *    name, and start at 0);
 *  - the rebuilt list always has >= 2 entries, so a valid list can never be
 *    replaced by a one-chapter fallback;
 *  - no chapters are ever synthesised at fixed intervals from nothing.
 */

/** Minimal read-only chapter row used by the planner. */
data class RepairChapterInput(
    val title: String,
    val startMs: Long,
    val endMs: Long? = null
)

/** Minimal read-only snapshot of an `audio_tracks` row. */
data class RepairTrackInput(
    val id: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val title: String,
    val durationMs: Long,
    val filePath: String?,
    val fileUri: String?,
    val fileSizeBytes: Long = 0L
)

/** A rebuilt chapter, ready to be serialised to `chapters_json`. */
data class RepairedChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val mediaUri: String?,
    val filePath: String?,
    val durationMs: Long
)

/**
 * What to persist for a book whose stored chapter list is a one-chapter
 * fallback. [trackNumbers] maps existing track row ids (in playback order) to
 * the sequential 1..N numbering ecf62df introduced; it is empty for the
 * single-file path.
 */
data class OneChapterRepairPlan(
    val chapters: List<RepairedChapter>,
    val trackNumbers: List<Pair<Long, Int>>
)

object OneChapterRepairPlanner {

    /** Same fallback duration the importer/consolidator uses for unknown track lengths. */
    const val FALLBACK_TRACK_DURATION_MS = 5L * 60L * 1000L

    /**
     * @param stored            parsed `chapters_json` (empty when blank/malformed)
     * @param tracks            `audio_tracks` rows for the book
     * @param reparsed          embedded chapters freshly parsed from a single audio file
     * @param primaryDurationMs book/track duration, used as the last chapter's end
     * @return null when the book must be left untouched.
     */
    fun plan(
        bookTitle: String,
        fileNameStem: String?,
        stored: List<RepairChapterInput>,
        tracks: List<RepairTrackInput>,
        reparsed: List<RepairChapterInput> = emptyList(),
        primaryDurationMs: Long = 0L
    ): OneChapterRepairPlan? {
        if (stored.size >= 2) return null
        if (stored.size == 1 && !isOneChapterFallback(bookTitle, fileNameStem, stored[0])) return null

        // Multi-track: rebuild one chapter per track, in playback order, and
        // renumber the tracks sequentially (same as consolidateFragmentedAudiobooks).
        if (tracks.size >= 2) {
            val ordered = orderTracks(tracks)
            var offset = 0L
            val chapters = ordered.mapIndexed { idx, track ->
                val duration = track.durationMs.takeIf { it > 0L } ?: FALLBACK_TRACK_DURATION_MS
                val start = offset
                offset += duration
                RepairedChapter(
                    index = idx,
                    title = track.title,
                    startMs = start,
                    endMs = offset,
                    mediaUri = track.fileUri?.takeIf { it.isNotBlank() } ?: track.filePath,
                    filePath = track.filePath,
                    durationMs = duration
                )
            }
            return OneChapterRepairPlan(
                chapters = chapters,
                trackNumbers = ordered.mapIndexed { idx, track -> track.id to idx + 1 }
            )
        }

        // Single file: only a re-parse that found MORE THAN ONE real embedded
        // chapter may replace the fallback. Reparsed equal/less than the stored
        // list is ignored, so the fallback survives.
        if (reparsed.size <= 1) return null
        val primary = tracks.firstOrNull()
        val primaryUri = primary?.fileUri?.takeIf { it.isNotBlank() } ?: primary?.filePath
        val sorted = reparsed.sortedBy { it.startMs }
        val chapters = sorted.mapIndexed { idx, chapter ->
            val start = chapter.startMs.coerceAtLeast(0L)
            val end = chapter.endMs?.takeIf { it > start }
                ?: sorted.getOrNull(idx + 1)?.startMs?.takeIf { it > start }
                ?: primaryDurationMs.takeIf { it > start }
                ?: (start + FALLBACK_TRACK_DURATION_MS)
            RepairedChapter(
                index = idx,
                title = chapter.title,
                startMs = start,
                endMs = end,
                mediaUri = primaryUri,
                filePath = primary?.filePath,
                durationMs = (end - start).coerceAtLeast(1L)
            )
        }
        return OneChapterRepairPlan(chapters = chapters, trackNumbers = emptyList())
    }

    /**
     * Applies a regeneration plan to a book row. ONLY the chapter fields change
     * (`chapters_json`, `chapter_count`, `last_modified_at`); everything that
     * identifies the book or its reading state keeps the same value, so a repair
     * can never orphan the ReadingProgressEntity or the audio-track rows.
     */
    fun repairedBook(
        book: BookEntity,
        plan: OneChapterRepairPlan,
        chaptersJson: String,
        repairedAt: Long = System.currentTimeMillis()
    ): BookEntity = book.copy(
        chaptersJson = chaptersJson,
        chapterCount = plan.chapters.size,
        lastModifiedAt = repairedAt
    )

    /**
     * True when a single stored entry is the importer's synthetic/fallback entry
     * rather than a real chapter: blank title, or title matching the book title /
     * audio file stem (same containment rule as ChapterRefresh.titlesMatch), and
     * starting at 0.
     */
    internal fun isOneChapterFallback(
        bookTitle: String,
        fileNameStem: String?,
        chapter: RepairChapterInput
    ): Boolean {
        if (chapter.startMs > 0L) return false
        val title = AudiobookNormalizer.normalizeString(chapter.title)
        if (title.isBlank()) return true
        if (titleMatches(title, AudiobookNormalizer.normalizeString(bookTitle))) return true
        return titleMatches(title, AudiobookNormalizer.normalizeString(fileNameStem))
    }

    private fun titleMatches(normalizedTitle: String, normalizedOther: String): Boolean {
        if (normalizedTitle.isBlank() || normalizedOther.isBlank()) return false
        return normalizedTitle == normalizedOther ||
            normalizedTitle.contains(normalizedOther) ||
            normalizedOther.contains(normalizedTitle)
    }

    /**
     * Track playback order: trackNumber first; when every track carries the same
     * (or no) number, fall back to natural file-name order (1, 2, 10 — never 1, 10, 2).
     */
    internal fun orderTracks(tracks: List<RepairTrackInput>): List<RepairTrackInput> {
        val numbered = tracks.sortedWith(
            compareBy<RepairTrackInput> { it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }
                .thenBy { it.filePath ?: it.fileUri ?: "" }
        )
        val ambiguous = numbered.map { it.trackNumber }.toSet().size == 1
        if (!ambiguous) return numbered
        return numbered.sortedWith { a, b ->
            naturalFileNameCompare(trackOrderKey(a), trackOrderKey(b))
        }
    }

    private fun trackOrderKey(track: RepairTrackInput): String =
        track.filePath?.substringAfterLast('/')
            ?: track.fileUri?.substringAfterLast('/')
            ?: track.title

    /**
     * Natural ordering shared with `BookImportRepository.consolidateFragmentedAudiobooks`:
     * digit runs compare numerically, so `…009` < `…010` < `…100`.
     */
    internal fun naturalFileNameCompare(a: String, b: String): Int {
        val al = a.lowercase()
        val bl = b.lowercase()
        var i = 0
        var j = 0
        while (i < al.length && j < bl.length) {
            val ca = al[i]
            val cb = bl[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i
                while (i2 < al.length && al[i2].isDigit()) i2++
                var j2 = j
                while (j2 < bl.length && bl[j2].isDigit()) j2++
                val na = al.substring(i, i2).trimStart('0')
                val nb = bl.substring(j, j2).trimStart('0')
                val cmp = when {
                    na.length != nb.length -> na.length - nb.length
                    else -> na.compareTo(nb)
                }
                if (cmp != 0) return cmp
                i = i2
                j = j2
            } else {
                val cmp = ca.compareTo(cb)
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return al.length - bl.length
    }
}

/**
 * Filename with extension for embedded-chapter parsing. Same contract as
 * `ChapterRefresh.chapterParserFileName` in :player; mirrored here so :library
 * does not need a dependency on the playback module.
 */
internal fun chapterParserFileName(filePath: String?, fileUri: String?, formatName: String): String {
    val fromPath = filePath?.substringBefore('?')?.substringAfterLast('/')
    if (!fromPath.isNullOrBlank() && fromPath.contains('.')) return fromPath
    val fromUri = fileUri?.substringBefore('?')?.substringAfterLast('/')
    if (!fromUri.isNullOrBlank() && fromUri.contains('.')) return fromUri
    return "audio.${formatName.lowercase()}"
}