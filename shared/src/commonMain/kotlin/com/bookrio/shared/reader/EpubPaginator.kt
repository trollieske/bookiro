package com.bookrio.shared.reader

/**
 * Deterministic, platform-independent line/column pagination. This approximates
 * the real layout: the iOS host measures its view and font, computes a character
 * grid (columns x rows) and hands those numbers to [paginate].
 *
 * Word wrapping is preferred; a word longer than a line is hard-split. Each page
 * holds at most [rowsPerPage] lines and carries the chapter it came from plus its
 * global page index. Empty input still yields one (empty) page so the reader
 * always has something to show.
 */
internal object EpubPaginator {

    fun paginate(chapters: List<EpubChapter>, columnsPerPage: Int, rowsPerPage: Int): List<EpubPage> {
        val columns = columnsPerPage.coerceAtLeast(1)
        val rows = rowsPerPage.coerceAtLeast(1)
        val pages = ArrayList<EpubPage>()
        for (chapter in chapters) {
            val lines = wrapChapter(chapter.text, columns)
            var start = 0
            do {
                val end = minOf(start + rows, lines.size)
                pages.add(
                    EpubPage(
                        chapterIndex = chapter.index,
                        title = chapter.title,
                        text = lines.subList(start, end).joinToString("\n"),
                        globalIndex = pages.size,
                        globalCount = 0,
                    ),
                )
                start = end
            } while (start < lines.size)
        }
        if (pages.isEmpty()) {
            pages.add(
                EpubPage(
                    chapterIndex = 0,
                    title = chapters.firstOrNull()?.title,
                    text = "",
                    globalIndex = 0,
                    globalCount = 1,
                ),
            )
        }
        val total = pages.size
        return pages.map { it.copy(globalCount = total) }
    }

    private fun wrapChapter(text: String, columns: Int): List<String> {
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) continue
            wrapParagraph(paragraph, columns, lines)
        }
        return lines
    }

    private fun wrapParagraph(paragraph: String, columns: Int, out: MutableList<String>) {
        val current = StringBuilder()
        for (word in paragraph.split(' ')) {
            if (word.isEmpty()) continue
            if (word.length > columns) {
                if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.setLength(0)
                }
                var start = 0
                while (word.length - start > columns) {
                    out.add(word.substring(start, start + columns))
                    start += columns
                }
                current.append(word.substring(start))
            } else if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= columns) {
                current.append(' ').append(word)
            } else {
                out.add(current.toString())
                current.setLength(0)
                current.append(word)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
    }
}