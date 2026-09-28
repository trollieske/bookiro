package com.bookrio.calibre.data

import android.content.Context
import com.bookrio.data.crypto.KeystoreSecretCipher
import com.bookrio.data.local.ShelfDatabase
import java.io.File

/** Small explicit dependency graph for the Calibre module. */
class CalibreGraph private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = ShelfDatabase.getInstance(appContext)

    val sourceRepository: CalibreSourceRepository = CalibreSourceRepository(
        serverDao = db.calibreServerDao(),
        taskDao = db.downloadTaskDao(),
        cipher = KeystoreSecretCipher(KeystoreSecretCipher.CALIBRE_ALIAS)
    )

    val transferRepository: CalibreTransferRepository = CalibreTransferRepository(
        taskDao = db.downloadTaskDao(),
        downloadRoot = File(appContext.filesDir, "calibre").apply { mkdirs() }
    )

    companion object {
        @Volatile private var instance: CalibreGraph? = null

        fun get(context: Context): CalibreGraph =
            instance ?: synchronized(this) {
                instance ?: CalibreGraph(context).also { instance = it }
            }
    }
}