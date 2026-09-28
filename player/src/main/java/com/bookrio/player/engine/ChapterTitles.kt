package com.bookrio.player.engine

import android.content.Context
import com.bookrio.player.R

/**
 * Auto-generated, book-agnostic placeholders such as "Kapittel 3" / "Chapter 3"
 * are UI text that must follow the app language — never baked into stored data.
 */
private val GENERIC_CHAPTER_TITLE =
    Regex("^(kapittel|kapitel|chapter)\\s*\\d+$", RegexOption.IGNORE_CASE)

internal fun localizedChapterTitle(ctx: Context, raw: String?, index: Int): String {
    val t = raw?.trim().orEmpty()
    return if (t.isBlank() || GENERIC_CHAPTER_TITLE.matches(t)) {
        ctx.getString(R.string.ply_chapter_n, index + 1)
    } else t
}
