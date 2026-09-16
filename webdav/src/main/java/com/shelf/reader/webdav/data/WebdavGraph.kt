package com.shelf.reader.webdav.data

import android.content.Context
import com.shelf.reader.data.crypto.KeystoreSecretCipher
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.transfer.RemoteTransferRepository
import java.io.File

/** Small explicit dependency graph for the WebDAV module. */
class WebdavGraph private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = ShelfDatabase.getInstance(appContext)

    private val legacyStore by lazy { WebdavServerStore(appContext) }

    val sourceRepository: WebdavSourceRepository = WebdavSourceRepository(
        serverDao = db.webdavServerDao(),
        taskDao = db.downloadTaskDao(),
        cipher = KeystoreSecretCipher(KeystoreSecretCipher.WEBDAV_ALIAS)
    )

    val transferRepository: RemoteTransferRepository = RemoteTransferRepository(
        taskDao = db.downloadTaskDao(),
        kind = WebdavSourceRepository.KIND,
        downloadRootForSource = { sourceId -> downloadRoot(sourceId) }
    )

    fun downloadRoot(sourceId: Long): File =
        File(appContext.filesDir, "webdav/source_$sourceId").apply { mkdirs() }

    suspend fun migrateLegacyIfNeeded(): Int {
        val legacy = runCatching { legacyStore.servers.value }.getOrDefault(emptyList())
        return if (legacy.isEmpty()) 0 else sourceRepository.migrateLegacy(legacy)
    }

    companion object {
        @Volatile private var instance: WebdavGraph? = null

        fun get(context: Context): WebdavGraph =
            instance ?: synchronized(this) {
                instance ?: WebdavGraph(context).also { instance = it }
            }
    }
}