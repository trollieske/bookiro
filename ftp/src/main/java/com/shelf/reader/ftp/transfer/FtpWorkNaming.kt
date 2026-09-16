package com.shelf.reader.ftp.transfer

/**
 * Single source of truth for work names and input keys.
 *
 * Kept free of Android types so the naming/credential guarantees can be unit
 * tested on the JVM. Only the server id ever appears in WorkManager input; no
 * credential material is ever serialized.
 */
object FtpWorkNaming {
    const val INPUT_KEY_SERVER_ID = "serverId"
    private const val PREFIX = "ftp-sync-server-"
    const val PERIODIC_WORK_NAME = "ftp-periodic-sync"

    fun uniqueName(serverId: Long): String = "$PREFIX$serverId"

    /** The complete set of input keys a sync worker may ever receive. */
    val allowedInputKeys: Set<String> = setOf(INPUT_KEY_SERVER_ID)
}