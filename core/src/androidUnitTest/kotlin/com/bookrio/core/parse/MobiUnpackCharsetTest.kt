package com.bookrio.core.parse

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

/**
 * RegresjonsTester for MOBI→EPUB-konverteringen.
 *
 * Dekker to feil brukeren opplevde:
 *  1) MOBI-filer ble gibberish — parseMobiHeader leste firstNonBookIndex fra feil
 *     offset (48 i stedet for 80), ble 0xFFFFFFFF (-1), og tekstrekkevidden falt
 *     tilbake til ALLE poster inkludert bildeposter → JPEG-data dekomprimert og
 *     dekodet som tekst.
 *  2) Norsk æøå: innhold deklarert som CP1252 som i realiteten er gyldig UTF-8
 *     gir mojibake uten U+FFFD — strict-UTF-8-validering skal velge UTF-8.
 */
class MobiUnpackCharsetTest {

    /** Bygger en minimal, gyldig MOBI (compression=NONE) med spesifisert deklarert tegnkode. */
    private fun buildMobi(
        textBytes: ByteArray,
        declaredEncoding: Int,
        includeImageRecord: Boolean = true
    ): ByteArray {
        val fullName = "Test Bok".toByteArray(Charsets.UTF_8)
        val headerLen = 232

        // ── rec0: PalmDOC (16) + MOBI-header (232) + fullt navn ──
        val rec0 = ByteArray(16 + headerLen + fullName.size)
        // PalmDOC-header
        rec0[0] = 0x00; rec0[1] = 0x01                       // compression = NONE
        putU32(rec0, 4, textBytes.size.toLong())             // textLength
        rec0[8] = 0x00; rec0[9] = 0x01                       // text record count = 1
        putU16(rec0, 10, 4096)                               // record size
        rec0[12] = 0; rec0[13] = 0                           // encryption = none
        // MOBI-header
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(rec0, 16)
        putU32(rec0, 20, headerLen.toLong())
        putU32(rec0, 24, 2)                                  // mobiType = 2 (mobi7)
        putU32(rec0, 28, declaredEncoding.toLong())
        putU32(rec0, 32, 12345)                              // unique id
        putU32(rec0, 36, 6)                                  // file version
        for (off in 40..76 step 4) putU32(rec0, off, -1L)    // reserverte indekser = 0xFFFFFFFF
        putU32(rec0, 80, 2)                                  // First Non-book index = 2
        putU32(rec0, 84, (16 + headerLen).toLong())          // Full Name Offset
        putU32(rec0, 88, fullName.size.toLong())             // Full Name Length
        putU32(rec0, 92, 9)                                  // locale = en_US
        putU32(rec0, 104, 6)                                 // min version
        putU32(rec0, 108, 0)                                 // first image index
        putU32(rec0, 128, 0)                                 // EXTH flags = ingen EXTH
        fullName.copyInto(rec0, 16 + headerLen)

        // ── Records ──
        val records = mutableListOf(rec0, textBytes)
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                ByteArray(512) { it.toByte() }
        if (includeImageRecord) records.add(fakeJpeg)

        // ── PalmDB-container ──
        val out = ByteArrayOutputStream()
        val header = ByteArray(78)
        "TestBok".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        "BOOK".toByteArray(Charsets.US_ASCII).copyInto(header, 60)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(header, 64)
        putU16(header, 76, records.size)
        out.write(header)
        var offset = 78 + records.size * 8 + 2
        records.forEachIndexed { i, r ->
            putU32(out, offset.toLong())
            out.write(byteArrayOf(0, (i shr 16 and 0xFF).toByte(), (i shr 8 and 0xFF).toByte(), (i and 0xFF).toByte()))
            offset += r.size
        }
        out.write(byteArrayOf(0, 0)) // padding
        records.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v shr 24) and 0xFF).toByte()
        b[off + 1] = ((v shr 16) and 0xFF).toByte()
        b[off + 2] = ((v shr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun putU32(sink: ByteArrayOutputStream, v: Long) {
        sink.write(((v shr 24) and 0xFF).toInt())
        sink.write(((v shr 16) and 0xFF).toInt())
        sink.write(((v shr 8) and 0xFF).toInt())
        sink.write((v and 0xFF).toInt())
    }

    private fun convertedChapterText(mobi: ByteArray): String {
        val out = createTempFile()
        MobiUnpack.convertToEpub(mobi, out)
        ZipFile(out).use { zip ->
            val entry = zip.getEntry("OEBPS/chapter_1.xhtml")
                ?: error("chapter_1.xhtml mangler i konvertert EPUB")
            return zip.getInputStream(entry).bufferedReader().readText()
        }
    }

    private fun createTempFile(): java.io.File =
        java.io.File.createTempFile("mobi_test_", ".epub")

    @Test
    fun `UTF-8 innhold deklarert som CP1252 gir æøå - ikke mojibake`() {
        val norwegian = "<h1>Kapittel én</h1><p>Blåbærsyltetøy ogæøå på fjellet i åsen.</p>"
            .toByteArray(Charsets.UTF_8)
        val mobi = buildMobi(norwegian, declaredEncoding = 1252)
        val xhtml = convertedChapterText(mobi)

        assertTrue("æ mangler i konvertert tekst: ${xhtml.take(200)}", xhtml.contains("Blåbærsyltetøy"))
        assertTrue("å mangler", xhtml.contains("fjellet i åsen"))
        assertFalse("Mojibake-fortegn (Ã) — feil tegnkode", xhtml.contains("Ã"))
        assertFalse("Mojibake-fortegn (Ã¸)", xhtml.contains("Ã¸"))
    }

    @Test
    fun `UTF-8 innhold deklarert som UTF-8 gir æøå`() {
        val norwegian = "<h1>Kapittel én</h1><p>Smørbukk på Høvdingen høyde.</p>"
            .toByteArray(Charsets.UTF_8)
        val mobi = buildMobi(norwegian, declaredEncoding = 65001)
        val xhtml = convertedChapterText(mobi)
        assertTrue("ø mangler: ${xhtml.take(200)}", xhtml.contains("Smørbukk"))
        assertTrue("Høvdingen mangler", xhtml.contains("Høvdingen"))
    }

    @Test
    fun `bildeposter kommer ikke med i teksten (firstNonBookIndex-offset-regresjon)`() {
        val text = "<h1>Kapittel én</h1><p>Ekte tekst med æøå her.</p>".toByteArray(Charsets.UTF_8)
        val mobi = buildMobi(text, declaredEncoding = 65001, includeImageRecord = true)
        val xhtml = convertedChapterText(mobi)

        assertFalse(
            "Bilde-binærdata lekket inn i tekst (gammel firstNonBookIndex-bug)",
            xhtml.contains("\uFFFD")
        )
        assertTrue("Ekte tekst mangler", xhtml.contains("Ekte tekst med æøå"))
        // Ingen kapittelnummer-overløp: bare ETT kapittel (bildeposten er ikke tekst)
        val zip = ZipFile(createTempFile().also { MobiUnpack.convertToEpub(mobi, it) })
        val chapterCount = zip.entries().toList().count { it.name.startsWith("OEBPS/chapter_") }
        assertTrue("Forventet 1 kapittel, fant $chapterCount", chapterCount == 1)
    }

    @Test
    fun `CP1252 innhold deklarert som CP1252 dekodes fortsatt riktig`() {
        // Ekte CP1252-innhold (ikke gyldig UTF-8: 0xE6 = æ i CP1252 er ugyldig alene i UTF-8)
        val cp1252Text = "<h1>Kapittel</h1><p>Bl\u00E6b\u00E6rsyltet\u00F8y og \u00E5se.</p>"
            .map { it.toByte() }.toByteArray()
        val mobi = buildMobi(cp1252Text, declaredEncoding = 1252)
        val xhtml = convertedChapterText(mobi)
        assertTrue("æ mangler (ekte CP1252): ${xhtml.take(200)}", xhtml.contains("Blæbærsyltetøy"))
        assertTrue("å mangler (ekte CP1252)", xhtml.contains("åse"))
    }
}
