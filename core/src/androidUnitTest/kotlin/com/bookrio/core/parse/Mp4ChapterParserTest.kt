package com.bookrio.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Tester for MP4/M4B kapittel-parsingen (chpl-atomet).
 *
 * Regresjon: hos store single-file M4B-lydbøker ligger 'moov' ofte ETTER en
 * stor 'mdat'-atom. Den gamle parseren leste kun de første ~8 MB og brakk på
 * 'mdat' — 0 kapitler. Den nye parseren går gjennom topp-nivå-atomene via
 * strøm-skip og leser kun 'moov' inn i minnet.
 */
class Mp4ChapterParserTest {

    private fun writeU32(out: ByteArray, off: Int, v: Int) {
        out[off] = (v ushr 24).toByte()
        out[off + 1] = (v ushr 16).toByte()
        out[off + 2] = (v ushr 8).toByte()
        out[off + 3] = v.toByte()
    }

    private fun atom(tag: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        writeU32(out, 0, size)
        tag.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun mvhdV0(timeScale: Int, duration: Int): ByteArray {
        val payload = ByteArray(100)
        writeU32(payload, 0, 0)     // version 0 + flags
        writeU32(payload, 4, 0)     // creation
        writeU32(payload, 8, 0)     // modification
        writeU32(payload, 12, timeScale)
        writeU32(payload, 16, duration)
        return atom("mvhd", payload)
    }

    private fun ticksPayload(starts: List<Long>, titles: List<String>, fourByteCount: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0)) // ver + flags
        if (fourByteCount) {
            out.write(byteArrayOf(0, 0, 0, titles.size.toByte()))
        } else {
            out.write(byteArrayOf(0, 0, 0, 0)) // reserved
            out.write(titles.size)
        }
        for (i in titles.indices) {
            var v = starts[i]
            for (k in 7 downTo 0) out.write(((v ushr (8 * k)) and 0xFF).toInt())
            val t = titles[i].toByteArray(Charsets.UTF_8)
            out.write(t.size)
            out.write(t)
        }
        return atom("chpl", out.toByteArray())
    }

    @Test
    fun `moov after large mdat is found and quicktime-ticks convert to ms`() {
        // KL-scenarioet: stor mdat først, moov (med chpl) ETTER — moov var utenfor
        // den gamle 8 MB-bufrgrensen. Vi bruker 9 MB mdat (gammel grense = 8 MB).
        val mdat = atom("mdat", ByteArray(9 * 1024 * 1024))
        val totalMs = 5_400_000L // 90 min
        val mvhd = mvhdV0(1000, totalMs.toInt())
        // 3 kapitler: 0, 30 min, 60 min — i 100-ns ticks (ms × 10 000)
        val chpl = ticksPayload(
            starts = listOf(0L, 18_000_000_000L, 36_000_000_000L),
            titles = listOf("Del én", "Del to", "Del tre"),
            fourByteCount = false,
        )
        val moov = atom("moov", mvhd + atom("udta", chpl))

        val mp4 = atom("ftyp", ByteArray(8)) + mdat + moov
        val (chapters, duration) = parseMp4Chapters(ByteArrayInputStream(mp4), mp4.size.toLong())

        assertEquals(3, chapters.size)
        assertEquals("Del én", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(1_800_000L, chapters[0].endMs) // 30 min
        assertEquals(1_800_000L, chapters[1].startMs)
        assertEquals(3_600_000L, chapters[1].endMs)
        assertEquals(3_600_000L, chapters[2].startMs)
        assertEquals(5_400_000L, chapters[2].endMs) // mvhd-varighet fyller siste slutt
        assertEquals(5_400_000L, duration)
    }

    @Test
    fun `ffmpeg-style four-byte chapter count is parsed`() {
        val mvhd = mvhdV0(1000, 600_000)
        val chpl = ticksPayload(
            starts = listOf(0L, 1_800_000_000L),
            titles = listOf("Kapittel én", "Kapittel to"),
            fourByteCount = true,
        )
        val moov = atom("moov", mvhd + atom("udta", chpl))
        val mp4 = atom("ftyp", ByteArray(4)) + moov
        val (chapters, _) = parseMp4Chapters(ByteArrayInputStream(mp4), mp4.size.toLong())
        assertEquals(2, chapters.size)
        assertEquals("Kapittel én", chapters[0].title)
        // 1.8e9 "ticks" > totalDurMs (600 000) → tolket som 100-ns ticks → 180 000 ms
        assertEquals(180_000L, chapters[1].startMs)
        assertEquals(600_000L, chapters[1].endMs)
    }

    @Test
    fun `millisecond-unit chapters are not rescaled`() {
        val mvhd = mvhdV0(1000, 180_000) // 3 min
        val chpl = ticksPayload(
            starts = listOf(0L, 60_000L, 120_000L),
            titles = listOf("Først", "Andre", "Tredje"),
            fourByteCount = true,
        )
        val moov = atom("moov", mvhd + atom("udta", chpl))
        val mp4 = atom("ftyp", ByteArray(4)) + moov
        val (chapters, _) = parseMp4Chapters(ByteArrayInputStream(mp4), mp4.size.toLong())
        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(60_000L, chapters[1].startMs)
        assertEquals(120_000L, chapters[2].startMs)
        assertEquals(180_000L, chapters[2].endMs)
    }

    @Test
    fun `moov before mdat still works`() {
        val mvhd = mvhdV0(1000, 180_000)
        val chpl = ticksPayload(
            starts = listOf(0L, 90_000L),
            titles = listOf("Først", "Sist"),
            fourByteCount = true,
        )
        val moov = atom("moov", mvhd + atom("udta", chpl))
        val mdat = atom("mdat", ByteArray(1024))
        val mp4 = atom("ftyp", ByteArray(4)) + moov + mdat
        val (chapters, duration) = parseMp4Chapters(ByteArrayInputStream(mp4), mp4.size.toLong())
        assertEquals(2, chapters.size)
        assertEquals(90_000L, chapters[1].startMs)
        assertEquals(180_000L, duration)
    }

    @Test
    fun `file without moov yields no chapters`() {
        val mp4 = atom("ftyp", ByteArray(4)) + atom("mdat", ByteArray(64))
        val (chapters, duration) = parseMp4Chapters(ByteArrayInputStream(mp4), mp4.size.toLong())
        assertTrue(chapters.isEmpty())
        assertNull(duration)
    }

    @Test
    fun `empty size-8 free atom before moov is skipped`() {
        // Regresjon: ekte M4B-er har ofte 'ftyp' + tom 'free' (size == 8) + 'moov'.
        // Den gamle topp-nivå-walkeren behandlet size 8 som korrupt (payload = -1),
        // aborterte før 'moov' og leste aldri 'chpl' (0 kapitler).
        val mvhd = mvhdV0(1000, 180_000)
        val chpl = ticksPayload(
            starts = listOf(0L, 90_000L),
            titles = listOf("Først", "Sist"),
            fourByteCount = false,
        )
        val moov = atom("moov", mvhd + atom("udta", chpl))
        val freeAtom = atom("free", ByteArray(0)) // size == 8, ingen payload
        val mp4 = atom("ftyp", ByteArray(4)) + freeAtom + moov + atom("mdat", ByteArray(64))
        val (chapters, duration) = parseMp4Chapters(ByteArrayInputStream(mp4), mp4.size.toLong())
        assertEquals(2, chapters.size)
        assertEquals("Først", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(90_000L, chapters[1].startMs)
        assertEquals(180_000L, duration)
    }

    @Test
    fun `audio chapter kind falls back to the uri extension`() {
        assertEquals("m4b", audioChapterExtension("Dungeon Crawler Carl: A LitRPG Adventure", "/x/Book.m4b"))
        assertEquals("mp3", audioChapterExtension("Some Title", "file:///x/Book.mp3"))
        assertEquals("m4b", audioChapterExtension("Book.m4b", null))
        assertNull(audioChapterExtension("Some Title", "content://doc/1234"))
    }
}
