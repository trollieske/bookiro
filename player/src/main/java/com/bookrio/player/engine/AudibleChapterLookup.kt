package com.bookrio.player.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Henter reelle kapittelnavn/-grenser fra nettet (Audible-metadata via audnexus).
 *
 * Pipeline:
 *  1. Audible-katalogsøk på tittel (+forfatter) → kandidat-ASIN-er.
 *  2. For hver kandidat: audnexus /books/{asin}/chapters → titler + varigheter.
 *  3. Validering:
 *     - Titler kartlegges KUN ved nøyaktig likt antall kapitler (indeks-til-indeks).
 *     - Kapitler kan OPPRETTES fra varigheter KUN når vi mangler kapitler (≤ 1)
 *       og total varighet stemmer innenfor 5 % — aldri falske kapitler ellers.
 *  4. Titler skrives aldri over noe som allerede er bedre enn generisk.
 *
 * Ingen følsom data sendes; kun boktittel/forfatter i søket. Gated diagnostikk.
 */
object AudibleChapterLookup {

    private const val TAG = "AudibleLookup"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    /** Generiske titler er verdt å erstatte: "Chapter 01", "Kapittel 3", "Track 12". */
    private val GENERIC_TITLE = Regex(
        "^(chapter|kapittel|kap|track|del|part)\\s*0*\\d+\\b.*$|^(chapter|kapittel|kap|track)\\s*0*\\d+$",
        RegexOption.IGNORE_CASE,
    )

    fun looksGeneric(title: String): Boolean {
        val t = title.trim()
        if (t.isBlank()) return true
        return GENERIC_TITLE.matches(t) || t.lowercase().startsWith("chapter 0") ||
            t.lowercase().startsWith("chapter ") || t.lowercase().startsWith("kapittel ")
    }

    fun hasAnyRealTitle(titles: List<String>): Boolean = titles.any { !looksGeneric(it) }

    /** Søker Audible-katalogen og returnerer kandidat-ASIN-er (maks [maxResults]). */
    fun searchAsins(title: String, author: String?, maxResults: Int = 6): List<String> {
        val seen = LinkedHashSet<String>()
        for (q in buildSearchQueries(title, author)) {
            if (seen.size >= maxResults) break
            val url = "https://api.audible.com/1.0/catalog/products" +
                "?response_groups=product_attrs&num_results=${maxResults}&keywords=" +
                URLEncoder.encode(q, "UTF-8")
            fetchJson(url)?.optJSONArray("products")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val asin = arr.optJSONObject(i)?.optString("asin") ?: continue
                    if (asin.isNotBlank()) seen.add(asin)
                    if (seen.size >= maxResults) break
                }
            }
        }
        return seen.toList()
    }

    /**
     * Bygger ordnede søkestrenger fra rå tittel/forfatter. Nødvendig fordi
     * importerte titler ofte inneholder forfatterprefiks og serienummer, f.eks.
     * «Stephen R. Donaldson - Thomas Covenant 01 - Lord Fouls Bane». Et søk på
     * hele den strengen gir NULL treff (verifisert), mens
     * «Lord Fouls Bane Stephen R. Donaldson» finner riktig utgave.
     *
     * Rekkefølgen er «mest konkret først» slik at [searchAsins] treffer den
     * riktige utgaven før et bredt søk kan fylle resultatlisten med støy.
     * Ren funksjon → JVM-testbar.
     */
    internal fun buildSearchQueries(title: String, author: String?): List<String> {
        val raw = title.trim()
        val authorTrim = author?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()

        fun stripNoise(s: String): String = s
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\{[^}]*\\}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', ':', '\u2013', '\u2014', '|')

        val cleaned = stripNoise(raw)

        // Fjern ledende forfatternavn: «Forfatter - Tittel», «Forfatter: Tittel».
        var body = cleaned
        if (authorTrim.isNotBlank()) {
            for (sep in listOf(" - ", " \u2013 ", " \u2014 ", ": ", " | ")) {
                val prefix = authorTrim + sep
                if (body.startsWith(prefix, ignoreCase = true)) {
                    body = body.substring(prefix.length).trim()
                    break
                }
            }
        }

        // Fjern serienummer: «..., Book 3», «Vol. 2», «Del 1», ledende «01 - ».
        fun stripSeries(s: String): String = s
            .replace(Regex("(?i)\\s*,?\\s*(book|bok|vol|volume|del|part)\\s*\\.?\\s*\\d+\\s*$"), " ")
            .replace(Regex("^\\s*[\\[(]?\\d{1,3}[\\])]?\\s*[.\\-:\u2013\u2014]\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', ':', '\u2013', '\u2014')

        val afterAuthor = body
        // Siste ledd dropper «Serie 01 - »-prefiks; håndter alle tankestreker.
        val segmented = afterAuthor.replace(" \u2013 ", " - ").replace(" \u2014 ", " - ")
        val lastSegment = segmented.substringAfterLast(" - ").trim()
        val beforeColon = afterAuthor.substringBefore(":").trim()

        val titleCandidates = LinkedHashSet<String>()
        titleCandidates.add(stripSeries(lastSegment))
        titleCandidates.add(stripSeries(beforeColon))
        titleCandidates.add(stripSeries(afterAuthor))
        titleCandidates.add(cleaned)

        val queries = LinkedHashSet<String>()
        for (t in titleCandidates) {
            if (t.isBlank()) continue
            if (authorTrim.isNotBlank()) queries.add("$t $authorTrim")
            queries.add(t)
        }
        return queries.toList()
    }

    /** Henter kapitler (title, startOffsetMs, lengthMs) for en ASIN, eller null. */
    fun fetchChapters(asin: String): List<Triple<String, Long, Long>>? {
        val json = fetchJson("https://api.audnex.us/books/${URLEncoder.encode(asin, "UTF-8")}/chapters")
            ?: return null
        val arr = json.optJSONArray("chapters") ?: return null
        val out = mutableListOf<Triple<String, Long, Long>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", "").trim()
            val start = o.optLong("startOffsetMs", -1L)
            val len = o.optLong("lengthMs", -1L)
            if (title.isBlank() || start < 0L || len <= 0L) continue
            out.add(Triple(title, start, len))
        }
        return out.ifEmpty { null }
    }

    /**
     * Tilfelle A: kartlegg hentede titler til våre kapitler ved nøyaktig likt
     * antall. Returnerer null ved antalls-avvik eller når ingenting blir bedre.
     */
    fun mergeTitlesIfCountMatches(
        chapters: List<AudiobookChapter>,
        fetched: List<Triple<String, Long, Long>>,
    ): List<AudiobookChapter>? {
        if (fetched.size != chapters.size) return null
        if (!hasAnyRealTitle(fetched.map { it.first })) return null
        val merged = chapters.mapIndexed { i, ch ->
            val better = fetched[i].first.takeIf { !looksGeneric(it) } ?: ch.title
            ch.copy(title = better)
        }
        return merged.takeIf { it != chapters }
    }

    /**
     * Tilfelle B: bygg kapitler fra hentede varigheter når vi selv har ≤ 1
     * kapittel. Krever at total varighet stemmer innenfor [tolerance].
     */
    fun buildChaptersFromFetched(
        fetched: List<Triple<String, Long, Long>>,
        sourceUri: String?,
        ourDurationMs: Long,
        tolerance: Double = 0.05,
    ): List<AudiobookChapter>? {
        if (fetched.size < 2) return null
        val fetchedTotal = fetched.last().second + fetched.last().third
        if (ourDurationMs <= 0L) return null
        val drift = kotlin.math.abs(fetchedTotal - ourDurationMs).toDouble() / ourDurationMs
        if (drift > tolerance) return null
        return fetched.mapIndexed { idx, (title, start, len) ->
            AudiobookChapter(
                index = idx,
                title = title,
                startMs = start,
                endMs = start + len,
                mediaUri = sourceUri,
            )
        }
    }

    private fun fetchJson(url: String): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Shelf-Audiobook/1.0 (chapter metadata lookup)")
        conn.inputStream.use { stream ->
            val text = stream.bufferedReader().readText()
            JSONObject(text)
        }.also { conn.disconnect() }
    }.onFailure {
        Log.d(TAG, "fetch failed: ${it.javaClass.simpleName}")
    }.getOrNull()
}

/** Gated nettverks-diagnostikk (samme bryter som ChapterRefresh). */
internal fun lookupDiag(msg: String) {
    if (CHAPTER_REFRESH_DIAG) Log.d(ChapterRefresh.DIAG_TAG, msg)
}

/**
 * Nett-henting kjørt fra [AudiobookEngine.ensureFreshChapters]:
 *  - ≤ 1 kapittel: forsøk å OPPRETTE kapitler fra Audible-varigheter.
 *  - > 1 kapittel med generiske titler: forsøk å erstatte titler (lik antall).
 * Returnerer oppdatert kapittelliste eller input uendret. Kaster aldri.
 */
internal suspend fun lookupOnlineChapters(
    engine: AudiobookEngine,
    book: com.bookrio.data.local.entity.BookEntity,
    chapters: List<AudiobookChapter>,
    sourceUri: String?,
): List<AudiobookChapter> = withContext(Dispatchers.IO) {
    runCatching {
        val durationMs = book.durationMs ?: 0L
        lookupDiag("NET-LOOKUP id=${book.id} stored=${chapters.size} durationMs=$durationMs")
        val asins = AudibleChapterLookup.searchAsins(book.title, book.author)
        if (asins.isEmpty()) {
            lookupDiag("NET-LOOKUP id=${book.id} search=EMPTY")
            return@runCatching chapters
        }
        for (asin in asins) {
            val fetched = AudibleChapterLookup.fetchChapters(asin) ?: continue
            // Tilfelle A: likt antall + generiske titler → erstatt titler
            if (chapters.size > 1) {
                val merged = AudibleChapterLookup.mergeTitlesIfCountMatches(chapters, fetched)
                if (merged != null) {
                    lookupDiag("NET-TITLES id=${book.id} asin=$asin applied=${merged.size}")
                    return@runCatching merged
                }
                continue
            }
            // Tilfelle B: ingen/én kapittel → bygg kapitler fra varigheter
            val built = AudibleChapterLookup.buildChaptersFromFetched(fetched, sourceUri, durationMs)
            if (built != null) {
                lookupDiag("NET-CREATED id=${book.id} asin=$asin chapters=${built.size}")
                return@runCatching built
            }
        }
        lookupDiag("NET-LOOKUP id=${book.id} no-match")
        chapters
    }.getOrElse { chapters }
}
