package com.bookrio.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueParserTest {

    // Ekte KL-struktur (torrent-utpakket lydbok): FILE + TRACK/TITLE/INDEX 01
    private val klCue = """
        FILE "KL A History of the Nazi Concentration Camps.mp3" MP3
        TRACK 1 AUDIO
          TITLE "Chapter 01"
          INDEX 01 0:0:00
        TRACK 2 AUDIO
          TITLE "Chapter 02"
          INDEX 01 30:56:76
        TRACK 3 AUDIO
          TITLE "Chapter 03"
          INDEX 01 61:18:87
        TRACK 4 AUDIO
          TITLE "Chapter 04"
          INDEX 01 104:56:27
        TRACK 5 AUDIO
          TITLE "Chapter 05"
          INDEX 01 141:57:82
    """.trimIndent()

    @Test
    fun `parses real KL cue with minutes over 60`() {
        val chapters = CueParser.parse(klCue)
        assertEquals(5, chapters.size)
        assertEquals("Chapter 01", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        // 30:56:76 → 30 min + 56 s + 76/75 s ≈ 1 857 013 ms
        assertEquals(1_857_013L, chapters[1].startMs)
        // 61:18:87 → 61 min over minuttgrensen, 87 bilder (tolerert)
        assertEquals(61L * 60_000L + 18_000L + 87L * 1000L / 75L, chapters[2].startMs)
        assertEquals(104L * 60_000L + 56_000L + 27L * 1000L / 75L, chapters[3].startMs)
        assertEquals(141L * 60_000L + 57_000L + 82L * 1000L / 75L, chapters[4].startMs)
    }

    @Test
    fun `referenced audio file is extracted`() {
        assertEquals(
            "KL A History of the Nazi Concentration Camps.mp3",
            CueParser.referencedAudioFile(klCue),
        )
        assertNull(CueParser.referencedAudioFile("REM nothing here"))
    }

    @Test
    fun `titles may appear after INDEX and are still captured`() {
        val cue = """
            FILE "bok.mp3" WAVE
            TRACK 1 AUDIO
              INDEX 01 0:0:00
              TITLE "Første del"
            TRACK 2 AUDIO
              INDEX 01 12:34:56
              TITLE "Andre del"
        """.trimIndent()
        val chapters = CueParser.parse(cue)
        assertEquals(2, chapters.size)
        assertEquals("Første del", chapters[0].title)
        assertEquals("Andre del", chapters[1].title)
        assertEquals(12L * 60_000L + 34_000L + 56L * 1000L / 75L, chapters[1].startMs)
    }

    @Test
    fun `index 00 pregap and rem lines are ignored`() {
        val cue = """
            REM COMMENT "tagger"
            PERFORMER "Forfatter"
            TITLE "Bok"
            FILE "bok.flac" WAVE
              TRACK 01 AUDIO
                FLAGS DCP
                REM note
                INDEX 00 0:30:00
                INDEX 01 0:0:00
              TRACK 02 AUDIO
                INDEX 00 5:00:00
                INDEX 01 5:0:01
        """.trimIndent()
        val chapters = CueParser.parse(cue)
        assertEquals(2, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(300_013L, chapters[1].startMs) // 5 min + 1 frame (13 ms)
        assertEquals("Kapittel 1", chapters[0].title) // fallback-tittel
    }

    @Test
    fun `invalid or single-track cues yield at most one chapter`() {
        assertEquals(1, CueParser.parse("FILE \"a.mp3\" MP3\nTRACK 01 AUDIO\nINDEX 01 0:0:00").size)
        assertEquals(0, CueParser.parse("REM tom").size)
        assertNull(CueParser.parseCueTime("1:2"))
        // Frame 75+ er utenfor spesifikasjonen men tolereres (rundes til ms)
        assertEquals(63_000L, CueParser.parseCueTime("1:2:75"))
        assertNull(CueParser.parseCueTime("1:x:00"))
    }
}
