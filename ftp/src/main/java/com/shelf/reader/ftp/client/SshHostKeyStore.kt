package com.shelf.reader.ftp.client

import android.content.Context

/**
 * Trust-on-first-use store for SFTP host key fingerprints.
 *
 * Host key fingerprints are **not** secrets, so a plain SharedPreferences file
 * is fine. Keeping them lets us reject a key that changes after the first
 * successful connection, which protects against later MITM.
 */
object SshHostKeyStore {

    private const val PREFS = "shelf_ftp_hostkeys"
    private val memory = mutableMapOf<String, String>()

    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun install(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun key(host: String, port: Int) = "$host:$port"

    /** Returns true when the fingerprint is known-and-equal or newly recorded. */
    fun verify(host: String, port: Int, fingerprint: String): Boolean {
        val k = key(host, port)
        val stored = prefs?.getString(k, null) ?: memory[k]
        return when {
            stored == null -> {
                if (prefs != null) prefs!!.edit().putString(k, fingerprint).apply() else memory[k] = fingerprint
                true
            }
            else -> stored == fingerprint
        }
    }

    fun clear(host: String, port: Int) {
        val k = key(host, port)
        memory.remove(k)
        prefs?.edit()?.remove(k)?.apply()
    }
}