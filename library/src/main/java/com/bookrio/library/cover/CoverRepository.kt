package com.bookrio.library.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withSave
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.dispatchers.DispatcherProvider
import com.bookrio.core.net.MetadataFetcher
import com.bookrio.library.mapper.DomainMappers
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.getInstance
import com.bookrio.data.local.entity.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

class CoverRepository(
    private val ctx: Context,
    private val db: ShelfDatabase = ShelfDatabase.getInstance(ctx.applicationContext),
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) {

    private val coversDir: File by lazy { File(ctx.filesDir, "covers").apply { mkdirs() } }

    private val prefs by lazy {
        com.bookrio.data.prefs.UserPreferencesRepository(ctx.applicationContext)
    }

    private val bitmapMemCache = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(
            evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?
        ) {
            if (evicted && !oldValue.isRecycled) runCatching { oldValue.recycle() }
        }
    }

    fun cachedBitmapFor(book: BookEntity): Bitmap? {
        val key = book.coverPath ?: generatedFile(book.id).absolutePath
        return bitmapMemCache[key]?.takeUnless { it.isRecycled } ?: run {
            val f = book.coverPath?.let { File(it) }?.takeIf { it.exists() }
                ?: generatedFile(book.id).takeIf { it.exists() }
            f?.let { decodeSampled(it, 600, 900, noRecycle = false) }
                ?.also { b -> bitmapMemCache.put(key, b) }
        }
    }

    suspend fun coverFileFor(book: BookEntity): File = withContext(dispatchers.io) {
        val existingPath = book.coverPath?.let { File(it) }?.takeIf { it.exists() }
            ?: generatedFile(book.id).takeIf { it.exists() }
        if (existingPath != null) {
            val stale = try {
                existingPath.lastModified() < book.lastModifiedAt
            } catch (_: Throwable) { false }
            if (stale) {
                evictCacheFor(book)
                runCatching { existingPath.delete() }
                renderAndPersist(book)
            } else existingPath
        } else renderAndPersist(book)
    }

    private fun evictCacheFor(book: BookEntity) {
        val key = book.coverPath ?: generatedFile(book.id).absolutePath
        runCatching { bitmapMemCache.remove(key)?.recycle() }
        runCatching {
            val generatedKey = generatedFile(book.id).absolutePath
            if (generatedKey != key) bitmapMemCache.remove(generatedKey)?.recycle()
        }
    }

    fun coverFileFlow(book: BookEntity): Flow<File> = flow { emit(coverFileFor(book)) }
        .flowOn(dispatchers.io)

    suspend fun regenerate(book: BookEntity): File = withContext(dispatchers.io) {
        evictCacheFor(book)
        book.coverPath?.let { File(it) }?.takeIf { it.exists() }?.delete()
        generatedFile(book.id).takeIf { it.exists() }?.delete()
        renderAndPersist(book)
    }

    suspend fun ensureAllCovered(books: List<BookEntity>) {
        books.forEach { coverFileFor(it) }
    }

    private fun generatedFile(id: Long) = File(coversDir, "book_$id.webp")

    private suspend fun renderAndPersist(book: BookEntity): File {
        val file = generatedFile(book.id)
        val spineColorArgb = DomainMappers.pickSpineColor(book.id, book.spineColor).colorToArgb()

        var effectiveTitle: String = book.title
        var effectiveAuthor: String = book.author ?: ""
        var effectiveIsbn: String = book.isbn ?: ""

        val needsEnrichment = MetadataFetcher.isAuthorUnknown(book.author) ||
                book.title.isBlank() ||
                looksLikeFilenameOnly(book.title)

        // Privacy: online metadata/cover lookup is strictly opt-in (Settings →
        // "Online cover lookup"). When disabled we never touch the network and
        // fall back to embedded / typographic covers.
        val onlineEnabled = runCatching { prefs.onlineCoverLookup.first() }.getOrDefault(false)

        val enriched: com.bookrio.core.net.FetchedMetadata? = if (needsEnrichment && onlineEnabled) {
            runCatching {
                MetadataFetcher.enrich(
                    rawTitle = book.title,
                    rawAuthor = book.author.takeUnless { MetadataFetcher.isAuthorUnknown(it) },
                    rawIsbn = book.isbn
                )
            }.getOrNull()
        } else null

        if (enriched != null) {
            if (!enriched.title.isNullOrBlank()) effectiveTitle = enriched.title ?: ""
            if (MetadataFetcher.isAuthorUnknown(effectiveAuthor) && !enriched.author.isNullOrBlank()) {
                effectiveAuthor = enriched.author ?: ""
            }
            if (effectiveIsbn.isBlank() && !enriched.isbn.isNullOrBlank()) {
                effectiveIsbn = enriched.isbn ?: ""
            }

            val changed =
                effectiveTitle != book.title ||
                effectiveAuthor != (book.author ?: "") ||
                effectiveIsbn != (book.isbn ?: "") ||
                !enriched.publisher.isNullOrBlank() ||
                !enriched.publishedDate.isNullOrBlank() ||
                !enriched.description.isNullOrBlank()

            if (changed) {
                runCatching {
                    db.bookDao().enrichMetadataSilently(
                        id = book.id,
                        title = effectiveTitle,
                        sortTitle = normalizeForSort(effectiveTitle),
                        author = effectiveAuthor,
                        sortAuthor = normalizeForSort(effectiveAuthor),
                        isbn = effectiveIsbn.ifBlank { null },
                        publisher = enriched.publisher ?: book.publisher,
                        publishedDate = enriched.publishedDate ?: book.publishedDate,
                        description = enriched.description ?: book.description
                    )
                }
            }
        }

        val embedded: Bitmap? = try {
            val format = com.bookrio.core.domain.model.BookFormat.entries
                .firstOrNull { it.name == book.format.name }
            val path = book.filePath.takeUnless { it.isNullOrBlank() }
            val uri = book.fileUri.takeUnless { it.isNullOrBlank() }
            if (format != null && (path != null || uri != null)) {
                val input: java.io.InputStream? = uri?.let {
                    runCatching {
                        ctx.contentResolver.openInputStream(android.net.Uri.parse(it))
                    }.getOrNull()
                }
                com.bookrio.core.parse.CoverExtractor.extract(
                    ctx = ctx,
                    filePath = path,
                    input = input,
                    formatHint = format,
                    maxWidthPx = 800,
                    maxHeightPx = 1200
                )
            } else null
        } catch (_: Throwable) { null }

        var onlineCover: Bitmap? = null
        if (onlineEnabled && embedded == null) {
            onlineCover = enriched?.coverUrl?.let { url ->
                runCatching { MetadataFetcher.downloadCoverBitmap(url, 800, 1200) }.getOrNull()
            }
            if (onlineCover == null) {
                onlineCover = runCatching {
                    val fallbackFetched = if (!needsEnrichment) {
                        MetadataFetcher.enrich(effectiveTitle, effectiveAuthor, effectiveIsbn.ifBlank { null })
                    } else enriched
                    fallbackFetched?.coverUrl?.let { url ->
                        MetadataFetcher.downloadCoverBitmap(url, 800, 1200)
                    }
                }.getOrNull()
            }
        }

        val bitmap = embedded ?: onlineCover ?: renderTypographicCover(
            title = effectiveTitle,
            author = effectiveAuthor,
            spineColorArgb = spineColorArgb,
            formatBadge = book.format.name,
            widthPx = 600,
            heightPx = 900
        )

        file.outputStream().buffered().use { os ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, os)
            } else {
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 82, os)
            }
        }
        if (bitmap !== embedded) {
            runCatching { bitmap.recycle() }
        }

        val (finalSpine, updatedCoverPath) = if (embedded != null) {
            val domColor: android.graphics.Color? = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.WallpaperColors.fromBitmap(embedded).primaryColor
                } else null
            }.getOrNull()
            val domArgb: Int? = domColor?.toArgb()
            (domArgb ?: spineColorArgb) to file.absolutePath
        } else {
            spineColorArgb to file.absolutePath
        }

        db.bookDao().updateCoverSilently(
            id = book.id,
            coverPath = updatedCoverPath,
            spineColor = finalSpine
        )
        return file
    }

    private fun looksLikeFilenameOnly(title: String): Boolean {
        val t = title.trim()
        if (t.length < 3) return true
        if (t.contains(" - ")) return false
        val hasSpace = t.contains(" ")
        val hasUpperLower = t.any { it.isUpperCase() } && t.any { it.isLowerCase() }
        return !hasSpace || !hasUpperLower
    }

    companion object {

        private val LEADING_ARTICLES = listOf("the ", "en ", "et ", "ei ")

        fun normalizeForSort(value: String): String {
            val trimmed = value.trim()
            val lower = trimmed.lowercase()
            for (article in LEADING_ARTICLES) {
                if (lower.startsWith(article)) {
                    return trimmed.substring(article.length).trim()
                }
            }
            return trimmed
        }

        @JvmStatic
        fun renderTypographicCover(
            title: String,
            author: String,
            spineColorArgb: Int,
            formatBadge: String,
            widthPx: Int = 600,
            heightPx: Int = 900
        ): Bitmap {
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val density = min(widthPx, heightPx).toFloat()

            val bgLight = ColorUtils.blendARGB(spineColorArgb, AndroidColor.WHITE, 0.22f)
            val bgMid = spineColorArgb
            val bgDark = ColorUtils.blendARGB(spineColorArgb, AndroidColor.BLACK, 0.35f)
            canvas.drawRect(
                0f, 0f, widthPx.toFloat(), heightPx.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, heightPx.toFloat(),
                        intArrayOf(bgLight, bgMid, bgDark),
                        floatArrayOf(0f, 0.48f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
            )

            canvas.drawRect(
                0f, 0f, widthPx.toFloat(), heightPx.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.RadialGradient(
                        widthPx / 2f, heightPx * 0.42f, heightPx * 0.7f,
                        AndroidColor.TRANSPARENT,
                        ColorUtils.setAlphaComponent(AndroidColor.BLACK, 110),
                        Shader.TileMode.CLAMP
                    )
                }
            )

            val spineW = density * 0.04f
            canvas.drawRect(
                0f, 0f, spineW, heightPx.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgDark; alpha = 90 }
            )

            canvas.withSave {
                translate(spineW / 2, heightPx * 0.5f)
                rotate(-90f)
                drawText(
                    formatBadge.uppercase(),
                    0f, 0f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.WHITE
                        alpha = 220
                        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                        textSize = density * 0.038f
                        textAlign = Paint.Align.CENTER
                    }
                )
            }

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pickFg(bgMid)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textSize = fitText(title, density * 0.11f, maxWidth = widthPx - density * 0.42f)
                textAlign = Paint.Align.LEFT
            }
            val yTitle = heightPx * 0.30f
            drawWrapped(canvas, title, titlePaint, density * 0.14f, yTitle,
                widthPx - density * 0.28f, density * 0.025f)

            val authorDisplay = author.takeIf { !MetadataFetcher.isAuthorUnknown(it) } ?: ""
            val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(pickFg(bgMid), 210)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = density * 0.055f
                textAlign = Paint.Align.LEFT
            }
            val authorBaseline = heightPx * 0.78f
            canvas.drawText(authorDisplay.take(40), density * 0.14f, authorBaseline, authorPaint)

            canvas.drawLine(
                density * 0.14f,
                heightPx * 0.72f,
                density * 0.32f,
                heightPx * 0.72f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = pickFg(bgMid); alpha = 170; strokeWidth = density * 0.004f
                }
            )

            canvas.drawRect(
                widthPx - density * 0.014f, 0f, widthPx.toFloat(), heightPx.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        widthPx - density * 0.014f, 0f, widthPx.toFloat(), 0f,
                        intArrayOf(
                            ColorUtils.setAlphaComponent(AndroidColor.WHITE, 40),
                            ColorUtils.setAlphaComponent(AndroidColor.BLACK, 60)
                        ), null, Shader.TileMode.CLAMP
                    )
                }
            )

            return bmp
        }

        private fun drawWrapped(
            c: Canvas, text: String, p: Paint,
            x: Float, yStart: Float, maxW: Float, lineGap: Float
        ) {
            var y = yStart
            val words = text.split("\\s+".toRegex())
            var line = ""
            for (w in words) {
                val trial = if (line.isEmpty()) w else "$line $w"
                if (p.measureText(trial) <= maxW) {
                    line = trial
                } else {
                    c.drawText(line, x, y, p)
                    y += p.textSize + lineGap
                    line = w
                }
            }
            if (line.isNotEmpty()) c.drawText(line, x, y, p)
        }

        private fun fitText(text: String, startSize: Float, maxWidth: Float): Float {
            var size = startSize
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            while (size > 10f) {
                p.textSize = size
                val longest = text.split("\\s+".toRegex()).maxOfOrNull { p.measureText(it) } ?: 0f
                val total = p.measureText(text) * 0.62f
                if (longest <= maxWidth && total <= maxWidth * 4) return size
                size *= 0.94f
            }
            return size
        }

        private fun pickFg(bg: Int): Int {
            val r = AndroidColor.red(bg)
            val g = AndroidColor.green(bg)
            val b = AndroidColor.blue(bg)
            val yiq = (r * 299 + g * 587 + b * 114) / 1000f
            return if (yiq > 155f) AndroidColor.BLACK else AndroidColor.WHITE
        }

        private fun decodeSampled(
            f: File, maxW: Int, maxH: Int, noRecycle: Boolean
        ): Bitmap? = runCatching {
            f.inputStream().buffered().use { decodeSampled(it, maxW, maxH, noRecycle) }
        }.getOrNull()

        private fun decodeSampled(
            input: java.io.InputStream,
            maxW: Int, maxH: Int, noRecycle: Boolean
        ): Bitmap? {
            return runCatching {
                val buf: ByteArray = input.readBytes()
                val bounds = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(buf, 0, buf.size, bounds)
                var sample = 1
                val w = bounds.outWidth.coerceAtLeast(1)
                val h = bounds.outHeight.coerceAtLeast(1)
                while ((w / sample) > maxW || (h / sample) > maxH) {
                    sample *= 2
                }
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    inDensity = 160
                }
                val tmp = android.graphics.BitmapFactory.decodeByteArray(buf, 0, buf.size, opts)
                    ?: return null
                val sw = tmp.width
                val sh = tmp.height
                val scale = minOf(maxW.toFloat() / sw, maxH.toFloat() / sh, 1f)
                if (scale >= 0.98f) tmp
                else {
                    val out = Bitmap.createScaledBitmap(
                        tmp, (sw * scale).roundToInt().coerceAtLeast(1),
                        (sh * scale).roundToInt().coerceAtLeast(1), true
                    )
                    if (!noRecycle && out !== tmp) {
                        runCatching { tmp.recycle() }
                    }
                    out
                }
            }.getOrNull()
        }
    }
}

private fun Color.colorToArgb(): Int {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
