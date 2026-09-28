package com.bookrio.ftp.client

enum class FtpProtocol(
    val displayName: String,
    val defaultPort: Int,
    val isSecure: Boolean
) {
    FTP("FTP", 21, false),
    FTPS_EXPLICIT("FTPS Explicit", 21, true),
    FTPS_IMPLICIT("FTPS Implicit", 990, true),
    SFTP("SFTP (SSH)", 22, true)
}
