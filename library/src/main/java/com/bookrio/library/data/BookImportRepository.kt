package com.bookrio.library.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.dispatchers.DispatcherProvider
import com.bookrio.core.domain.model.BookFormat
import com.bookrio.core.parse.getParserFor
import com.bookrio.core.parse.BookTitleCleaner
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.AudioTrackEntity
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.library.util.AudiobookNormalizer
import com.bookrio.library.util.EbookFilenameParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class BookImportRepository(
    private val ctx: Context,
    private val db: ShelfDatabase,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) {

    companion object {
        private const val TAG = "BookImportRepo"

        private val LEADING_ARTICLES = listOf("the ", "en ", "et ", "ei ")

        /**
         * Serialises the two audiobook self-heal passes (fragment consolidation and
         * one-chapter repair) so they can never interleave their reads/writes.
         */
        private val audiobookHealMutex = Mutex()

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

        fun filenameWithoutExtension(filename: String): String {
            val lower = filename.lowercase()
            if (lower.endsWith(".fb2.zip")) {
                return filename.substring(0, filename.length - 8)
            }
            val dot = filename.lastIndexOf('.')
            return if (dot < 0) filename else filename.substring(0, dot)
        }

        fun coreFormatToEntity(f: BookFormat): FormatEntity = when (f) {
            BookFormat.EPUB -> FormatEntity.EPUB
            BookFormat.PDF -> FormatEntity.PDF
            BookFormat.MOBI -> FormatEntity.MOBI
            BookFormat.AZW -> FormatEntity.AZW
            BookFormat.AZW3 -> FormatEntity.AZW3
            BookFormat.FB2 -> FormatEntity.FB2
            BookFormat.CBZ -> FormatEntity.CBZ
            BookFormat.CBR -> FormatEntity.CBR
            BookFormat.TXT -> FormatEntity.TXT
            BookFormat.HTML -> FormatEntity.HTML
            BookFormat.RTF -> FormatEntity.RTF
            BookFormat.DOCX -> FormatEntity.DOCX
            BookFormat.MD -> FormatEntity.MD
            BookFormat.M4B -> FormatEntity.M4B
            BookFormat.M4A -> FormatEntity.M4A
            BookFormat.MP3 -> FormatEntity.MP3
            BookFormat.AAC -> FormatEntity.AAC
            BookFormat.FLAC -> FormatEntity.FLAC
            BookFormat.OGG -> FormatEntity.OGG
            BookFormat.OPUS -> FormatEntity.OPUS
            BookFormat.WAV -> FormatEntity.WAV
            BookFormat.ZIP -> FormatEntity.ZIP
            BookFormat.UNKNOWN -> FormatEntity.UNKNOWN
        }
    }

    /**
     * Persistent per-book marker for single-file chapter re-parse attempts
     * (MINOR 4): key = book id, value = `size:lastModified`. A file identity that
     * already yielded no embedded chapters is not re-parsed on every cold start.
     */
    private val repairAttemptPrefs by lazy {
        ctx.getSharedPreferences("one_chapter_repair_attempts", Context.MODE_PRIVATE)
    }

    suspend fun importUris(
        uris: List<Uri>,
        source: ImportSourceEntity,
        serverId: Long? = null,
        remotePath: String? = null,
        filePathOverride: String? = null,
        /** Callers that import in batches should pass false and consolidate once at the end. */
        consolidate: Boolean = true
    ): List<Long> = withContext(dispatchers.io) {
        val insertedIds = mutableListOf<Long>()
        val audioUris = mutableListOf<Pair<Uri, String>>()
        val nonAudioUris = mutableListOf<Pair<Uri, String>>()

        for (uri in uris) {
            val path = filePathOverride ?: if (uri.scheme == "file") uri.path else null
            if (path != null) {
                val f = File(path)
                if (f.exists() && (f.isDirectory || f.name.lowercase().endsWith(".zip") || f.name.lowercase().endsWith(".cbz"))) {
                    val imported = importDirectoryOrArchive(f, source, serverId, remotePath)
                    if (imported.isNotEmpty()) {
                        insertedIds.addAll(imported)
                        continue
                    }
                }
            }
            val (name, _) = queryDisplayNameAndSize(uri)
            val fmt = BookFormat.fromFilename(name)
            if (fmt.isAudio) {
                audioUris.add(uri to name)
            } else {
                nonAudioUris.add(uri to name)
            }
        }

        if (audioUris.isNotEmpty()) {
            val groupedByParent = audioUris.groupBy { (uri, _) ->
                val path = if (uri.scheme == "file") uri.path else uri.toString()
                path?.substringBeforeLast('/') ?: "Audiobook"
            }

            for ((parentDir, tracks) in groupedByParent) {
                val folderName = parentDir.substringAfterLast('/').ifBlank { "Audiobook" }
                val abId = importAudiobookFolder(
                    files = tracks,
                    folderName = folderName,
                    source = source,
                    serverId = serverId,
                    remotePath = remotePath
                )
                if (abId > 0L) insertedIds.add(abId)
            }
        }

        // Deduplisering: samme bok i flere formater blant de valgte URI-ene —
        // kun beste format importeres (gruppert per mappe for å unngå falske treff på tvers)
        val nonAudioByFolder = nonAudioUris.groupBy { (uri, _) ->
            val path = if (uri.scheme == "file") uri.path else uri.toString()
            path?.substringBeforeLast('/') ?: "Valgte filer"
        }
        for ((_, group) in nonAudioByFolder) {
            for ((uri, _) in dedupeByBestFormat(group)) {
                val singleId = importSingleUri(uri, source, serverId, remotePath, filePathOverride)
                if (singleId > 0L) insertedIds.add(singleId)
            }
        }

        if (consolidate) consolidateFragmentedAudiobooks()

        insertedIds
    }

    suspend fun importSingleUri(
        uri: Uri,
        source: ImportSourceEntity,
        serverId: Long? = null,
        remotePath: String? = null,
        filePathOverride: String? = null
    ): Long = withContext(dispatchers.io) {
        try {
            val (displayNameRaw, sizeBytesRaw) = queryDisplayNameAndSize(uri)
            var displayName = displayNameRaw
            var sizeBytes = sizeBytesRaw
            var format = BookFormat.fromFilename(displayName)
            var effectiveUri = uri
            var effectivePathOverride = filePathOverride

            if (format == BookFormat.UNKNOWN) {
                Log.w(TAG, "Skipping import of '${displayName}' (format=UNKNOWN, not a recognised book/audio file)")
                return@withContext 0L
            }

            if (format.isAudio) {
                return@withContext importAudiobookFolder(
                    files = listOf(uri to displayName),
                    folderName = displayName.substringBeforeLast('/'),
                    source = source,
                    serverId = serverId,
                    remotePath = remotePath
                )
            }

            // ── MOBI/AZW/AZW3 konverteres til EPUB VED IMPORT ──
            // Biblioteket får dermed aldri MOBI-format-oppføringer (ingen formatforvirring),
            // kapitler/TOC parses fra den konverterte EPUBen med korrekt æøå-tegnkode.
            // Konvertering som feiler (f.eks. DRM) faller tilbake til originalen.
            if (format == BookFormat.MOBI || format == BookFormat.AZW || format == BookFormat.AZW3) {
                val converted = convertMobiToEpubAtImport(uri)
                if (converted != null) {
                    val (epubFile, epubSize) = converted
                    displayName = displayNameRaw.substringBeforeLast('.') + ".epub"
                    format = BookFormat.EPUB
                    effectiveUri = Uri.fromFile(epubFile)
                    effectivePathOverride = epubFile.absolutePath
                    sizeBytes = epubSize
                    Log.i(TAG, "[MOBI_IMPORT] Konvertert '$displayNameRaw' -> ${epubFile.name}")
                } else {
                    Log.w(TAG, "[MOBI_IMPORT] Konvertering feilet for '$displayNameRaw' — importerer originalen som MOBI")
                }
            }

            val persistable = takePersistableUriPermissionSafely(effectiveUri)
            val streamProvider: (suspend () -> java.io.InputStream)? = {
                ctx.contentResolver.openInputStream(effectiveUri)
                    ?: error("Could not open input stream for $effectiveUri")
            }
            val parser = getParserFor(format)
            val meta = parser.parse(ctx, effectiveUri, displayName, sizeBytes, streamProvider)
            val nameNoExt = filenameWithoutExtension(displayName)
            // Parse filename for author/title/series clues — many release groups tag filenames
            // better than the actual EPUB OPF metadata (e.g. "[Herbert, Dune 005, Messiah]" format).
            val parsed = runCatching { EbookFilenameParser.parse(nameNoExt) }
                .getOrNull()
                ?: EbookFilenameParser.ParsedFilename(nameNoExt, "", null, null)

            val hasMetaAuthor = !meta?.author.isNullOrBlank()
            val hasMetaTitle = !meta?.title.isNullOrBlank()
            val hasMetaSeries = !meta?.series.isNullOrBlank()
            val hasMetaSeriesIndex = (meta?.seriesIndex != null)

            val authorFull = if (hasMetaAuthor) {
                val a = meta!!.author!!
                a.ifBlank { parsed.author }
            } else parsed.author
            val author: String = EbookFilenameParser.resolveAuthor(authorFull)

            val title = BookTitleCleaner.clean(
                (if (hasMetaTitle) meta!!.title!!.trim() else "").ifBlank { parsed.title.ifBlank { nameNoExt } }
            )
            val pageCount = meta?.pageCount
                ?: meta?.durationMs?.let { (it / 60_000).toInt() }
                ?: meta?.chapters?.size?.takeIf { it > 0 }
            val series = (if (hasMetaSeries) meta!!.series else null) ?: parsed.series
            val seriesIndex = (if (hasMetaSeriesIndex) meta!!.seriesIndex else null) ?: parsed.seriesIndex
            val formatEntity = coreFormatToEntity(format)
            val path = effectivePathOverride ?: if (effectiveUri.scheme == "file") effectiveUri.path else null

            val chaptersJson = meta?.chapters?.let { list ->
                val arr = JSONArray()
                list.forEachIndexed { idx, ch ->
                    val chObj = JSONObject().apply {
                        put("index", idx)
                        put("title", ch.title.orEmpty())
                        put("startMs", ch.startMs)
                        put("endMs", ch.endMs ?: JSONObject.NULL)
                        ch.href?.let { put("href", it) }
                    }
                    arr.put(chObj)
                }
                arr.toString()
            }

            // Exact-file dedup: the same local path OR the same SAF uri is already
            // in the library. Without the uri check every media scan re-imported
            // scoped-storage files and multiplied the library.
            val existingExact = path?.let { db.bookDao().getByPath(it) }
                ?: db.bookDao().getByFileUri(effectiveUri.toString())
            if (existingExact != null) {
                Log.d(TAG, "[UPSERT_SKIP] Existing EBOOK for uri=$effectiveUri id=${existingExact.id}")
                return@withContext existingExact.id
            }

            val unsaved = BookEntity(
                title = title,
                sortTitle = normalizeForSort(title),
                author = author,
                sortAuthor = normalizeForSort(author),
                series = meta?.series,
                seriesIndex = meta?.seriesIndex,
                description = meta?.description,
                publisher = meta?.publisher,
                publishedDate = meta?.publishedDate,
                language = meta?.language,
                isbn = meta?.isbn,
                type = BookTypeEntity.EBOOK,
                format = formatEntity,
                fileUri = effectiveUri.toString(),
                fileSizeBytes = sizeBytes,
                persistableUriPermission = persistable,
                importSource = source,
                filePath = path,
                serverId = serverId,
                remotePath = remotePath,
                coverPath = null,
                spineColor = null,
                chaptersJson = chaptersJson,
                pageCount = pageCount,
                durationMs = meta?.durationMs,
                chapterCount = meta?.chapters?.size?.takeIf { it > 0 }
            )

            val bookId = db.bookDao().insert(unsaved)
            insertProgressFor(bookId)
            val savedBook = unsaved.copy(id = bookId)

            Log.i(TAG, "[CREATE_EBOOK] id=$bookId title='$title' author='$author' format=$formatEntity path=$path")

            // Konvertert MOBI → EPUB: fjern eventuell gammel MOBI-oppføring på samme
            // opprinnelige filsti slik at ikke duplikatformatet blir liggende i listen.
            val wasConvertedFromMobiFamily = formatEntity == com.bookrio.data.local.entity.FormatEntity.EPUB &&
                displayNameRaw.substringAfterLast('.', "").lowercase() in setOf("mobi", "azw", "azw3")
            if (wasConvertedFromMobiFamily) {
                val originalPath = if (uri.scheme == "file") uri.path
                    else filePathOverride
                if (originalPath != null) {
                    runCatching {
                        db.bookDao().getByPath(originalPath)?.let { old ->
                            if (old.id != bookId && old.format in listOf(
                                    com.bookrio.data.local.entity.FormatEntity.MOBI,
                                    com.bookrio.data.local.entity.FormatEntity.AZW,
                                    com.bookrio.data.local.entity.FormatEntity.AZW3)
                            ) {
                                db.bookDao().softDelete(old.id)
                                Log.i(TAG, "[MOBI_IMPORT] Gammel MOBI-oppføring id=${old.id} erstattet av EPUB id=$bookId")
                            }
                        }
                    }
                }
            }

            val coverRepo = com.bookrio.library.cover.CoverRepository(ctx, db, dispatchers)
            coverRepo.coverFileFor(savedBook)

            bookId
        } catch (t: Exception) {
            Log.e(TAG, "Error importing single URI $uri", t)
            -1L
        }
    }

    suspend fun importAudiobookFolder(
        files: List<Pair<Uri, String>>,
        folderName: String,
        source: ImportSourceEntity,
        serverId: Long? = null,
        remotePath: String? = null,
        cueFiles: List<Pair<Uri, String>> = emptyList()
    ): Long = withContext(dispatchers.io) {
        if (files.isEmpty()) return@withContext -1L

        // CUE-sheets i mappen: kapitler for lydfiler uten innebygd kapittelinfo.
        // Nøkkel = basenavn (lavere), matchet mot TRACK-navnets FILE-referanse
        // eller selve cue-filnavnet.
        val cueChaptersByBase: Map<String, List<com.bookrio.core.domain.model.ChapterInfo>> =
            cueFiles.mapNotNull { (cueUri, cueName) ->
                runCatching {
                    val text = ctx.contentResolver.openInputStream(cueUri)
                        ?.bufferedReader()?.use { it.readText() } ?: return@runCatching null
                    val parsed = com.bookrio.core.parse.CueParser.parse(text)
                    if (parsed.size <= 1) return@runCatching null
                    val ref = com.bookrio.core.parse.CueParser.referencedAudioFile(text)
                        ?.substringBeforeLast('.')?.lowercase()
                        ?: cueName.substringBeforeLast('.').lowercase()
                    ref to parsed.map {
                        com.bookrio.core.domain.model.ChapterInfo(
                            index = it.index,
                            title = it.title,
                            startMs = it.startMs,
                            endMs = null,
                            href = null
                        )
                    }
                }.getOrNull()
            }.toMap()

        var detectedAlbum: String? = null
        // Explicit ALBUMARTIST (normally the author) vs ARTIST (often the narrator).
        var detectedAuthorTag: String? = null
        var detectedArtist: String? = null
        var detectedNarrator: String? = null

        val parsedTracks = mutableListOf<ParsedTrack>()

        for ((uri, displayName) in files) {
            val (name, size) = queryDisplayNameAndSize(uri)
            val format = BookFormat.fromFilename(name)
            if (!format.isAudio) continue

            val streamProvider: (suspend () -> java.io.InputStream)? = {
                ctx.contentResolver.openInputStream(uri) ?: error("Stream error")
            }
            val meta = getParserFor(format).parse(ctx, uri, name, size, streamProvider)
            val trackDurationMs = meta?.durationMs ?: (5L * 60L * 1000L)

            if (detectedAlbum.isNullOrBlank() && !meta?.album.isNullOrBlank()) {
                detectedAlbum = meta?.album
            }
            if (detectedAuthorTag.isNullOrBlank()) {
                detectedAuthorTag = meta?.albumArtist?.takeIf { it.isNotBlank() }
            }
            if (detectedArtist.isNullOrBlank()) {
                detectedArtist = meta?.author?.takeIf { it.isNotBlank() }
            }
            if (detectedNarrator.isNullOrBlank()) {
                detectedNarrator = meta?.narrator?.takeIf { it.isNotBlank() }
            }

            val trackTitle = meta?.title?.takeIf { it.isNotBlank() && it != name }
                ?: AudiobookNormalizer.normalizeTitle(name)

            val localPath = if (uri.scheme == "file") uri.path else null
            val embeddedChapters = meta?.chapters.orEmpty().ifEmpty {
                cueChaptersByBase[name.substringBeforeLast('.').lowercase()].orEmpty()
            }

            parsedTracks.add(
                ParsedTrack(
                    uri = uri,
                    displayName = name,
                    sizeBytes = size,
                    durationMs = trackDurationMs,
                    title = trackTitle,
                    trackNumber = parsedTracks.size + 1,
                    localPath = localPath,
                    embeddedChapters = embeddedChapters
                )
            )
        }

        if (parsedTracks.isEmpty()) return@withContext -1L

        val sortedTracks = parsedTracks.sortedWith(
            compareBy<ParsedTrack> { it.trackNumber }.thenBy { it.displayName.lowercase() }
        )

        val rawFolder = AudiobookNormalizer.extractCanonicalFolderName(folderName.ifBlank { files.firstOrNull()?.second })
        val titleCandidate = detectedAlbum?.ifBlank { null } ?: AudiobookNormalizer.normalizeTitle(rawFolder)
        // Prefer the explicit author tag; then an ARTIST value that we know was
        // stripped of a narrator credit; then a confident name parse; then a
        // filename/folder guess only when it names a *known* author; and only
        // last the raw ARTIST value. An unknown guess never outranks a real tag.
        val parsedFolderAuthor = runCatching { EbookFilenameParser.parse(rawFolder) }
            .getOrNull()?.author?.takeIf { it.isNotBlank() }
        val knownGuess = AudiobookNormalizer.guessAuthorFromName(rawFolder, detectedArtist)
            ?.takeIf { EbookFilenameParser.isKnownAuthor(it) }
        val strippedArtist = detectedArtist?.takeIf { detectedNarrator != null }
        val authorCandidate = detectedAuthorTag?.takeIf { it.isNotBlank() }
            ?: strippedArtist
            ?: parsedFolderAuthor
            ?: knownGuess
            ?: detectedArtist.orEmpty()
        val groupKey = AudiobookNormalizer.computeGroupKey(titleCandidate, authorCandidate, rawFolder)

        val existingBooks = db.bookDao().getAllOnce().filter { it.type == BookTypeEntity.AUDIOBOOK && !it.isDeleted }
        val normTitleCand = AudiobookNormalizer.normalizeString(titleCandidate)
        val normAuthorCand = AudiobookNormalizer.normalizeString(authorCandidate)
        var targetBook = existingBooks.firstOrNull { b ->
            val bKey = AudiobookNormalizer.computeGroupKey(b.title, b.author, b.filePath)
            if (bKey == groupKey) return@firstOrNull true
            // Fallback: require BOTH title AND author to match (if author available)
            val bNormTitle = AudiobookNormalizer.normalizeString(b.title)
            val bNormAuthor = AudiobookNormalizer.normalizeString(b.author)
            val titleMatches = normTitleCand.isNotBlank() &&
                    bNormTitle == normTitleCand
            val authorMatches = (normAuthorCand.isBlank() && bNormAuthor.isBlank()) ||
                    (normAuthorCand.isNotBlank() && bNormAuthor == normAuthorCand)
            titleMatches && authorMatches
        }

        var totalSizeBytes = 0L
        var totalDurationMs = 0L
        val chaptersArray = JSONArray()

        var trackIdx = 0
        var chapterIdx = 0
        for (t in sortedTracks) {
            totalSizeBytes += t.sizeBytes
            val trackStartMs = totalDurationMs
            totalDurationMs += t.durationMs

            val embed = t.embeddedChapters
            if (embed.isNotEmpty() && embed.all { it.startMs >= 0L }) {
                // This single audio file has embedded chapters; emit one chapter per
                // embedded entry, with absolute start/end offsets based on trackStartMs.
                // Ensure chapters are sorted ascending by startMs before emitting.
                val sortedEmbed = embed.sortedBy { it.startMs }
                for ((localIdx, c) in sortedEmbed.withIndex()) {
                    val absStart = trackStartMs + c.startMs
                    val relEnd = c.endMs
                        ?: sortedEmbed.getOrNull(localIdx + 1)?.startMs
                        ?: t.durationMs.takeIf { it > 0L }
                        ?: (trackStartMs + c.startMs + 5L * 60L * 1000L)
                    val absEnd = (trackStartMs + relEnd).coerceAtMost(totalDurationMs)
                    val chObj = JSONObject().apply {
                        put("index", chapterIdx)
                        put("title", c.title.orEmpty())
                        put("startMs", absStart)
                        put("endMs", absEnd)
                        put("mediaUri", t.uri.toString())
                        put("filePath", t.localPath)
                        put("durationMs", (absEnd - absStart).coerceAtLeast(1L))
                    }
                    chaptersArray.put(chObj)
                    chapterIdx++
                }
            } else {
                // File-level chapter (either no embedded chapters, or the info is malformed)
                var chapterTitle = t.title
                if (chapterTitle.equals(titleCandidate, ignoreCase = true) || chapterTitle.isBlank()) {
                    val fileClean = AudiobookNormalizer.normalizeTitle(t.displayName)
                    chapterTitle = if (fileClean.isNotBlank() && !fileClean.equals(titleCandidate, ignoreCase = true)) {
                        fileClean
                    } else {
                        ""
                    }
                }

                val chObj = JSONObject().apply {
                    put("index", chapterIdx)
                    put("title", chapterTitle)
                    put("startMs", trackStartMs)
                    put("endMs", totalDurationMs)
                    put("mediaUri", t.uri.toString())
                    put("filePath", t.localPath)
                    put("durationMs", t.durationMs)
                }
                chaptersArray.put(chObj)
                chapterIdx++
            }
            trackIdx++
        }

        val actualChapterCount = chaptersArray.length()
        val primaryUri = sortedTracks.first().uri
        val primaryPath = sortedTracks.first().localPath

        val bookId: Long
        if (targetBook != null) {
            bookId = targetBook.id
            val updated = targetBook.copy(
                fileSizeBytes = targetBook.fileSizeBytes + totalSizeBytes,
                durationMs = (targetBook.durationMs ?: 0L) + totalDurationMs,
                chapterCount = (targetBook.chapterCount ?: 0) + actualChapterCount,
                chaptersJson = chaptersArray.toString(),
                lastModifiedAt = System.currentTimeMillis()
            )
            db.bookDao().update(updated)
            Log.i(TAG, "[UPDATE_AUDIOBOOK] Merged ${sortedTracks.size} tracks into existing audiobook id=$bookId title='${updated.title}' ($actualChapterCount chapters)")
        } else {
            val newBook = BookEntity(
                title = titleCandidate,
                sortTitle = normalizeForSort(titleCandidate),
                author = authorCandidate,
                sortAuthor = normalizeForSort(authorCandidate),
                type = BookTypeEntity.AUDIOBOOK,
                format = FormatEntity.M4B,
                fileUri = primaryUri.toString(),
                filePath = primaryPath,
                fileSizeBytes = totalSizeBytes,
                importSource = source,
                serverId = serverId,
                remotePath = remotePath,
                coverPath = null,
                durationMs = totalDurationMs,
                chapterCount = actualChapterCount,
                chaptersJson = chaptersArray.toString()
            )
            bookId = db.bookDao().insert(newBook)
            insertProgressFor(bookId)
            targetBook = newBook.copy(id = bookId)
            Log.i(TAG, "[CREATE_AUDIOBOOK] id=$bookId title='$titleCandidate' author='$authorCandidate' tracks=${sortedTracks.size} chapters=$actualChapterCount")
        }

        for ((idx, t) in sortedTracks.withIndex()) {
            val trackEntity = AudioTrackEntity(
                bookId = bookId,
                trackNumber = idx + 1,
                title = t.title,
                durationMs = t.durationMs,
                filePath = t.localPath,
                fileUri = t.uri.toString(),
                remotePath = remotePath,
                fileSizeBytes = t.sizeBytes
            )
            try {
                db.audioTrackDao().insert(trackEntity)
            } catch (_: Exception) {
            }
        }

        targetBook?.let { b ->
            val coverRepo = com.bookrio.library.cover.CoverRepository(ctx, db, dispatchers)
            coverRepo.coverFileFor(b)
        }

        bookId
    }

    suspend fun consolidateFragmentedAudiobooks(): Int = audiobookHealMutex.withLock {
        consolidateFragmentedAudiobooksLocked()
    }

    /**
     * Idempotent repair for books that an older build filed under the narrator
     * because the ARTIST tag was used as the author. Strips a recognisable
     * narrator credit ("(innlest av …)", "read by …") and, when the file/folder
     * name carries a safer author hint, re-derives the author from it. Uses the
     * silent metadata update so it never touches last_modified_at / library order.
     */
    suspend fun repairNarratorAuthors(): Int = withContext(dispatchers.io) {
        var fixed = 0
        try {
            val books = db.bookDao().getAllOnce().filter { !it.isDeleted }
            for (b in books) {
                val split = com.bookrio.core.parse.NarratorTags.split(b.author)
                val nameHint = AudiobookNormalizer.extractCanonicalFolderName(b.filePath)
                // Only trust a filename guess when it names a known author and is
                // not just the book title — otherwise repair could corrupt a
                // correct author with a series/title prefix.
                val guessed = if (b.type == BookTypeEntity.AUDIOBOOK) {
                    AudiobookNormalizer.guessAuthorFromName(nameHint, b.author)
                        ?.takeIf { EbookFilenameParser.isKnownAuthor(it) }
                        ?.takeIf { AudiobookNormalizer.normalizeString(it) != AudiobookNormalizer.normalizeString(b.title) }
                } else null
                val newAuthor = split.author.takeIf { it.isNotBlank() && it != b.author }
                    ?: guessed
                    ?: continue
                if (newAuthor == b.author) continue
                db.bookDao().enrichMetadataSilently(
                    id = b.id,
                    title = b.title,
                    sortTitle = b.sortTitle,
                    author = newAuthor,
                    sortAuthor = normalizeForSort(newAuthor),
                    isbn = b.isbn,
                    publisher = b.publisher,
                    publishedDate = b.publishedDate,
                    description = b.description
                )
                fixed++
                Log.i(TAG, "[REPAIR_AUTHOR] id=${b.id} '$b.author' -> '$newAuthor'")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[REPAIR_AUTHOR] failed", t)
        }
        fixed
    }

    /**
     * Cleans titles/authors that earlier builds left polluted: leading series
     * indexes in titles ("01 The Sword of Shannara") and series indexes parsed as
     * authors ("the 01"). Idempotent and metadata-only (never touches progress).
     */
    suspend fun repairTitlesAndAuthors(): Int = withContext(dispatchers.io) {
        var fixed = 0
        try {
            for (b in db.bookDao().getAllOnce().filter { !it.isDeleted }) {
                val newTitle = BookTitleCleaner.clean(b.title)
                val newAuthor = if (isSeriesIndexAuthor(b.author)) "" else b.author
                if (newTitle == b.title && newAuthor == b.author) continue
                db.bookDao().enrichMetadataSilently(
                    id = b.id,
                    title = newTitle,
                    sortTitle = normalizeForSort(newTitle),
                    author = newAuthor,
                    sortAuthor = normalizeForSort(newAuthor),
                    isbn = b.isbn,
                    publisher = b.publisher,
                    publishedDate = b.publishedDate,
                    description = b.description
                )
                fixed++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[REPAIR_TITLE] failed", t)
        }
        fixed
    }

    private fun isSeriesIndexAuthor(author: String?): Boolean {
        val value = author?.trim().orEmpty()
        if (value.isEmpty()) return false
        return value.matches(Regex("(?i)^(the|a|an)?\\s*\\d+([.,]\\d+)?$"))
    }

    /**
     * Collapses duplicate library rows created by earlier builds:
     *  1. the exact same file (same `file_uri`/`file_path`) imported repeatedly by
     *     the scheduled media scan, and
     *  2. the same book (normalized title + author) present in several files/formats.
     *
     * EPUB wins over other formats, then the larger file, then the oldest row.
     * Reading progress is moved to the survivor, and duplicates are soft-deleted so
     * no data is destroyed. Idempotent: a second run changes nothing.
     */
    suspend fun deduplicateLibrary(): Int = withContext(dispatchers.io) {
        var removed = 0
        try {
            val books = db.bookDao().getAllOnce().filter { !it.isDeleted }
            if (books.size <= 1) return@withContext 0

            // Pass 1: the exact same file.
            val byFile = books.groupBy { book ->
                book.fileUri?.takeIf { it.isNotBlank() }
                    ?: book.filePath?.takeIf { it.isNotBlank() }?.let { "path:$it" }
                    ?: "id:${book.id}"
            }
            val survivors = mutableListOf<BookEntity>()
            for ((_, group) in byFile) {
                val keep = pickCanonical(group)
                survivors.add(keep)
                for (dup in group) {
                    if (dup.id != keep.id) {
                        mergeAndSoftDelete(dup, keep)
                        removed++
                    }
                }
            }

            // Pass 2: same normalized title + author. A real author is required so
            // unrelated books that only share a generic title are never merged.
            val byKey = survivors
                .filter { it.author.isNotBlank() && it.title.isNotBlank() }
                .groupBy { Triple(it.sortTitle.lowercase(), it.sortAuthor.lowercase(), it.type) }
            for ((_, group) in byKey) {
                if (group.size <= 1) continue
                val keep = pickCanonical(group)
                for (dup in group) {
                    if (dup.id != keep.id) {
                        mergeAndSoftDelete(dup, keep)
                        removed++
                    }
                }
            }
            if (removed > 0) Log.i(TAG, "[DEDUP] removed $removed duplicate book row(s)")
        } catch (t: Throwable) {
            Log.w(TAG, "[DEDUP] failed", t)
        }
        removed
    }

    private fun pickCanonical(group: List<BookEntity>): BookEntity =
        group.sortedWith(
            compareByDescending<BookEntity> { formatPreference(it.format) }
                .thenByDescending { it.fileSizeBytes }
                .thenBy { it.id }
        ).first()

    private fun formatPreference(format: FormatEntity): Int = when (format) {
        FormatEntity.EPUB -> 100
        FormatEntity.PDF -> 80
        FormatEntity.FB2 -> 70
        FormatEntity.MOBI, FormatEntity.AZW, FormatEntity.AZW3 -> 60
        FormatEntity.CBZ, FormatEntity.CBR -> 50
        FormatEntity.HTML -> 40
        FormatEntity.TXT -> 30
        else -> 10
    }

    private suspend fun mergeAndSoftDelete(dup: BookEntity, keep: BookEntity) {
        runCatching {
            val dupProgress = db.progressDao().getByBook(dup.id)
            val keepProgress = db.progressDao().getByBook(keep.id)
            val keepHasProgress = keepProgress != null &&
                ((keepProgress.positionMs ?: 0L) > 0L || keepProgress.progressPercent > 0.001f)
            if (dupProgress != null && !keepHasProgress) {
                db.progressDao().insertOrReplace(dupProgress.copy(bookId = keep.id))
            }
            db.bookDao().softDelete(dup.id)
        }
    }

    private suspend fun consolidateFragmentedAudiobooksLocked(): Int = withContext(dispatchers.io) {
        var countMerged = 0
        try {
            val allBooks: List<BookEntity> = db.bookDao().getAllOnce()
            val audiobooks: List<BookEntity> = allBooks.filter { it.type == BookTypeEntity.AUDIOBOOK && !it.isDeleted }

            val grouped = audiobooks.groupBy { book ->
                AudiobookNormalizer.computeGroupKey(book.title, book.author, book.filePath)
            }

            for ((groupKey, booksInGroup) in grouped) {
                if (booksInGroup.size <= 1) continue

                val sortedList = booksInGroup.sortedWith(
                    Comparator { a, b ->
                        // Fragment order follows the primary track's natural file order
                        // (…001, …002, …010) so a healed audiobook plays in order.
                        val c = naturalCompare(primaryTrackName(a), primaryTrackName(b))
                        if (c != 0) c else a.id.compareTo(b.id)
                    }
                )
                val canonicalBook = sortedList.first()

                // Safety: do not merge books whose durations or file sizes diverge wildly,
                // as they are almost certainly different books sharing a weak group key.
                // EXCEPTION: a strong title+author identity is a reliable same-book
                // signal. Fragmenting an audiobook into one record per track makes the
                // per-record duration/size diverge *by construction*, so the divergence
                // check must not block merging those fragments back together.
                val strongIdentity =
                    AudiobookNormalizer.hasStrongIdentity(canonicalBook.title, canonicalBook.author) &&
                        booksInGroup.all {
                            AudiobookNormalizer.normalizeString(it.title) ==
                                AudiobookNormalizer.normalizeString(canonicalBook.title) &&
                                AudiobookNormalizer.normalizeString(it.author) ==
                                AudiobookNormalizer.normalizeString(canonicalBook.author)
                        }
                val safeToMerge = strongIdentity || sortedList.all { b ->
                    val durOk = canonicalBook.durationMs == null || b.durationMs == null ||
                            kotlin.math.abs((canonicalBook.durationMs ?: 0L) - (b.durationMs ?: 0L))
                                    .toDouble() / (canonicalBook.durationMs ?: 1L).coerceAtLeast(1L) < 1.5
                    val sizeOk = canonicalBook.fileSizeBytes == 0L || b.fileSizeBytes == 0L ||
                            kotlin.math.abs(canonicalBook.fileSizeBytes - b.fileSizeBytes)
                                    .toDouble() / canonicalBook.fileSizeBytes.coerceAtLeast(1L) < 5.0
                    durOk && sizeOk
                }
                if (!safeToMerge) {
                    Log.w(TAG, "[CONSOLIDATE_SKIP] Group '$groupKey' has ${booksInGroup.size} books but divergent metadata; skipping merge.")
                    continue
                }

                val duplicates = sortedList.drop(1)

                val allTracks = mutableListOf<JSONObject>()
                var totalDuration = 0L
                var totalSize = 0L
                var currentOffset = 0L
                var trackIndex = 0
                var mergedTrackNumber = 1

                for (b in sortedList) {
                    val tracksForB = db.audioTrackDao().getTracksForBook(b.id)
                    totalSize += b.fileSizeBytes

                    if (tracksForB.isNotEmpty()) {
                        for (tr in tracksForB) {
                            val dur = if (tr.durationMs > 0) tr.durationMs else (5L * 60L * 1000L)
                            totalDuration += dur
                            val obj = JSONObject().apply {
                                put("index", trackIndex)
                                put("title", tr.title)
                                put("startMs", currentOffset)
                                put("endMs", currentOffset + dur)
                                put("mediaUri", tr.fileUri)
                                put("filePath", tr.filePath)
                                put("durationMs", dur)
                            }
                            allTracks.add(obj)
                            currentOffset += dur
                            trackIndex++

                            // Always renumber onto the canonical book in playback order:
                            // fragments inherit trackNumber 1 from their single-track
                            // origin, so without this the player would interleave tracks.
                            db.audioTrackDao().insert(
                                tr.copy(
                                    bookId = canonicalBook.id,
                                    discNumber = 1,
                                    trackNumber = mergedTrackNumber++
                                )
                            )
                        }
                    } else {
                        val dur = b.durationMs ?: (5L * 60L * 1000L)
                        totalDuration += dur
                        val obj = JSONObject().apply {
                            put("index", trackIndex)
                            put("title", b.title)
                            put("startMs", currentOffset)
                            put("endMs", currentOffset + dur)
                            put("mediaUri", b.fileUri)
                            put("filePath", b.filePath)
                            put("durationMs", dur)
                        }
                        allTracks.add(obj)
                        currentOffset += dur
                        trackIndex++
                    }
                }

                val chaptersArray = JSONArray()
                allTracks.forEach { chaptersArray.put(it) }

                val updatedCanonical = canonicalBook.copy(
                    chaptersJson = chaptersArray.toString(),
                    chapterCount = allTracks.size,
                    durationMs = totalDuration,
                    fileSizeBytes = totalSize,
                    lastModifiedAt = System.currentTimeMillis()
                )

                db.bookDao().update(updatedCanonical)

                for (dup in duplicates) {
                    val dupProgress = db.progressDao().getByBook(dup.id)
                    if (dupProgress != null && dupProgress.progressPercent > 0) {
                        val canonProgress = db.progressDao().getByBook(canonicalBook.id)
                        if (canonProgress == null || canonProgress.progressPercent < dupProgress.progressPercent) {
                            db.progressDao().insertOrReplace(dupProgress.copy(bookId = canonicalBook.id))
                        }
                    }

                    db.audioTrackDao().deleteTracksForBook(dup.id)
                    db.bookDao().delete(dup)
                    Log.i(TAG, "[CONSOLIDATE_DELETE] Merged duplicate audiobook id=${dup.id} into canonical id=${canonicalBook.id}")
                    countMerged++
                }

                val coverRepo = com.bookrio.library.cover.CoverRepository(ctx, db, dispatchers)
                coverRepo.coverFileFor(updatedCanonical)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error consolidating audiobooks", e)
        }
        countMerged
    }

    /**
     * BUG A repair: rewrite audiobooks whose stored chapter list is the importer's
     * one-chapter fallback (typically left behind when a torrent was imported while
     * sibling files were still arriving).
     *
     * - multi-track: rebuild one chapter per `audio_tracks` row and renumber the
     *   rows sequentially (as ecf62df does during consolidation);
     * - single file: re-parse the real file with the canonical format parser and
     *   persist ONLY when more than one real embedded chapter is found.
     *
     * The stale fallback `duration_ms` (5-minute placeholder) is replaced by the
     * rebuilt timeline end / fresh metadata duration, and `file_size_bytes` by the
     * actual on-disk length. Book id, fileUri/filePath, cover, lastOpenedAt, file
     * associations and the ReadingProgressEntity row are untouched. Idempotent, and
     * serialised with consolidation through [audiobookHealMutex].
     */
    suspend fun repairOneChapterAudiobooks(): Int = audiobookHealMutex.withLock {
        withContext(dispatchers.io) {
            var repaired = 0
            try {
                val audiobooks = db.bookDao().getAllOnce()
                    .filter { it.type == BookTypeEntity.AUDIOBOOK && !it.isDeleted }

                for (book in audiobooks) {
                    val stored = parseStoredChapterRows(book.chaptersJson)
                    if (stored.size >= 2) continue
                    // Never even re-parse a genuine single chapter.
                    if (stored.size == 1 && !OneChapterRepairPlanner.isOneChapterFallback(
                            bookTitle = book.title,
                            fileNameStem = audioFileStem(book),
                            chapter = stored[0]
                        )
                    ) {
                        continue
                    }

                    val tracks = runCatching { db.audioTrackDao().getTracksForBook(book.id) }
                        .getOrDefault(emptyList())
                    val singleFile = tracks.size < 2
                    // MINOR 4: do not re-parse the same unchanged file on every start.
                    val reparse = if (singleFile && !reparseAlreadyAttempted(book)) {
                        reparseEmbeddedChapters(book)
                    } else {
                        null
                    }
                    if (reparse != null && reparse.sourceFound && reparse.chapters.size <= 1) {
                        markReparseAttempt(book)
                    }

                    val plan = OneChapterRepairPlanner.plan(
                        bookTitle = book.title,
                        fileNameStem = audioFileStem(book),
                        stored = stored,
                        tracks = tracks.map { t ->
                            RepairTrackInput(
                                id = t.id,
                                trackNumber = t.trackNumber,
                                discNumber = t.discNumber,
                                title = t.title,
                                durationMs = t.durationMs,
                                filePath = t.filePath,
                                fileUri = t.fileUri
                            )
                        },
                        reparsed = reparse?.chapters.orEmpty(),
                        primaryDurationMs = if (singleFile) {
                            reparse?.durationMs ?: book.durationMs ?: 0L
                        } else {
                            0L
                        },
                        fallbackMediaUri = book.fileUri ?: book.filePath,
                        fallbackFilePath = book.filePath ?: book.fileUri
                    ) ?: continue

                    // Re-read before writing: an import/consolidation pass may have
                    // healed the book while the file was being parsed.
                    val current = db.bookDao().getById(book.id) ?: continue
                    if (current.chaptersJson != book.chaptersJson) continue
                    if (current.chapterCount != book.chapterCount) continue
                    if (parseStoredChapterRows(current.chaptersJson).size >= 2) continue

                    db.bookDao().update(
                        OneChapterRepairPlanner.repairedBook(
                            book = current,
                            plan = plan,
                            chaptersJson = repairChaptersToJson(plan.chapters),
                            knownFileSizeBytes = knownBookFileSize(book, tracks)
                        )
                    )

                    if (plan.trackNumbers.isNotEmpty()) {
                        val tracksById = tracks.associateBy { it.id }
                        for ((trackId, number) in plan.trackNumbers) {
                            val track = tracksById[trackId] ?: continue
                            if (track.discNumber == 1 && track.trackNumber == number) continue
                            runCatching {
                                db.audioTrackDao().insert(
                                    track.copy(discNumber = 1, trackNumber = number)
                                )
                            }
                        }
                    }

                    Log.i(
                        TAG,
                        "[REPAIR_ONE_CHAPTER] book id=${book.id} " +
                            "chapters=${plan.chapters.size} renumberedTracks=${plan.trackNumbers.size} " +
                            "durationChanged=${plan.durationMs != null && plan.durationMs > (book.durationMs ?: 0L)}"
                    )
                    repaired++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error repairing one-chapter audiobooks", e)
            }
            repaired
        }
    }

    /** Real file stem (no directory, no extension) used for fallback-title detection. */
    private fun audioFileStem(book: BookEntity): String? =
        book.filePath?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: book.fileUri?.substringAfterLast('/')?.substringBeforeLast('.')

    /**
     * Total size from the actual files on disk: single file -> `File.length()`;
     * multi-track -> only when every track file exists (a partial sum would
     * under-count). Null keeps the stored value.
     */
    private fun knownBookFileSize(book: BookEntity, tracks: List<AudioTrackEntity>): Long? {
        if (tracks.size < 2) {
            val file = book.filePath?.let { File(it) }?.takeIf { it.isFile } ?: return null
            return file.length().takeIf { it > 0L }
        }
        var total = 0L
        for (track in tracks) {
            val file = track.filePath?.let { File(it) }?.takeIf { it.isFile } ?: return null
            total += file.length()
        }
        return total.takeIf { it > 0L }
    }

    private fun reparseAlreadyAttempted(book: BookEntity): Boolean =
        repairAttemptPrefs.getString("book_${book.id}", null) == reparseAttemptKey(book)

    private fun markReparseAttempt(book: BookEntity) {
        repairAttemptPrefs.edit().putString("book_${book.id}", reparseAttemptKey(book)).apply()
    }

    private fun reparseAttemptKey(book: BookEntity): String =
        OneChapterRepairPlanner.reparseAttemptKey(
            bookId = book.id,
            fileSizeBytes = attemptFileSize(book),
            lastModified = attemptLastModified(book)
        )

    private fun attemptFileSize(book: BookEntity): Long =
        book.filePath?.let { File(it) }?.takeIf { it.isFile }?.length() ?: book.fileSizeBytes

    private fun attemptLastModified(book: BookEntity): Long =
        book.filePath?.let { File(it) }?.takeIf { it.isFile }?.lastModified() ?: book.lastModifiedAt

    /** Parses stored chaptersJson into the planner's minimal rows. */
    private fun parseStoredChapterRows(json: String?): List<RepairChapterInput> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val rows = mutableListOf<RepairChapterInput>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val hasEnd = o.has("endMs") && !o.isNull("endMs")
                rows.add(
                    RepairChapterInput(
                        title = o.optString("title", ""),
                        startMs = o.optLong("startMs", 0L),
                        endMs = if (hasEnd) o.optLong("endMs", 0L) else null
                    )
                )
            }
            rows
        }.getOrDefault(emptyList())
    }

    private fun repairChaptersToJson(chapters: List<RepairedChapter>): String {
        val arr = JSONArray()
        for (c in chapters) {
            arr.put(
                JSONObject().apply {
                    put("index", c.index)
                    put("title", c.title)
                    put("startMs", c.startMs)
                    put("endMs", c.endMs)
                    put("mediaUri", c.mediaUri ?: JSONObject.NULL)
                    put("filePath", c.filePath ?: JSONObject.NULL)
                    put("durationMs", c.durationMs)
                }
            )
        }
        return arr.toString()
    }

    /** Result of the single-file re-parse: chapters + fresh duration, when a source existed. */
    private data class ReparsedFile(
        val chapters: List<RepairChapterInput>,
        val durationMs: Long?,
        val sourceFound: Boolean
    )

    /**
     * Re-parses a single audio file with the canonical parser and a real filename
     * (with extension), mirroring `AudiobookEngine.discoverChapters`. Returns the
     * real embedded chapters found; empty when the container has none.
     */
    private suspend fun reparseEmbeddedChapters(book: BookEntity): ReparsedFile {
        val fileName = chapterParserFileName(book.filePath, book.fileUri, book.format.name)
        val format = BookFormat.fromFilename(fileName)
        if (!format.isAudio) return ReparsedFile(emptyList(), null, sourceFound = false)

        val file = book.filePath?.let { File(it) }?.takeIf { it.isFile }
        val uri = file?.let { Uri.fromFile(it) }
            ?: book.fileUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return ReparsedFile(emptyList(), null, sourceFound = false)
        val size = file?.length()?.takeIf { it > 0L } ?: book.fileSizeBytes
        val streamProvider: (suspend () -> java.io.InputStream)? =
            file?.let { f -> { FileInputStream(f) } }

        val meta = runCatching {
            getParserFor(format).parse(ctx, uri, fileName, size, streamProvider)
        }.getOrNull()

        val chapters = meta?.chapters.orEmpty()
            .filter { it.startMs >= 0L }
            .map { RepairChapterInput(title = it.title, startMs = it.startMs, endMs = it.endMs) }
        return ReparsedFile(
            chapters = chapters,
            durationMs = meta?.durationMs?.takeIf { it > 0L },
            sourceFound = true
        )
    }

    suspend fun importAssetsSamples(): Int = withContext(dispatchers.io) {
        val sampleNames = ctx.assets.list("samples")?.toList().orEmpty()
        val importsDir = File(ctx.filesDir, "imports").apply { mkdirs() }
        var successCount = 0
        for (name in sampleNames) {
            try {
                val outFile = File(importsDir, name)
                ctx.assets.open("samples/$name").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val sizeBytes = outFile.length()
                val uri = Uri.fromFile(outFile)
                val format = BookFormat.fromFilename(name)
                val parser = getParserFor(format)
                val streamProvider: (suspend () -> java.io.InputStream)? = {
                    FileInputStream(outFile)
                }
                val meta = parser.parse(ctx, uri, name, sizeBytes, streamProvider)
                val nameNoExt = filenameWithoutExtension(name)
                val title = meta?.title?.takeIf { it.isNotBlank() } ?: nameNoExt
                val author = com.bookrio.core.parse.NarratorTags.split(meta?.author).author
                val pageCount = meta?.pageCount
                    ?: meta?.durationMs?.let { (it / 60_000).toInt() }
                    ?: meta?.chapters?.size?.takeIf { it > 0 }
                val formatEntity = coreFormatToEntity(format)
                val type = if (format.isAudio) BookTypeEntity.AUDIOBOOK else BookTypeEntity.EBOOK
                
                val chaptersJson = meta?.chapters?.let { list ->
                    val arr = JSONArray()
                    list.forEach { arr.put(it.title) }
                    arr.toString()
                }

                val book = BookEntity(
                    title = title,
                    sortTitle = normalizeForSort(title),
                    author = author,
                    sortAuthor = normalizeForSort(author),
                    series = meta?.series,
                    seriesIndex = meta?.seriesIndex,
                    description = meta?.description,
                    publisher = meta?.publisher,
                    publishedDate = meta?.publishedDate,
                    language = meta?.language,
                    isbn = meta?.isbn,
                    type = type,
                    format = formatEntity,
                    fileUri = uri.toString(),
                    filePath = outFile.absolutePath,
                    fileSizeBytes = sizeBytes,
                    persistableUriPermission = true,
                    importSource = ImportSourceEntity.SAMPLE,
                    isSample = true,
                    coverPath = null,
                    spineColor = null,
                    chaptersJson = chaptersJson,
                    pageCount = pageCount,
                    durationMs = meta?.durationMs,
                    chapterCount = meta?.chapters?.size?.takeIf { it > 0 }
                )
                val bookId = db.bookDao().insert(book)
                insertProgressFor(bookId)
                successCount++
            } catch (_: Exception) {
            }
        }
        successCount
    }

    /**
     * Konverterer en MOBI/AZW/AZW3-fil til EPUB ved import (MobiUnpack, korrekt
     * tegnkode — æøå). Resultatet caches på innholdshash så re-import er billig.
     * Returnerer null hvis konvertering ikke var mulig (DRM, ukjent kompresjon …).
     */
    private fun convertMobiToEpubAtImport(uri: Uri): Pair<java.io.File, Long>? = runCatching {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null
        if (bytes.size < 128) return@runCatching null
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }.take(24)
        val out = java.io.File(java.io.File(ctx.filesDir, "converted"), "mobi_$hash.epub")
        if (!out.exists() || out.length() < 64L) {
            out.parentFile?.mkdirs()
            com.bookrio.core.parse.MobiUnpack.convertToEpub(bytes, out)
        }
        Pair(out, out.length())
    }.getOrNull()

    /**
     * Bok-identitet for format-deduplisering innen samme mappe:
     * filnavn uten utvidelse, uten parenteser/klammer (f.eks. "(v5.0)"),
     * uten tegnsetting — slik at "Bok (v5.0).mobi" og "Bok.epub" grupperes sammen.
     */
    private fun bookIdentityKey(filename: String): String =
        filename.substringBeforeLast('.')
            .lowercase()
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("[^a-z0-9\u00E6\u00F8\u00E5]+"), " ")
            .trim()

    /** Filename of an audiobook's primary track (first track as stored). */
    private fun primaryTrackName(book: BookEntity): String =
        book.filePath?.substringAfterLast('/')?.ifBlank { null }
            ?: book.title

    /**
     * Natural ordering used to stitch fragmented audiobook tracks back in order:
     * digit runs compare numerically, so `…009` < `…010` < `…100`.
     */
    private fun naturalCompare(a: String, b: String): Int =
        OneChapterRepairPlanner.naturalFileNameCompare(a, b)

    /** Import-prioritet: lavest vinner. EPUB > MOBI/AZW (konverteres) > FB2 > PDF > CBZ/CBR > DOCX/RTF/HTML > MD > TXT. */
    private fun formatImportPriority(f: BookFormat): Int = when (f) {
        BookFormat.EPUB -> 0
        BookFormat.MOBI, BookFormat.AZW, BookFormat.AZW3 -> 1
        BookFormat.FB2 -> 2
        BookFormat.PDF -> 3
        BookFormat.CBZ, BookFormat.CBR -> 4
        BookFormat.DOCX, BookFormat.RTF, BookFormat.HTML -> 5
        BookFormat.MD -> 6
        BookFormat.TXT -> 7
        else -> 9
    }

    /** Beholder kun beste format per bok-identitet i gruppen. */
    private fun <T> dedupeByBestFormat(
        files: List<Pair<T, String>>
    ): List<Pair<T, String>> = files
        .groupBy { (_, name) -> bookIdentityKey(name) }
        .mapValues { (_, group) ->
            group.minByOrNull { (_, name) ->
                formatImportPriority(BookFormat.fromFilename(name)) * 10_000 + name.hashCode()
            }!!
        }
        .values
        .toList()

    suspend fun importFolderTree(treeUri: Uri): Int = withContext(dispatchers.io) {
        var totalImported = 0
        try {
            // Walk the SAF tree and group content by subfolder
            totalImported = importSafTreeNode(treeUri, treeUri)
        } catch (e: Exception) {
            Log.e(TAG, "importFolderTree error", e)
        }
        consolidateFragmentedAudiobooks()
        // The scan can surface the same title in several files/formats; collapse it.
        deduplicateLibrary()
        totalImported
    }

    /**
     * Recursively imports a SAF directory tree, grouping audio files by their immediate parent
     * folder into single audiobooks, and importing each ebook file individually.
     */
    private suspend fun importSafTreeNode(treeUri: Uri, nodeUri: Uri): Int {
        var count = 0
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        val docId = try {
            DocumentsContract.getDocumentId(nodeUri)
        } catch (_: Exception) {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

        val subFolders = mutableListOf<Uri>()
        val audioTracksHere = mutableListOf<Pair<Uri, String>>()
        val cueSheetsHere = mutableListOf<Pair<Uri, String>>()
        val ebooksHere = mutableListOf<Pair<Uri, String>>()
        var folderName = nodeUri.lastPathSegment?.substringAfterLast('/') ?: "Folder"

        ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                val childId = if (idCol >= 0) cursor.getString(idCol) ?: continue else continue
                val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                val childDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subFolders.add(childDocUri)
                } else {
                    val fmt = BookFormat.fromFilename(name)
                    when {
                        name.lowercase().endsWith(".cue") -> cueSheetsHere.add(childDocUri to name)
                        fmt.isAudio -> audioTracksHere.add(childDocUri to name)
                        fmt != BookFormat.UNKNOWN && fmt != BookFormat.ZIP -> ebooksHere.add(childDocUri to name)
                        name.lowercase().endsWith(".zip") -> {
                            // Try to import zip via temp copy
                            val id = importSingleUri(childDocUri, ImportSourceEntity.FOLDER_IMPORT)
                            if (id > 0L) count++
                        }
                    }
                    if (name.isNotBlank()) folderName = name.substringBeforeLast('.').substringBeforeLast('/')
                }
            }
        }

        // Import audio files in this folder as one audiobook
        if (audioTracksHere.isNotEmpty()) {
            val abId = importAudiobookFolder(
                files = audioTracksHere,
                folderName = folderName,
                source = ImportSourceEntity.FOLDER_IMPORT,
                cueFiles = cueSheetsHere
            )
            if (abId > 0L) count++
        }

        // Import each ebook individually — samme bok i flere formater: kun beste format
        // (EPUB > MOBI/AZW > FB2 > PDF > CBZ/CBR > DOCX/RTF > TXT)
        for ((uri, _) in dedupeByBestFormat(ebooksHere)) {
            val id = importSingleUri(uri, ImportSourceEntity.FOLDER_IMPORT)
            if (id > 0L) count++
        }

        // Recurse into subfolders
        for (sub in subFolders) {
            count += importSafTreeNode(treeUri, sub)
        }

        return count
    }

    suspend fun importDirectoryOrArchive(
        target: File,
        source: ImportSourceEntity = ImportSourceEntity.FILE_PICKER,
        serverId: Long? = null,
        remotePath: String? = null
    ): List<Long> = withContext(dispatchers.io) {
        val insertedIds = mutableListOf<Long>()
        if (!target.exists()) return@withContext insertedIds

        val dirToScan: File = if (target.isFile) {
            val unpacked = ArchiveImporter.unpackArchiveIfMultiBook(ctx, target)
            unpacked ?: return@withContext run {
                val uri = Uri.fromFile(target)
                val id = importSingleUri(uri, source, serverId, remotePath, target.absolutePath)
                if (id > 0L) listOf(id) else emptyList()
            }
        } else {
            target
        }

        val allFiles = dirToScan.walkTopDown().filter { it.isFile }.toList()
        if (allFiles.isEmpty()) return@withContext insertedIds

        val nestedArchives = allFiles.filter { it.name.lowercase().endsWith(".zip") || it.name.lowercase().endsWith(".cbz") }
        for (arc in nestedArchives) {
            val unpacked = ArchiveImporter.unpackArchiveIfMultiBook(ctx, arc)
            if (unpacked != null) {
                insertedIds.addAll(importDirectoryOrArchive(unpacked, source, serverId, remotePath))
            }
        }

        val cueFiles = allFiles.filter { it.name.lowercase().endsWith(".cue") }
        val (audioFiles, nonAudioFiles) = allFiles
            .filterNot { it.name.lowercase().endsWith(".zip") || it.name.lowercase().endsWith(".cbz") }
            .partition { BookFormat.fromFilename(it.name).isAudio }

        if (audioFiles.isNotEmpty()) {
            val audioByFolder = audioFiles.groupBy { it.parentFile?.absolutePath ?: dirToScan.absolutePath }
            for ((folderPath, filesInFolder) in audioByFolder) {
                val folderName = File(folderPath).name.ifBlank { dirToScan.name }
                val tracks = filesInFolder.map { Uri.fromFile(it) to it.name }
                val cuesInFolder = cueFiles
                    .filter { it.parentFile?.absolutePath == folderPath }
                    .map { Uri.fromFile(it) to it.name }
                val abId = importAudiobookFolder(
                    files = tracks,
                    folderName = folderName,
                    source = source,
                    serverId = serverId,
                    remotePath = remotePath,
                    cueFiles = cuesInFolder
                )
                if (abId > 0L) insertedIds.add(abId)
            }
        }

        // Ebøker grupperes per mappe og dedupliseres på format (kun beste format per bok)
        val ebooksByFolder = nonAudioFiles
            .filter { BookFormat.fromFilename(it.name) != BookFormat.UNKNOWN && BookFormat.fromFilename(it.name) != BookFormat.ZIP }
            .groupBy { it.parentFile?.absolutePath ?: "" }
        for ((_, folderFiles) in ebooksByFolder) {
            for (f in dedupeByBestFormat(folderFiles.map { it to it.name }).map { it.first }) {
                val uri = Uri.fromFile(f)
                val id = importSingleUri(uri, source, serverId, remotePath, f.absolutePath)
                if (id > 0L) insertedIds.add(id)
            }
        }

        consolidateFragmentedAudiobooks()
        insertedIds
    }

    suspend fun rescanAndExpandFragmentedArchives(): Int = withContext(dispatchers.io) {
        var expandedCount = 0
        try {
            val allBooks = db.bookDao().getAllOnce().filter { !it.isDeleted }

            for (book in allBooks) {
                val filePath = book.filePath
                if (filePath != null) {
                    val file = File(filePath)
                    val parentDir = if (file.isDirectory) file else file.parentFile
                    if (parentDir != null && parentDir.exists()) {
                        val validBookFiles = parentDir.walkTopDown().filter { f ->
                            f.isFile && BookFormat.fromFilename(f.name).let { it != BookFormat.UNKNOWN && it != BookFormat.ZIP }
                        }.toList()

                        val isPlaceholder = book.fileSizeBytes <= 1024L || book.format == FormatEntity.ZIP || book.format == FormatEntity.CBZ || book.format == FormatEntity.CBR
                        if (isPlaceholder && validBookFiles.isNotEmpty()) {
                            Log.i(TAG, "[EXPAND_PLACEHOLDER] Expanding placeholder book id=${book.id} title='${book.title}' into ${validBookFiles.size} books from ${parentDir.absolutePath}")
                            db.bookDao().delete(book)
                            val newIds = importDirectoryOrArchive(parentDir, book.importSource)
                            expandedCount += newIds.size
                        }
                    }
                }
            }

            val torrents = db.torrentDownloadDao().getAllOnce()
            for (t in torrents) {
                val saveDir = File(t.savePath)
                if (saveDir.exists() && saveDir.isDirectory) {
                    val validFiles = saveDir.walkTopDown().filter { f ->
                        f.isFile && BookFormat.fromFilename(f.name).let { it != BookFormat.UNKNOWN && it != BookFormat.ZIP }
                    }.toList()
                    if (validFiles.isNotEmpty()) {
                        val imported = importDirectoryOrArchive(saveDir, ImportSourceEntity.TORRENT_DOWNLOAD)
                        if (imported.isNotEmpty()) {
                            expandedCount += imported.size
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error expanding fragmented archives", e)
        }
        expandedCount
    }

    private suspend fun insertProgressFor(bookId: Long) {
        db.progressDao().insertOrReplace(
            ReadingProgressEntity(
                bookId = bookId,
                progressPercent = 0f
            )
        )
    }

    private fun queryDisplayNameAndSize(uri: Uri): Pair<String, Long> {
        var displayName = uri.lastPathSegment ?: "unknown"
        var sizeBytes = 0L
        if (uri.scheme == "file" && uri.path != null) {
            val file = File(uri.path!!)
            if (file.exists()) {
                displayName = file.name
                sizeBytes = file.length()
            }
        } else {
            try {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex >= 0) {
                            cursor.getString(nameIndex)?.let { displayName = it }
                        }
                        if (sizeIndex >= 0) {
                            sizeBytes = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (sizeBytes == 0L && uri.path != null) {
                val f = File(uri.path!!)
                if (f.exists()) sizeBytes = f.length()
            }
        }
        return displayName to sizeBytes
    }

    private fun takePersistableUriPermissionSafely(uri: Uri): Boolean {
        return try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun collectTreeChildren(treeUri: Uri): List<Uri> {
        val result = mutableListOf<Uri>()
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    val docIdChild = if (idCol >= 0) cursor.getString(idCol) ?: continue else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val childTree = DocumentsContract.buildDocumentUriUsingTree(treeUri, docIdChild)
                        result.addAll(collectTreeChildren(childTree))
                    } else {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docIdChild)
                        if (BookFormat.fromFilename(name) != BookFormat.UNKNOWN) {
                            result.add(childUri)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private data class ParsedTrack(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val title: String,
        val trackNumber: Int,
        val localPath: String?,
        val embeddedChapters: List<com.bookrio.core.domain.model.ChapterInfo> = emptyList()
    )
}
