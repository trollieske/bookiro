package com.bookrio.core.domain.model

/**
 * Library sort modes for the visible Sort Rail (in rail order).
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
            HYLLE -> "Hylle"
            SERIE -> "Serie"
            FORFATTER -> "Forfatter"
            NYLIG -> "Nylig"
            TITTEL -> "Tittel"
            LAGT_TIL -> "Lagt til"
        }

    companion object {
        /** Default and fallback for invalid legacy values is always HYLLE. */
        fun from(storage: String?): LibrarySortMode =
            entries.firstOrNull { it.storage == storage } ?: HYLLE
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
