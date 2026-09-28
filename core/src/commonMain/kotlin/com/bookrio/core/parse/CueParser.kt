package com.bookrio.core.parse

/**
 * Lettvekts CUE-sheet-parser for lydbok-kapitler (ingen Android-avhengigheter —
 * JVM-testbar).
 *
 * Støtter:
 *  - FILE "navn.mp3" MP3/WAVE (referanse til lydfilen)
 *  - TRACK nn AUDIO
 *  - TITLE "..." (før eller etter INDEX)
 *  - INDEX 01 mm:ss:ff — minutter kan overstige 60 (lange lydbøker), ff = 75 fps
 *  - CRLF/LF
 *
 * Ignorerer INDEX 00 (pregap) og REM-linjer. Returnerer kapitler sortert etter
 * starttid med sammenhengende indekser.
 */
object CueParser {

    data class CueChapter(val index: Int, val title: String, val startMs: Long)

    /** Referert lydfil fra FILE-linjen (uten anførselstegn), eller null. */
    fun referencedAudioFile(text: String): String? {
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("FILE ", ignoreCase = true) }
            ?: return null
        val first = line.indexOf('"')
        val last = line.lastIndexOf('"')
        if (first < 0 || last <= first) return null
        return line.substring(first + 1, last).trim().ifBlank { null }
    }

    fun parse(text: String): List<CueChapter> {
        data class Row(var title: String? = null, var startMs: Long? = null)

        val rows = mutableListOf<Row>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("TRACK ", ignoreCase = true) -> rows.add(Row())
                line.startsWith("TITLE ", ignoreCase = true) -> {
                    val row = rows.lastOrNull() ?: continue
                    val first = line.indexOf('"')
                    val last = line.lastIndexOf('"')
                    if (first >= 0 && last > first) {
                        row.title = line.substring(first + 1, last).trim().ifBlank { null }
                    }
                }
                line.startsWith("INDEX 01", ignoreCase = true) ||
                    line.startsWith("INDEX 1 ", ignoreCase = true) -> {
                    val row = rows.lastOrNull() ?: continue
                    val time = line.trim().split(Regex("\\s+")).getOrNull(2) ?: continue
                    row.startMs = parseCueTime(time) ?: continue
                }
                // INDEX 00 (pregap), REM, PREGAP, FLAGS osv. ignoreres
            }
        }
        return rows.asSequence()
            .filter { it.startMs != null }
            .sortedBy { it.startMs!! }
            .mapIndexed { idx, row ->
                CueChapter(
                    index = idx,
                    title = row.title?.takeIf { it.isNotBlank() } ?: "Kapittel ${idx + 1}",
                    startMs = row.startMs!!,
                )
            }
            .toList()
    }

    /**
     * «mm:ss:ff» → millisekunder. Minutter kan være > 59 (lange lydbøker).
     * ff = 75 bilder per sekund i spesifikasjonen, men virkelige verktøy skriver
     * ofte verdier over 74 (f.eks. 76, 87) — vi tolererer det (avrundingsavvik
     * på < 15 ms) i stedet for å forkaste hele kapittellisten.
     */
    fun parseCueTime(time: String): Long? {
        val parts = time.trim().split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].trim().toLongOrNull() ?: return null
        val seconds = parts[1].trim().toLongOrNull() ?: return null
        val frames = parts[2].trim().toLongOrNull() ?: return null
        if (minutes < 0 || seconds < 0 || frames < 0) return null
        return minutes * 60_000L + seconds * 1_000L + frames * 1_000L / 75L
    }
}
