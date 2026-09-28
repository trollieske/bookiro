package com.bookrio.torrent.engine

/** Coarse tracker state shown in the Connection details view. */
enum class TrackerState {
    ANNOUNCING,
    OK,
    WARNING,
    AUTH_REJECTED,
    POLICY_REJECTED,
    TLS_ERROR,
    TIMEOUT,
    DNS_ERROR,
    ERROR
}

/** Sanitized per-tracker diagnostic. Never contains a passkey or auth token. */
data class TrackerDiagnostic(
    val url: String,
    val state: TrackerState,
    val reason: String? = null,
    val peers: Int? = null,
    val lastAttemptAt: Long? = null,
    val tier: Int? = null,
    val verified: Boolean? = null
)

/**
 * Pure, unit-testable tracker diagnostics helpers.
 *
 * The URL is always masked before it ever leaves this class: userinfo, and the
 * common secret query parameters (`passkey`, `key`, `token`, `authkey`, …) are
 * replaced with `***`. Tracker error messages are classified into a small set
 * of user-meaningful states and stripped of anything that looks like a secret.
 */
object TrackerDiagnostics {

    private val SECRET_PARAMS = Regex(
        "(?i)(passkey|key|token|authkey|apikey|secret|passphrase)=([^&\\s]+)"
    )
    private val USERINFO = Regex("://[^/@\\s]+@")

    fun maskUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        var masked = url.replace(USERINFO, "://")
        masked = SECRET_PARAMS.replace(masked, "$1=***")
        return masked
    }

    /** Removes URL-looking content and secret parameters from an error message. */
    fun sanitizeReason(message: String?): String? {
        if (message.isNullOrBlank()) return null
        var clean = message.replace(Regex("(?:https?|udp|wss?)://\\S+"), "<tracker>")
        clean = SECRET_PARAMS.replace(clean, "$1=***")
        return clean.trim().take(200).ifBlank { null }
    }

    fun classify(message: String?): TrackerState {
        val text = message?.lowercase() ?: return TrackerState.ERROR
        return when {
            text.contains("not authorized") ||
                text.contains("unauthorized") ||
                text.contains("unregistered") ||
                text.contains("invalid passkey") ||
                text.contains("passkey") ||
                text.contains("401") ||
                text.contains("403") -> TrackerState.AUTH_REJECTED

            text.contains("not allowed") ||
                text.contains("not permitted") ||
                text.contains("banned") ||
                text.contains("client") && text.contains("reject") ||
                text.contains("whitelist") ||
                text.contains("blacklist") -> TrackerState.POLICY_REJECTED

            text.contains("certificate") ||
                text.contains("ssl") ||
                text.contains("tls") ||
                text.contains("cert ") -> TrackerState.TLS_ERROR

            text.contains("timed out") ||
                text.contains("timeout") -> TrackerState.TIMEOUT

            text.contains("resolve") ||
                text.contains("host not found") ||
                text.contains("name or service") ||
                text.contains("dns") -> TrackerState.DNS_ERROR

            text.contains("warning") -> TrackerState.WARNING
            else -> TrackerState.ERROR
        }
    }
}

/** User-facing seeding policy after download completes. */
enum class TorrentSeedPolicy {
    /** Stop the torrent as soon as the download is complete. */
    STOP_WHEN_DOWNLOADED,

    /** Keep seeding until the user stops or deletes it. */
    SEED_UNTIL_STOPPED,

    /** Seed only while the app process is running. */
    SEED_WHILE_ACTIVE;

    companion object {
        fun fromEntityName(name: String?): TorrentSeedPolicy? =
            name?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}