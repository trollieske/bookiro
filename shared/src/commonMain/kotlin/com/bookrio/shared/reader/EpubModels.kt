package com.bookrio.shared.reader

/**
 * A parsed EPUB, ready for pagination. Everything here is plain Kotlin so the
 * parser/paginator stay unit-testable and platform independent.
 */
internal data class EpubBook(
    val title: String,
    val author: String?,
    val chapters: List<EpubChapter>,
)

/**
 * One spine document (an XHTML file).
 *
 * [text] is the plain-text reduction (kept for compatibility with the line-based
 * [EpubPaginator]); [html] is the body markup the reader actually renders, with
 * relative image sources already inlined as `data:` URIs and wrapped in a
 * `<section>` — the same content shape the Android reader feeds to its WebView.
 * Never render [text] directly: the iOS reader shows [html].
 */
internal data class EpubChapter(
    val index: Int,
    val title: String?,
    val text: String,
    val html: String = "",
)

/**
 * One paginated page. [globalIndex] is the page's position across the whole book
 * (zero based); [globalCount] is the total number of pages.
 */
internal data class EpubPage(
    val chapterIndex: Int,
    val title: String?,
    val text: String,
    val globalIndex: Int,
    val globalCount: Int,
)