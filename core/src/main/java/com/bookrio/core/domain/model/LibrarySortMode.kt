package com.bookrio.core.domain.model

/**
 * Library sort modes. [HYLLE] is a legacy "Shelf" grouping kept only so stored
 * preferences can be migrated; it is never shown in the UI. The visible modes
 * are [visible]: Recently played · Recently added · Title · Author · Series.
 * Pure domain model — shared by data (persistence) and library (sorting).
 */
enum class LibrarySortMode(val storage: String) {
    HYLLE("hylle"),
    SERIE("serie"),
    FORFATTER("forfatter"),
    NYLIG("nylig"),
    TITTEL("tittel"),
    LAGT_TIL("lagt_til");

    val label: String
        get() = when (this) {
            HYLLE -> "Serie" // legacy alias; never rendered
            SERIE -> "Serie"
            FORFATTER -> "Forfatter"
            NYLIG -> "Nylig"
            TITTEL -> "Tittel"
            LAGT_TIL -> "Lagt til"
        }

    companion object {
        /** Modes presented in the sort sheet, most useful first. */
        val visible: List<LibrarySortMode> = listOf(NYLIG, LAGT_TIL, TITTEL, FORFATTER, SERIE)

        /** Sensible first-run default: newest additions first. */
        val DEFAULT: LibrarySortMode = LAGT_TIL

        /**
         * Maps stored (possibly legacy) values. The old "Shelf" mode sorted
         * series-first, so it migrates to [SERIE]; the legacy keys
         * "date_added"/"progress" map onto their modern equivalents.
         */
        fun from(storage: String?): LibrarySortMode = when (storage) {
            "hylle" -> SERIE
            "date_added" -> LAGT_TIL
            "progress" -> NYLIG
            else -> entries.firstOrNull { it.storage == storage } ?: DEFAULT
        }
    }
}

enum class SortDirection(val storage: String) {
    ASC("asc"),
    DESC("desc");

    companion object {
        fun from(storage: String?): SortDirection =
            entries.firstOrNull { it.storage == storage } ?: ASC
    }
}

/** Default direction per mode: Nylig/Lagt til are newest-first, the rest ascending. */
fun defaultDirectionFor(mode: LibrarySortMode): SortDirection = when (mode) {
    LibrarySortMode.NYLIG, LibrarySortMode.LAGT_TIL -> SortDirection.DESC
    else -> SortDirection.ASC
}