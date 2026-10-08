package com.bookrio.reader.readium

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.search

/** One navigation-ready search hit. */
internal data class ReadiumSearchHit(
    val locator: Locator,
    val chapterTitle: String?,
    val snippet: String,
)

/**
 * Runs a full-text search through Readium's `SearchService`.
 *
 * Proof from the pinned 3.0.3 artifacts: the streamer's `EpubParser` always installs
 * `Publication.ServicesBuilder(search = StringSearchService.createDefaultFactory())`
 * (verified in the 3.0.3 streamer bytecode/source), so `Publication.search(query)` is
 * available for every EPUB opened through `PublicationOpener`. Results are real Readium
 * `Locator`s, which the EpubNavigatorFragment can jump to with `go(locator)`.
 *
 * The iterator is always closed; results are capped so a common word in a large book cannot
 * flood the sheet.
 */
@OptIn(ExperimentalReadiumApi::class)
internal suspend fun searchEpub(
    publication: Publication,
    query: String,
    chapters: BookChapters?,
    maxResults: Int = 120,
): List<ReadiumSearchHit> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val iterator = publication.search(
        query = trimmed,
        options = SearchService.Options(caseSensitive = false),
    ) ?: return emptyList()

    val hits = ArrayList<ReadiumSearchHit>()
    try {
        while (hits.size < maxResults) {
            val page = iterator.next()
            if (page.isFailure) break
            val collection = page.getOrNull() ?: break
            for (locator in collection.locators) {
                if (hits.size >= maxResults) break
                hits.add(hitFor(locator, chapters))
            }
        }
    } finally {
        iterator.close()
    }
    return hits
}

/** Builds the list label for a raw Readium search locator. */
internal fun hitFor(locator: Locator, chapters: BookChapters?): ReadiumSearchHit {
    val tocTitle = locator.title?.takeIf { it.isNotBlank() }
    val chapterTitle = tocTitle ?: chapters?.let { chapters ->
        val index = chapterIndexForLocator(chapters.entries, locator.href)
        chapters.entries.getOrNull(index)?.title?.takeIf { it.isNotBlank() }
    }
    return ReadiumSearchHit(
        locator = locator,
        chapterTitle = chapterTitle,
        snippet = searchSnippet(locator),
    )
}

/** `before … highlight … after`, whitespace-collapsed and length-capped. */
internal fun searchSnippet(locator: Locator, context: Int = 40): String {
    val before = locator.text.before.orEmpty().takeLast(context).trim()
    val highlight = locator.text.highlight.orEmpty().trim()
    val after = locator.text.after.orEmpty().take(context).trim()
    val body = listOf(before, highlight, after)
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return body.ifEmpty { locator.href.toString() }
}