package com.bookrio.calibre.client

enum class CalibreErrorKind {
    AUTH,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    NETWORK,
    TIMEOUT,
    PARSE,
    UNKNOWN
}

class CalibreException(
    val kind: CalibreErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)