package com.shelf.reader.smb.data

import android.content.Context
import com.shelf.reader.data.crypto.KeystoreSecretCipher
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.transfer.RemoteTransferRepository
import java.io.File

/** Small explicit dependency graph for the SMB module. */
class SmbGraph private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = ShelfDatabase.getInstance(appContext)

    private val legacyStore by lazy { SmbServerStore(appContext) }

    val sourceRepository: SmbSourceRepository = SmbSourceRepository(
        serverDao = db.smbServerDao(),
        taskDao = db.downloadTaskDao(),
        cipher = KeystoreSecretCipher(KeystoreSecretCipher.SMB_ALIAS)
    )

    val transferRepository: RemoteTransferRepository = RemoteTransferRepository(
        taskDao = db.downloadTaskDao(),
        kind = SmbSourceRepository.KIND,
        downloadRootForSource = { sourceId -> downloadRoot(sourceId) }
    )

    fun downloadRoot(sourceId: Long): File =
        File(appContext.filesDir, "smb/source_$sourceId").apply { mkdirs() }

    /**
     * One-shot, idempotent import of the legacy `EncryptedSharedPreferences`
     * blob into Room. Runs at graph creation; repeated runs never duplicate a
     * source because `upsert` reuses the natural key.
     */
    suspend fun migrateLegacyIfNeeded(): Int {
        val legacy = runCatching { legacyStore.servers.value }.getOrDefault(emptyList())
        return if (legacy.isEmpty()) 0 else sourceRepository.migrateLegacy(legacy)
    }

    companion object {
        @Volatile private var instance: SmbGraph? = null

        fun get(context: Context): SmbGraph =
            instance ?: synchronized(this) {
                instance ?: SmbGraph(context).also { instance = it }
            }
    }
}