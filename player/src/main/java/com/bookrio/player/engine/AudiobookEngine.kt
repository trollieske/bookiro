package com.bookrio.player.engine

import android.content.Context
import com.bookrio.player.R
import android.net.Uri
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import java.io.File
import kotlin.math.max

data class AudiobookChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long? = null,
    val mediaUri: String? = null
)

data class AudiobookState(
    val title: String,
    val author: String,
    val format: FormatEntity,
    val type: BookTypeEntity,
    val coverPath: String? = null,
    val mediaUri: String? = null,
    val durationMs: Long = 0L,
    val currentMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val chapters: List<AudiobookChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val percent: Float = 0f,
    val sleepTimerMinutes: Int? = null,
    val sleepTimerRemainingMs: Long = 0L,
    val error: String? = null
)

class AudiobookEngine(
    private val ctx: Context,
    private val db: ShelfDatabase
) {


    suspend fun loadBook(bookId: Long): AudiobookState {
        val book = db.bookDao().getById(bookId)
            ?: return AudiobookState(
                title = "",
                author = "",
                format = FormatEntity.UNKNOWN,
                type = BookTypeEntity.AUDIOBOOK,
                coverPath = null,
                error = ctx.getString(R.string.ply_book_not_found)
            )

        val prog = db.progressDao().getByBook(bookId)?.progressPercent ?: 0f

        val mediaUri = resolveSourceForPlayback(ctx, book)

        val durationMs = book.durationMs ?: estimateDuration(ctx, book, mediaUri)

        // KANONISK kapitteloppdatering: oppdager og persisterer reelle kapitler for
        // eksisterende importerte lydbøker uten re-import (se ensureFreshChapters).
        val chapters = ensureFreshChapters(book)

        val currentMs = (prog * durationMs).toLong()
            .coerceAtMost(max(durationMs - 5_000L, 0L))

        val currentChapterIndex = chapters
            .indexOfLast { it.startMs <= currentMs }
            .coerceAtLeast(0)

        val coverPath = resolveCoverPath(ctx, book)

        return AudiobookState(
            title = book.title,
            author = book.author,
            format = book.format,
            type = book.type,
            coverPath = coverPath,
            mediaUri = mediaUri,
            durationMs = durationMs,
            currentMs = currentMs,
            isPlaying = false,
            playbackSpeed = 1.0f,
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            percent = prog,
            sleepTimerMinutes = null,
            error = null
        )
    }

    private fun resolveCoverPath(ctx: Context, book: BookEntity): String? {
        book.coverPath?.let { p ->
            if (File(p).exists()) return p
        }
        val generated = File(ctx.filesDir, "covers/book_${book.id}.webp")
        if (generated.exists()) return generated.absolutePath
        return null
    }

    private fun resolveSourceForPlayback(ctx: Context, book: BookEntity): String? {
        book.filePath?.let { path ->
            val file = File(path)
            if (file.canRead()) {
                return path
            }
        }
        book.fileUri?.let { uri ->
            return try {
                Uri.parse(uri).toString()
            } catch (_: Exception) {
                uri
            }
        }
        return null
    }

    private fun estimateDuration(ctx: Context, book: BookEntity, mediaUri: String?): Long {
        return 2L * 60L * 60L * 1000L
    }

    /**
     * KANONISK «sørg for ferske lydbok-kapitler»-sti. Kalles av både [loadBook]
     * og AudiobookPlaybackService — aldri fra PlayerScreen.
     *
     * 1. Les lagret chaptersJson.
     * 2. Vurder stale via [ChapterRefresh.evaluateStoredChapters] (blank, ødelagt,
     *    tom, én syntetisk stub-oppføring, chapterCount-uenighet).
     * 3. Ved stale: kjør oppdagelse via samme pipeline som import:
     *    mappe/flerfil (én kapittel per fil, naturlig sortering) → M4B/M4A chpl →
     *    MP3 ID3 CHAP. Ingen falske kapitler lages for lange bøker.
     * 4. Persister KUN hvis funnet liste har > 1 reelle kapitler (og flere enn
     *    lagret). Én ekte kapittelfil beholder nøyaktig én kapittel med boktittel.
     * 5. Vellykket refresh persisteres; re-parse skjer aldri per avspillings-tick,
     *    og aldri to ganger for samme filidentitet (størrelse + lastModified).
     */
    suspend fun ensureFreshChapters(book: BookEntity): List<AudiobookChapter> {
        val jsonBlank = book.chaptersJson.isNullOrBlank()
        val stored = parseChapters(book.chaptersJson ?: "")
        val rows = stored.map { StoredChapterRow(it.title, it.startMs) }
        // Containere som kan ha innebygde kapitler: en enkelt lagret oppføring er
        // UAVKLART (kan være import-fallback) og verifiseres ved skanning.
        val mayEmbedChapters = book.format in setOf(
            FormatEntity.M4B, FormatEntity.M4A, FormatEntity.MP3, FormatEntity.AAC
        )
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = jsonBlank,
            parsedChapters = rows,
            bookTitle = book.title,
            storedChapterCount = book.chapterCount,
            mayEmbedChapters = mayEmbedChapters,
        )
        val ext = (book.filePath?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?: book.format.name.lowercase())

        if (reason == null) {
            chapterDiag("FRESH id=${book.id} ext=$ext stored=${stored.size}")
            val fallback = stored.ifEmpty { buildStubChapters(book.title, book.durationMs ?: 0L) }
            return netEnhance(book, fallback)
        }

        // Unngå gjentatt parsing av samme filidentitet: kun én discovery per
        // (størrelse, sistEndret) per app-prosess.
        val identity = "${book.fileSizeBytes}:${book.lastModifiedAt}"
        val alreadyAttempted = attemptedRefresh.put(book.id, identity) == identity
        if (alreadyAttempted) {
            chapterDiag("SKIP id=${book.id} ext=$ext reason=$reason (samme identitet forsøkt)")
            // En annen kalller (view model / service) kan nettopp ha persistert nye
            // kapitler. Les på nytt fra DB så vi aldri returnerer et utdatert snapshot.
            val fresh = runCatching { db.bookDao().getById(book.id) }.getOrNull() ?: book
            val freshStored = parseChapters(fresh.chaptersJson ?: "")
            return freshStored.ifEmpty { buildStubChapters(fresh.title, fresh.durationMs ?: 0L) }
        }
        chapterDiag("STALE id=${book.id} ext=$ext stored=${stored.size} reason=$reason")

        val discovery = discoverChapters(book)
        val found = discovery?.chapters.orEmpty()
        if (discovery != null && found.size > 1 && found.size > stored.size) {
            runCatching {
                db.bookDao().update(
                    book.copy(
                        chaptersJson = discovery.json,
                        chapterCount = found.size,
                        durationMs = book.durationMs?.takeIf { it > 0L } ?: discovery.durationMs.takeIf { it > 0L },
                        lastModifiedAt = System.currentTimeMillis(),
                    )
                )
            }
            chapterDiag("REFRESHED id=${book.id} source=${discovery.source} found=${found.size}")
            return netEnhance(book, found)
        }
        chapterDiag("KEEP id=${book.id} source=${discovery?.source ?: ChapterDiscoverySource.NONE} found=${found.size}")
        // Én ekte kapittelfil uten metadata: behold nøyaktig én kapittel med boktittel.
        val fallback = stored.ifEmpty { buildStubChapters(book.title, book.durationMs ?: 0L) }
        return netEnhance(book, fallback)
    }

    private val netLookupAttempted = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * Nett-fase: hent reelle kapittelnavn/grenser fra Audible-metadata når våre
     * egne kilder ikke ga dem:
     *  - stub (én generisk kapittel) → kapitler kan OPPRETTES fra Audibles
     *    varigheter (validert mot vår totale varighet, ±5 %).
     *  - > 1 kapittel med generiske titler («Chapter N») → titler erstattes
     *    KUN ved nøyaktig likt antall (indeks-til-indeks).
     * Persisteres umiddelbart; maks ÉN nett-attempt per filidentitet per prosess.
     */
    private suspend fun netEnhance(book: BookEntity, base: List<AudiobookChapter>): List<AudiobookChapter> {
        // Stub-deteksjon: identisk tittel, «Forfatter - Tittel»-form, eller generisk.
        val chapterTitle = base[0].title.trim()
        val bookTitle = book.title.trim()
        val isStub = base.size == 1 && base[0].startMs == 0L &&
            (chapterTitle.equals(bookTitle, ignoreCase = true) ||
                (bookTitle.isNotBlank() && chapterTitle.contains(bookTitle, ignoreCase = true)) ||
                AudibleChapterLookup.looksGeneric(chapterTitle))
        val wantsTitles = base.size >= 2 && base.any { AudibleChapterLookup.looksGeneric(it.title) }
        if (!isStub && !wantsTitles) return base

        val identity = "${book.fileSizeBytes}:${book.lastModifiedAt}"
        // Persistent huskemarkør: aldri mer enn én nett-attempt per filidentitet,
        // også på tvers av app-omstarter (unødvendig nettverk ellers).
        val netPrefs = ctx.getSharedPreferences("chapter_net_lookup", Context.MODE_PRIVATE)
        val netKey = "${book.id}:$identity"
        if (netPrefs.getStringSet("attempted", emptySet())?.contains(netKey) == true) {
            chapterDiag("NET-SKIP id=${book.id} (allerede forsøkt)")
            return base
        }
        if (netLookupAttempted.put(book.id, identity) == identity) return base

        val updated = lookupOnlineChapters(this, book, base, playableSourceUri(book))
        netPrefs.edit()
            .putStringSet("attempted", (netPrefs.getStringSet("attempted", emptySet())!! + netKey))
            .apply()
        if (updated != base && updated.size > 1) {
            runCatching {
                db.bookDao().update(
                    book.copy(
                        chaptersJson = chaptersToJson(updated),
                        chapterCount = updated.size,
                        durationMs = book.durationMs?.takeIf { it > 0L },
                        lastModifiedAt = System.currentTimeMillis(),
                    )
                )
            }
            chapterDiag("NET-PERSISTED id=${book.id} chapters=${updated.size}")
        }
        return updated
    }

    private val attemptedRefresh = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private data class Discovery(
        val chapters: List<AudiobookChapter>,
        val json: String,
        val source: ChapterDiscoverySource,
        val durationMs: Long = 0L,
    )

    /**
     * Kapittel-oppdagelse med samme prioritet som import:
     * 1. Mappe/flerfil: én kapittel per lydfil (naturlig sortering: 1, 2, 10).
     * 2. M4B/M4A: QuickTime/iTunes chpl.
     * 3. MP3: ID3v2 CHAP.
     * Ingen metadata → null (kalleren beholder én ekte/stub-kapittel).
     */
    private suspend fun discoverChapters(book: BookEntity): Discovery? {
        // 1) Flerfil/mappe-lydbok
        val tracks = runCatching { db.audioTrackDao().getTracksForBook(book.id) }.getOrDefault(emptyList())
        if (tracks.size >= 2) {
            // trackNumber følger import-rekkefølgen; naturlig filnavn-tiebreak.
            val ordered = tracks.sortedWith(
                compareBy({ it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE })
            ).let { sorted ->
                if (sorted.all { it.trackNumber == sorted.first().trackNumber }) {
                    ChapterRefresh.sortedNaturally(sorted) { it.title.ifBlank { it.fileUri ?: it.filePath ?: "" } }
                } else sorted
            }
            var cum = 0L
            val chapters = ordered.mapIndexed { i, t ->
                val dur = t.durationMs.takeIf { it > 0L } ?: 300_000L
                val ch = AudiobookChapter(
                    index = i,
                    title = localizedChapterTitle(ctx, t.title, i),
                    startMs = cum,
                    endMs = cum + dur,
                    mediaUri = t.fileUri?.takeIf { it.isNotBlank() } ?: t.filePath,
                )
                cum += dur
                ch
            }
            return Discovery(chapters, chaptersToJson(chapters), ChapterDiscoverySource.FOLDER, cum)
        }

        // 2) CUE-sheet ved siden av lydfilen (f.eks. torrent-utpakket MP3 + .cue)
        val sourceUri = playableSourceUri(book) ?: return null
        discoverCueChapters(book, sourceUri)?.let { return it }

        // 3/4) Innebygde kapitler i enkeltfil
        val coreFormat = when (book.format) {
            FormatEntity.M4B -> com.bookrio.core.domain.model.BookFormat.M4B
            FormatEntity.M4A -> com.bookrio.core.domain.model.BookFormat.M4A
            FormatEntity.MP3 -> com.bookrio.core.domain.model.BookFormat.MP3
            FormatEntity.AAC -> com.bookrio.core.domain.model.BookFormat.AAC
            else -> return null
        }
        val uri = if (sourceUri.startsWith("/")) Uri.fromFile(File(sourceUri)) else Uri.parse(sourceUri)
        // Kalleren sendte tidligere book.title her → uten filending hoppet
        // AudioMetadataParser over innebygd kapittel-parsing og fant 0 kapitler.
        val parseFileName = ChapterRefresh.chapterParserFileName(book.filePath, book.fileUri, book.format.name)
        val meta = runCatching {
            com.bookrio.core.parse.getParserFor(coreFormat).parse(ctx, uri, parseFileName, book.fileSizeBytes, null)
        }.getOrNull()
        val list = meta?.chapters.orEmpty().filter { it.startMs >= 0L }
        if (list.size <= 1) return null
        val duration = meta?.durationMs ?: 0L
        val source = when (coreFormat) {
            com.bookrio.core.domain.model.BookFormat.MP3 -> ChapterDiscoverySource.ID3_CHAP
            else -> ChapterDiscoverySource.MP4_CHPL
        }
        val chapters = chaptersFromDiscovered(list, duration, sourceUri)
            .mapIndexed { idx, ch -> ch.copy(title = localizedChapterTitle(ctx, ch.title, idx)) }
        return Discovery(chapters, chaptersToJson(chapters), source, duration)
    }

    /**
     * CUE-oppdagelse: finn «<sammeBase>.cue» ved siden av lydfilen, parse den.
     * Filsti → direkte filsøsken; SAF-URI → visningsnavn + MediaStore-oppslag av
     * søsken-dokument innenfor samme tre (begrenset, billig, én gang per identitet).
     */
    private suspend fun discoverCueChapters(book: BookEntity, sourceUri: String): Discovery? {
        val cueUri = findCueSibling(sourceUri) ?: return null
        val text = runCatching {
            ctx.contentResolver.openInputStream(cueUri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return null
        val cue = com.bookrio.core.parse.CueParser.parse(text)
        if (cue.size <= 1) return null
        val duration = book.durationMs?.takeIf { it > 0L } ?: 0L
        val list = cue.map {
            com.bookrio.core.domain.model.ChapterInfo(
                index = it.index,
                title = it.title,
                startMs = it.startMs,
                endMs = null,
                href = null,
            )
        }
        val chapters = chaptersFromDiscovered(list, duration, sourceUri)
            .mapIndexed { idx, ch -> ch.copy(title = localizedChapterTitle(ctx, ch.title, idx)) }
        return Discovery(chapters, chaptersToJson(chapters), ChapterDiscoverySource.CUE, duration)
    }

    private fun findCueSibling(sourceUri: String): android.net.Uri? {
        // 1) Direkte filsti: søsken med samme basenavn
        if (sourceUri.startsWith("/")) {
            val f = File(sourceUri)
            val cue = File(f.parentFile, f.nameWithoutExtension + ".cue")
            return if (cue.exists()) Uri.fromFile(cue) else null
        }

        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return null
        val docId = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return null
        val treeId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val displayName = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: return null
        val base = displayName.substringBeforeLast('.')
        val audioStoreId = docId.substringAfter(':').toLongOrNull()

        // 2) MediaStore: finn «<base>.cue» (samme mappe hvis mulig), bygg dokument-URI
        //    innenfor det allerede gitte treet.
        val prefix = docId.substringBefore(':') + ":" // f.eks. "msf:"
        for (collection in listOf(
            android.provider.MediaStore.Downloads.getContentUri("external"),
            android.provider.MediaStore.Files.getContentUri("external"),
        )) {
            runCatching {
                ctx.contentResolver.query(
                    collection,
                    arrayOf("_id", "_display_name", "relative_path"),
                    "_display_name = ? COLLATE NOCASE",
                    arrayOf("$base.cue"),
                    null,
                )?.use { c ->
                    var fallbackId: Long? = null
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (fallbackId == null) fallbackId = id
                        val rowPath = c.getString(2)
                        if (audioStoreId != null && id == audioStoreId) continue // aldri lydfilen selv
                        // Samme mappe som lydfilen er foretrukket, men ikke påkrevd
                        if (rowPath != null && audioStoreId != null) {
                            val audioPath = queryRelativePath(audioStoreId)
                            if (audioPath != null && rowPath != audioPath) continue
                        }
                        val cueDocId = prefix + id
                        val treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority, treeId)
                        return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, cueDocId)
                    }
                    if (fallbackId != null && fallbackId != audioStoreId) {
                        val treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority, treeId)
                        return android.provider.DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, prefix + fallbackId
                        )
                    }
                }
            }
        }
        return null
    }

    private fun queryRelativePath(mediaStoreId: Long): String? = runCatching {
        ctx.contentResolver.query(
            android.provider.MediaStore.Files.getContentUri("external"),
            arrayOf("relative_path"),
            "_id = ?",
            arrayOf(mediaStoreId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun playableSourceUri(book: BookEntity): String? {
        book.filePath?.let { p ->
            if (p.isNotBlank() && File(p).canRead()) return p
        }
        return book.fileUri?.takeIf { it.isNotBlank() }
    }


    /** Samme JSON-format som import-pipelinen skriver. */
    private fun chaptersToJson(chapters: List<AudiobookChapter>): String {
        val arr = org.json.JSONArray()
        chapters.forEach { ch ->
            arr.put(
                org.json.JSONObject().apply {
                    put("index", ch.index)
                    put("title", ch.title)
                    put("startMs", ch.startMs)
                    put("endMs", ch.endMs ?: org.json.JSONObject.NULL)
                    ch.mediaUri?.let { put("mediaUri", it) }
                    put("durationMs", ((ch.endMs ?: ch.startMs) - ch.startMs).coerceAtLeast(1L))
                }
            )
        }
        return arr.toString()
    }

    private fun parseChapters(json: String): List<AudiobookChapter> {
        val parsed = runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AudiobookChapter(
                    index = obj.optInt("index", i),
                    title = localizedChapterTitle(ctx, obj.optString("title"), i),
                    startMs = obj.optLong("startMs", 0L),
                    endMs = obj.optLong("endMs", 0L).takeIf { it > 0L },
                    mediaUri = obj.optString("mediaUri").takeIf { it.isNotBlank() }
                )
            }
        }.getOrElse { emptyList() }
        return normalizeChapterEnds(parsed)
    }

    /** Fyll inn manglende endMs fra neste kapittel / varighet slik at klipping blir eksakt. */
    private fun normalizeChapterEnds(list: List<AudiobookChapter>): List<AudiobookChapter> {
        if (list.isEmpty()) return list
        return list.mapIndexed { i, ch ->
            val end = ch.endMs ?: list.getOrNull(i + 1)?.startMs?.takeIf { it > ch.startMs }
            ch.copy(endMs = end)
        }
    }

    /**
     * Ingen kapittelinformasjon i det hele tatt: ÉN kapittelpost med boktittelen.
     * Aldri fabrikerte "Kapittel N"-stubber — spilleren viser boken som den er.
     */
    private fun buildStubChapters(bookTitle: String, durationMs: Long): List<AudiobookChapter> {
        return listOf(
            AudiobookChapter(
                index = 0,
                title = bookTitle.ifBlank { ctx.getString(R.string.ply_title) },
                startMs = 0L,
                endMs = durationMs.takeIf { it > 0L }
            )
        )
    }

    suspend fun readDurationStub(book: BookEntity, uri: String?): Long {
        return book.durationMs ?: (120L * 60L * 1000L)
    }
}

/** Ren konvertering av oppdagede kapitler → AudiobookChapter (JVM-testbar). */
internal fun chaptersFromDiscovered(
    list: List<com.bookrio.core.domain.model.ChapterInfo>,
    durationMs: Long,
    sourceUri: String?,
): List<AudiobookChapter> {
    val sorted = list.sortedBy { it.startMs }
    return sorted.mapIndexed { idx, ch ->
        val end = sorted.getOrNull(idx + 1)?.startMs?.takeIf { it > ch.startMs }
            ?: durationMs.takeIf { it > ch.startMs }
            ?: ch.endMs?.takeIf { it > ch.startMs }
            ?: (ch.startMs + 10L * 60L * 1000L)
        AudiobookChapter(
            index = idx,
            title = ch.title,
            startMs = ch.startMs,
            endMs = end,
            mediaUri = sourceUri,
        )
    }
}
