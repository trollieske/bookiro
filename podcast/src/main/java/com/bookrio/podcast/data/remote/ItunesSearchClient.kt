package com.bookrio.podcast.data.remote

import com.bookrio.podcast.domain.PodcastSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Discovery-only client for the public Apple iTunes Search API. No Apple login,
 * no web-page scraping, no embedded secrets, no API key. iTunes is used purely
 * to turn a user's text query into candidate podcasts; every subscription still
 * goes through the canonical RSS parser and feed URL validation.
 */
object ItunesSearchClient {

    const val SEARCH_ENDPOINT = "https://itunes.apple.com/search"
    const val LOOKUP_ENDPOINT = "https://itunes.apple.com/lookup"
    const val DEFAULT_LIMIT = 25

    /** Builds the exact HTTPS search URL (deterministic, unit-testable). */
    fun buildSearchUrl(term: String, country: String, limit: Int = DEFAULT_LIMIT): String {
        val encoded = URLEncoder.encode(term.trim(), Charsets.UTF_8.name())
        val c = country.uppercase(Locale.US)
        return "$SEARCH_ENDPOINT?term=$encoded&media=podcast&entity=podcast&country=$c&limit=$limit"
    }

    fun buildLookupUrl(collectionId: Long): String =
        "$LOOKUP_ENDPOINT?id=$collectionId&entity=podcast"

    suspend fun search(
        term: String,
        country: String,
        fetch: suspend (String) -> String = { url -> PodcastHttp.fetchPlain(url) }
    ): List<PodcastSearchResult> {
        val normalized = term.trim()
        if (normalized.length < 2) return emptyList()
        val c = country.uppercase(Locale.US)
        ItunesSearchCache.get(normalized, c)?.let { return it }

        val body = withContext(Dispatchers.IO) { fetch(buildSearchUrl(normalized, c)) }
        coroutineContext.ensureActive()
        val results = parseResults(body)
        ItunesSearchCache.put(normalized, c, results)
        return results
    }

    /** Resolves a feed URL from the iTunes lookup API when search omitted it. */
    suspend fun lookupFeedUrl(collectionId: Long, fetch: suspend (String) -> String = { url -> PodcastHttp.fetchPlain(url) }): String? {
        val body = runCatching { withContext(Dispatchers.IO) { fetch(buildLookupUrl(collectionId)) } }.getOrNull() ?: return null
        coroutineContext.ensureActive()
        return parseResults(body).firstOrNull()?.feedUrl
    }

    fun parseResults(json: String): List<PodcastSearchResult> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<PodcastSearchResult>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("collectionName").ifBlank { o.optString("trackName") }.trim()
            if (title.isEmpty()) continue
            val genres = mutableListOf<String>()
            o.optJSONArray("genres")?.let { g ->
                for (j in 0 until g.length()) {
                    val s = g.optString(j).trim()
                    if (s.isNotEmpty()) genres.add(s)
                }
            }
            val primary = o.optString("primaryGenreName").trim().ifBlank { genres.firstOrNull() ?: "" }
            val explicitness = o.optString("collectionExplicitness").ifBlank { o.optString("trackExplicitness") }
            out.add(
                PodcastSearchResult(
                    collectionId = o.optLong("collectionId").takeIf { it > 0L },
                    trackId = o.optLong("trackId").takeIf { it > 0L },
                    title = title,
                    author = o.optString("artistName").trim().takeIf { it.isNotEmpty() },
                    artworkUrl = o.optString("artworkUrl100").trim().takeIf { it.isNotEmpty() }
                        ?: o.optString("artworkUrl600").trim().takeIf { it.isNotEmpty() },
                    feedUrl = o.optString("feedUrl").trim().takeIf { it.isNotEmpty() },
                    genre = primary.takeIf { it.isNotEmpty() },
                    country = o.optString("country").trim().takeIf { it.isNotEmpty() },
                    explicit = explicitness.equals("explicit", ignoreCase = true)
                )
            )
        }
        return out
    }
}

/** Small in-memory 24h cache for successful search responses. */
object ItunesSearchCache {
    private const val TTL_MS = 24L * 60L * 60L * 1000L
    private data class Entry(val at: Long, val results: List<PodcastSearchResult>)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    private fun key(query: String, country: String) = query.trim().lowercase(Locale.US) + "|" + country.uppercase(Locale.US)

    fun get(query: String, country: String): List<PodcastSearchResult>? {
        val e = cache[key(query, country)] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            cache.remove(key(query, country))
            return null
        }
        return e.results
    }

    fun put(query: String, country: String, results: List<PodcastSearchResult>) {
        cache[key(query, country)] = Entry(System.currentTimeMillis(), results)
    }

    fun clear() = cache.clear()
}