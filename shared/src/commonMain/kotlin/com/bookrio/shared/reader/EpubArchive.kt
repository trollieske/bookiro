package com.bookrio.shared.reader

/**
 * Raw DEFLATE decompression (zlib `inflateInit2_` with `windowBits = -15`, i.e. no
 * zlib header/trailer) used by [EpubZip] for ZIP entries with compression method 8.
 *
 * It is an `expect` because the actual needs the platform zlib; the common ZIP code
 * never touches a platform API directly.
 */
internal expect fun inflateRaw(data: ByteArray): ByteArray

/**
 * Minimal read-only ZIP reader tailored to EPUB containers. It parses the End Of
 * Central Directory and the central directory records itself (little endian), then
 * streams single entries out of the local headers. Compression methods 0 (stored)
 * and 8 (deflate, via [inflateRaw]) are supported; anything else (or any structure
 * that looks malformed, including ZIP64) makes [open]/[read] return null instead of
 * throwing — a broken EPUB must never take the reader down.
 */
internal class EpubZip private constructor(
    private val data: ByteArray,
    private val entries: Map<String, Entry>,
) {

    class Entry(
        val name: String,
        val compressionMethod: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int,
    )

    val entryNames: Set<String> get() = entries.keys

    /** Reads and decompresses the entry with exactly this path, or null. */
    fun read(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        val local = entry.localHeaderOffset
        if (local < 0 || local + LOCAL_HEADER_SIZE > data.size) return null
        if (readU32(data, local) != LOCAL_HEADER_SIGNATURE) return null
        val nameLength = readU16(data, local + 26) ?: return null
        val extraLength = readU16(data, local + 28) ?: return null
        val start = local + LOCAL_HEADER_SIZE + nameLength + extraLength
        val end = start + entry.compressedSize
        if (start < 0 || start > data.size || end < start || end > data.size) return null
        val raw = data.copyOfRange(start, end)
        return when (entry.compressionMethod) {
            METHOD_STORED -> raw
            METHOD_DEFLATE -> runCatching { inflateRaw(raw) }.getOrNull()
            else -> null
        }
    }

    companion object {

        fun open(bytes: ByteArray): EpubZip? {
            if (bytes.size < EOCD_MIN_SIZE) return null
            val eocd = findEndOfCentralDirectory(bytes) ?: return null
            val entryCount = readU16(bytes, eocd + 10) ?: return null
            val directorySize = readU32(bytes, eocd + 12) ?: return null
            val directoryOffset = readU32(bytes, eocd + 16) ?: return null
            // ZIP64 entries (0xFFFF/0xFFFFFFFF markers) are not used by EPUBs in
            // practice; bail out instead of guessing.
            if (entryCount <= 0 || entryCount == MAX_U16 ||
                directorySize == MAX_U32 || directoryOffset == MAX_U32
            ) {
                return null
            }
            if (directoryOffset > Int.MAX_VALUE.toLong() ||
                directoryOffset + directorySize > bytes.size.toLong()
            ) {
                return null
            }

            val entries = HashMap<String, Entry>(entryCount.coerceAtLeast(4))
            var position = directoryOffset.toInt()
            var index = 0
            while (index < entryCount) {
                if (readU32(bytes, position) != CENTRAL_DIRECTORY_SIGNATURE) break
                val method = readU16(bytes, position + 10) ?: break
                val compressedSize = readU32(bytes, position + 20) ?: break
                val uncompressedSize = readU32(bytes, position + 24) ?: break
                val nameLength = readU16(bytes, position + 28) ?: break
                val extraLength = readU16(bytes, position + 30) ?: break
                val commentLength = readU16(bytes, position + 32) ?: break
                val localOffset = readU32(bytes, position + 42) ?: break
                val nameStart = position + CENTRAL_DIRECTORY_FIXED_SIZE
                val nameEnd = nameStart + nameLength
                if (nameStart < 0 || nameEnd > bytes.size) break
                // EPUB requires UTF-8 entry names; treat everything as UTF-8.
                val name = bytes.copyOfRange(nameStart, nameEnd).decodeToString()
                if (compressedSize <= Int.MAX_VALUE.toLong() &&
                    uncompressedSize <= Int.MAX_VALUE.toLong() &&
                    localOffset <= Int.MAX_VALUE.toLong()
                ) {
                    entries[name] = Entry(
                        name = name,
                        compressionMethod = method,
                        compressedSize = compressedSize.toInt(),
                        uncompressedSize = uncompressedSize.toInt(),
                        localHeaderOffset = localOffset.toInt(),
                    )
                }
                position = nameEnd + extraLength + commentLength
                index++
            }
            if (entries.isEmpty()) return null
            return EpubZip(bytes, entries)
        }

        private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
            val lowest = (bytes.size - EOCD_MIN_SIZE - MAX_COMMENT_SIZE).coerceAtLeast(0)
            var position = bytes.size - EOCD_MIN_SIZE
            while (position >= lowest) {
                if (readU32(bytes, position) == EOCD_SIGNATURE) return position
                position--
            }
            return null
        }
    }
}

private fun readU16(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset + 2 > bytes.size) return null
    return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

private fun readU32(bytes: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset + 4 > bytes.size) return null
    return (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

private const val EOCD_SIGNATURE = 0x06054b50L
private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
private const val EOCD_MIN_SIZE = 22
private const val MAX_COMMENT_SIZE = 0xFFFF
private const val LOCAL_HEADER_SIZE = 30
private const val CENTRAL_DIRECTORY_FIXED_SIZE = 46
private const val METHOD_STORED = 0
private const val METHOD_DEFLATE = 8
private const val MAX_U16 = 0xFFFF
private const val MAX_U32 = 0xFFFFFFFFL