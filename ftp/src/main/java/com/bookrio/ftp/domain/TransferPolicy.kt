package com.bookrio.ftp.domain

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
        const val MAX_WIFI_CONCURRENCY_POWERED = 6
        const val MAX_MOBILE_CONCURRENCY = 2
        const val MAX_MOBILE_CONCURRENCY_POWERED = 4
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
 * - `Auto` is conservative on battery (2 Wi-Fi / 1 mobile) and more aggressive
 *   while charging (4 Wi-Fi / 2 mobile), because extra lanes cost radio and TLS
 *   CPU that matter on battery but are cheap on external power.
 * - A user override is clamped to a safe per-transport maximum, which is also
 *   higher while charging.
 * - Concurrency is only ever reduced (never raised) while errors are frequent.
 */
object TransferPolicyResolver {

    /** Auto default for a transport. */
    fun autoConcurrency(transport: TransportType, powered: Boolean = false): Int = when (transport) {
        TransportType.WIFI -> if (powered) 4 else 2
        TransportType.MOBILE -> if (powered) 2 else 1
        TransportType.OTHER -> if (powered) 4 else 2
    }

    fun maxConcurrency(transport: TransportType, powered: Boolean = false): Int = when (transport) {
        TransportType.WIFI ->
            if (powered) TransferPolicy.MAX_WIFI_CONCURRENCY_POWERED else TransferPolicy.MAX_WIFI_CONCURRENCY
        TransportType.MOBILE ->
            if (powered) TransferPolicy.MAX_MOBILE_CONCURRENCY_POWERED else TransferPolicy.MAX_MOBILE_CONCURRENCY
        TransportType.OTHER ->
            if (powered) TransferPolicy.MAX_WIFI_CONCURRENCY_POWERED else TransferPolicy.MAX_WIFI_CONCURRENCY
    }

    /**
     * @param userOverride 0 = Auto, otherwise the user's pinned lane count.
     * @param degraded true when recent operations failed often; halves the lanes
     *        (but never below 1).
     * @param powered true while the device is charging.
     */
    fun resolve(
        transport: TransportType,
        userOverride: Int = 0,
        degraded: Boolean = false,
        bufferSize: Int = TransferPolicy.DEFAULT_BUFFER,
        powered: Boolean = false
    ): TransferPolicy {
        val base = if (userOverride <= 0) autoConcurrency(transport, powered)
        else userOverride.coerceIn(1, maxConcurrency(transport, powered))
        val effective = if (degraded) (base / 2).coerceAtLeast(1) else base
        return TransferPolicy(effective, bufferSize)
    }
}