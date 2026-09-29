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

/** One spine document (an XHTML file) reduced to readable plain text. */
internal data class EpubChapter(
    val index: Int,
    val title: String?,
    val text: String,
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