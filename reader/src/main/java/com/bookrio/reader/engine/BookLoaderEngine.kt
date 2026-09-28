package com.bookrio.reader.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bookrio.reader.R
import com.bookrio.core.parse.MobiDrmException
import com.bookrio.core.parse.MobiMetadata
import com.bookrio.core.parse.MobiParseException
import com.bookrio.core.parse.MobiUnpack
import com.bookrio.core.parse.ParsedBook
import com.bookrio.core.parse.buildComicHtml
import com.bookrio.core.parse.parseCbz
import com.bookrio.core.parse.parseEpub
import com.bookrio.core.parse.parseFb2
import com.bookrio.core.parse.parsePdf
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

private const val TAG = "BookLoaderEngine"

// Bump this value every time the MOBI converter/decompressor has a correctness fix
// (LZ77, charset, record-range, etc.). It is mixed into the cache hash so that
// previously cached bad conversions are automatically invalidated and reconverted
// on the next book open — no manual cache clearing needed.
private const val MOBI_CONVERTER_CACHE_VERSION = 6

data class ReaderChapter(
    val index: Int,
    val title: String,
    val htmlContent: String,
    val startByte: Int,
    val endByte: Int? = null,
    /** true = nav/NCX-drevet TOC-oppføring; false = frontmatter-seksjon. */
    val inToc: Boolean = true
)

data class ReaderBookState(
    val bookId: Long = 0L,
    val bookTitle: String,
    val author: String,
    val format: FormatEntity,
    val type: BookTypeEntity,
    val chapters: List<ReaderChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val percent: Float = 0f,
    val scrollPct: Float = 0f,
    val fontSizeSp: Int = 18,
    val readerTheme: String = "sepia",
    /** Global page index within the rendered book (filled by PageCurlReader). */
    val currentPage: Int = 0,
    /** Total page count after off-screen rendering (filled by HtmlPageRenderer). */
    val totalPages: Int = 0,
    val error: String? = null,
    /** When non-null: the next time onPageCountKnown arrives, re-seek currentPage to this percent.
     *  Set by setFontSize() / setTheme() to preserve the user's reading position when
     *  pagination changes (same relative position in the text, like iBooks does). */
    val pendingRepositionPct: Float? = null,
)

class BookLoaderEngine(
    private val ctx: Context,
    private val db: ShelfDatabase
) {

    suspend fun loadBook(bookId: Long): ReaderBookState = withContext(Dispatchers.IO) {
        val book = db.bookDao().getById(bookId)
            ?: return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = "",
                author = "",
                format = FormatEntity.UNKNOWN,
                type = BookTypeEntity.EBOOK,
                error = ctx.getString(R.string.rdr_error_book_not_found)
            )

        val percent = db.progressDao().getByBook(bookId)?.progressPercent ?: 0f

        // SAFETY: If it's an audiobook, do NOT try to read it as text.
        if (book.type == BookTypeEntity.AUDIOBOOK && !isSupportedEbook(book.format)) {
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = ctx.getString(R.string.rdr_error_is_audiobook)
            )
        }

        val filePath = book.filePath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.canRead() }
        val fileUri = book.fileUri?.takeIf { it.isNotBlank() }

        val mobiFormats = listOf(FormatEntity.MOBI, FormatEntity.AZW, FormatEntity.AZW3)
        val isMobiFamily = book.format in mobiFormats

        // ---- MOBI FAMILY: convert to cached EPUB, then feed into existing EPUB parser ----
        // This ELIMINATES the old broken "decode raw bytes as UTF-8" path for MOBI, which
        // always produced gibberish because MOBI is a compressed PalmDB binary container.
        // Conversion errors / DRM are surfaced as clear ReaderBookState.error — never gibberish.
        if (isMobiFamily) {
            return@withContext loadMobiFamilyBook(book, percent, filePath, fileUri)
        }

        val parsed: ParsedBook? = try {
            when (book.format) {
                FormatEntity.EPUB -> {
                    val first = parseEpub(ctx, filePath?.absolutePath, null)
                    if (first == null && filePath != null && filePath.exists()) {
                        openStreamSafely(filePath, fileUri)?.use { s -> parseEpub(ctx, null, s) }
                    } else {
                        first ?: openStreamSafely(null, fileUri)?.use { s -> parseEpub(ctx, null, s) }
                    }
                }
                FormatEntity.FB2 -> {
                    parseFb2(ctx, filePath?.absolutePath, null)
                        ?: openStreamSafely(filePath, fileUri)?.use { s -> parseFb2(ctx, null, s) }
                }
                FormatEntity.PDF -> parsePdf(ctx, filePath?.absolutePath, null)
                    ?: openStreamSafely(filePath, fileUri)?.use { s -> parsePdf(ctx, null, s) }
                FormatEntity.CBZ -> (parseCbz(ctx, filePath?.absolutePath, null)
                    ?: openStreamSafely(filePath, fileUri)?.use { s -> parseCbz(ctx, null, s) })?.let { buildComicHtml(it) }
                FormatEntity.CBR -> (parseCbz(ctx, filePath?.absolutePath, null)
                    ?: openStreamSafely(filePath, fileUri)?.use { s -> parseCbz(ctx, null, s) })?.let { buildComicHtml(it) }
                else -> null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Parser crash", t)
            null
        }

        if (parsed != null && parsed.chapters.isNotEmpty()) {
            val readerChapters = parsed.chapters.map {
                ReaderChapter(it.index, it.title, it.htmlContent, it.startByte, it.startByte + it.byteLength, it.inToc)
            }
            // Oppdater chaptersJson når titlene har endret seg (bedre TOC-navn fra
            // nav/NCX/h1 etter parserforbedring) — gamle importerer får navn ved neste
            // åpning uten reimport.
            val newTitles = parsed.chapters.map { it.title }
            val oldTitles = runCatching {
                val arr = org.json.JSONArray(book.chaptersJson ?: "[]")
                (0 until arr.length()).map { arr.optString(it) }
            }.getOrElse { emptyList() }
            if (book.chaptersJson.isNullOrBlank() || oldTitles != newTitles) {
                runCatching {
                    val jsonArray = org.json.JSONArray()
                    parsed.chapters.forEach { jsonArray.put(it.title) }
                    val refreshed = book.copy(
                        chapterCount = parsed.chapters.size,
                        chaptersJson = jsonArray.toString(),
                        lastModifiedAt = System.currentTimeMillis()
                    )
                    db.bookDao().update(refreshed)
                }
            }
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = parsed.title?.ifBlank { book.title } ?: book.title,
                author = parsed.author?.ifBlank { book.author } ?: book.author,
                format = book.format,
                type = book.type,
                chapters = readerChapters,
                currentChapterIndex = 0,
                percent = percent,
                error = null
            )
        }

        // Fallback for non-structured or failed parsing: stream in chunks with hard cap to prevent
        // OOM from multi-GB audiobook/PDF files accidentally hitting this fallback.
        data class FallbackPacket(val drmScan: ByteArray, val fullText: ByteArray, val sizeBytes: Long)

        val packet: FallbackPacket = try {
            openStreamSafely(filePath, fileUri)?.use { s ->
                val capForDrmScan = 256 * 1024   // 256 KB suffices for MOBI EXTH + bookmobi magic
                val capForFullText = 320 * 1024 * 1024 // 320 MB hard cap for raw text decode (OOM-safe)
                val drmBuf = ByteArray(capForDrmScan)
                var drmLen = 0
                // Read drm-scan head
                while (drmLen < capForDrmScan) {
                    val n = s.read(drmBuf, drmLen, capForDrmScan - drmLen)
                    if (n < 0) break
                    drmLen += n
                }
                // Remaining → text buffer, bounded
                val textBuf = java.io.ByteArrayOutputStream(drmLen.coerceAtLeast(8192))
                textBuf.write(drmBuf, 0, drmLen)
                val copyBuf = ByteArray(64 * 1024)
                var total = drmLen.toLong()
                while (total < capForFullText) {
                    val n = s.read(copyBuf)
                    if (n < 0) break
                    textBuf.write(copyBuf, 0, n)
                    total += n
                }
                FallbackPacket(
                    drmScan = if (drmLen == drmBuf.size) drmBuf else drmBuf.copyOf(drmLen),
                    fullText = textBuf.toByteArray(),
                    sizeBytes = total
                )
            } ?: return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = if (fileUri == null && filePath == null) ctx.getString(R.string.rdr_error_file_not_found)
                        else ctx.getString(R.string.rdr_error_no_access)
            )
        } catch (t: Throwable) {
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = "Feil ved lesing av fil: ${t.message ?: "Ukjent feil"}"
            )
        }
        val bytesForDrm: ByteArray = packet.drmScan
        val bytes: ByteArray = packet.fullText
        val totalBytes = packet.sizeBytes.toInt().coerceAtLeast(bytes.size)

        // Structured format failure handling:
        //  - EPUB/FB2/CBZ/CBR/ZIP/PDF : show structured-parse error (they have actual parsers)
        //  - MOBI/AZW/AZW3            : NEVER fall through here — they are handled earlier in
        //                               loadMobiFamilyBook() with real PalmDB parsing, DRM detection,
        //                               and MOBI→EPUB conversion. If they DO reach here, it's because
        //                               the book's format entity was reassigned after the MOBI early-
        //                               return — treat as "definitely damaged" to avoid the old bug
        //                               of decoding compressed binary bytes as UTF-8 (gibberish output).
        val mobiFormatsHere = listOf(FormatEntity.MOBI, FormatEntity.AZW, FormatEntity.AZW3)
        val knownTextFormats = listOf(FormatEntity.TXT, FormatEntity.HTML, FormatEntity.MD, FormatEntity.RTF, FormatEntity.DOCX)
        val definitelyDamagedOrDrm = when {
            book.format in listOf(FormatEntity.EPUB, FormatEntity.FB2, FormatEntity.CBZ,
                FormatEntity.CBR, FormatEntity.ZIP, FormatEntity.PDF) -> true
            book.format in mobiFormatsHere -> {
                Log.w(TAG, "MOBI-family format ${book.format.name} unexpectedly reached raw-text fallback; " +
                        "should have been handled by loadMobiFamilyBook(). Treating as damaged to avoid gibberish output.")
                true
            }
            // UNKNOWN files: NEVER attempt raw text decoding.
            // Import scanner should have filtered them, but this is the safety net.
            book.format == FormatEntity.UNKNOWN -> {
                Log.w(TAG, "UNKNOWN-format book (id=${bookId}) reached raw-text fallback. " +
                        "Showing error instead of possibly decoding binary/Bencode as gibberish.")
                true
            }
            else -> false
        }
        if (definitelyDamagedOrDrm) {
            val drmHint = when {
                book.format in mobiFormatsHere ->
                    "Denne ${book.format.name}-filen kunne ikke leses (ugyldig format, skadet, eller støtten er endret). " +
                        "Prøv å importere boken på nytt, eller bruk en DRM-fri EPUB-versjon av boken."
                book.format == FormatEntity.UNKNOWN ->
                    "Filtypen er ikke gjenkjent som en bok (${book.filePath?.substringAfterLast('/')
                        ?: book.fileUri?.substringAfterLast('/')?.substringBefore('?') ?: "ukjent"}). " +
                        "Hvis dette skulle vært en bok, prøv å gi filen et riktig navn (f.eks. .epub, .mobi eller .pdf) og importer på nytt."
                else ->
                    "Filen kan være skadet, tom, ha en DRM-beskyttelse, eller ha et støttet format som ikke kunne tolkes."
            }
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = "Klarte ikke å åpne ${book.format.name}-filen. $drmHint"
            )
        }

        // Final attempt: treat as raw text/markdown if not already handled
        // SAFETY NET (strong content detector):
        // Even for TXT/HTML/MD, block obviously structured garbage (Bencode torrents,
        // zip headers, object code, XML dumps, JSON lists with only binary metadata).
        // Prevents the infamous "4:pathl...eedd6:lengthi...e" (BitTorrent Bencode) display bug.
        runCatching { detectStructuredGarbage(bytes) }.getOrNull()?.let { garbageHint ->
            Log.w(TAG, "Rejecting raw-text decode for book=$bookId (format=${book.format}): $garbageHint")
            val what = if (garbageHint.contains("torrent", ignoreCase = true))
                "Dette er en BitTorrent-fil (.torrent) eller torrent-metadata. " +
                    "Åpne den i Torrent-skjermstedet for å laste ned bøkene, ikke i leseren!"
            else
                "Innholdet ser ikke ut som en bok (oppdaget $garbageHint). " +
                    "Sjekk at filen er riktig format (.epub, .mobi, .pdf, .txt osv.) og ikke en " +
                    "zip, skadet eller binærfil som er feilnavngitt."
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = "Kan ikke vise innhold. $what"
            )
        }
        val rawContent = try {
            val utf8 = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            // Heuristic: if UTF-8 decode produces replacement chars (U+FFFD), try Windows-1252 (Norwegian/Western)
            val decoded = if (utf8.contains('\uFFFD') && bytes.size < 10_000_000) {
                runCatching {
                    bytes.toString(java.nio.charset.Charset.forName("windows-1252"))
                }.getOrElse { utf8 }
            } else {
                utf8
            }
            decoded.replace("\u0000", "")
        } catch (_: Exception) {
            return@withContext ReaderBookState(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                format = book.format,
                type = book.type,
                percent = percent,
                error = "Klarte ikke å lese filinnholdet som tekst."
            )
        }
        val chapters = buildChapters(book.format, rawContent, totalBytes)

        return@withContext ReaderBookState(
            bookId = bookId,
            bookTitle = book.title,
            author = book.author,
            format = book.format,
            type = book.type,
            chapters = chapters,
            currentChapterIndex = 0,
            percent = percent,
            error = null
        )
    }

    /**
     * MOBI / AZW / AZW3 load path — convert to a cached EPUB, then re-use the existing EPUB parser.
     *
     * Design:
     *  - MOBI files go through MobiUnpack which does real PalmDB container parsing,
     *    PalmDOC LZ77 decompression, EXTH header/DRM detection, chapter splitting, and outputs
     *    a minimal valid EPUB 2 file into app-private cache.
     *  - Cache key = SHA-256 of (absolute path + size bytes + lastModified ms), so re-opening
     *    the same book is instant and edits to the original file invalidate the cache.
     *  - On DRM or conversion failure, return a clear ReaderBookState.error — NEVER fall back
     *    to decoding raw bytes as UTF-8 (that's the exact bug this pipeline was written to fix).
     *  - The cached EPUB is then fed directly into parseEpub() — the exact same pipeline used by
     *    native EPUBs, so all downstream behavior (pagecurl, fonts/themes, pagination) is identical.
     */
    private suspend fun loadMobiFamilyBook(
        book: BookEntity,
        percent: Float,
        filePath: File?,
        fileUri: String?
    ): ReaderBookState = withContext(Dispatchers.IO) {
        val mobiBytes: ByteArray = try {
            val stream = openStreamSafely(filePath, fileUri)
                ?: return@withContext ReaderBookState(
                    bookId = book.id,
                    bookTitle = book.title,
                    author = book.author,
                    format = book.format,
                    type = book.type,
                    percent = percent,
                    error = if (fileUri == null && filePath == null) ctx.getString(R.string.rdr_error_file_not_found)
                    else ctx.getString(R.string.rdr_error_no_access)
                )
            stream.use { s ->
                val size = if (filePath?.exists() == true) filePath.length() else book.fileSizeBytes
                val cap = (size.coerceAtLeast(1024L)).coerceAtMost(512L * 1024L * 1024L).toInt()
                val baos = java.io.ByteArrayOutputStream(cap.coerceAtLeast(8192))
                val buf = ByteArray(128 * 1024)
                var total = 0L
                while (total < cap) {
                    val n = s.read(buf)
                    if (n < 0) break
                    baos.write(buf, 0, n)
                    total += n
                }
                if (total == 0L) {
                    return@withContext ReaderBookState(
                        bookId = book.id,
                        bookTitle = book.title, author = book.author,
                        format = book.format, type = book.type, percent = percent,
                        error = "MOBI-filen var tom eller kunne ikke leses."
                    )
                }
                baos.toByteArray()
            }
        } catch (t: Throwable) {
            return@withContext ReaderBookState(
                bookId = book.id,
                bookTitle = book.title, author = book.author,
                format = book.format, type = book.type, percent = percent,
                error = "Feil ved lesing av ${book.format.name}-fil: ${t.message ?: "Ukjent feil"}"
            )
        }

        // --- 1. Quick pre-flight DRM/metadata check (uses only record 0, fast) ---
        // NOTE: every catch branch does `return@withContext`, so `preMeta` is guaranteed non-null here.
        val preMeta: MobiMetadata = try {
            MobiUnpack.parseMetadata(mobiBytes)
        } catch (drm: MobiDrmException) {
            return@withContext ReaderBookState(
                bookId = book.id,
                bookTitle = book.title, author = book.author,
                format = book.format, type = book.type, percent = percent,
                error = "Denne ${book.format.name}-filen er DRM-beskyttet (${drm.message ?: "ukjent DRM"}). " +
                        "Det er ikke tillatt å omgå DRM. Prøv en DRM-fri kopi, EPUB-versjonen, eller importer bok fra en ekstern kilde som tilbyr åpne formater."
            )
        } catch (mp: MobiParseException) {
            return@withContext ReaderBookState(
                bookId = book.id,
                bookTitle = book.title, author = book.author,
                format = book.format, type = book.type, percent = percent,
                error = "Kunne ikke tolke ${book.format.name}-filen: ${mp.message ?: "Ugyldig format"}"
            )
        } catch (t: Throwable) {
            return@withContext ReaderBookState(
                bookId = book.id,
                bookTitle = book.title, author = book.author,
                format = book.format, type = book.type, percent = percent,
                error = "Ugyldig ${book.format.name}-fil: ${t.message ?: "Ukjent feil"}"
            )
        }
        if (preMeta.hasDrm) {
            return@withContext ReaderBookState(
                bookId = book.id,
                bookTitle = book.title, author = book.author,
                format = book.format, type = book.type, percent = percent,
                error = "Denne ${book.format.name}-filen er DRM-beskyttet (${preMeta.drmReason ?: "Amazon/Kindle DRM"}). " +
                        "Det er ikke tillatt å omgå DRM. Prøv en DRM-fri kopi, EPUB-versjonen, eller importer bok fra en ekstern kilde som tilbyr åpne formater."
            )
        }

        // --- 2. Build conversion cache key and resolve output path ---
        val cacheDir = File(ctx.cacheDir, "mobi_conversions").apply { mkdirs() }
        val cacheKeySource = buildString {
            append(MOBI_CONVERTER_CACHE_VERSION)
            append('|').append(filePath?.absolutePath ?: fileUri ?: book.id.toString())
            append('|').append(book.fileSizeBytes)
            val mtime = if (filePath?.exists() == true) filePath.lastModified() else book.lastModifiedAt
            append('|').append(mtime)
        }
        val cacheHash = sha256Hex(cacheKeySource.toByteArray())
        val cachedEpub = File(cacheDir, "$cacheHash.epub")

        // --- 3. Convert (only if not already cached) ---
        if (!cachedEpub.exists() || cachedEpub.length() < 64L) {
            try {
                MobiUnpack.convertToEpub(mobiBytes, cachedEpub)
                Log.i(TAG, "[MOBI→EPUB v$MOBI_CONVERTER_CACHE_VERSION] converted ${book.format.name} '${book.title}' -> ${cachedEpub.absolutePath} (${cachedEpub.length()} bytes, key=$cacheHash)")
            } catch (drm: MobiDrmException) {
                cachedEpub.delete()
                return@withContext ReaderBookState(
                    bookId = book.id,
                    bookTitle = book.title, author = book.author,
                    format = book.format, type = book.type, percent = percent,
                    error = "Denne ${book.format.name}-filen er DRM-beskyttet (${drm.message ?: "ukjent DRM"}). " +
                            "Det er ikke tillatt å omgå DRM."
                )
            } catch (mp: MobiParseException) {
                cachedEpub.delete()
                Log.w(TAG, "[MOBI→EPUB] failed for '${book.title}': ${mp.message}")
                return@withContext ReaderBookState(
                    bookId = book.id,
                    bookTitle = book.title, author = book.author,
                    format = book.format, type = book.type, percent = percent,
                    error = "Kunne ikke konvertere ${book.format.name}-fil til lesbart format: ${mp.message ?: "Ugyldig MOBI-format"}"
                )
            } catch (t: Throwable) {
                cachedEpub.delete()
                Log.e(TAG, "[MOBI→EPUB] unexpected crash for '${book.title}'", t)
                return@withContext ReaderBookState(
                    bookId = book.id,
                    bookTitle = book.title, author = book.author,
                    format = book.format, type = book.type, percent = percent,
                    error = "Feil ved konvertering av ${book.format.name}-fil: ${t.message ?: "Ukjent konverteringsfeil"}"
                )
            }
        }

        // --- 4. Parse the converted EPUB using the *existing* EPUB parser pipeline ---
        //    This means the converted book uses HtmlPageRenderer, pagecurl, theme/typography,
        //    chapter navigation, etc. — 100% identical to native EPUB. No separate MOBI renderer.
        val parsed: ParsedBook? = try {
            parseEpub(ctx, cachedEpub.absolutePath, null)
        } catch (t: Throwable) {
            Log.e(TAG, "[MOBI→EPUB] post-conversion EPUB parser crash for '${book.title}'", t)
            null
        }

        if (parsed != null && parsed.chapters.isNotEmpty()) {
            val readerChapters = parsed.chapters.map {
                ReaderChapter(it.index, it.title, it.htmlContent, it.startByte, it.startByte + it.byteLength, it.inToc)
            }
            // Oppdater chaptersJson når titlene har endret seg (bedre TOC-navn fra
            // nav/NCX/h1 etter parserforbedring) — gamle importerer får navn ved neste
            // åpning uten reimport.
            val newTitles = parsed.chapters.map { it.title }
            val oldTitles = runCatching {
                val arr = org.json.JSONArray(book.chaptersJson ?: "[]")
                (0 until arr.length()).map { arr.optString(it) }
            }.getOrElse { emptyList() }
            if (book.chaptersJson.isNullOrBlank() || oldTitles != newTitles) {
                runCatching {
                    val jsonArray = org.json.JSONArray()
                    parsed.chapters.forEach { jsonArray.put(it.title) }
                    val refreshed = book.copy(
                        chapterCount = parsed.chapters.size,
                        chaptersJson = jsonArray.toString(),
                        lastModifiedAt = System.currentTimeMillis()
                    )
                    db.bookDao().update(refreshed)
                }
            }
            return@withContext ReaderBookState(
                bookId = book.id,
                bookTitle = parsed.title?.ifBlank { book.title } ?: book.title,
                author = parsed.author?.ifBlank { book.author } ?: book.author,
                format = book.format,
                type = book.type,
                chapters = readerChapters,
                currentChapterIndex = 0,
                percent = percent,
                error = null
            )
        }

        // If the EPUB parser rejected the converted output, report clearly and leave a breadcrumb
        // so the cached file can be inspected later (don't auto-delete; developer artifact).
        return@withContext ReaderBookState(
            bookId = book.id,
            bookTitle = preMeta.title?.ifBlank { book.title } ?: book.title,
            author = preMeta.author?.ifBlank { book.author } ?: book.author,
            format = book.format, type = book.type, percent = percent,
            error = "Konverterte ${book.format.name}→EPUB, men kunne ikke tolke resultatet. Prøv å konvertere boken til EPUB i forveien (f.eks. i Calibre), eller benytt en annen DRM-fri kopi av boken."
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun openStreamSafely(filePath: File?, fileUri: String?): InputStream? {
        if (filePath != null && filePath.canRead()) {
            return try {
                FileInputStream(filePath)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open file stream for ${filePath.absolutePath}", t)
                null
            }
        }
        if (!fileUri.isNullOrBlank()) {
            return try {
                ctx.contentResolver.openInputStream(Uri.parse(fileUri))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open URI stream for $fileUri. " +
                        "This usually means persistable permissions were lost.", t)
                null
            }
        }
        return null
    }

    private fun buildChapters(
        format: FormatEntity,
        content: String,
        totalBytes: Int
    ): List<ReaderChapter> {
        if (format == FormatEntity.HTML) {
            return listOf(ReaderChapter(0, "Innhold", content, 0, totalBytes))
        }

        // Split into paragraphs: blank lines separate paragraphs; long lines auto-split at 500 chars
        // NOTE: use literal \r?\n (not double-escaped) to actually match newlines
        val rawLines = content.split(Regex("\r?\n"))
        val paragraphs = mutableListOf<String>()
        var currentP = StringBuilder()

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (currentP.isNotEmpty()) {
                    paragraphs.add(currentP.toString())
                    currentP = StringBuilder()
                }
            } else {
                if (currentP.isNotEmpty()) currentP.append(" ")
                currentP.append(trimmed)
                if (currentP.length > 500) {
                    paragraphs.add(currentP.toString())
                    currentP = StringBuilder()
                }
            }
        }
        if (currentP.isNotEmpty()) {
            paragraphs.add(currentP.toString())
        }

        if (paragraphs.isEmpty()) {
            return listOf(ReaderChapter(0, "Innhold", "<section><p>Tom fil.</p></section>", 0, totalBytes))
        }

        // Chunk paragraphs: 40 paragraphs per chapter gives good page density
        // This is critical for TXT paging to work — too many paragraphs in one chapter
        // causes WebView to measure incorrectly and prevents page turns.
        val chunkSize = 40
        val chunks = paragraphs.chunked(chunkSize)

        val chapters = mutableListOf<ReaderChapter>()
        var cumulativeStart = 0
        chunks.forEachIndexed { i, pList ->
            val htmlBody = pList.joinToString("") { p ->
                val escaped = escapeHtml(p)
                "<p>$escaped</p>"
            }
            val htmlContent = "<section>$htmlBody</section>"
            val byteLength = htmlContent.toByteArray().size
            chapters.add(
                ReaderChapter(
                    index = i,
                    title = if (chunks.size == 1) "Innhold" else "Del ${i + 1}",
                    htmlContent = htmlContent,
                    startByte = cumulativeStart,
                    endByte = cumulativeStart + byteLength
                )
            )
            cumulativeStart += byteLength
        }

        return chapters
    }

    private fun isSupportedEbook(format: FormatEntity): Boolean = when (format) {
        FormatEntity.EPUB, FormatEntity.PDF, FormatEntity.FB2, FormatEntity.MOBI,
        FormatEntity.AZW, FormatEntity.AZW3,
        FormatEntity.CBZ, FormatEntity.CBR, FormatEntity.TXT, FormatEntity.MD,
        FormatEntity.HTML, FormatEntity.RTF, FormatEntity.DOCX -> true
        else -> false
    }

    /**
     * Strong heuristic content detector to prevent displaying non-book text
     * (BitTorrent Bencode, ZIP headers, object code, binary metadata dumps)
     * as "book content" in the reader. Returns a human-readable hint if the
     * content is almost certainly garbage, `null` otherwise.
     */
    private fun detectStructuredGarbage(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val headSize = minOf(bytes.size, 8192)
        val headStr = bytes.copyOfRange(0, headSize).toString(Charsets.UTF_8)

        // === #1: EXACT BitTorrent Bencode file list pattern ===
        // The bug we actually hit:
        //   "4:pathl63:Author - Title (US) (epub).epubeed6:lengthi3429231e4:..."
        // Pattern = digit+:path[lL] + anything + (epub).epub + "eed" + lengthi + digits + e
        if (headStr.contains(Regex("""\d+:path[lLd].*\(epub\)\.epub\s*e{1,2}\s*\d+:lengthi\d+e""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)))
            || headStr.contains(Regex("""^\s*d\s*8:announce"""))
            || headStr.contains(Regex("""4:name\d{1,4}:"""))
        ) {
            return "BitTorrent-metadata (Bencode filliste med .epub oppføringer)"
        }

        // === #2: Bencode-like structural density without sentence punctuation ===
        // Count signature patterns: "<digits>:" prefixes, "i<digits>e" ints, closing "de"/"le".
        val bencodeTokens = Regex("""(?:^|[\r\n ,;:])\d{1,6}:[A-Za-z0-9_]""").findAll(headStr).count() +
            Regex("""i\d{1,10}e""").findAll(headStr).count()
        val sentences = Regex("""[.!?]\s+[A-ZÆØÅ]""").findAll(headStr).count()
        if (bencodeTokens >= 6 && sentences <= 1) {
            return "strukturerte data (Bencode-lignende token-tetthet=$bencodeTokens)"
        }

        // === #3: ZIP / RAR / 7z / EXE / ELF headers ===
        val head4 = bytes.copyOfRange(0, minOf(bytes.size, 4))
        val s4 = head4.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        if (s4.startsWith("504B0304") || s4.startsWith("504B0506") || s4.startsWith("504B0708")) return "ZIP-fil-header (PK)"
        if (s4.startsWith("52617221")) return "RAR-arkiv-header (Rar!)"
        if (s4.startsWith("377ABCAF")) return "7z-arkiv-header"
        if (s4.startsWith("4D5A")) return "PE/EXE kjørbar Windows-fil"
        if (s4.startsWith("7F454C46")) return "ELF kjørbar Linux-fil"
        if (s4.startsWith("CAFEBABE")) return "Java Class-fil"
        if (s4.startsWith("1F8B")) return "gzip-komprimert data (.gz)"
        if (s4.startsWith("FD377A58")) return "XZ/LZMA komprimert data (.xz)"
        if (s4.startsWith("425A68")) return "bzip2 komprimert data (.bz2)"
        if (s4.startsWith("89504E47")) return "PNG bildefil"
        if (s4.startsWith("FFD8FFE0")) return "JPEG bildefil"
        if (s4.startsWith("25504446")) return "PDF-fil (bruk PDF-leseren, ikke rå tekst)"
        if (s4.startsWith("00000018") || s4.startsWith("00000020")) {
            // ftyp box (MP4/M4B)
            if (bytes.size >= 12) {
                val ftyp = bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII)
                if (ftyp == "ftyp") return "MP4/M4B lyd-container (bruk spilleren, ikke rå tekst)"
            }
        }
        if (bytes.size >= 3 && bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) {
            return "MP3-lydfil (ID3-header) (bruk spilleren, ikke rå tekst)"
        }

        // === #4: Non-printable ratio ===
        var printable = 0
        for (i in 0 until headSize) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x09 || b == 0x0A || b == 0x0D || (b in 0x20..0x7E) || (b in 0xA0..0xFF)) printable++
        }
        val printableRatio = printable.toDouble() / headSize.toDouble()
        if (printableRatio < 0.85) {
            return "lav andel printbare tegn (${(printableRatio*100).toInt()}% — binær data)"
        }

        // === #5: Whitespace sanity (normal books have ~20% whitespace, data dumps ~2%) ===
        var whitespace = 0
        for (i in 0 until headSize) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) whitespace++
        }
        val wsRatio = whitespace.toDouble() / headSize.toDouble()
        if (wsRatio < 0.04 && bencodeTokens >= 4) {
            return "manglende mellomrom/avsnitt (strukturerte data, ikke bok)"
        }

        return null
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

}
