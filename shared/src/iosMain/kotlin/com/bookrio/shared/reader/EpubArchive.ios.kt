package com.bookrio.shared.reader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/**
 * iOS actual for [inflateRaw]: zlib's `inflateInit2_(..., windowBits = -15, ...)`
 * decodes a bare DEFLATE stream (no zlib/gzip wrapper), exactly what ZIP method 8
 * entries contain. Chunks are collected and concatenated; a truncated stream (or
 * one with a zlib error) simply yields the bytes produced so far, and the caller
 * treats missing output as an unreadable entry.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun inflateRaw(data: ByteArray): ByteArray {
    if (data.isEmpty()) return ByteArray(0)
    memScoped {
        val stream = alloc<z_stream>()
        stream.next_in = null
        stream.avail_in = 0u
        stream.next_out = null
        stream.avail_out = 0u
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        val initialized = inflateInit2_(stream.ptr, -15, ZLIB_VERSION, sizeOf<z_stream>().convert())
        check(initialized == Z_OK) { "inflateInit2_ returned $initialized" }
        try {
            val chunks = ArrayList<ByteArray>()
            var total = 0
            var done = false
            data.usePinned { pinnedInput ->
                stream.next_in = pinnedInput.addressOf(0).reinterpret<UByteVar>()
                stream.avail_in = data.size.toUInt()
                while (!done) {
                    val chunk = ByteArray(CHUNK_SIZE)
                    chunk.usePinned { pinnedOutput ->
                        stream.next_out = pinnedOutput.addressOf(0).reinterpret<UByteVar>()
                        stream.avail_out = chunk.size.toUInt()
                        val result = inflate(stream.ptr, Z_NO_FLUSH)
                        val produced = chunk.size - stream.avail_out.toInt()
                        if (produced > 0) {
                            chunks.add(chunk.copyOf(produced))
                            total += produced
                        }
                        // Stop on stream end, any zlib error, or when a full output
                        // buffer produced nothing (corrupt/truncated stream) so we can
                        // never spin forever.
                        done = result != Z_OK || produced == 0
                    }
                }
            }
            val output = ByteArray(total)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(output, offset)
                offset += chunk.size
            }
            return output
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}

private const val CHUNK_SIZE = 64 * 1024