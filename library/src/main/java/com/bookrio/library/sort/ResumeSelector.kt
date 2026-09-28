package com.bookrio.library.sort

/**
 * Smart multi-book continue ("Fortsett") selection.
 *
 * Pure Kotlin so it is unit-testable on the JVM. The ViewModel maps
 * BookEntity + ReadingProgressEntity into [ResumeBook]; this object picks the
 * active candidates for the thin resume strip — never a parallel database.
 *
 * Active candidate = local item, progress > 0, below completed threshold,
 * genuine activity timestamp available, correct media type for the tab.
 */
object ResumeSelector {

    const val MAX_CANDIDATES = 5
    const val COMPLETED_THRESHOLD = 0.99f

    data class ResumeBook(
        val id: Long,
        val title: String,
        val author: String,
        val progressPercent: Float,
        val positionMs: Long,
        val durationMs: Long,
        val isAudio: Boolean,
        /** Genuine reading/listening activity (progress.updatedAt, fallback lastOpenedAt). */
        val lastActivity: Long,
        val updatedAt: Long,
        val dateFinished: Long?,
        val isDeleted: Boolean
    )

    data class ResumeCandidate(
        val bookId: Long,
        val title: String,
        val author: String,
        val detail: String,
        val lastActivity: Long,
        val updatedAt: Long,
        val id: Long
    )

    /** Deterministic: activity desc → updatedAt desc → stable book id. */
    fun select(
        books: List<ResumeBook>,
        wantAudio: Boolean,
        max: Int = 5,
        now: Long = System.currentTimeMillis(),
        remainingLabel: ((Long) -> String)? = null
    ): List<ResumeCandidate> {
        return books.asSequence()
            .filter { !it.isDeleted }
            .filter { it.isAudio == wantAudio }
            .filter { it.dateFinished == null }
            .filter { it.progressPercent in 0.0001f..COMPLETED_THRESHOLD || it.positionMs > 0L }
            .filter { it.lastActivity > 0L }
            .sortedWith(
                compareByDescending<ResumeBook> { it.lastActivity }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.id }
            )
            .take(max)
            .map { b ->
                ResumeCandidate(
                    bookId = b.id,
                    title = b.title,
                    author = b.author,
                    detail = detailFor(b, remainingLabel),
                    lastActivity = b.lastActivity,
                    updatedAt = b.updatedAt,
                    id = b.id
                )
            }
            .toList()
    }

    private fun detailFor(b: ResumeBook, remainingLabel: ((Long) -> String)?): String {
        val pct = (b.progressPercent.coerceIn(0f, 1f) * 100).toInt()
        return if (b.isAudio) {
            val remaining = (b.durationMs - b.positionMs).coerceAtLeast(0L)
            if (remaining > 0L) (remainingLabel ?: ::formatRemaining)(remaining) else "$pct%"
        } else {
            "$pct%"
        }
    }

    /** Language-neutral fallback; callers inject a localized formatter via [select]. */
    private fun formatRemaining(ms: Long): String {
        val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
}
