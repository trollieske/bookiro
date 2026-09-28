package com.bookrio.podcast.data.remote

import android.util.Xml
import com.bookrio.podcast.domain.ParsedPodcastEpisode
import com.bookrio.podcast.domain.ParsedPodcastFeed
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Recoverable parse failure. The message is a stable code, never raw XML detail. */
class PodcastFeedParseException(val code: String, cause: Throwable? = null) : Exception(code, cause) {
    companion object {
        const val MALFORMED = "malformed"
        const val NO_AUDIO = "no_audio"
    }
}

/**
 * Streaming RSS 2.0 / Atom podcast parser.
 *
 * Uses [XmlPullParser] so feed XML is never fully loaded into memory, never
 * executes scripts, and never fetches remote HTML. Unsupported or malformed
 * structures are skipped rather than crashing. Only episodes with a valid
 * playable audio enclosure survive.
 */
object PodcastFeedParser {

    private val AUDIO_MIME_PREFIX = "audio/"
    private val VIDEO_MIME_PREFIX = "video/"

    fun parse(input: InputStream, feedUrl: String): ParsedPodcastFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false)
        parser.setInput(input, null)
        return parse(parser, feedUrl)
    }

    /**
     * Parses from an already configured [XmlPullParser] whose input has been set.
     * Kept separate so the parser can be exercised from JVM unit tests without the
     * Android `Xml` factory.
     */
    fun parse(parser: XmlPullParser, feedUrl: String): ParsedPodcastFeed {
        var channelTitle: String? = null
        var itunesTitle: String? = null
        var author: String? = null
        var description: String? = null
        var summary: String? = null
        var artwork: String? = null
        var website: String? = null
        var language: String? = null
        var explicit: Boolean? = null
        val categories = LinkedHashSet<String>()
        val episodes = ArrayList<ParsedPodcastEpisode>()

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel", "rss", "feed" -> {
                            // Containers: descend into them.
                        }
                        "item", "entry" -> {
                            parseEpisode(parser)?.let { episodes.add(it) }
                        }
                        "title" -> if (channelTitle.isNullOrBlank()) channelTitle = readElementText(parser)
                        "itunes:title" -> if (itunesTitle.isNullOrBlank()) itunesTitle = readElementText(parser)
                        "itunes:author" -> if (author.isNullOrBlank()) author = readElementText(parser)
                        "author" -> if (author.isNullOrBlank()) author = readElementText(parser, childFilter = setOf("name"))
                        "description" -> if (description.isNullOrBlank()) description = readElementText(parser)
                        "itunes:summary", "itunes:subtitle" -> if (summary.isNullOrBlank()) summary = readElementText(parser)
                        "language" -> if (language.isNullOrBlank()) language = readElementText(parser)
                        "itunes:explicit" -> if (explicit == null) explicit = parseExplicit(readElementText(parser))
                        "itunes:image" -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (artwork.isNullOrBlank() && !href.isNullOrBlank()) artwork = href.trim()
                            readElementText(parser)
                        }
                        "image" -> {
                            val url = readElementText(parser, childFilter = setOf("url"))
                            if (artwork.isNullOrBlank() && url.isNotBlank()) artwork = url
                        }
                        "itunes:category" -> {
                            val text = parser.getAttributeValue(null, "text")
                            if (!text.isNullOrBlank()) categories.add(text.trim())
                            readElementText(parser)
                        }
                        "category" -> {
                            val term = parser.getAttributeValue(null, "term")
                            if (!term.isNullOrBlank()) {
                                categories.add(term.trim())
                                readElementText(parser)
                            } else {
                                val text = readElementText(parser)
                                if (text.isNotBlank()) categories.add(text)
                            }
                        }
                        "link" -> {
                            val rel = parser.getAttributeValue(null, "rel")
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank() && (rel.isNullOrBlank() || rel == "alternate")) {
                                if (website.isNullOrBlank()) website = href.trim()
                                readElementText(parser)
                            } else if (rel.isNullOrBlank() && href.isNullOrBlank()) {
                                val text = readElementText(parser)
                                if (website.isNullOrBlank() && text.startsWith("http")) website = text
                            } else {
                                readElementText(parser)
                            }
                        }
                        else -> {
                            // Unknown feed-level element with possible children. Skip it
                            // only when it is a known non-descending container; otherwise
                            // descend so nested feed metadata (e.g. owner>name) is found.
                        }
                    }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            throw PodcastFeedParseException(PodcastFeedParseException.MALFORMED, t)
        }

        if (episodes.isEmpty()) {
            throw PodcastFeedParseException(PodcastFeedParseException.NO_AUDIO)
        }

        return ParsedPodcastFeed(
            feedUrl = feedUrl,
            title = (channelTitle ?: itunesTitle ?: feedUrl).trim().ifBlank { feedUrl },
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            description = (description ?: summary)?.trim()?.takeIf { it.isNotEmpty() },
            artworkUrl = resolveUrl(feedUrl, artwork),
            websiteUrl = website?.trim()?.takeIf { it.isNotEmpty() },
            language = language?.trim()?.takeIf { it.isNotEmpty() },
            categories = categories.toList(),
            explicit = explicit,
            episodes = episodes
        )
    }

    /** Parses a single `<item>` / `<entry>` and fully consumes its subtree. */
    private fun parseEpisode(parser: XmlPullParser): ParsedPodcastEpisode? {
        var guid: String? = null
        var title: String? = null
        var description: String? = null
        var published: String? = null
        var duration: String? = null
        var explicit: Boolean? = null
        var season: Int? = null
        var episodeNumber: Int? = null
        var episodeType: String? = null
        var artwork: String? = null
        var enclosureUrl: String? = null
        var enclosureType: String? = null
        var enclosureLength: Long? = null

        val entryDepth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "item" && parser.depth == entryDepth) &&
            !(event == XmlPullParser.END_TAG && parser.name == "entry" && parser.depth == entryDepth) &&
            event != XmlPullParser.END_DOCUMENT
        ) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "guid", "id" -> if (guid.isNullOrBlank()) guid = readElementText(parser)
                    "title", "itunes:title" -> if (title.isNullOrBlank()) title = readElementText(parser)
                    "description", "itunes:summary", "itunes:subtitle", "summary", "content" ->
                        if (description.isNullOrBlank()) description = readElementText(parser)
                    "pubDate", "published", "updated", "itunes:pubDate" ->
                        if (published.isNullOrBlank()) published = readElementText(parser)
                    "itunes:duration" -> if (duration.isNullOrBlank()) duration = readElementText(parser)
                    "itunes:explicit" -> if (explicit == null) explicit = parseExplicit(readElementText(parser))
                    "itunes:season" -> if (season == null) season = readElementText(parser).toIntOrNull()
                    "itunes:episode" -> if (episodeNumber == null) episodeNumber = readElementText(parser).toIntOrNull()
                    "itunes:episodeType" -> if (episodeType.isNullOrBlank()) episodeType = readElementText(parser)
                    "itunes:image" -> {
                        val href = parser.getAttributeValue(null, "href")
                        if (artwork.isNullOrBlank() && !href.isNullOrBlank()) artwork = href.trim()
                        readElementText(parser)
                    }
                    "image" -> {
                        val url = readElementText(parser, childFilter = setOf("url", "href"))
                        if (artwork.isNullOrBlank() && url.isNotBlank()) artwork = url
                    }
                    "enclosure" -> {
                        val url = parser.getAttributeValue(null, "url")
                        val type = parser.getAttributeValue(null, "type")
                        val length = parser.getAttributeValue(null, "length")
                        if (!url.isNullOrBlank() && isPlayableAudio(type, url)) {
                            if (enclosureUrl.isNullOrBlank()) {
                                enclosureUrl = url.trim()
                                enclosureType = type?.trim()
                                enclosureLength = length?.toLongOrNull()
                            }
                        }
                        readElementText(parser)
                    }
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel")
                        val type = parser.getAttributeValue(null, "type")
                        val href = parser.getAttributeValue(null, "href")
                        if (!href.isNullOrBlank() && rel == "enclosure" && isPlayableAudio(type, href)) {
                            if (enclosureUrl.isNullOrBlank()) {
                                enclosureUrl = href.trim()
                                enclosureType = type?.trim()
                            }
                        }
                        readElementText(parser)
                    }
                    else -> {
                        // Unknown element inside the item; skip its subtree so its
                        // children are not mistaken for episode fields.
                        readElementText(parser)
                    }
                }
            }
            event = parser.next()
        }

        val audioUrl = enclosureUrl ?: return null
        return ParsedPodcastEpisode(
            guid = guid?.trim()?.takeIf { it.isNotEmpty() },
            enclosureUrl = audioUrl,
            enclosureMimeType = enclosureType?.takeIf { it.isNotEmpty() },
            enclosureLengthBytes = enclosureLength?.takeIf { it > 0 },
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: audioUrl.substringAfterLast('/'),
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            artworkUrl = resolveUrl(audioUrl, artwork),
            publishedAt = parseDate(published),
            durationMs = parseDuration(duration),
            seasonNumber = season,
            episodeNumber = episodeNumber,
            explicit = explicit,
            episodeType = episodeType?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Reads the text of the element at the current START_TAG and consumes it.
     * When [childFilter] is set, only text inside those direct child elements is
     * collected (e.g. `<image><url>` or `<author><name>`).
     */
    private fun readElementText(parser: XmlPullParser, childFilter: Set<String>? = null): String {
        val sb = StringBuilder()
        var depth = 1
        var captureDepth = if (childFilter == null) 1 else -1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (childFilter != null && captureDepth == -1 && childFilter.contains(parser.name)) {
                        captureDepth = depth
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (childFilter != null && captureDepth == depth) captureDepth = -1
                    depth--
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (captureDepth != -1 && depth >= captureDepth) sb.append(parser.text)
                }
                XmlPullParser.ENTITY_REF -> {
                    if (captureDepth != -1 && depth >= captureDepth) {
                        sb.append(parser.text ?: "")
                    }
                }
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
                else -> Unit
            }
        }
        return sb.toString().trim()
    }

    /** True when the enclosure type/URL identifies playable audio. */
    fun isPlayableAudio(type: String?, url: String): Boolean {
        val mime = type?.lowercase(Locale.US)?.substringBefore(';')?.trim()
        if (!mime.isNullOrEmpty()) {
            if (mime.startsWith(VIDEO_MIME_PREFIX)) return false
            if (mime.startsWith(AUDIO_MIME_PREFIX)) return true
            if (mime == "application/ogg" || mime == "application/octet-stream") {
                return PodcastUrls.looksLikeAudio(url)
            }
            return false
        }
        return PodcastUrls.looksLikeAudio(url)
    }

    private fun parseExplicit(raw: String?): Boolean? {
        val v = raw?.trim()?.lowercase(Locale.US) ?: return null
        return when (v) {
            "yes", "true", "explicit", "1" -> true
            "no", "false", "clean", "0" -> false
            else -> null
        }
    }

    /** Parses `HH:MM:SS`, `MM:SS`, or plain seconds. */
    fun parseDuration(raw: String?): Long? {
        val v = raw?.trim() ?: return null
        if (v.isEmpty()) return null
        val parts = v.split(':')
        return when (parts.size) {
            1 -> v.toLongOrNull()?.times(1000L)
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toLongOrNull() ?: return null
                (m * 60L + s) * 1000L
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toLongOrNull() ?: return null
                (h * 3600L + m * 60L + s) * 1000L
            }
            else -> null
        }?.takeIf { it > 0L }
    }

    /** Best-effort RFC-822 / RFC-3339 date parsing. Never throws. */
    fun parseDate(raw: String?): Long? {
        val v = raw?.trim() ?: return null
        if (v.isEmpty()) return null
        return runCatching { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(v, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(v, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching {
                val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply { isLenient = true }
                fmt.parse(v)?.time
            }
            .getOrNull()
    }

    /** Resolves a possibly relative artwork URL against the feed URL. */
    private fun resolveUrl(base: String, candidate: String?): String? {
        val c = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (c.startsWith("http://") || c.startsWith("https://")) return c
        return runCatching { java.net.URI(base).resolve(c).toString() }.getOrNull()
    }
}