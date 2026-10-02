package com.bookrio.data.repository

import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.dispatchers.DispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.dao.WorkWithEditions
import com.bookrio.data.local.entity.*
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class ResolvedHandoff(
    val targetBookId: Long,
    val targetIsAudiobook: Boolean,
    val chapterLabel: String? = null,
    val positionFraction: Float,
    val positionMs: Long? = null,
    val chapterIndex: Int? = null,
    val mappingMethod: String,
    val wasEstimate: Boolean
)

data class EditionLinkResult(
    val workId: Long,
    val createdNewWork: Boolean,
    val ebookEditionId: Long?,
    val audiobookEditionId: Long?,
    val autoMatched: Boolean,
    val confidence: Float
)

class HandoffRepository(
    private val db: ShelfDatabase,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) {
    private val workDao: com.bookrio.data.local.dao.WorkDao get() = db.workDao()
    private val editionDao: com.bookrio.data.local.dao.WorkEditionDao get() = db.workEditionDao()
    private val linkDao: com.bookrio.data.local.dao.HandoffLinkDao get() = db.handoffLinkDao()
    private val workWithEditionsDao: com.bookrio.data.local.dao.WorkWithEditionsDao get() = db.workWithEditionsDao()
    private val bookDao: com.bookrio.data.local.dao.BookDao get() = db.bookDao()
    private val progressDao: com.bookrio.data.local.dao.ReadingProgressDao get() = db.progressDao()

    fun observeWorkForBook(bookId: Long): Flow<WorkWithEditions?> =
        workWithEditionsDao.observeWorkContainingBook(bookId)

    suspend fun getWorkForBook(bookId: Long): WorkWithEditions? =
        withContext(dispatchers.io) { workWithEditionsDao.getWorkContainingBook(bookId) }

    suspend fun findSiblingBookId(bookId: Long): Long? = withContext(dispatchers.io) {
        val work = workWithEditionsDao.getWorkContainingBook(bookId) ?: return@withContext null
        val other = work.editions.firstOrNull { it.bookId != bookId } ?: return@withContext null
        other.bookId
    }

    suspend fun findSiblingBookIdOfType(bookId: Long, type: EditionTypeEntity): Long? =
        withContext(dispatchers.io) {
            editionDao.findSiblingEditionOfType(bookId, type)?.bookId
        }

    suspend fun autoLinkIfPossible(ebookId: Long, audiobookId: Long): EditionLinkResult? =
        withContext(dispatchers.io) {
            val ebook = bookDao.getById(ebookId) ?: return@withContext null
            val audio = bookDao.getById(audiobookId) ?: return@withContext null
            val match = WorkMatcher.matchBooks(ebook, audio) ?: return@withContext null
            linkEditionsInternal(ebook, audio, match, manual = false)
        }

    suspend fun linkEditionsManually(ebookId: Long, audiobookId: Long): EditionLinkResult? =
        withContext(dispatchers.io) {
            val ebook = bookDao.getById(ebookId) ?: return@withContext null
            val audio = bookDao.getById(audiobookId) ?: return@withContext null
            val match = WorkMatcher.matchBooks(ebook, audio) ?: WorkMatcher.MatchResult(
                strength = MatchStrengthEntity.MEDIUM_TITLE_AUTHOR,
                confidence = 0.7f,
                reason = "Manuell kobling"
            )
            linkEditionsInternal(ebook, audio, match, manual = true)
        }

    suspend fun unlinkBook(bookId: Long) = withContext(dispatchers.io) {
        val edition = editionDao.getByBookId(bookId) ?: return@withContext
        editionDao.delete(edition)
        val remaining = editionDao.getByWorkId(edition.workId)
        if (remaining.isEmpty()) {
            linkDao.deleteByWorkId(edition.workId)
            workDao.getById(edition.workId)?.let { workDao.delete(it) }
        } else {
            bookDao.getById(bookId)?.let { b ->
                val canonical =
                    WorkMatcher.normalizeTitle(b.title).ifBlank { b.title.lowercase() }
                if (canonical.isBlank()) {
                    linkDao.deleteByWorkId(edition.workId)
                    workDao.getById(edition.workId)?.let { workDao.delete(it) }
                }
            }
        }
    }

    suspend fun scanAndLinkLibrary(
        onProgress: ((done: Int, total: Int, pairs: Int) -> Unit)? = null
    ): Int = withContext(dispatchers.io) {
        val all = bookDao.getAllOnce()
        val ebooks = all.filter { it.type == BookTypeEntity.EBOOK || it.type == BookTypeEntity.MIXED }
        val audios = all.filter { it.type == BookTypeEntity.AUDIOBOOK || it.type == BookTypeEntity.MIXED }
        var linked = 0
        val done = IntArray(1)
        val total = ebooks.size * audios.size
        for (e in ebooks) {
            if (editionDao.getByBookId(e.id) != null) {
                done[0] += audios.size
                continue
            }
            var best: Pair<BookEntity, WorkMatcher.MatchResult>? = null
            for (a in audios) {
                if (editionDao.getByBookId(a.id) != null) {
                    done[0] += 1
                    continue
                }
                val m = WorkMatcher.matchBooks(e, a)
                if (m != null && m.confidence >= 0.75f) {
                    val current = best
                    if (current == null || m.confidence > current.second.confidence) {
                        best = a to m
                    }
                }
                done[0] += 1
                if (total > 0 && done[0] % 50 == 0) {
                    onProgress?.invoke(done[0], total, linked)
                }
            }
            val pair = best
            if (pair != null) {
                linkEditionsInternal(e, pair.first, pair.second, manual = false)
                linked += 1
            }
        }
        onProgress?.invoke(total.coerceAtLeast(1), total.coerceAtLeast(1), linked)
        linked
    }

    data class HandoffContext(
        val fromProgressFraction: Float = 0f,
        val fromChapterIndex: Int? = null,
        val fromChapterLabel: String? = null,
        val fromPositionMs: Long? = null,
        val fromTotalDurationMs: Long? = null,
        val fromEbookTocTitles: List<String> = emptyList(),
        val toAudioChapterTitles: List<String> = emptyList(),
        val toAudioChapterDurationsMs: List<Long> = emptyList(),
        val toAudioTotalDurationMs: Long? = null,
        val toEbookTocTitles: List<String> = emptyList(),
        val precision: HandoffPrecisionEntity = HandoffPrecisionEntity.SMART
    )

    suspend fun resolveHandoffPosition(
        fromBookId: Long,
        toBookId: Long,
        ctx: HandoffContext = HandoffContext()
    ): ResolvedHandoff = withContext(dispatchers.default) {
        val fromBook = bookDao.getById(fromBookId)
        val toBook = bookDao.getById(toBookId)
        val fromIsEbook = fromBook?.type != BookTypeEntity.AUDIOBOOK
        val toIsAudiobook = toBook?.type == BookTypeEntity.AUDIOBOOK || toBook?.type == BookTypeEntity.MIXED

        val fraction = when {
            ctx.fromProgressFraction.isFinite() -> ctx.fromProgressFraction.coerceIn(0f, 1f)
            ctx.fromPositionMs != null && (ctx.fromTotalDurationMs ?: 0L) > 0 ->
                (ctx.fromPositionMs.toDouble() / (ctx.fromTotalDurationMs ?: 1L).toDouble()).toFloat().coerceIn(0f, 1f)
            else -> 0f
        }

        val ebookChapters = if (fromIsEbook) ctx.fromEbookTocTitles else ctx.toEbookTocTitles
        val audioChapters = ctx.toAudioChapterTitles
        val audioDurations = ctx.toAudioChapterDurationsMs

        var method = "percent_fallback"
        var label: String? = null
        var chapterIndex: Int? = null
        var estimate = true
        var audioMs: Long? = null

        if (ctx.precision != HandoffPrecisionEntity.PERCENT_ONLY &&
            ebookChapters.isNotEmpty() && audioChapters.isNotEmpty()) {

            if (ctx.fromChapterLabel != null) {
                val idx = fuzzyFindChapter(ctx.fromChapterLabel, audioChapters)
                if (idx != null) {
                    chapterIndex = idx
                    label = audioChapters[idx]
                    method = if (ctx.precision == HandoffPrecisionEntity.CHAPTER_ONLY) "chapter_only" else "chapter_title_match"
                    estimate = ctx.precision == HandoffPrecisionEntity.CHAPTER_ONLY && idx !in audioChapters.indices
                    if (audioDurations.isNotEmpty() && idx in audioDurations.indices) {
                        val upTo = audioDurations.take(idx).sum()
                        audioMs = upTo
                    }
                }
            }

            if (chapterIndex == null && ctx.precision == HandoffPrecisionEntity.SMART) {
                val ratio = ebookChapters.size.toDouble() / audioChapters.size.toDouble()
                if (ratio in 0.66..1.5 && ctx.fromChapterIndex != null) {
                    val mapped = (ctx.fromChapterIndex.toDouble() / ratio).toInt()
                        .coerceIn(0, audioChapters.size - 1)
                    chapterIndex = mapped
                    label = audioChapters[mapped]
                    method = "chapter_index_ratio"
                    estimate = true
                    if (audioDurations.isNotEmpty() && mapped in audioDurations.indices) {
                        audioMs = audioDurations.take(mapped).sum()
                    }
                }
            }
        }

        val finalAudioMs = if (audioMs == null && toIsAudiobook) {
            val total = ctx.toAudioTotalDurationMs ?: 0L
            if (total > 0) (fraction * total).toLong().coerceIn(0L, total) else null
        } else {
            audioMs
        }

        ResolvedHandoff(
            targetBookId = toBookId,
            targetIsAudiobook = toIsAudiobook,
            chapterLabel = label,
            positionFraction = fraction,
            positionMs = finalAudioMs,
            chapterIndex = chapterIndex,
            mappingMethod = method,
            wasEstimate = estimate || method == "percent_fallback"
        )
    }

    suspend fun recordHandoff(
        fromEditionBookId: Long,
        toEditionBookId: Long,
        precision: HandoffPrecisionEntity,
        fromFraction: Float,
        toFraction: Float,
        fromChapter: String?,
        toChapter: String?,
        method: String,
        estimate: Boolean
    ) = withContext(dispatchers.io) {
        val fromEd = editionDao.getByBookId(fromEditionBookId) ?: return@withContext
        val toEd = editionDao.getByBookId(toEditionBookId) ?: return@withContext
        val work = workDao.getById(fromEd.workId)
        if (work != null) {
            workDao.update(work.copy(lastActiveEditionId = toEd.id, lastUpdatedAt = System.currentTimeMillis()))
        }
        linkDao.insert(
            HandoffLinkEntity(
                workId = fromEd.workId,
                fromEditionId = fromEd.id,
                toEditionId = toEd.id,
                fromPositionFraction = fromFraction,
                toPositionFraction = toFraction,
                fromChapterLabel = fromChapter,
                toChapterLabel = toChapter,
                precisionUsed = precision,
                mappingMethod = method,
                wasEstimate = estimate
            )
        )
    }

    private suspend fun linkEditionsInternal(
        ebook: BookEntity,
        audiobook: BookEntity,
        match: WorkMatcher.MatchResult,
        manual: Boolean
    ): EditionLinkResult {
        var result: EditionLinkResult? = null
        // Room's suspend transaction. The previous `db.runInTransaction { runBlocking { ... } }`
        // blocked the IO thread inside the transaction and could deadlock against the
        // query executor on the ebook<->audio handoff path.
        db.withTransaction {
            val existingEbook = editionDao.getByBookId(ebook.id)
            val existingAudio = editionDao.getByBookId(audiobook.id)

            val workId = when {
                existingEbook != null -> existingEbook.workId
                existingAudio != null -> existingAudio.workId
                else -> {
                    val canonical = WorkMatcher.normalizeTitle(ebook.title).ifBlank { audiobook.title.lowercase() }.trim()
                    val auth = WorkMatcher.normalizeAuthor(ebook.author).ifBlank { audiobook.author }
                    workDao.insert(
                        WorkEntity(
                            canonicalTitle = canonical.ifBlank { ebook.title },
                            canonicalAuthor = auth.ifBlank { ebook.author },
                            isbn = WorkMatcher.normalizeIsbn(ebook.isbn) ?: WorkMatcher.normalizeIsbn(audiobook.isbn),
                            series = ebook.series ?: audiobook.series,
                            seriesIndex = ebook.seriesIndex ?: audiobook.seriesIndex
                        )
                    )
                }
            }
            val created = existingEbook == null && existingAudio == null
            val eId = editionDao.ensureEditionForBook(workId, ebook.id, EditionTypeEntity.EBOOK, match, manual)
            val aId = editionDao.ensureEditionForBook(workId, audiobook.id, EditionTypeEntity.AUDIOBOOK, match, manual)
            result = EditionLinkResult(
                workId = workId,
                createdNewWork = created,
                ebookEditionId = eId,
                audiobookEditionId = aId,
                autoMatched = !manual,
                confidence = match.confidence
            )
        }
        return result!!
    }

    private fun fuzzyFindChapter(needleRaw: String, haystack: List<String>): Int? {
        if (haystack.isEmpty()) return null
        val needle = WorkMatcher.normalizeTitle(needleRaw)
        if (needle.isBlank()) return null
        var bestIdx = -1
        var bestScore = 0f
        haystack.forEachIndexed { i, raw ->
            val candidate = WorkMatcher.normalizeTitle(raw)
            if (candidate.isBlank()) return@forEachIndexed
            val sim = WorkMatcher.normalizedSimilarity(needle, candidate)
            val tokenSim = WorkMatcher.tokenJaccard(needle, candidate)
            val final = (sim * 0.5f) + (tokenSim * 0.5f)
            if (final > bestScore) {
                bestScore = final
                bestIdx = i
            }
        }
        return if (bestScore >= 0.45f) bestIdx else null
    }
}
