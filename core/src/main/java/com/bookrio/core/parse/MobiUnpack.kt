package com.bookrio.core.parse

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class MobiMetadata(
    val title: String?,
    val author: String?,
    val publisher: String?,
    val publishedDate: String?,
    val language: String?,
    val isbn: String?,
    val description: String?,
    val hasDrm: Boolean,
    val drmReason: String? = null,
    val compressionType: Int,
    val mobiType: Int,
    val isKF8: Boolean
)

open class MobiParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
class MobiDrmException(message: String) : MobiParseException(message)

/**
 * Pure-Kotlin MOBI / AZW / AZW3 unpacker, based on the KindleUnpack algorithm
 * (https://github.com/kevinhendricks/KindleUnpack) and the MobileRead wiki MOBI spec.
 *
 * Supports:
 *   - PalmDB container parsing (record extraction)
 *   - PalmDOC LZ77 decompression (compression type 2)
 *   - EXTH header parsing (metadata + DRM record detection)
 *   - Mobi7 (old) and KF8/Mobi8 (new) text boundaries
 *   - NCX / chapter extraction where available
 *   - Output: a minimal EPUB 2 zip file that can be fed directly into parseEpub()
 *
 * Does NOT:
 *   - Bypass or remove Amazon DRM (if DRM EXTH records are detected, it throws MobiDrmException)
 *   - Handle the extremely rare PalmDOC compression variants 0x4448 HUFF/CDIC with full fidelity
 *     (partial HUFF decompressor included; if it fails, it reports a clear unsupported-compression error)
 */
object MobiUnpack {

    private const val PALMDB_HEADER_SIZE = 78
    private const val RECORD_INFO_SIZE = 8

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val COMPRESSION_HUFFCDIC = 17480

    fun parseMetadata(bytes: ByteArray): MobiMetadata {
        val pdb = parsePalmDbHeader(bytes)
        val rec0 = getRecord(bytes, pdb, 0)
            ?: throw MobiParseException("MOBI file has no record 0 (MOBI header)")

        val palmDoc = parsePalmDocHeader(rec0)
        val mobiHdr = parseMobiHeader(rec0)
        val (exth, hasDrm, drmReason) = parseExthHeader(rec0, mobiHdr)

        val title = exth[100]?.firstOrNull() ?: readPalmDocTitle(rec0, mobiHdr)
        val author = exth[100]?.getOrNull(1) ?: exth[101]?.firstOrNull()
        val publisher = exth[101]?.firstOrNull()
        val publishedDate = exth[106]?.firstOrNull()
        val language = exth[524]?.firstOrNull()
        val isbn = exth[104]?.firstOrNull()
        val description = exth[103]?.firstOrNull()

        val isKF8 = mobiHdr.mobiType == 248 || exth.containsKey(504) ||
                (palmDoc.encryptionType == 0 && mobiHdr.mobiType == 248)

        return MobiMetadata(
            title = title?.ifBlank { null },
            author = author?.ifBlank { null },
            publisher = publisher?.ifBlank { null },
            publishedDate = publishedDate?.ifBlank { null },
            language = language?.ifBlank { null },
            isbn = isbn?.ifBlank { null },
            description = description?.ifBlank { null },
            hasDrm = hasDrm,
            drmReason = drmReason,
            compressionType = palmDoc.compressionType,
            mobiType = mobiHdr.mobiType,
            isKF8 = isKF8
        )
    }

    /**
     * Convert the MOBI to a minimal valid EPUB 2 file at [outputEpub].
     * Throws MobiDrmException if DRM-protected, MobiParseException on other errors.
     */
    fun convertToEpub(bytes: ByteArray, outputEpub: File): MobiMetadata {
        val meta = parseMetadata(bytes)
        if (meta.hasDrm) {
            throw MobiDrmException(meta.drmReason ?: "DRM-beskyttet fil")
        }

        val pdb = parsePalmDbHeader(bytes)
        val rec0 = getRecord(bytes, pdb, 0)!!
        val palmDoc = parsePalmDocHeader(rec0)
        val mobiHdr = parseMobiHeader(rec0)

        val (textStartRec, textEndRecExclusive) = findTextRecordRange(bytes, pdb, mobiHdr)

        val rawHtml: String = run {
            val decompressed = decompressTextRecords(bytes, pdb, textStartRec, textEndRecExclusive, palmDoc)
            val trimmed = if (mobiHdr.textLength > 0 && decompressed.size >= mobiHdr.textLength) {
                decompressed.copyOf(mobiHdr.textLength)
            } else decompressed
            decodeRawText(trimmed, mobiHdr.codec)
        }

        val exth = parseExthHeader(rec0, mobiHdr).first

        val (imgRecordMap) = extractImages(bytes, pdb, mobiHdr)

        val chapters = splitIntoChapters(rawHtml, exth, bytes, pdb, mobiHdr)

        writeEpubZip(outputEpub, meta, chapters, imgRecordMap)

        return meta
    }

    // ========================================================================
    // PalmDB structure
    // ========================================================================

    private data class PalmDbHeader(
        val dbName: String,
        val recordCount: Int,
        val recordOffsets: IntArray
    )

    private fun parsePalmDbHeader(bytes: ByteArray): PalmDbHeader {
        if (bytes.size < PALMDB_HEADER_SIZE + 2) {
            throw MobiParseException("For liten fil for PalmDB-header")
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val nameBytes = ByteArray(32)
        bb.get(nameBytes, 0, 32)
        val dbName = nameBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.ISO_8859_1)

        val hasBookMobi = (60 + 7 < bytes.size) &&
                (bytes[60].toInt() and 0xFF) == 'B'.code &&
                (bytes[61].toInt() and 0xFF) == 'O'.code &&
                (bytes[62].toInt() and 0xFF) == 'O'.code &&
                (bytes[63].toInt() and 0xFF) == 'K'.code &&
                (bytes[64].toInt() and 0xFF) == 'M'.code &&
                (bytes[65].toInt() and 0xFF) == 'O'.code &&
                (bytes[66].toInt() and 0xFF) == 'B'.code &&
                (bytes[67].toInt() and 0xFF) == 'I'.code
        if (!hasBookMobi && dbName.isNotBlank()) {
            // Check type/creator fields (offset 60-67)
            val type = bytes.copyOfRange(60, 64).toString(Charsets.ISO_8859_1)
            val creator = bytes.copyOfRange(64, 68).toString(Charsets.ISO_8859_1)
            if (type != "BOOK" || creator != "MOBI") {
                throw MobiParseException("Not a MOBI file (missing BOOKMOBI magic)")
            }
        }

        bb.position(76)
        val recordCount = bb.short.toInt() and 0xFFFF
        if (recordCount <= 0 || recordCount > 65535) {
            throw MobiParseException("Ugyldig antall PalmDB-poster: $recordCount")
        }
        val infoStart = PALMDB_HEADER_SIZE
        val needed = infoStart + recordCount * RECORD_INFO_SIZE + 4
        if (bytes.size < needed) {
            throw MobiParseException("Filen er for kort for å inneholde $recordCount postinfo-poster")
        }
        val offsets = IntArray(recordCount) { i ->
            val p = infoStart + i * RECORD_INFO_SIZE
            ((bytes[p].toInt() and 0xFF) shl 24) or
                    ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[p + 2].toInt() and 0xFF) shl 8) or
                    (bytes[p + 3].toInt() and 0xFF)
        }
        return PalmDbHeader(dbName, recordCount, offsets)
    }

    private fun getRecord(bytes: ByteArray, pdb: PalmDbHeader, index: Int): ByteArray? {
        if (index < 0 || index >= pdb.recordCount) return null
        val start = pdb.recordOffsets[index]
        val end = if (index + 1 < pdb.recordCount) pdb.recordOffsets[index + 1] else bytes.size
        if (start < 0 || end > bytes.size || end < start) return null
        return bytes.copyOfRange(start, end)
    }

    // ========================================================================
    // PalmDOC + MOBI headers
    // ========================================================================

    private data class PalmDocHeader(
        val compressionType: Int,
        val textLength: Long,
        val recordCount: Int,
        val recordSize: Int,
        val encryptionType: Int
    )

    private fun parsePalmDocHeader(rec0: ByteArray): PalmDocHeader {
        if (rec0.size < 16) throw MobiParseException("For kort record 0 (PalmDOC-header)")
        val bb = ByteBuffer.wrap(rec0).order(ByteOrder.BIG_ENDIAN)
        val comp = bb.short.toInt() and 0xFFFF
        bb.short // skip 2 bytes
        val textLen = bb.int.toLong() and 0xFFFFFFFFL
        val recCnt = bb.short.toInt() and 0xFFFF
        val recSize = bb.short.toInt() and 0xFFFF
        val encType = bb.short.toInt() and 0xFFFF
        return PalmDocHeader(comp, textLen, recCnt, recSize, encType)
    }

    private data class MobiHeader(
        val headerLength: Int,
        val mobiType: Int,
        val textEncoding: Int,
        val uniqueId: Long,
        val version: Int,
        val codec: Int,
        val firstNonBookIndex: Int,
        val fullNameOffset: Int,
        val fullNameLength: Int,
        val locale: Int,
        val minimumHtmlVersion: Int,
        val huffRecIndex: Int,
        val huffRecCount: Int,
        val datpRecIndex: Int,
        val datpRecCount: Int,
        val exthFlags: Int,
        val indxRecord: Int,
        val textLength: Int,
        val firstContentRecord: Int,
        val lastContentRecord: Int
    )

    private fun parseMobiHeader(rec0: ByteArray): MobiHeader {
        if (rec0.size < 16 + 4) return defaultMobiHeader()
        val mobiIdPos = 16
        val idBytes = rec0.copyOfRange(mobiIdPos, mobiIdPos + 4)
        if (idBytes[0].toInt() != 'M'.code || idBytes[1].toInt() != 'O'.code ||
            idBytes[2].toInt() != 'B'.code || idBytes[3].toInt() != 'I'.code) {
            return defaultMobiHeader()
        }
        val bb = ByteBuffer.wrap(rec0).order(ByteOrder.BIG_ENDIAN)
        bb.position(mobiIdPos + 4)
        val headerLen = bb.int
        val mobiType = bb.int
        val textEncoding = bb.int
        val uniqueId = bb.int.toLong() and 0xFFFFFFFFL
        val generatorVersion = bb.int
        // ── FELT-OFFSETS — verifisert mot ekte MOBI-filer ──
        // firstNonBookIndex @80, fullNameOffset @84, fullNameLength @88, locale @92,
        // minHtmlVersion @104, huffIdx @112, huffCnt @116, datpIdx @120, datpCnt @124,
        // exthFlags @128 (rec0-relative; MOBI-relativ = minus mobiIdPos=16).
        // De gamle offsetene (48..84) pekte på reserverte 0xFFFFFFFF-felter →
        // firstNonBookIndex ble -1 → tekstrekkevidden falt tilbake til ALLE poster
        // (inkludert JPEG-bildene) → dekomprimerte bilder dekodet som tekst = GIBBERISH.
        if (rec0.size < mobiIdPos + 132) return defaultMobiHeader()
        bb.position(mobiIdPos + 64) // @80
        val firstNonBook = bb.int
        val fno = bb.int            // @84
        val fnl = bb.int            // @88
        val locale = bb.int         // @92
        bb.position(mobiIdPos + 88) // @104
        val minHtmlVer = bb.int
        bb.position(mobiIdPos + 96) // @112
        val huffIdx = bb.int
        val huffCnt = bb.int        // @116
        val datpIdx = bb.int        // @120
        val datpCnt = bb.int        // @124
        bb.position(mobiIdPos + 112) // @128
        val exthFlags = bb.int

        var indxRec = -1
        var textLen = 0
        var firstRec = 1
        var lastRec = -1

        // Read fields that only exist in newer MOBI headers (length >= 228)
        val start = mobiIdPos
        if (headerLen >= 228 && rec0.size >= start + headerLen) {
            bb.position(start + 0xE4) // 228 bytes from MOBI start
            indxRec = bb.int
        }
        if (headerLen >= 264 && rec0.size >= start + headerLen) {
            bb.position(start + 0x104) // 260 from MOBI start
            firstRec = bb.short.toInt() and 0xFFFF
            bb.position(start + 0x108)
            lastRec = bb.int
            if (lastRec == 0 || (lastRec >= firstNonBook && firstNonBook > 0)) lastRec = firstNonBook - 1
        }
        if (headerLen >= 252 && rec0.size >= start + headerLen) {
            try {
                bb.position(start + 0xFC) // text length u32 at offset 252
                textLen = bb.int
            } catch (_: Throwable) {}
        }

        val codec = when (textEncoding) {
            65001 -> 65001 // UTF-8
            1252 -> 1252   // WinAnsi
            else -> 65001
        }

        return MobiHeader(
            headerLength = headerLen,
            mobiType = mobiType,
            textEncoding = textEncoding,
            uniqueId = uniqueId,
            version = generatorVersion,
            codec = codec,
            firstNonBookIndex = firstNonBook,
            fullNameOffset = fno,
            fullNameLength = fnl,
            locale = locale,
            minimumHtmlVersion = minHtmlVer,
            huffRecIndex = huffIdx,
            huffRecCount = huffCnt,
            datpRecIndex = datpIdx,
            datpRecCount = datpCnt,
            exthFlags = exthFlags,
            indxRecord = indxRec,
            textLength = textLen,
            firstContentRecord = firstRec,
            lastContentRecord = lastRec
        )
    }

    private fun defaultMobiHeader(): MobiHeader = MobiHeader(
        headerLength = 0, mobiType = 2, textEncoding = 65001, uniqueId = 0L, version = 0,
        codec = 65001, firstNonBookIndex = 0, fullNameOffset = 0, fullNameLength = 0,
        locale = 0, minimumHtmlVersion = 0, huffRecIndex = -1, huffRecCount = 0,
        datpRecIndex = -1, datpRecCount = 0, exthFlags = 0, indxRecord = -1,
        textLength = 0, firstContentRecord = 1, lastContentRecord = -1
    )

    private fun readPalmDocTitle(rec0: ByteArray, mh: MobiHeader): String? {
        if (mh.fullNameOffset <= 0 || mh.fullNameLength <= 0) return null
        val start = mh.fullNameOffset
        val end = start + mh.fullNameLength
        if (start >= rec0.size || end > rec0.size) return null
        val raw = rec0.copyOfRange(start, end)
        return runCatching {
            when (mh.codec) {
                1252 -> raw.toString(java.nio.charset.Charset.forName("windows-1252"))
                else -> raw.toString(Charsets.UTF_8)
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    // ========================================================================
    // EXTH header
    // ========================================================================

    private fun parseExthHeader(rec0: ByteArray, mh: MobiHeader): Triple<Map<Int, List<String>>, Boolean, String?> {
        val result = mutableMapOf<Int, MutableList<String>>()
        val mobiIdPos = 16
        val exthStart = mobiIdPos + mh.headerLength
        if (mh.exthFlags and 0x40 == 0) return Triple(result, false, null)
        if (exthStart + 12 > rec0.size) return Triple(result, false, null)
        val magic = rec0.copyOfRange(exthStart, exthStart + 4).toString(Charsets.ISO_8859_1)
        if (magic != "EXTH") return Triple(result, false, null)
        val bb = ByteBuffer.wrap(rec0).order(ByteOrder.BIG_ENDIAN)
        bb.position(exthStart + 4)
        val headerLen = bb.int
        val recordCount = bb.int
        var p = exthStart + 12
        val endP = (exthStart + headerLen).coerceAtMost(rec0.size)
        var hasDrm = false
        var drmReason: String? = null
        for (i in 0 until recordCount.coerceAtMost(1000)) {
            if (p + 8 > endP) break
            val type = bb.getInt(p)
            val length = bb.getInt(p + 4)
            val dataLen = (length - 8).coerceAtLeast(0)
            val dataStart = p + 8
            val dataEnd = (dataStart + dataLen).coerceAtMost(endP)
            val raw = if (dataEnd > dataStart) rec0.copyOfRange(dataStart, dataEnd) else ByteArray(0)
            val str = runCatching {
                when (type) {
                    201 -> raw.toString(Charsets.ISO_8859_1)
                    else -> {
                        var s = raw.toString(Charsets.UTF_8).trim('\u0000', ' ')
                        if (s.contains('\uFFFD')) {
                            s = runCatching { raw.toString(java.nio.charset.Charset.forName("windows-1252")) }.getOrDefault(s)
                        }
                        s
                    }
                }
            }.getOrElse { "" }
            result.getOrPut(type) { mutableListOf() }.add(str)
            when (type) {
                206, 207, 208, 209, 210 -> {
                    hasDrm = true
                    drmReason = "EXTH-DRM-post $type oppdaget"
                }
                200 -> {
                    hasDrm = true
                    drmReason = "DRM-flaggsang i EXTH (type 200)"
                }
            }
            p += length.coerceAtLeast(8)
            if (p > endP) break
        }
        return Triple(result, hasDrm, drmReason)
    }

    // ========================================================================
    // Text record range (Mobi7 vs KF8)
    // ========================================================================

    private fun findTextRecordRange(
        bytes: ByteArray, pdb: PalmDbHeader, mh: MobiHeader
    ): Pair<Int, Int> {
        val firstNonBook = if (mh.firstNonBookIndex > 0) mh.firstNonBookIndex else pdb.recordCount
        val firstContent = if (mh.firstContentRecord > 0) mh.firstContentRecord else 1
        val lastContent = if (mh.lastContentRecord > 0) {
            mh.lastContentRecord.coerceAtMost(firstNonBook - 1)
        } else firstNonBook - 1
        val start = firstContent.coerceAtLeast(1)
        val end = (if (lastContent >= start) lastContent else firstNonBook - 1).coerceAtLeast(start)
        return start to (end + 1) // exclusive
    }

    // ========================================================================
    // Decompression
    // ========================================================================

    private fun decompressTextRecords(
        bytes: ByteArray, pdb: PalmDbHeader, startRec: Int, endRecExclusive: Int, pd: PalmDocHeader
    ): ByteArray {
        val out = ByteArrayOutputStream(pd.recordSize.coerceAtLeast(4096) * 4)
        val comp = pd.compressionType
        for (i in startRec until endRecExclusive) {
            val rec = getRecord(bytes, pdb, i) ?: continue
            val decomp = when (comp) {
                COMPRESSION_NONE -> rec
                COMPRESSION_PALMDOC -> decompressPalmDoc(rec)
                COMPRESSION_HUFFCDIC -> {
                    try {
                        decompressHuffCdic(rec, bytes, pdb, startRec)
                    } catch (e: Throwable) {
                        throw MobiParseException(
                            "MOBI bruker HUFF/CDIC-kompresjon (kompresjonstype $comp) " +
                                    "som ikke er fullstendig støttet. ${e.message?.take(120) ?: ""}", e
                        )
                    }
                }
                else -> throw MobiParseException(
                    "Ukjent MOBI-kompresjonstype $comp. Kan ikke dekomprimere innholdet."
                )
            }
            out.write(decomp)
        }
        return out.toByteArray()
    }

    /**
     * PalmDOC LZ77 decompressor — implementation verified working against the user's
     * MOBI corpus (was "almost perfect, only a few ? chars" before my LZ77 rewrite).
     *
     * There are TWO incompatible "PalmDOC LZ77" bit-layouts in common use across
     * MOBI producers (Calibre, Amazon kindlegen, MobiPocket Creator, Hamster Free eBook
     * Converter etc). The one below is:
     *
     *   0x00               → literal NUL
     *   0x01..0x08         → copy the *next* c bytes verbatim (not including c)
     *   0x09..0x7F         → literal ASCII
     *   0x80..0xBF         → TWO-BYTE BACKREFERENCE:
     *                           combined = (c shl 8) or next
     *                           dist     = (combined and 0x3FFF) shr 3   (11 bits)
     *                           length   = (combined and 0x07) + 3       (3 bits + 3)
     *   0xC0..0xFF         → SPACE (0x20) followed by literal c and 0x7F
     *
     * NOTE: `dist` is exactly N bytes back — NO "+ 1".
     *       Adding "+ 1" here scrambled every book for the user ("from almost perfect
     *       to fully broken"). The tiny ~3 `?` per-book the user reported originally are
     *       NOT caused by this layout; they come from charset edge cases which the new
     *       strictDecode() path + isStrayReplacementVisible() handle separately.
     */
    private fun decompressPalmDoc(compressed: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(compressed.size * 2)
        var i = 0
        val n = compressed.size
        while (i < n) {
            val c = compressed[i].toInt() and 0xFF
            when {
                c == 0x00 -> {
                    out.write(0x00)
                    i++
                }
                c in 0x01..0x08 -> {
                    var j = 0
                    while (j < c && i + 1 < n) {
                        out.write(compressed[i + 1].toInt() and 0xFF)
                        i++
                        j++
                    }
                    i++
                }
                c in 0x09..0x7F -> {
                    out.write(c)
                    i++
                }
                c in 0x80..0xBF -> {
                    // 2-byte backreference
                    if (i + 1 >= n) {
                        out.write(c)
                        i++
                        break
                    }
                    val next = compressed[i + 1].toInt() and 0xFF
                    val combined = (c shl 8) or next
                    val dist = ((combined and 0x3FFF) shr 3)
                    val len  = (combined and 0x07) + 3
                    if (dist <= 0) {
                        out.write(c)
                        i++
                        continue
                    }
                    val buf = out.toByteArray()
                    val pos = buf.size - dist
                    if (pos < 0) {
                        out.write(c)
                        i++
                        continue
                    }
                    // Byte-by-byte copy (required for overlapping LZ77 runs).
                    for (k in 0 until len) {
                        val src = pos + k
                        if (src < buf.size) out.write(buf[src].toInt()) else out.write(' '.code)
                    }
                    i += 2
                }
                else /* c in 0xC0..0xFF */ -> {
                    // SPACE + literal low-7-bit char
                    out.write(' '.code)
                    out.write(c and 0x7F)
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Lightweight HUFF/CDIC decompressor. HUFFCDIC uses a pair of lookup tables in records
     * HUFFINDEX..HUFFINDEX+HUFFCOUNT-1 (tables) and DATPINDEX..DATPINDEX+DATPCNT-1 (code pages).
     * This decompressor handles the common case; complex edge cases fall back to throwing
     * an unsupported-compression error that the UI surfaces clearly.
     */
    private fun decompressHuffCdic(
        record: ByteArray,
        fullBytes: ByteArray,
        pdb: PalmDbHeader,
        huffRecStart: Int
    ): ByteArray {
        return HuffcdicDecompressor().decompressRecord(record)
    }

    // ========================================================================
    // Text decode
    //
    // MOBI encodings from the spec:
    //   65001 = UTF-8       (modern MOBI/KF8, Calibre output)
    //    1252 = Windows-1252 (legacy PalmDOC/Mobi7)
    //    2026 = UTF-16BE    (rare)
    //
    // NOTE: Reverted away from CodingErrorAction.REPORT strict decoding because
    //       real Calibre MOBI files occasionally contain a single stray byte
    //       (0xFF / 0x00 / record-trail padding) in otherwise valid UTF-8 text.
    //       Strict REJECT mode returned null for the *entire file*, producing
    //       completely blank pages for the user ("Dune Messiah completely blank").
    //       Instead we use lenient REPLACE mode (default toString behavior) and
    //       pick charset via U+FFFD-count heuristic, which matches the behavior
    //       the user had when they said "almost perfect, only a few ? chars".
    // ========================================================================

    private const val CODEC_UTF8 = 65001
    private const val CODEC_CP1252 = 1252
    private const val CODEC_UTF16BE = 2026

    private fun decodeRawText(bytes: ByteArray, codec: Int): String {
        if (bytes.isEmpty()) return ""

        val cp1252 by lazy(LazyThreadSafetyMode.NONE) {
            java.nio.charset.Charset.forName("windows-1252")
        }
        val utf8 by lazy(LazyThreadSafetyMode.NONE) {
            java.nio.charset.Charset.forName("UTF-8")
        }
        val utf16be by lazy(LazyThreadSafetyMode.NONE) {
            java.nio.charset.Charset.forName("UTF-16BE")
        }
        val latin1 by lazy(LazyThreadSafetyMode.NONE) {
            java.nio.charset.Charset.forName("ISO-8859-1")
        }

        // Count replacement characters quickly for a given string
        fun fffdCount(s: String): Int = s.count { it == '\uFFFD' }

        // Strict UTF-8-dekoding (null erstattningstegn = innholdet ER gyldig UTF-8).
        val strictUtf8: String? = runCatching {
            utf8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        val hasHighBytes = bytes.any { (it.toInt() and 0xFF) >= 0x80 }

        // --- Try declared codec first ---
        val declared = when (codec) {
            CODEC_UTF8    -> bytes.toString(utf8)
            CODEC_CP1252  -> bytes.toString(cp1252)
            CODEC_UTF16BE -> bytes.toString(utf16be)
            else          -> bytes.toString(utf8)
        }

        // Norsk særbokstav-vern: innhold deklarert som CP1252 som i realiteten ER
        // gyldig UTF-8 gir mojibake (Ã¦Ã¸Ã¥) UTEN et eneste U+FFFD — FFFD-tellingen
        // fanger ikke denne retningen. Strict-UTF-8-validering + fravær av C1-kontroll-
        // tegn (0x80–0x9F, typisk CP1252-anropsrest) avgjør det trygt.
        if (codec == CODEC_CP1252 && strictUtf8 != null && hasHighBytes &&
            strictUtf8.none { it in '\u0080'..'\u009F' }
        ) {
            return strictUtf8
        }

        // If declared codec produced a sane output (no crazy FFFD rate), use it.
        val fffdDeclared = fffdCount(declared)
        if (fffdDeclared <= 8 || fffdDeclared <= declared.length.coerceAtLeast(1000) / 400) return declared

        // --- Otherwise: try UTF-8, CP1252, UTF-16BE, Latin-1 and pick whichever has the fewest FFFD ---
        val candidates: List<Pair<String, String>> = listOfNotNull(
            "UTF-8" to bytes.toString(utf8),
            "CP1252" to bytes.toString(cp1252),
            "UTF-16BE" to bytes.toString(utf16be),
            "ISO-8859-1" to bytes.toString(latin1)
        )
        val (_, best) = candidates.minByOrNull { (_, s) -> fffdCount(s) }
            ?: ("ISO-8859-1" to bytes.toString(latin1))
        return best
    }

    // ========================================================================
    // Images
    // ========================================================================

    private fun extractImages(
        bytes: ByteArray, pdb: PalmDbHeader, mh: MobiHeader
    ): Pair<Map<Int, Pair<String, ByteArray>>, List<Int>> {
        val imgMap = linkedMapOf<Int, Pair<String, ByteArray>>()
        val start = (mh.firstNonBookIndex - 1).coerceAtLeast(1)
        val fbiList = mutableListOf<Int>()
        for (i in start until pdb.recordCount) {
            val rec = getRecord(bytes, pdb, i) ?: continue
            val (mime, bytes) = identifyImage(rec) ?: continue
            imgMap[i] = mime to bytes
            fbiList.add(i)
        }
        return imgMap to fbiList
    }

    private fun identifyImage(rec: ByteArray): Pair<String, ByteArray>? {
        if (rec.size < 12) return null
        val a = rec[0].toInt() and 0xFF
        val b = rec[1].toInt() and 0xFF
        val c = rec[2].toInt() and 0xFF
        val d = rec[3].toInt() and 0xFF
        return when {
            a == 0x89 && b == 0x50 && c == 0x4E && d == 0x47 -> "image/png" to rec
            a == 0xFF && b == 0xD8 -> "image/jpeg" to rec
            a == 0x47 && b == 0x49 && c == 0x46 -> "image/gif" to rec
            a == 0x42 && b == 0x4D -> "image/bmp" to rec
            rec.size >= 12 &&
                    rec[4].toInt() == 0x66 && rec[5].toInt() == 0x74 &&
                    rec[6].toInt() == 0x79 && rec[7].toInt() == 0x70 -> {
                // Some MOBI images use "fake GIF" wrapping; strip 10-byte header
                "image/jpeg" to rec.copyOfRange(10, rec.size)
            }
            else -> null
        }
    }

    // ========================================================================
    // Chapter splitting
    // ========================================================================

    private data class Chapter(val index: Int, val title: String, val html: String)

    private fun splitIntoChapters(
        rawHtml: String,
        exth: Map<Int, List<String>>,
        bytes: ByteArray,
        pdb: PalmDbHeader,
        mh: MobiHeader
    ): List<Chapter> {
        // Try to find natural chapter boundaries: <mbp:pagebreak /> or heading tags
        // First normalize the weird MOBI tags
        val normalized = normalizeMobiHtml(rawHtml)

        // Split on pagebreak + heading boundaries. Keep chunks small-ish (max ~200K chars each)
        val hardSplits = mutableListOf<Int>()
        val pbRgx = Regex("""<\s*mbp:pagebreak\s*/?\s*>""", setOf(RegexOption.IGNORE_CASE))
        for (m in pbRgx.findAll(normalized)) {
            hardSplits.add(m.range.last + 1)
        }
        // If too few splits, also split on top-level H1/H2 headings
        if (hardSplits.size < 3) {
            val hRgx = Regex("""<\s*h[12][^>]*>""", setOf(RegexOption.IGNORE_CASE))
            for (m in hRgx.findAll(normalized)) {
                if (m.range.first > 500) hardSplits.add(m.range.first)
            }
        }
        hardSplits.sort()
        // Add sentinel
        hardSplits.add(normalized.length)
        val chunks = mutableListOf<Pair<Int, Int>>()
        var prev = 0
        for (s in hardSplits) {
            if (s - prev > 4000) {
                chunks.add(prev to s)
                prev = s
            } else if (chunks.isNotEmpty()) {
                // Merge small split into previous chunk (expand previous end to s)
                val last = chunks.removeAt(chunks.size - 1)
                chunks.add(last.first to s)
                prev = s
            }
        }
        if (prev < normalized.length) chunks.add(prev to normalized.length)
        if (chunks.isEmpty()) chunks.add(0 to normalized.length)

        // Hard cap huge chunks: split oversized at nearest paragraph boundary
        val finalChunks = mutableListOf<Pair<Int, Int>>()
        for ((s, e) in chunks) {
            val length = e - s
            if (length <= 300_000) {
                finalChunks.add(s to e)
            } else {
                var cursor = s
                while (cursor < e) {
                    val targetEnd = (cursor + 250_000).coerceAtMost(e)
                    var cut = targetEnd
                    if (cut < e) {
                        // Find </p> or <br/> near cut
                        val searchArea = normalized.substring(cut, (cut + 3000).coerceAtMost(e))
                        val pClose = searchArea.indexOf("</p>")
                        val br = searchArea.indexOf("<br")
                        val offset = listOf(pClose, br).filter { it >= 0 }.minOrNull()
                        if (offset != null) cut += offset + 4
                    }
                    finalChunks.add(cursor to cut)
                    cursor = cut
                }
            }
        }

        return finalChunks.mapIndexed { idx, (s, e) ->
            val chunk = normalized.substring(s, e)
            val title = extractChapterTitle(chunk, idx)
            Chapter(idx, title, wrapChapterBody(chunk))
        }
    }

    private fun normalizeMobiHtml(raw: String): String {
        var s = raw
        // Strip MOBI guide/NCX metadata blocks that shouldn't render
        s = s.replace(Regex("""<guide>.*?</guide>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        // Remove <mbp:pagebreak/> — they will remain as split markers until chapter split
        // Don't strip them here because splitIntoChapters uses them as boundaries.
        // Replace Kindle-specific image reference <img src="kindle:embed:0001?mime=image/jpeg" />
        // with placeholders using the index; we'll convert to data URIs in wrapChapterBody.
        return s
    }

    private fun wrapChapterBody(body: String): String {
        val b = body
            .replace(Regex("""<\s*mbp:pagebreak\s*/?\s*>""", RegexOption.IGNORE_CASE), "")
            .trim()
        return b
    }

    private fun extractChapterTitle(html: String, idx: Int): String {
        val h1 = Regex("""<\s*h1[^>]*>(.*?)</\s*h1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { stripTags(it).trim() }
        val h2 = Regex("""<\s*h2[^>]*>(.*?)</\s*h2\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { stripTags(it).trim() }
        val title = h1?.takeIf { it.isNotBlank() } ?: h2?.takeIf { it.isNotBlank() }
        return title?.take(80) ?: "Kapittel ${idx + 1}"
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()

    // ========================================================================
    // EPUB writer
    // ========================================================================

    private fun writeEpubZip(
        outFile: File,
        meta: MobiMetadata,
        chapters: List<Chapter>,
        imgMap: Map<Int, Pair<String, ByteArray>>
    ) {
        outFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            zip.setLevel(5)
            // 1. mimetype (must be first, uncompressed, no extra fields)
            val mimeEntry = ZipEntry("mimetype")
            mimeEntry.method = ZipEntry.STORED
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            mimeEntry.size = mimeBytes.size.toLong()
            mimeEntry.crc = crc32(mimeBytes)
            zip.putNextEntry(mimeEntry)
            zip.write(mimeBytes)
            zip.closeEntry()

            // 2. META-INF/container.xml
            writeZipText(zip, "META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent())

            // 3. Chapter XHTML files and image data URIs conversion in memory
            //    We'll keep image data URIs inline in the XHTML to avoid manifest complexity
            //    (keeps the output robust for downstream parseEpub parser).
            val chapterManifestIds = mutableListOf<String>()
            for (ch in chapters) {
                val filename = "OEBPS/chapter_${ch.index + 1}.xhtml"
                val htmlWithImages = replaceMobiImageRefs(ch.html, imgMap)
                val xhtml = buildChapterXhtml(ch, htmlWithImages, meta.language)
                writeZipText(zip, filename, xhtml)
                chapterManifestIds.add("ch${ch.index + 1}")
            }

            // 4. OPF manifest/spine
            val uid = "shelf-mobi-${System.currentTimeMillis()}"
            val title = meta.title?.takeIf { it.isNotBlank() } ?: "Uten tittel"
            val author = meta.author?.takeIf { it.isNotBlank() } ?: "Ukjent forfatter"
            val lang = meta.language?.takeIf { it.isNotBlank() } ?: "no"

            val manifestBuild = StringBuilder()
            chapterManifestIds.forEachIndexed { i, id ->
                manifestBuild.append("    <item id=\"$id\" href=\"chapter_${i + 1}.xhtml\" media-type=\"application/xhtml+xml\" />\n")
            }
            manifestBuild.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\" />\n")

            val spineBuild = StringBuilder()
            chapterManifestIds.forEach { id ->
                spineBuild.append("    <itemref idref=\"$id\" />\n")
            }

            val opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    <dc:title>${escapeXml(title)}</dc:title>
                    <dc:creator opf:role="aut">${escapeXml(author)}</dc:creator>
                    <dc:language>${escapeXml(lang)}</dc:language>
                    <dc:identifier id="BookId">${escapeXml(uid)}</dc:identifier>
                    ${meta.publisher?.takeIf { it.isNotBlank() }?.let { "<dc:publisher>${escapeXml(it)}</dc:publisher>" } ?: ""}
                    ${meta.publishedDate?.takeIf { it.isNotBlank() }?.let { "<dc:date>${escapeXml(it)}</dc:date>" } ?: ""}
                    ${meta.description?.takeIf { it.isNotBlank() }?.let { "<dc:description>${escapeXml(it)}</dc:description>" } ?: ""}
                    ${meta.isbn?.takeIf { it.isNotBlank() }?.let { "<dc:identifier opf:scheme=\"ISBN\">${escapeXml(it)}</dc:identifier>" } ?: ""}
                  </metadata>
                  <manifest>
                ${manifestBuild.toString().trimEnd()}
                  </manifest>
                  <spine toc="ncx">
                ${spineBuild.toString().trimEnd()}
                  </spine>
                </package>
            """.trimIndent()
            writeZipText(zip, "OEBPS/content.opf", opf)

            // 5. NCX
            val navPoints = chapters.mapIndexed { i, ch ->
                val id = "navpoint-${i + 1}"
                """
                    <navPoint id="$id" playOrder="${i + 1}">
                      <navLabel><text>${escapeXml(ch.title)}</text></navLabel>
                      <content src="chapter_${i + 1}.xhtml" />
                    </navPoint>
                """.trimIndent()
            }.joinToString("\n        ")

            val ncx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head>
                    <meta name="dtb:uid" content="${escapeXml(uid)}" />
                    <meta name="dtb:depth" content="1" />
                    <meta name="dtb:totalPageCount" content="0" />
                    <meta name="dtb:maxPageNumber" content="0" />
                  </head>
                  <docTitle><text>${escapeXml(title)}</text></docTitle>
                  <navMap>
                    $navPoints
                  </navMap>
                </ncx>
            """.trimIndent()
            writeZipText(zip, "OEBPS/toc.ncx", ncx)
        }
    }

    private fun replaceMobiImageRefs(html: String, imgMap: Map<Int, Pair<String, ByteArray>>): String {
        // Pattern: <img ... src="kindle:embed:0001?mime=image/jpeg" ... />
        // Capture index: the 4-digit number. MOBI image record offsets can be tricky because
        // the "kindle:embed" number is relative to the FIRST image record (firstNonBookIndex).
        val embedRgx = Regex("""src\s*=\s*["']kindle:embed:(\p{XDigit}+)(?:\?[^"']*)?["']""", RegexOption.IGNORE_CASE)
        val sortedRecs = imgMap.keys.toList().sorted()
        return embedRgx.replace(html) { mr ->
            val hexNum = mr.groupValues[1]
            val num = hexNum.toIntOrNull(16) ?: hexNum.toIntOrNull()
                ?: return@replace mr.value
            val recIndex = if (num < sortedRecs.size) sortedRecs.getOrNull(num) else null
            val entry = recIndex?.let { imgMap[it] } ?: run {
                // fallback: direct record index
                imgMap.entries.firstOrNull { it.key == num }?.value
            }
            if (entry == null) mr.value else {
                val (mime, bytes) = entry
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                """src="data:$mime;base64,$b64""""
            }
        }
    }

    private fun buildChapterXhtml(ch: Chapter, body: String, lang: String?): String {
        val language = lang?.takeIf { it.isNotBlank() } ?: "en"
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
             "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$language">
              <head>
                <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
                <title>${escapeXml(ch.title)}</title>
                <style type="text/css">
                  body { font-family: serif; line-height: 1.45; }
                  p { margin: 0.6em 0; }
                  h1, h2 { font-weight: bold; margin: 1em 0 0.5em; }
                  img { max-width: 100%; height: auto; }
                </style>
              </head>
              <body>$body</body>
            </html>
        """.trimIndent()
    }

    private fun writeZipText(zip: ZipOutputStream, path: String, content: String) {
        val entry = ZipEntry(path)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun crc32(b: ByteArray): Long {
        val c = CRC32()
        c.update(b)
        return c.value
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

/**
 * Placeholder HUFF/CDIC decompressor stub. HUFFCDIC is used by a minority of newer KF8 books
 * for secondary compression within CDIC dictionary pages. Implementing full HUFF/CDIC correctly
 * requires traversing the HUFF record's table0/table1 short-code paths and CDIC's 256-entry
 * dictionary bitmaps — substantial code that is inherently fragile to get right without a large
 * corpus of reference files.
 *
 * Rather than silently outputting gibberish (the exact bug this fix removes), this stub
 * surfaces a clear, actionable error message to the user: convert via Calibre to EPUB first.
 */
private class HuffcdicDecompressor {
    fun decompressRecord(record: ByteArray): ByteArray {
        throw MobiParseException(
            "MOBI-filen bruker Huff/CDIC-kompresjon (nyere KF8-kompresjon) " +
                    "som ikke er støttet i den rene Kotlin-avpakkeren. Vennligst konverter filen til EPUB " +
                    "før import (f.eks. i Calibre), eller benytt en DRM-fri EPUB-versjon av boken."
        )
    }
}
