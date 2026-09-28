package com.bookrio.ftp.transfer

import android.content.Context
import android.net.Uri
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.library.data.BookImportRepository
import java.io.File

/** Production [FtpImporter] backed by the existing book import pipeline. */
class BookImportFtpImporter(
    context: Context,
    private val db: ShelfDatabase = ShelfDatabase.getInstance(context)
) : FtpImporter {

    private val repo = BookImportRepository(context.applicationContext, db, DefaultDispatcherProvider)

    override suspend fun findImportedBookId(localPath: String): Long? =
        runCatching { db.bookDao().getByPath(localPath)?.id }.getOrNull()

    override suspend fun importBatch(localFiles: List<File>, serverId: Long, remotePath: String?): Long? =
        runCatching {
            repo.importUris(
                uris = localFiles.map { Uri.fromFile(it) },
                source = ImportSourceEntity.FTP_DOWNLOAD,
                serverId = serverId,
                remotePath = remotePath,
                filePathOverride = null,
                consolidate = false
            ).firstOrNull()
        }.getOrNull()

    override suspend fun consolidateFragmentedAudiobooks(): Int =
        runCatching { repo.consolidateFragmentedAudiobooks() }.getOrDefault(0)
}