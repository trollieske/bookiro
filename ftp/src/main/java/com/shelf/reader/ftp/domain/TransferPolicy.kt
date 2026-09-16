package com.shelf.reader.ftp.domain

/**
 * How the device is currently connected. Derived from ConnectivityManager at the
 * call site so this class stays pure and unit-testable.
 */
enum class TransportType { WIFI, MOBILE, OTHER }

/**
 * Resolved transfer configuration. Not magic numbers spread through the worker:
 * one place decides concurrency and buffer size.
 */
data class TransferPolicy(
    val concurrency: Int,
    val bufferSizeBytes: Int
) {
    companion object {
        const val MAX_WIFI_CONCURRENCY = 4
        const val MAX_MOBILE_CONCURRENCY = 2
        const val SAME_FILE_STREAMS = 1

        const val BUFFER_64_KIB = 64 * 1024
        const val BUFFER_256_KIB = 256 * 1024
        const val BUFFER_512_KIB = 512 * 1024

        /** 256 KiB measured as the best stable default; see architecture doc. */
        const val DEFAULT_BUFFER = BUFFER_256_KIB

        val CANDIDATE_BUFFERS = listOf(BUFFER_64_KIB, BUFFER_256_KIB, BUFFER_512_KIB)
    }
}

/**
 * Concurrency policy.
 *
 * - `Auto` starts at 2 on Wi-Fi and 1 on mobile.
 * - A user override is clamped to a safe per-transport maximum.
 * - Concurrency is only ever reduced (never raised) while errors are frequent.
 */
object TransferPolicyResolver {

    /** Auto default for a transport. */
    fun autoConcurrency(transport: TransportType): Int = when (transport) {
        TransportType.WIFI -> 2
        TransportType.MOBILE -> 1
        TransportType.OTHER -> 2
    }

    fun maxConcurrency(transport: TransportType): Int = when (transport) {
        TransportType.WIFI -> TransferPolicy.MAX_WIFI_CONCURRENCY
        TransportType.MOBILE -> TransferPolicy.MAX_MOBILE_CONCURRENCY
        TransportType.OTHER -> TransferPolicy.MAX_WIFI_CONCURRENCY
    }

    /**
     * @param userOverride 0 = Auto, otherwise the user's pinned lane count.
     * @param degraded true when recent operations failed often; halves the lanes
     *        (but never below 1).
     */
    fun resolve(
        transport: TransportType,
        userOverride: Int = 0,
        degraded: Boolean = false,
        bufferSize: Int = TransferPolicy.DEFAULT_BUFFER
    ): TransferPolicy {
        val base = if (userOverride <= 0) autoConcurrency(transport)
        else userOverride.coerceIn(1, maxConcurrency(transport))
        val effective = if (degraded) (base / 2).coerceAtLeast(1) else base
        return TransferPolicy(effective, bufferSize)
    }
}