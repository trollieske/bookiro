package com.bookrio.core.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class FetchedMetadata(
    val title: String? = null,
    val author: String? = null,
    val isbn: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val source: String
)

object MetadataFetcher {

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 4000
    private const val UA = "ShelfEbookReader/1.1 (Android; +https://shelf.local)"

    private val UNKNOWN_AUTHOR_MARKERS = listOf(
        "ukjent", "unknown", "n/a", "anon", "anonymous",
        "ingen forfatter", "no author", "(ukjent", "[ukjent"
    )

    fun isAuthorUnknown(rawAuthor: String?): Boolean {
        if (rawAuthor.isNullOrBlank()) return true
        val lower = rawAuthor.lowercase().trim()
        if (lower.length < 3) return true
        return UNKNOWN_AUTHOR_MARKERS.any { lower.contains(it) }
    }

    suspend fun enrich(
        rawTitle: String,
        rawAuthor: String?,
        rawIsbn: String?
    ): FetchedMetadata? = withContext(Dispatchers.IO) {
        val authorUnknown = isAuthorUnknown(rawAuthor)
        val isbnClean = rawIsbn?.filter { it.isDigit() || it == 'X' || it == 'x' }
            ?.takeIf { it.length in 10..13 }

        val titleForSearch = cleanTitleForSearch(rawTitle)
            ?: return@withContext null

        coroutineScope {
            val openByIsbn = isbnClean?.let {
                async { runCatching { fetchOpenLibraryByIsbn(it) }.getOrNull() }
            }
            val openSearch = async {
                runCatching {
                    fetchOpenLibrarySearch(
                        title = titleForSearch,
                        author = if (authorUnknown) null else rawAuthor
                    )
                }.getOrNull()
            }
            val gbSearch = async {
                runCatching {
                    fetchGoogleBooks(
                        title = titleForSearch,
                        author = if (authorUnknown) null else rawAuthor
                    )
                }.getOrNull()
            }
            val itunes = async {
                runCatching {
                    fetchItunesAudiobook(
                        title = titleForSearch,
                        author = if (authorUnknown) null else rawAuthor
                    )
                }.getOrNull()
            }

            val results = listOfNotNull(
                openByIsbn?.await(),
                openSearch.await(),
                gbSearch.await(),
                itunes.await()
            )

            pickBestResult(results, rawTitle, rawAuthor, authorUnknown)
        }
    }

    suspend fun downloadCoverBitmap(coverUrl: String, maxW: Int = 800, maxH: Int = 1200): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = openConn(coverUrl).apply { connectTimeout = 4000; readTimeout = 6000 }
                if (conn.responseCode == 200) {
                    conn.inputStream.use { stream ->
                        val bytes = stream.readBytes()
                        decodeSampled(bytes, maxW, maxH)
                    }
                } else null
            }.getOrNull()
        }

    private fun pickBestResult(
        results: List<FetchedMetadata>,
        rawTitle: String,
        rawAuthor: String?,
        authorUnknown: Boolean,
        rawIsbn: String? = null
    ): FetchedMetadata? {
        if (results.isEmpty()) return null

        val raw = cleanTitleForSearch(rawTitle) ?: return results.first()

        val scored = results.map { r ->
            var score = 0
            val t = r.title?.lowercase() ?: ""
            val a = r.author?.lowercase() ?: ""

            if (t.contains(raw, ignoreCase = true) || raw.contains(t, ignoreCase = true)) score += 20
            if (r.isbn != null) score += 8
            if (r.coverUrl != null) score += 10
            if (r.author != null && a.isNotBlank()) {
                if (authorUnknown) score += 15 else {
                    val ra = rawAuthor?.lowercase() ?: ""
                    if (ra.isNotBlank() && a.any { ra.contains(it) }) score += 12
                }
            }
            score to r
        }.sortedByDescending { it.first }

        // Minimum-match gate: a candidate is only acceptable when its title matches
        // the queried title or its ISBN matches a known ISBN. Anything else is a
        // clearly-mismatched hit and must NOT overwrite good local metadata/cover.
        // When nothing passes, no metadata update happens (retain existing data).
        val best = scored
            .firstOrNull { (_, r) -> matchesQuery(r, raw, rawIsbn) }
            ?.second
            ?: return null

        val mergedAuthor = if (authorUnknown && best.author != null) best.author
        else best.author ?: rawAuthor
        val mergedTitle = best.title?.takeIf { it.isNotBlank() } ?: rawTitle
        val mergedIsbn = best.isbn ?: rawIsbn

        return best.copy(
            title = mergedTitle,
            author = mergedAuthor,
            isbn = mergedIsbn
        )
    }

    /**
     * True when a candidate is close enough to the query to be trusted: its title
     * matches the (cleaned) query title, or its ISBN matches a known ISBN.
     */
    private fun matchesQuery(r: FetchedMetadata, rawTitle: String, rawIsbn: String?): Boolean {
        val t = r.title?.trim()?.lowercase().orEmpty()
        val titleMatch = t.isNotBlank() &&
            (t.contains(rawTitle, ignoreCase = true) || rawTitle.contains(t, ignoreCase = true))
        if (titleMatch) return true
        val queryIsbn = rawIsbn?.filter { it.isDigit() || it == 'X' || it == 'x' }.orEmpty()
        val candidateIsbn = r.isbn?.filter { it.isDigit() || it == 'X' || it == 'x' }.orEmpty()
        return queryIsbn.isNotBlank() && queryIsbn == candidateIsbn
    }

    private fun cleanTitleForSearch(t: String): String? {
        var s = t.trim()
            .replace(Regex("""\[[^\]]*libgen[^\]]*\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\([^)]*z-library[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""_libgen\.[a-z]+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""_z-lib\.[a-z]+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\.(epub|pdf|mobi|azw3?|cbz|fb2|m4b|m4a|mp3)$""", RegexOption.IGNORE_CASE), "")
            .replace("_", " ")
            .replace("  ", " ")
            .trim()
        if (s.contains(" - ")) {
            val parts = s.split(" - ", limit = 2)
            val (p0, p1) = parts[0].trim() to parts[1].trim()
            s = if (p0.length > p1.length * 1.5) p0 else p1
        }
        return s.takeIf { it.isNotBlank() && it.length >= 2 }
    }

    private fun fetchOpenLibraryByIsbn(isbn: String): FetchedMetadata? {
        val clean = isbn.filter { it.isDigit() || it == 'X' || it == 'x' }
        val url = "https://openlibrary.org/api/books?bibkeys=ISBN:$clean&format=json&jscmd=data"
        val json = httpGet(url) ?: return null

        val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
        val authors = Regex(""""authors"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1)
            ?.let { Regex(""""name"\s*:\s*"([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.joinToString(", ") }
        val publishers = Regex(""""publishers"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1)
            ?.let { Regex(""""name"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        val pubDate = Regex(""""publish_date"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

        val coverUrl = "https://covers.openlibrary.org/b/isbn/$clean-L.jpg"
            .takeIf { verifyImageHead("https://covers.openlibrary.org/b/isbn/$clean-S.jpg") }

        if (title == null && coverUrl == null) return null
        return FetchedMetadata(
            title = title,
            author = authors,
            isbn = clean,
            publisher = publishers,
            publishedDate = pubDate,
            coverUrl = coverUrl,
            source = "openlibrary_isbn"
        )
    }

    private fun fetchOpenLibrarySearch(title: String, author: String?): FetchedMetadata? {
        val q = buildQuery(title, author) ?: return null
        val url = "https://openlibrary.org/search.json?q=$q&limit=3&fields=key,title,author_name,isbn,cover_i,first_publish_year,publisher"
        val json = httpGet(url) ?: return null

        val firstDoc = extractFirstDoc(json) ?: return null
        val docTitle = Regex(""""title"\s*:\s*"([^"]+)"""").find(firstDoc)?.groupValues?.get(1)
        val authors = Regex(""""author_name"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(firstDoc)?.groupValues?.get(1)
            ?.let { Regex(""""([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.joinToString(", ") }
        val coverId = Regex(""""cover_i"\s*:\s*(\d+)""").find(firstDoc)?.groupValues?.get(1)
        val isbn = Regex(""""isbn"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(firstDoc)?.groupValues?.get(1)
            ?.let { Regex(""""([^"]+)"""").find(it)?.groupValues?.get(1) }
        val pubYear = Regex(""""first_publish_year"\s*:\s*(\d+)""").find(firstDoc)?.groupValues?.get(1)
        val pub = Regex(""""publisher"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(firstDoc)?.groupValues?.get(1)
            ?.let { Regex(""""([^"]+)"""").find(it)?.groupValues?.get(1) }

        val coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }

        if (docTitle == null && coverUrl == null && authors == null) return null
        return FetchedMetadata(
            title = docTitle,
            author = authors,
            isbn = isbn,
            publisher = pub,
            publishedDate = pubYear,
            coverUrl = coverUrl,
            source = "openlibrary_search"
        )
    }

    private fun fetchGoogleBooks(title: String, author: String?): FetchedMetadata? {
        val q = buildQuery(title, author) ?: return null
        val url = "https://www.googleapis.com/books/v1/volumes?q=$q&maxResults=2&fields=items(volumeInfo(title,authors,publisher,publishedDate,industryIdentifiers,imageLinks,description))"
        val json = httpGet(url) ?: return null

        val firstItem = extractFirstGItem(json) ?: return null
        val gTitle = Regex(""""title"\s*:\s*"([^"]+)"""").find(firstItem)?.groupValues?.get(1)
        val gAuthors = Regex(""""authors"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(firstItem)?.groupValues?.get(1)
            ?.let { Regex(""""([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.joinToString(", ") }
        val gPub = Regex(""""publisher"\s*:\s*"([^"]+)"""").find(firstItem)?.groupValues?.get(1)
        val gPubDate = Regex(""""publishedDate"\s*:\s*"([^"]+)"""").find(firstItem)?.groupValues?.get(1)
        val gDesc = Regex(""""description"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(firstItem)?.groupValues?.get(1)
            ?.replace("\\n", " ")?.take(600)
        val gIsbn = Regex(""""industryIdentifiers"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(firstItem)?.groupValues?.get(1)
            ?.let {
                Regex(""""identifier"\s*:\s*"([^"]+)"""").findAll(it)
                    .map { m -> m.groupValues[1] }
                    .firstOrNull { id -> id.length in 10..13 && id.all { c -> c.isDigit() || c == 'X' || c == 'x' } }
            }
        val thumbnail = Regex(""""thumbnail"\s*:\s*"([^"]+)"""").find(firstItem)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("http://", "https://")
            ?.replace("&edge=curl", "")
            ?.replace("zoom=1", "zoom=2")

        if (gTitle == null && thumbnail == null) return null
        return FetchedMetadata(
            title = gTitle,
            author = gAuthors,
            isbn = gIsbn,
            publisher = gPub,
            publishedDate = gPubDate,
            description = gDesc,
            coverUrl = thumbnail,
            source = "google_books"
        )
    }

    private fun fetchItunesAudiobook(title: String, author: String?): FetchedMetadata? {
        val q = buildQuery(title, author) ?: return null
        val url = "https://itunes.apple.com/search?term=$q&media=audiobook&limit=2"
        val json = httpGet(url) ?: return null
        val first = extractItunesResult(json) ?: return null

        val cName = Regex(""""collectionName"\s*:\s*"([^"]+)"""").find(first)?.groupValues?.get(1)
        val art100 = Regex(""""artworkUrl100"\s*:\s*"([^"]+)"""").find(first)?.groupValues?.get(1)
        val art = art100?.replace("100x100bb", "600x600bb")?.replace("\\/", "/")
        val artist = Regex(""""artistName"\s*:\s*"([^"]+)"""").find(first)?.groupValues?.get(1)
        val rel = Regex(""""releaseDate"\s*:\s*"([^"]+)"""").find(first)?.groupValues?.get(1)?.take(4)

        if (cName == null && art == null) return null
        return FetchedMetadata(
            title = cName,
            author = artist,
            publishedDate = rel,
            coverUrl = art,
            source = "itunes_audiobook"
        )
    }

    private fun buildQuery(title: String, author: String?): String? {
        val t = title.trim().takeIf { it.isNotBlank() } ?: return null
        val parts = mutableListOf(t)
        author?.trim()?.takeIf { it.isNotBlank() && !isAuthorUnknown(it) }?.let { parts.add(it) }
        return runCatching { URLEncoder.encode(parts.joinToString(" "), "UTF-8") }.getOrNull()
    }

    private fun extractFirstDoc(json: String): String? {
        val docs = Regex(""""docs"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return null
        val depth = IntArray(1) { 0 }
        var start = -1
        for (i in docs.indices) {
            val c = docs[i]
            if (c == '{') {
                if (depth[0] == 0) start = i
                depth[0]++
            } else if (c == '}') {
                depth[0]--
                if (depth[0] == 0 && start >= 0) return docs.substring(start, i + 1)
            }
        }
        return null
    }

    private fun extractFirstGItem(json: String): String? {
        val arr = Regex(""""items"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return null
        val depth = IntArray(1) { 0 }
        var start = -1
        for (i in arr.indices) {
            val c = arr[i]
            if (c == '{') {
                if (depth[0] == 0) start = i
                depth[0]++
            } else if (c == '}') {
                depth[0]--
                if (depth[0] == 0 && start >= 0) return arr.substring(start, i + 1)
            }
        }
        return null
    }

    private fun extractItunesResult(json: String): String? {
        val arr = Regex(""""results"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return null
        val depth = IntArray(1) { 0 }
        var start = -1
        for (i in arr.indices) {
            val c = arr[i]
            if (c == '{') {
                if (depth[0] == 0) start = i
                depth[0]++
            } else if (c == '}') {
                depth[0]--
                if (depth[0] == 0 && start >= 0) return arr.substring(start, i + 1)
            }
        }
        return null
    }

    private fun httpGet(urlString: String): String? = runCatching {
        val conn = openConn(urlString)
        if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else null
    }.getOrNull()

    private fun verifyImageHead(urlString: String): Boolean = runCatching {
        val conn = openConn(urlString).apply { requestMethod = "HEAD" }
        conn.responseCode == 200 && conn.contentLength > 500
    }.getOrElse { false }

    private fun openConn(urlString: String): HttpURLConnection {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json,image/*,*/*;q=0.8")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return conn
    }

    private fun decodeSampled(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val w = bounds.outWidth.coerceAtLeast(1)
            val h = bounds.outHeight.coerceAtLeast(1)
            while ((w / sample) > maxW || (h / sample) > maxH) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }
}
