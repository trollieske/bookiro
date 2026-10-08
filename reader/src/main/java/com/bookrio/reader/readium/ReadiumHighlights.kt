package com.bookrio.reader.readium

import com.bookrio.data.local.entity.HighlightEntity
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator

/**
 * Bridges Bookiro's persistent [HighlightEntity] rows to Readium 3.0.3 decorations.
 *
 * Readium renders decorations through `DecorableNavigator.applyDecorations(list, group)`
 * using the default `HtmlDecorationTemplates`, which ship a `Highlight` template for
 * reflowable EPUBs (verified in the 3.0.3 navigator artifact: `supportsDecorationStyle`
 * is backed by `HtmlDecorationTemplates.defaultTemplates()`).
 *
 * Persistence model:
 *  - `start_cfi` holds the canonical Readium Locator JSON of the selection.
 *  - Readium 3.0.3's `SelectableNavigator.currentSelection()` returns a single Locator
 *    whose `text.highlight` quote *is* the selected range (there is no public API for a
 *    separate end locator), so `end_cfi` stays null for EPUB highlights created by this
 *    reader. Legacy rows keep whatever they had; nothing is overwritten blindly.
 *  - `text`, `color` and `position_percent` are safe, human-readable columns.
 */
internal object ReadiumHighlights {

    const val GROUP = "bookiro-highlights"

    /** Bookiro lime at ~40 % alpha: `#BEF93F`. */
    const val DEFAULT_TINT: Int = 0x66BEF93F

    private const val ID_PREFIX = "bookiro-highlight-"

    fun decorationId(entityId: Long): String = "$ID_PREFIX$entityId"

    fun entityId(decorationId: String): Long? =
        decorationId.removePrefix(ID_PREFIX)
            .takeIf { it != decorationId }
            ?.toLongOrNull()

    /** Parses the canonical Locator JSON stored in `start_cfi`. */
    fun locatorOf(entity: HighlightEntity): Locator? =
        entity.startCfi
            ?.takeIf { it.isNotBlank() }
            ?.let { json ->
                runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
            }

    /** Builds a renderable Readium decoration, or null when the row has no usable anchor. */
    fun toDecoration(entity: HighlightEntity): Decoration? {
        val locator = locatorOf(entity) ?: return null
        return Decoration(
            id = decorationId(entity.id),
            locator = locator,
            style = Decoration.Style.Highlight(
                tint = entity.color ?: DEFAULT_TINT,
            ),
            extras = mapOf("text" to entity.text),
        )
    }

    /**
     * Builds a persistent row from a Readium selection. The selection locator already carries
     * the selected quote (`text.highlight`) plus its progression, which is exactly what the
     * decoration and later a jump back need.
     */
    fun toEntity(bookId: Long, locator: Locator): HighlightEntity? {
        val text = locator.text.highlight?.trim().orEmpty()
        if (text.isEmpty()) return null
        val json = ReadiumResume.locatorJson(locator) ?: return null
        return HighlightEntity(
            bookId = bookId,
            text = text,
            note = null,
            color = DEFAULT_TINT,
            startCfi = json,
            endCfi = null,
            pageIndex = null,
            startPageOffset = null,
            endPageOffset = null,
            positionPercent = (
                locator.locations.totalProgression
                    ?: locator.locations.progression
                    ?: 0.0
                ).toFloat().coerceIn(0f, 1f),
        )
    }

    /** One-line label for the highlight list. */
    fun label(entity: HighlightEntity): String = entity.text.replace(Regex("\\s+"), " ").trim()
}