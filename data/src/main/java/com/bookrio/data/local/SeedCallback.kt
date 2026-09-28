package com.bookrio.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SeedCallback(
    private val dbProvider: () -> ShelfDatabase
) : RoomDatabase.Callback() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        scope.launch { performSeed(dbProvider()) }
    }

    internal suspend fun performSeed(db: ShelfDatabase) {
        // Start with a clean slate as requested by user.
        // User will add their own files via local SAF import or FTP sync.
    }

    companion object {

        private enum class Palette(private val argb: Int) {
            NAVY(0xFF1C2B4A.toInt()),
            NAVY_2(0xFF223356.toInt()),
            BURGUNDY(0xFF5C1A2B.toInt()),
            FOREST(0xFF26402D.toInt()),
            SIENNA(0xFF8C4A2B.toInt()),
            SIENNA_2(0xFF7A4025.toInt()),
            PLUM(0xFF3E2C4A.toInt()),
            PLUM_2(0xFF4A3355.toInt()),
            SLATE(0xFF2F3A4A.toInt()),
            ROSE(0xFF6B2B3C.toInt()),
            TEAL(0xFF1A4D52.toInt()),
            MUSTARD(0xFF8A6820.toInt()),
            RUST(0xFF8C3A1E.toInt());
            fun toInt(): Int = argb
        }

        fun seedBooks(): List<BookEntity> {
            val now = System.currentTimeMillis()
            return listOf(
                b(1, now, "Sofies verden", "Jostein Gaarder", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0.62f, null, null, pages = 520, fav = true, addedMinus = 22, openMinus = 1,
                    palette = Palette.NAVY),
                b(2, now, "Naustet", "Lars Mytting", FormatEntity.M4B, BookTypeEntity.AUDIOBOOK,
                    0.30f, null, null, duration = 12.hours, addedMinus = 18, openMinus = 2,
                    palette = Palette.BURGUNDY),
                b(3, now, "Min kamp", "Karl Ove Knausgård", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0.15f, "Min kamp", 1f, pages = 650, addedMinus = 35, openMinus = 7,
                    palette = Palette.FOREST),
                b(4, now, "Hunger", "Knut Hamsun", FormatEntity.PDF, BookTypeEntity.EBOOK,
                    1f, null, null, pages = 210, finishedMinus = 3, addedMinus = 120, openMinus = 5,
                    palette = Palette.SIENNA),
                b(5, now, "Den siste viking", "Johan Bojer", FormatEntity.MOBI, BookTypeEntity.EBOOK,
                    0f, null, null, pages = 340, addedMinus = 60,
                    palette = Palette.PLUM),
                b(6, now, "Etterfølgere", "Jo Nesbø", FormatEntity.M4B, BookTypeEntity.AUDIOBOOK,
                    0.85f, null, null, duration = 14.hours, fav = true, addedMinus = 9, openMinus = 0,
                    palette = Palette.SLATE),
                b(7, now, "Heksene", "Roald Dahl", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0f, null, null, pages = 170, addedMinus = 40,
                    palette = Palette.ROSE),
                b(8, now, "Mennesker", "Peter S. Beagle", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0.44f, null, null, pages = 240, addedMinus = 12, openMinus = 3,
                    palette = Palette.TEAL),
                b(9, now, "Skjønnhetsdronningen", "Alexander McCall Smith", FormatEntity.AZW3, BookTypeEntity.EBOOK,
                    0.08f, null, null, pages = 280, addedMinus = 5, openMinus = 2,
                    palette = Palette.MUSTARD),
                b(10, now, "Døden på ørnen", "Agatha Christie", FormatEntity.FB2, BookTypeEntity.EBOOK,
                    0f, null, null, pages = 200, addedMinus = 80,
                    palette = Palette.RUST),
                b(11, now, "Hobbiten", "J.R.R. Tolkien", FormatEntity.M4B, BookTypeEntity.AUDIOBOOK,
                    0.71f, "Ringenes herre", 0f, duration = 11.hours, fav = true,
                    addedMinus = 4, openMinus = 0, palette = Palette.FOREST),
                b(12, now, "Ringens brorskap", "J.R.R. Tolkien", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0.12f, "Ringenes herre", 1f, pages = 480, addedMinus = 6,
                    palette = Palette.NAVY_2),
                b(13, now, "To tårn", "J.R.R. Tolkien", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0f, "Ringenes herre", 2f, pages = 490, addedMinus = 7,
                    palette = Palette.SIENNA_2),
                b(14, now, "Kongens tilbakekomst", "J.R.R. Tolkien", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0f, "Ringenes herre", 3f, pages = 530, addedMinus = 8,
                    palette = Palette.PLUM_2),
                b(15, now, "1984", "George Orwell", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0f, null, null, pages = 320, addedMinus = 100,
                    palette = Palette.NAVY),
                b(16, now, "Dyrene på gården", "George Orwell", FormatEntity.MP3, BookTypeEntity.AUDIOBOOK,
                    0f, null, null, duration = 3.hours, addedMinus = 50,
                    palette = Palette.BURGUNDY),
                b(17, now, "Jane Eyre", "Charlotte Brontë", FormatEntity.TXT, BookTypeEntity.EBOOK,
                    0.05f, null, null, pages = 510, addedMinus = 70, openMinus = 20,
                    palette = Palette.PLUM),
                b(18, now, "Krimen og straffen", "Fjodor Dostojevskij", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0f, null, null, pages = 620, addedMinus = 90,
                    palette = Palette.RUST),
                b(19, now, "Krigen og freden", "Leo Tolstoj", FormatEntity.EPUB, BookTypeEntity.EBOOK,
                    0.03f, null, null, pages = 1240, addedMinus = 110, openMinus = 14,
                    palette = Palette.SIENNA),
                b(20, now, "Don Quichote", "Miguel de Cervantes", FormatEntity.FB2, BookTypeEntity.EBOOK,
                    0f, null, null, pages = 960, addedMinus = 130,
                    palette = Palette.ROSE)
            )
        }

        val progressPercents: List<Float>
            get() = seedBooks().map { book ->
                when (book.title) {
                    "Sofies verden" -> 0.62f
                    "Naustet" -> 0.30f
                    "Min kamp" -> 0.15f
                    "Hunger" -> 1f
                    "Etterfølgere" -> 0.85f
                    "Mennesker" -> 0.44f
                    "Skjønnhetsdronningen" -> 0.08f
                    "Hobbiten" -> 0.71f
                    "Ringens brorskap" -> 0.12f
                    "Jane Eyre" -> 0.05f
                    "Krigen og freden" -> 0.03f
                    else -> 0f
                }
            }

        private val Int.hours get() = this * 3_600_000L

        @Suppress("LongParameterList")
        private fun b(
            id: Long, now: Long,
            title: String, author: String, format: FormatEntity, type: BookTypeEntity,
            progress: Float,
            series: String? = null, seriesIndex: Float? = null,
            pages: Int? = null, duration: Long? = null,
            addedMinus: Int = 0, openMinus: Int? = null, finishedMinus: Int? = null,
            fav: Boolean = false,
            palette: Palette = Palette.NAVY
        ): BookEntity {
            val dayMs = 86_400_000L
            val dateAdded = now - addedMinus * dayMs + (17 + id) * 60_000L
            val lastOpened = openMinus?.let { now - it * dayMs }
            val finished = finishedMinus?.let { now - it * dayMs }
            val color = palette.toInt()
            return BookEntity(
                id = id,
                title = title,
                sortTitle = sortify(title),
                author = author,
                sortAuthor = sortifyAuthor(author),
                series = series,
                seriesIndex = seriesIndex,
                description = "Eksempelbok for å vise frem Shelf sitt bibliotek. «$title» av $author.",
                language = "no",
                type = type,
                format = format,
                fileSizeBytes = (pages ?: 0) * 90_000L + (duration ?: 0L),
                pageCount = pages,
                durationMs = duration,
                chapterCount = pages?.let { (it / 20).coerceAtLeast(4) }
                    ?: duration?.let { (it / 3600_000f * 6).toInt().coerceAtLeast(4) },
                coverColor = color,
                spineColor = color,
                dateAdded = dateAdded,
                lastOpenedAt = lastOpened,
                lastModifiedAt = now,
                dateFinished = finished,
                isFavorite = fav,
                importSource = ImportSourceEntity.SAMPLE,
                isSample = true,
                tags = if (series != null) "serie" else ""
            )
        }

        private fun sortify(title: String): String =
            title.removePrefix("Den ").removePrefix("Det ").removePrefix("De ")
                .removePrefix("The ").trim()

        private fun sortifyAuthor(author: String): String {
            val parts = author.split(" ")
            if (parts.size <= 1) return author
            val last = parts.last()
            return "$last, ${parts.dropLast(1).joinToString(" ")}"
        }
    }
}
