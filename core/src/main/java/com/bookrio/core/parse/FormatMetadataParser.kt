package com.bookrio.core.parse

import android.content.Context
import android.net.Uri
import com.bookrio.core.domain.model.BookFormat
import com.bookrio.core.domain.model.BookMetadata
import com.bookrio.core.domain.model.ChapterInfo
import java.io.InputStream
import java.util.zip.ZipFile

interface FormatMetadataParser {
    suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata?
}

private fun filenameWithoutExtension(filename: String): String {
    val lower = filename.lowercase()
    if (lower.endsWith(".fb2.zip")) {
        return filename.substring(0, filename.length - 8)
    }
    val dot = filename.lastIndexOf('.')
    return if (dot < 0) filename else filename.substring(0, dot)
}

private fun fallbackMetadata(filename: String): BookMetadata {
    return BookMetadata(
        title = filenameWithoutExtension(filename),
        author = "",
        series = null,
        seriesIndex = null,
        description = null,
        publisher = null,
        publishedDate = null,
        language = null,
        isbn = null,
        pageCount = null,
        durationMs = null,
        chapters = emptyList()
    )
}

class EpubMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        return fallbackMetadata(filename)
    }
}

class PdfMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        return fallbackMetadata(filename)
    }
}

class MobiMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        val base = fallbackMetadata(filename)
        val bytes = tryReadFirstBytes(ctx, uri, filename, sizeBytes, sourceStreamProvider)
            ?: return base
        return try {
            val meta = MobiUnpack.parseMetadata(bytes)
            base.copy(
                title = meta.title?.ifBlank { null } ?: base.title,
                author = meta.author?.ifBlank { null } ?: base.author,
                publisher = meta.publisher,
                publishedDate = meta.publishedDate,
                language = meta.language,
                isbn = meta.isbn,
                description = meta.description
            )
        } catch (_: Throwable) {
            base
        }
    }
}

class AzwMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        val base = fallbackMetadata(filename)
        val bytes = tryReadFirstBytes(ctx, uri, filename, sizeBytes, sourceStreamProvider)
            ?: return base
        return try {
            val meta = MobiUnpack.parseMetadata(bytes)
            base.copy(
                title = meta.title?.ifBlank { null } ?: base.title,
                author = meta.author?.ifBlank { null } ?: base.author,
                publisher = meta.publisher,
                publishedDate = meta.publishedDate,
                language = meta.language,
                isbn = meta.isbn,
                description = meta.description
            )
        } catch (_: Throwable) {
            base
        }
    }
}

private suspend fun tryReadFirstBytes(
    ctx: Context,
    uri: Uri?,
    filename: String,
    sizeBytes: Long,
    sourceStreamProvider: (suspend () -> InputStream)?
): ByteArray? {
    val hardCap = (sizeBytes.coerceAtMost(16L * 1024L * 1024L)).toInt().coerceAtLeast(4096)
    return try {
        val stream: InputStream? = when {
            sourceStreamProvider != null -> runCatching { sourceStreamProvider() }.getOrNull()
            uri != null -> runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
            else -> null
        }
        stream?.use { s ->
            val baos = java.io.ByteArrayOutputStream(hardCap)
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (total < hardCap) {
                val n = s.read(buf)
                if (n < 0) break
                baos.write(buf, 0, n)
                total += n
            }
            if (total > 0) baos.toByteArray() else null
        }
    } catch (_: Throwable) {
        null
    }
}

class Fb2MetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        return fallbackMetadata(filename)
    }
}

class CbxMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        var pageCount: Int? = null
        val path = uri?.path
        if (path != null) {
            try {
                ZipFile(path).use { zip ->
                    pageCount = zip.entries().toList().count { !it.isDirectory }
                }
            } catch (_: Exception) {
            }
        }
        return fallbackMetadata(filename).copy(pageCount = pageCount)
    }
}

class TextMetadataParser : FormatMetadataParser {
    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        return fallbackMetadata(filename)
    }
}

class AudioMetadataParser : FormatMetadataParser {
    companion object {
        // MIDERTIDIG PÅ for live feilsøking på telefon — skru AV før release!
        private const val AUDIO_META_DIAG = true
        private const val AUDIO_META_TAG = "AudioMeta"
    }

    override suspend fun parse(
        ctx: Context,
        uri: Uri?,
        filename: String,
        sizeBytes: Long,
        sourceStreamProvider: (suspend () -> InputStream)?
    ): BookMetadata? {
        var mmr: android.media.MediaMetadataRetriever? = null
        var embeddedChapters: List<ChapterInfo> = emptyList()
        var streamDurMs: Long? = null

        // 1. Try embedded MP4/M4B chapter extraction (uses binary container atoms, works offline
        //    and doesn't require ExoPlayer initialization or playback licensing).
        val chapterKind = audioChapterExtension(filename, uri?.lastPathSegment)
        if (chapterKind == "mp3") {
            // 0. ID3v2 CHAP-rammer (MP3-lydbøker med innebygde kapitler)
            try {
                val stream = when {
                    sourceStreamProvider != null -> sourceStreamProvider()
                    uri != null -> ctx.contentResolver.openInputStream(uri)
                    else -> null
                }
                if (stream != null) {
                    stream.use { s ->
                        val ch = parseId3Chapters(s)
                        embeddedChapters = ch.first
                        if (streamDurMs == null) streamDurMs = ch.second
                    }
                }
            } catch (_: Exception) {}
        }
        if (chapterKind == "m4b" || chapterKind == "m4a" || chapterKind == "mp4") {
            try {
                // Re-openable source so the QuickTime chapter-track reader can fetch
                // its text samples from 'mdat' (which may sit before 'moov').
                val openStream: (() -> InputStream?)? = when {
                    sourceStreamProvider != null -> ({ kotlinx.coroutines.runBlocking { sourceStreamProvider() } })
                    uri != null -> ({ ctx.contentResolver.openInputStream(uri) })
                    else -> null
                }
                val stream = openStream?.invoke()
                if (stream != null) {
                    stream.use { s ->
                        val ch = parseMp4Chapters(s, sizeBytes, openStream)
                        embeddedChapters = ch.first
                        streamDurMs = ch.second
                    }
                }
            } catch (t: Exception) {
                if (AUDIO_META_DIAG) android.util.Log.d(AUDIO_META_TAG, "mp4 chapter parse failed", t)
            }
        }

        if (AUDIO_META_DIAG) {
            android.util.Log.d(
                AUDIO_META_TAG,
                "audio meta: ext=${filename.substringAfterLast('.')} embeddedChapters=${embeddedChapters.size} streamDurMs=$streamDurMs"
            )
        }

        try {
            mmr = android.media.MediaMetadataRetriever()
            if (uri != null) {
                if (uri.scheme == "file" && uri.path != null) {
                    mmr.setDataSource(uri.path)
                } else {
                    mmr.setDataSource(ctx, uri)
                }
            }

            val album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val albumArtist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: albumArtist
            val trackTitle = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durStr?.toLongOrNull() ?: streamDurMs

            val fallback = fallbackMetadata(filename)
            return fallback.copy(
                title = trackTitle?.takeIf { it.isNotBlank() }
                    ?: album?.takeIf { it.isNotBlank() }
                    ?: fallback.title,
                author = artist?.takeIf { it.isNotBlank() } ?: fallback.author,
                durationMs = durationMs,
                chapters = embeddedChapters,
                album = album?.takeIf { it.isNotBlank() },
                albumArtist = albumArtist?.takeIf { it.isNotBlank() } ?: artist?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            return fallbackMetadata(filename).copy(durationMs = streamDurMs, chapters = embeddedChapters)
        } finally {
            try { mmr?.release() } catch (_: Exception) {}
        }
    }
}

/**
 * Hvilken innebygd kapittel-representasjon filen kan ha, basert på filnavn
 * ELLER URI-ending. Sistnevnte gjør at oppdaging fungerer selv om kalleren
 * sender en visningstittel (uten filending) som 'filename'.
 */
internal fun audioChapterExtension(filename: String?, uriPath: String?): String? {
    val name = filename.orEmpty().lowercase()
    val uri = uriPath.orEmpty().lowercase()
    fun endsWith(ext: String) = name.endsWith(ext) || uri.endsWith(ext)
    return when {
        endsWith(".mp3") -> "mp3"
        endsWith(".m4b") -> "m4b"
        endsWith(".m4a") -> "m4a"
        endsWith(".mp4") -> "mp4"
        else -> null
    }
}

/**
 * Lightweight binary MP4/M4B/M4A chapter parser.
 * Reads the 'chpl' (chapters) QuickTime atom inside 'moov' → 'udta' hierarchy.
 * Never accesses MediaDrm or decodes audio - purely structural parsing of ISOBMFF container atoms.
 *
 * Returns Pair(chapters, totalDurationMillis?).
 *   chapters: zero or more entries, each with start/end millisecond offsets and title.
 *   durationMs: either the last chapter's end, or the 'mvhd' movie timescale duration, if available.
 *
 * FIX: 'moov' ligger ofte ETTER 'mdat' hos store lydbok-filer (single-file M4B).
 * Vi går derfor gjennom TOPP-nivå-atomene ved å skippe i strømmen (hele filen,
 * ingen 8 MB-begrensning) og leser KUN 'moov'-atomet inn i minnet.
 */
internal fun parseMp4Chapters(stream: InputStream, size: Long, openStream: (() -> InputStream?)? = null): Pair<List<ChapterInfo>, Long?> {
    var totalDurMs: Long? = null
    val embeddedChapters = mutableListOf<Pair<Long, String>>()

    fun u32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size || off < 0) return -1
        return ((b[off].toLong() and 0xFFL) shl 24) or
            ((b[off + 1].toLong() and 0xFFL) shl 16) or
            ((b[off + 2].toLong() and 0xFFL) shl 8) or
            (b[off + 3].toLong() and 0xFFL)
    }
    fun u64At(b: ByteArray, off: Int): Long {
        if (off + 8 > b.size || off < 0) return -1
        var v = 0L
        for (i in 0 until 8) {
            val byteVal = b[off + i].toLong() and 0xFFL
            if (i == 0 && byteVal != 0L) return -1L // u64 som ikke passer i Long-signert
            v = (v shl 8) or byteVal
        }
        return v
    }
    fun asciiTag(b: ByteArray, off: Int): String {
        if (off + 4 > b.size) return ""
        return buildString { append(b[off].toInt().toChar()); append(b[off+1].toInt().toChar()); append(b[off+2].toInt().toChar()); append(b[off+3].toInt().toChar()) }
    }

    // --- 1. Gå gjennom topp-nivå-atomer via strøm-skip (hele filen) -----------
    var moov: ByteArray? = null
    run {
        val header = ByteArray(8)
        while (true) {
            if (readN(stream, header, 8) < 8) break
            val aSize = u32(header, 0)
            val tag = asciiTag(header, 4)
            var payload = -1L
            if (aSize == 1L) {
                // 64-bit largesize
                val ext = ByteArray(8)
                if (readN(stream, ext, 8) < 8) break
                payload = ((ext[0].toLong() and 0xFFL) shl 56) or ((ext[1].toLong() and 0xFFL) shl 48) or
                        ((ext[2].toLong() and 0xFFL) shl 40) or ((ext[3].toLong() and 0xFFL) shl 32) or
                        ((ext[4].toLong() and 0xFFL) shl 24) or ((ext[5].toLong() and 0xFFL) shl 16) or
                        ((ext[6].toLong() and 0xFFL) shl 8) or (ext[7].toLong() and 0xFFL)
                payload -= 16
            } else if (aSize >= 8L) {
                // aSize == 8 er en GYLDIG tom atom (vanlig 'free'/'skip' i M4B fra
                // enkelte muxere). Den må hoppes over med payload 0, ellers aborterer
                // vi før 'moov' og finner aldri 'chpl'.
                payload = aSize - 8
            }
            if (tag == "moov") {
                if (payload in 8 until (128L * 1024L * 1024L)) {
                    val buf = ByteArray(payload.toInt())
                    val got = readN(stream, buf, buf.size)
                    if (got == buf.size) moov = buf
                }
                break // moov funnet (eller uleselig) — vi trenger ikke resten
            }
            if (payload < 0) break // størrelse 0 (til EOF) / korrupt — gi opp trygt
            if (skipFully(stream, payload) < payload) break
        }
    }

    val file = moov ?: return Pair(emptyList(), null)
    val fileEnd = file.size

    // --- 2. mvhd i moov → total varighet -------------------------------------
    // mvhd-layout: +8 ver/flags; v0: +12 creation(4) +16 modification(4) +20 timescale(4) +24 duration(4);
    //              v1: +12 creation(8) +20 modification(8) +28 timescale(4) +32 duration(8)
    var pos = 0
    var mvhdFound = false
    while (pos + 8 <= fileEnd) {
        val aSize = u32(file, pos)
        val aTag = asciiTag(file, pos + 4)
        if (aTag == "mvhd" && aSize > 28) {
            val ver = file[pos + 8].toInt() and 0xFF
            if (ver == 1) {
                val timeScale = u32(file, pos + 28)
                val duration = u64At(file, pos + 32)
                if (timeScale > 0 && duration > 0) totalDurMs = (duration * 1000L) / timeScale
            } else {
                val timeScale = u32(file, pos + 20)
                val duration = u32(file, pos + 24)
                if (timeScale > 0 && duration > 0) totalDurMs = (duration * 1000L) / timeScale
            }
            break
        }
        if (aSize < 8L || aSize > fileEnd) break
        pos += aSize.toInt()
    }

    // --- 3. chpl (moov → udta, rekursivt) -------------------------------------
    fun walk(parentStart: Int, parentEnd: Int, depth: Int) {
        if (depth > 8) return
        var p = parentStart
        while (p + 8 <= fileEnd && p < parentEnd) {
            val s = u32(file, p)
            val t = asciiTag(file, p + 4)
            if (s < 8L || s > fileEnd || p + s > fileEnd) break
            if (t == "chpl") {
                /*
                 * chpl-layout (Nero/QuickTime/ffmpeg):
                 *   +8  version(1) + flags(3)
                 *   nero/ffmpeg-variant: 4-byte count ved +12, oppføringer fra +16
                 *   QuickTime: 4-byte reserved ved +12, 1-byte count ved +16, oppføringer fra +17
                 *   Oppføring: uint64 starttid + titellengde (1 eller 2 byte) + UTF-8-tittel
                 *   TIDSENHET: 100-nanosecond ticks (ms × 10 000) i Nero/QuickTime/ffmpeg-konvensjonen.
                 */
                try {
                    val atomEnd = p + s.toInt()
                    val count4 = u32(file, p + 12).toInt()
                    val count1 = file[p + 16].toInt() and 0xFF
                    fun validCount(c: Int) = c in 1..5000

                    // Kandidater: (count, entryStartOffset, titleLengthBytes)
                    val candidates = listOf(
                        if (validCount(count4)) listOf(Triple(count4, p + 16, 1), Triple(count4, p + 16, 2)) else emptyList(),
                        if (validCount(count1)) listOf(Triple(count1, p + 17, 1), Triple(count1, p + 17, 2)) else emptyList(),
                    ).flatten()

                    for ((count, cur0, lenBytes) in candidates) {
                        val entries = tryParseEntries(file, atomEnd, cur0, count, lenBytes)
                        if (entries != null) {
                            embeddedChapters.addAll(entries)
                            break
                        }
                    }
                } catch (_: Throwable) {}
                return
            }
            if (t in setOf("moov", "udta", "meta", "ilst", "\u00A9nam", "----")) {
                val dataSkip = if (t == "meta") 4 else 0 // meta har 4-byte versjon/flags før barna
                walk(p + 8 + dataSkip, p + s.toInt(), depth + 1)
            }
            p += s.toInt()
        }
    }
    walk(0, fileEnd, 0)

    // --- 4. Tidsskala-konvertering: 100-ns ticks → ms --------------------------
    // Nero/QuickTime/ffmpeg skriver chpl-starttider i 100-nanosecond-enheter
    // (ms × 10 000). ms-verdier for lydbøker er alltid < ~4e7 (11 t); verdier
    // over ~1e9 kan derfor IKKE være ms — da er det ticks.
    val maxStart = embeddedChapters.maxOfOrNull { it.first } ?: 0L
    val isTicks = maxStart > 0L && (
            (totalDurMs != null && maxStart > totalDurMs!!) ||
            (totalDurMs == null && maxStart > 1_000_000_000L)
            )
    val scaledChapters = if (isTicks) {
        embeddedChapters.map { (raw, title) -> (raw / 10_000L) to title }
    } else embeddedChapters

    // --- 4b. QuickTime chapter track (no Nero 'chpl' atom) --------------------
    // iTunes/Audible-era M4B files often store chapters only as a referenced text
    // track ('tref' -> 'chap'); without this fallback such a book reported ZERO
    // embedded chapters and fell back to a single stub chapter.
    val chapterPairs = scaledChapters.ifEmpty { parseQuickTimeChapterTrack(file, openStream) }

    // --- 5. Build ChapterInfo with proper [start, end) ranges
    val chapters = chapterPairs.mapIndexed { idx, (startMs, title) ->
        ChapterInfo(
            index = idx,
            title = title.trim().ifBlank { "Kapittel ${idx + 1}" },
            startMs = startMs,
            endMs = null,
            href = null
        )
    }.toMutableList()
    for (i in 0 until chapters.size - 1) {
        chapters[i] = chapters[i].copy(endMs = chapters[i + 1].startMs)
    }
    if (chapters.isNotEmpty()) {
        val end = totalDurMs?.takeIf { it > chapters.last().startMs }
            ?: (chapters.last().startMs + 60L * 60L * 1000L)
        chapters[chapters.lastIndex] = chapters.last().copy(endMs = end)
    }
    return Pair(chapters, totalDurMs ?: chapters.lastOrNull()?.endMs?.takeIf { it > 0 })
}

/**
 * QuickTime chapter track: a media track references a text track via
 * `tref` → `chap`; the referenced track's `mdia → minf → stbl` carries the
 * chapter titles as text samples whose `stts` durations delimit the chapters.
 *
 * The chapter sample bytes live in `mdat` (often BEFORE `moov`), so they are
 * read by re-opening the source through [openStream] and skipping to each
 * sample's `stco` offset. Returns start-ms → title pairs (already in ms).
 */
private fun parseQuickTimeChapterTrack(
    moov: ByteArray,
    openStream: (() -> InputStream?)?,
): List<Pair<Long, String>> {
    if (openStream == null) return emptyList()

    fun u32(off: Int): Long =
        ((moov[off].toLong() and 0xFFL) shl 24) or
            ((moov[off + 1].toLong() and 0xFFL) shl 16) or
            ((moov[off + 2].toLong() and 0xFFL) shl 8) or
            (moov[off + 3].toLong() and 0xFFL)

    fun u64(off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (moov[off + i].toLong() and 0xFFL)
        return v
    }

    fun tag(off: Int): String =
        buildString { for (i in 0 until 4) append(moov[off + i].toInt().toChar()) }

    fun bounds(start: Int, end: Int, child: String): Pair<Int, Int>? {
        var q = start
        while (q + 8 <= end) {
            val s = u32(q)
            if (s < 8L || q + s > end) return null
            if (tag(q + 4) == child) return (q + 8) to (q + s).toInt()
            q += s.toInt()
        }
        return null
    }

    // 1) Chapter text track id(s) referenced by any track's tref.chap.
    val chapterTrackIds = HashSet<Long>()
    data class Trak(val start: Int, val end: Int, var trackId: Long)
    val traks = ArrayList<Trak>()
    var p = 0
    while (p + 8 <= moov.size) {
        val s = u32(p)
        if (s < 8L || p + s > moov.size) break
        if (tag(p + 4) == "trak") {
            val end = (p + s).toInt()
            var trackId = -1L
            var q = p + 8
            while (q + 8 <= end) {
                val ss = u32(q)
                if (ss < 8L || q + ss > end) break
                when (tag(q + 4)) {
                    "tkhd" -> {
                        val ver = moov[q + 8].toInt() and 0xFF
                        trackId = if (ver == 1) u64(q + 28) else u32(q + 20)
                    }
                    "tref" -> {
                        var r = q + 8
                        val refEnd = (q + ss).toInt()
                        while (r + 8 <= refEnd) {
                            // Track reference type atoms are standard atoms: size then type.
                            val refSize = u32(r).toInt()
                            val refType = tag(r + 4)
                            if (refSize < 8) break
                            if (refType == "chap") {
                                var v = r + 8
                                while (v + 4 <= r + refSize) {
                                    chapterTrackIds.add(u32(v))
                                    v += 4
                                }
                            }
                            r += refSize
                        }
                    }
                }
                q += ss.toInt()
            }
            traks.add(Trak(p, end, trackId))
        }
        p += s.toInt()
    }
    val chapterTrack = traks.firstOrNull { it.trackId > 0L && it.trackId in chapterTrackIds }
        ?: return emptyList()

    // 2) mdia → (mdhd timescale) / minf → stbl
    val mdia = bounds(chapterTrack.start + 8, chapterTrack.end, "mdia") ?: return emptyList()
    var timescale = 0L
    bounds(mdia.first, mdia.second, "mdhd")?.let { (ms, _) ->
        // `bounds` returns the atom PAYLOAD range: version/flags(4), creation(4|8),
        // modification(4|8), timescale(4).
        val ver = moov[ms].toInt() and 0xFF
        timescale = if (ver == 1) u32(ms + 20) else u32(ms + 12)
    }
    val minf = bounds(mdia.first, mdia.second, "minf") ?: return emptyList()
    val stbl = bounds(minf.first, minf.second, "stbl") ?: return emptyList()

    // 3) stts / stsz / stsc / stco|co64
    val sampleDurations = ArrayList<Long>()
    val sampleSizes = ArrayList<Int>()
    val chunkOffsets = ArrayList<Long>()
    val stsc = ArrayList<Triple<Int, Int, Int>>()
    var q = stbl.first
    while (q + 8 <= stbl.second) {
        val ss = u32(q)
        if (ss < 8L || q + ss > stbl.second) break
        val end = (q + ss).toInt()
        when (tag(q + 4)) {
            "stts" -> {
                val count = u32(q + 12).toInt()
                var r = q + 16
                for (i in 0 until count) {
                    if (r + 8 > end) break
                    val sc = u32(r).toInt().coerceAtMost(100_000)
                    val sd = u32(r + 4)
                    repeat(sc) { sampleDurations.add(sd) }
                    r += 8
                }
            }
            "stsz" -> {
                val uniform = u32(q + 12)
                val count = u32(q + 16).toInt()
                if (uniform > 0L) {
                    repeat(count) { sampleSizes.add(uniform.toInt()) }
                } else {
                    var r = q + 20
                    for (i in 0 until count) {
                        if (r + 4 > end) break
                        sampleSizes.add(u32(r).toInt())
                        r += 4
                    }
                }
            }
            "stsc" -> {
                val count = u32(q + 12).toInt()
                var r = q + 16
                for (i in 0 until count) {
                    if (r + 12 > end) break
                    stsc.add(Triple(u32(r).toInt(), u32(r + 4).toInt(), u32(r + 8).toInt()))
                    r += 12
                }
            }
            "stco" -> {
                val count = u32(q + 12).toInt()
                var r = q + 16
                for (i in 0 until count) {
                    if (r + 4 > end) break
                    chunkOffsets.add(u32(r))
                    r += 4
                }
            }
            "co64" -> {
                val count = u32(q + 12).toInt()
                var r = q + 16
                for (i in 0 until count) {
                    if (r + 8 > end) break
                    chunkOffsets.add(u64(r))
                    r += 8
                }
            }
        }
        q = end
    }
    if (sampleSizes.isEmpty() || chunkOffsets.isEmpty() || stsc.isEmpty()) return emptyList()

    // 4) Map each sample index to its absolute file offset via stsc/stco.
    val sampleOffsets = LongArray(sampleSizes.size)
    var chunk = 1
    var sampleIndex = 0
    var entryIndex = 0
    while (chunk <= chunkOffsets.size && sampleIndex < sampleSizes.size) {
        while (entryIndex + 1 < stsc.size && stsc[entryIndex + 1].first <= chunk) entryIndex++
        val perChunk = stsc[entryIndex].second.coerceAtLeast(1)
        var offset = chunkOffsets[chunk - 1]
        for (i in 0 until perChunk) {
            if (sampleIndex >= sampleSizes.size) break
            sampleOffsets[sampleIndex] = offset
            offset += sampleSizes[sampleIndex]
            sampleIndex++
        }
        chunk++
    }

    // 5) Read + decode each text sample; timestamps come from stts (× timescale).
    val out = ArrayList<Pair<Long, String>>()
    var ticks = 0L
    for (i in sampleSizes.indices) {
        val bytes = readByteRange(openStream, sampleOffsets[i], sampleSizes[i]) ?: ByteArray(0)
        val title = decodeTextSample(bytes)
        val startMs = if (timescale > 0L) (ticks * 1000L) / timescale else ticks
        if (title.isNotBlank()) out.add(startMs to title)
        ticks += sampleDurations.getOrNull(i) ?: 0L
    }
    return out
}

/** Re-opens the source and reads [size] bytes at absolute [offset]. */
private fun readByteRange(openStream: () -> InputStream?, offset: Long, size: Int): ByteArray? {
    if (size <= 0) return null
    return try {
        (openStream() ?: return null).use { s ->
            var toSkip = offset
            while (toSkip > 0) {
                val skipped = try { s.skip(toSkip) } catch (_: Exception) { 0L }
                if (skipped > 0) { toSkip -= skipped; continue }
                if (s.read() < 0) return null
                toSkip--
            }
            val buf = ByteArray(size)
            val got = readN(s, buf, size)
            if (got == size) buf else null
        }
    } catch (_: Exception) {
        null
    }
}

/** 3GPP/QuickTime text sample: 2-byte big-endian length prefix + UTF-8 text. */
private fun decodeTextSample(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val body = if (bytes.size >= 2) {
        val declared = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        // QuickTime `text` samples may carry trailing atoms (e.g. `encd`) after the
        // declared text; `tx3g` samples end exactly at the declared length.
        if (declared in 1..(bytes.size - 2)) bytes.copyOfRange(2, 2 + declared) else bytes
    } else {
        bytes
    }
    return body.toString(Charsets.UTF_8).trim('\u0000', ' ', '\n', '\r', '\t')
}

/** Leser nøyaktig n bytes (eller færre ved EOF) fra en InputStream. */
private fun readN(stream: InputStream, buf: ByteArray, want: Int): Int {
    var read = 0
    while (read < want) {
        val n = stream.read(buf, read, want - read)
        if (n < 0) break
        read += n
    }
    return read
}

/** Skipper n bytes, med read-fallback når stream.skip er upålitelig (SAF-strømmer). */
private fun skipFully(stream: InputStream, n: Long): Long {
    var remaining = n
    val buf = ByteArray(64 * 1024)
    while (remaining > 0) {
        val skipped = try { stream.skip(remaining) } catch (_: Exception) { 0L }
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        val toRead = minOf(buf.size.toLong(), remaining).toInt()
        val got = stream.read(buf, 0, toRead)
        if (got < 0) break
        remaining -= got
    }
    return n - remaining
}

/**
 * Parser chpl-oppføringer: (uint64 start + [1|2]-byte titellengde + UTF-8-tittel).
 * Validerer grenser og at starttidene er stigende — returnerer null ved
 * gal layout slik at neste kandidat (4/1-byte count, 1/2-byte lengde) prøves.
 */
private fun tryParseEntries(
    file: ByteArray,
    atomEnd: Int,
    cur0: Int,
    count: Int,
    lenBytes: Int,
): List<Pair<Long, String>>? {
    val out = mutableListOf<Pair<Long, String>>()
    var p = cur0
    var lastStart = -1L
    for (i in 0 until count) {
        if (p + 8 > atomEnd || p + 8 > file.size) return null
        var start = 0L
        for (k in 0 until 8) {
            val byteVal = file[p + k].toLong() and 0xFFL
            if (k == 0 && byteVal != 0L) return null // toppbyte != 0 → gal layout
            start = (start shl 8) or byteVal
        }
        p += 8
        if (p + lenBytes > atomEnd) return null
        val len = if (lenBytes == 1) (file[p].toInt() and 0xFF) else (((file[p].toInt() and 0xFF) shl 8) or (file[p + 1].toInt() and 0xFF))
        p += lenBytes
        if (len < 0 || p + len > atomEnd) return null
        val title = String(file, p, len, Charsets.UTF_8)
        p += len
        if (out.isNotEmpty() && start < out.last().first) return null // ikke stigende → feil layout
        out.add(start to title)
    }
    return out
}

/**
 * Lettvekts ID3v2-kapittelparser for MP3-lydbøker.
 * Leser CHAP-rammer (element-id, start/end ms) med TIT2-underramme som tittel.
 * Rent strukturell parsing — ingen dekoding av lyd.
 */
private fun parseId3Chapters(stream: InputStream): Pair<List<ChapterInfo>, Long?> {
    val head = ByteArray(10)
    var read = 0
    while (read < 10) {
        val n = stream.read(head, read, 10 - read)
        if (n < 0) return Pair(emptyList(), null)
        read += n
    }
    if (!(head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte())) {
        return Pair(emptyList(), null)
    }
    val major = (head[3].toInt() and 0xFF)
    if (major < 3 || major > 4) return Pair(emptyList(), null)
    val flags = head[5].toInt() and 0xFF

    fun syncsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

    fun plain32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    val tagSize = syncsafe(head, 6)
    val tagBytes = ByteArray(tagSize)
    var read2 = 0
    while (read2 < tagSize) {
        val n = stream.read(tagBytes, read2, tagSize - read2)
        if (n < 0) break
        read2 += n
    }
    var off = 0
    if (flags and 0x40 != 0 && off + 4 <= tagBytes.size) {
        // Extended header: størrelsen er syncsafe i v2.4, plain i v2.3
        val extSize = if (major >= 4) syncsafe(tagBytes, off) else plain32(tagBytes, off)
        off += if (extSize > 0) extSize else 6
    }

    data class Chap(val elementId: String, val startMs: Long, val endMs: Long?, val title: String?)

    val chaptersRaw = mutableListOf<Chap>()
    var totalDurMs: Long? = null

    fun decodeText(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val enc = body[0].toInt() and 0xFF
        val raw = body.copyOfRange(1, body.size)
        return try {
            when (enc) {
                0 -> String(raw, Charsets.ISO_8859_1)
                1 -> String(raw, Charsets.UTF_16).trimStart('\uFEFF')
                2 -> String(raw, Charsets.UTF_16BE)
                else -> String(raw, Charsets.UTF_8)
            }.trim('\u0000').trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    try {
        while (off + 10 <= tagBytes.size) {
            val id = String(tagBytes, off, 4, Charsets.ISO_8859_1)
            if (id.isBlank() || !id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = if (major >= 4) syncsafe(tagBytes, off + 4) else plain32(tagBytes, off + 4)
            if (size <= 0 || off + 10 + size > tagBytes.size) break
            val body = tagBytes.copyOfRange(off + 10, off + 10 + size)
            when (id) {
                "CHAP" -> {
                    // element-id (nullterminert), start/end ms (u32 BE), start/end byte-offset (u32)
                    var p = 0
                    while (p < body.size && body[p] != 0.toByte()) p++
                    p++ // null
                    if (p + 16 <= body.size) {
                        val startMs = plain32(body, p).toLong() and 0xFFFFFFFFL
                        val endMs = plain32(body, p + 4).toLong() and 0xFFFFFFFFL
                        val sub = body.copyOfRange(p + 16, body.size)
                        // Finn TIT2-underramme
                        var title: String? = null
                        var sp = 0
                        while (sp + 10 <= sub.size) {
                            val sid = String(sub, sp, 4, Charsets.ISO_8859_1)
                            if (!sid.all { it in 'A'..'Z' || it in '0'..'9' }) break
                            val ssize = if (major >= 4) syncsafe(sub, sp + 4) else plain32(sub, sp + 4)
                            if (ssize <= 0 || sp + 10 + ssize > sub.size) break
                            if (sid == "TIT2") {
                                title = decodeText(sub.copyOfRange(sp + 10, sp + 10 + ssize))
                                break
                            }
                            sp += 10 + ssize
                        }
                        chaptersRaw.add(Chap(id, startMs, endMs.takeIf { it > startMs }, title))
                    }
                }
                "TLEN" -> decodeText(body)?.toLongOrNull()?.let { if (it > 0) totalDurMs = it }
            }
            off += 10 + size
        }
    } catch (_: Exception) {
        // Best effort: returner det vi har funnet så langt
    }

    if (chaptersRaw.isEmpty()) return Pair(emptyList(), totalDurMs)
    val sorted = chaptersRaw.sortedBy { it.startMs }
    val chapters = sorted.mapIndexed { idx, c ->
        ChapterInfo(
            title = c.title ?: "Kapittel ${idx + 1}",
            startMs = c.startMs,
            endMs = c.endMs,
            index = idx
        )
    }.toMutableList()
    for (i in 0 until chapters.size - 1) {
        if (chapters[i].endMs == null || chapters[i].endMs!! <= chapters[i].startMs) {
            chapters[i] = chapters[i].copy(endMs = chapters[i + 1].startMs)
        }
    }
    return Pair(chapters, totalDurMs)
}

fun getParserFor(format: BookFormat): FormatMetadataParser = when (format) {
    BookFormat.EPUB -> EpubMetadataParser()
    BookFormat.PDF -> PdfMetadataParser()
    BookFormat.MOBI -> MobiMetadataParser()
    BookFormat.AZW, BookFormat.AZW3 -> AzwMetadataParser()
    BookFormat.FB2 -> Fb2MetadataParser()
    BookFormat.CBZ, BookFormat.CBR -> CbxMetadataParser()
    BookFormat.TXT, BookFormat.MD, BookFormat.HTML, BookFormat.RTF, BookFormat.DOCX -> TextMetadataParser()
    BookFormat.M4B, BookFormat.M4A, BookFormat.MP3, BookFormat.AAC,
    BookFormat.FLAC, BookFormat.OGG, BookFormat.OPUS, BookFormat.WAV -> AudioMetadataParser()
    BookFormat.ZIP, BookFormat.UNKNOWN -> TextMetadataParser()
}
