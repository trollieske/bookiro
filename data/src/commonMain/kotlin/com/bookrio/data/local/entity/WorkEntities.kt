package com.bookrio.data.local.entity

import com.bookrio.core.time.nowMillis
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EditionTypeEntity { EBOOK, AUDIOBOOK }
enum class HandoffPrecisionEntity { CHAPTER_ONLY, SMART, PERCENT_ONLY }
enum class MatchStrengthEntity { STRONG_ISBN, STRONG_UUID, STRONG_SERIES, MEDIUM_TITLE_AUTHOR, MEDIUM_CUSTOM_META, WEAK_FILENAME }

@Entity(
    tableName = "works",
    indices = [Index("canonical_title"), Index("canonical_author")]
)
data class WorkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "canonical_title") val canonicalTitle: String,
    @ColumnInfo(name = "canonical_author") val canonicalAuthor: String = "",
    @ColumnInfo(name = "isbn") val isbn: String? = null,
    @ColumnInfo(name = "series") val series: String? = null,
    @ColumnInfo(name = "series_index") val seriesIndex: Float? = null,
    @ColumnInfo(name = "last_active_edition_id") val lastActiveEditionId: Long? = null,
    @ColumnInfo(name = "last_updated_at") val lastUpdatedAt: Long = nowMillis(),
    @ColumnInfo(name = "created_at") val createdAt: Long = nowMillis()
)

@Entity(
    tableName = "work_editions",
    foreignKeys = [
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["id"],
            childColumns = ["work_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("work_id"),
        Index("book_id", unique = true),
        Index("edition_type")
    ]
)
data class WorkEditionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "work_id") val workId: Long,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "edition_type") val editionType: EditionTypeEntity,
    @ColumnInfo(name = "match_strength") val matchStrength: MatchStrengthEntity? = null,
    @ColumnInfo(name = "match_confidence") val matchConfidence: Float = 0f,
    @ColumnInfo(name = "linked_manually") val linkedManually: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = nowMillis()
)

@Entity(
    tableName = "handoff_links",
    foreignKeys = [
        ForeignKey(
            entity = WorkEditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["from_edition_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WorkEditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["to_edition_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("from_edition_id"),
        Index("to_edition_id"),
        Index("created_at")
    ]
)
data class HandoffLinkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "work_id") val workId: Long,
    @ColumnInfo(name = "from_edition_id") val fromEditionId: Long,
    @ColumnInfo(name = "to_edition_id") val toEditionId: Long,
    @ColumnInfo(name = "from_position_fraction") val fromPositionFraction: Float = 0f,
    @ColumnInfo(name = "to_position_fraction") val toPositionFraction: Float = 0f,
    @ColumnInfo(name = "from_chapter_label") val fromChapterLabel: String? = null,
    @ColumnInfo(name = "to_chapter_label") val toChapterLabel: String? = null,
    @ColumnInfo(name = "precision_used") val precisionUsed: HandoffPrecisionEntity = HandoffPrecisionEntity.SMART,
    @ColumnInfo(name = "mapping_method") val mappingMethod: String = "",
    @ColumnInfo(name = "was_estimate") val wasEstimate: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = nowMillis()
)

object WorkMatcher {

    data class MatchResult(
        val strength: MatchStrengthEntity,
        val confidence: Float,
        val reason: String
    )

    fun normalizeTitle(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw.trim().lowercase()
        val colonIdx = t.indexOfAny(listOf(":", "–", "—", "-", "|"), ignoreCase = true)
        if (colonIdx > 0) t = t.substring(0, colonIdx)
        t = t.replace("[\\p{Punct}]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
        val leadingArticles = listOf("the ", "a ", "an ", "en ", "ei ", "et ", "der ", "die ", "das ", "le ", "la ", "les ", "il ", "lo ", "i ", "un ", "una ", "uno ")
        for (a in leadingArticles) {
            if (t.startsWith(a)) {
                t = t.removePrefix(a).trim()
                break
            }
        }
        return t
    }

    fun normalizeAuthor(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.trim().lowercase()
            .replace("[\\p{Punct}]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun normalizeIsbn(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() || it.equals('X', ignoreCase = true) }
        if (digits.length !in 10..13) return null
        return digits.uppercase()
    }

    fun tokenJaccard(a: String, b: String): Float {
        if (a.isBlank() && b.isBlank()) return 1f
        if (a.isBlank() || b.isBlank()) return 0f
        val ta = a.split(" ").filter { it.length >= 2 }.toSet()
        val tb = b.split(" ").filter { it.length >= 2 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val inter = ta.intersect(tb).size
        val union = (ta union tb).size
        return if (union == 0) 0f else inter.toFloat() / union.toFloat()
    }

    fun levenshtein(a: String, b: String): Int {
        when {
            a == b -> return 0
            a.isEmpty() -> return b.length
            b.isEmpty() -> return a.length
        }
        val la = a.length
        val lb = b.length
        var prev = IntArray(lb + 1) { it }
        for (i in 1..la) {
            val curr = IntArray(lb + 1)
            curr[0] = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            prev = curr
        }
        return prev[lb]
    }

    fun normalizedSimilarity(a: String, b: String): Float {
        val na = normalizeTitle(a)
        val nb = normalizeTitle(b)
        if (na == nb && na.isNotBlank()) return 0.98f
        if (na.isBlank() || nb.isBlank()) return 0f
        val j = tokenJaccard(na, nb)
        val maxLen = maxOf(na.length, nb.length)
        val dist = levenshtein(na, nb)
        val levSim = if (maxLen == 0) 0f else 1f - (dist.toFloat() / maxLen.toFloat())
        return (0.6f * j) + (0.4f * levSim)
    }

    fun matchBooks(a: BookEntity, b: BookEntity): MatchResult? {
        if (a.id == b.id) return null
        val aIsEbook = a.type == BookTypeEntity.EBOOK || a.type == BookTypeEntity.MIXED
        val bIsEbook = b.type == BookTypeEntity.EBOOK || b.type == BookTypeEntity.MIXED
        val aIsAudio = a.type == BookTypeEntity.AUDIOBOOK || a.type == BookTypeEntity.MIXED
        val bIsAudio = b.type == BookTypeEntity.AUDIOBOOK || b.type == BookTypeEntity.MIXED
        val mixedPairOk = (aIsEbook && bIsAudio) || (aIsAudio && bIsEbook)
        if (!mixedPairOk) {
            val sameSeries = !a.series.isNullOrBlank() && a.series.equals(b.series, ignoreCase = true) && a.seriesIndex != null && a.seriesIndex == b.seriesIndex
            val sameIsbn = !normalizeIsbn(a.isbn).isNullOrBlank() && normalizeIsbn(a.isbn) == normalizeIsbn(b.isbn)
            if (!sameSeries && !sameIsbn) return null
        }

        val isbnA = normalizeIsbn(a.isbn)
        val isbnB = normalizeIsbn(b.isbn)
        if (!isbnA.isNullOrBlank() && isbnA == isbnB) {
            return MatchResult(MatchStrengthEntity.STRONG_ISBN, 0.99f, "ISBN=$isbnA")
        }

        val seriesA = a.series?.trim()?.lowercase()
        val seriesB = b.series?.trim()?.lowercase()
        if (!seriesA.isNullOrBlank() && seriesA == seriesB && a.seriesIndex != null && a.seriesIndex == b.seriesIndex) {
            return MatchResult(MatchStrengthEntity.STRONG_SERIES, 0.92f, "Serie=$seriesA #${a.seriesIndex}")
        }

        val titleSim = normalizedSimilarity(a.title, b.title)
        val authA = normalizeAuthor(a.author)
        val authB = normalizeAuthor(b.author)
        val authorMatch = authA.isNotBlank() && authB.isNotBlank() && (authA == authB || authA.contains(authB) || authB.contains(authA))
        if (titleSim >= 0.85f && authorMatch) {
            return MatchResult(
                MatchStrengthEntity.MEDIUM_TITLE_AUTHOR,
                (0.75f + 0.25f * titleSim).coerceIn(0f, 1f),
                "Tittel=${"%.0f".format(titleSim * 100)}%, forfatter match"
            )
        }

        val fnA = a.filePath?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
        val fnB = b.filePath?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
        if (fnA.isNotBlank() && fnB.isNotBlank()) {
            val fnSim = normalizedSimilarity(fnA, fnB)
            if (fnSim >= 0.88f && (authorMatch || titleSim >= 0.55f)) {
                return MatchResult(
                    MatchStrengthEntity.WEAK_FILENAME,
                    (0.4f + 0.6f * fnSim).coerceIn(0f, 1f),
                    "Filnavn=${"%.0f".format(fnSim * 100)}%"
                )
            }
        }

        if (titleSim >= 0.92f) {
            return MatchResult(
                MatchStrengthEntity.MEDIUM_TITLE_AUTHOR,
                (0.6f + 0.4f * titleSim).coerceIn(0f, 1f),
                "Tittel=${"%.0f".format(titleSim * 100)}% (uten forfatter)"
            )
        }

        return null
    }
}
