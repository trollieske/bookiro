package com.bookrio.ftp.data

import android.content.Context
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.getInstance
import com.bookrio.ftp.client.FtpClientEngine
import com.bookrio.ftp.client.RemoteFileClientFactory
import com.bookrio.ftp.client.SshHostKeyStore
import com.bookrio.ftp.transfer.BookImportFtpImporter
import com.bookrio.ftp.transfer.FtpImporter
import com.bookrio.ftp.transfer.FtpTransferRuntime
import java.io.File

/**
 * Small manual dependency graph for the FTP module. No DI framework is used in
 * this project, so the graph is explicit and lives in one place.
 */
class FtpGraph private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = ShelfDatabase.getInstance(appContext)

    init {
        SshHostKeyStore.install(appContext)
    }

    val clientFactory: RemoteFileClientFactory = RemoteFileClientFactory {
        FtpClientEngine()
    }

    val sourceRepository: FtpSourceRepository = FtpSourceRepository(
        serverDao = db.ftpServerDao(),
        taskDao = db.downloadTaskDao(),
        cipher = AndroidKeystoreCredentialCipher(),
        clientFactory = clientFactory
    )

    val transferRepository: FtpTransferRepository = FtpTransferRepository(
        taskDao = db.downloadTaskDao(),
        downloadRootForServer = { serverId -> downloadRootFor(serverId) }
    )

    val importer: FtpImporter = BookImportFtpImporter(appContext, db)

    fun downloadRootFor(serverId: Long): File =
        File(appContext.filesDir, "ftp/server_$serverId").apply { mkdirs() }

    companion object {
        @Volatile private var instance: FtpGraph? = null

        fun get(context: Context): FtpGraph =
            instance ?: synchronized(this) {
                instance ?: FtpGraph(context).also { instance = it }
            }

        /** Shared live view of current lanes so the notification can read it. */
        val runtime: FtpTransferRuntime by lazy { FtpTransferRuntime() }
    }
}