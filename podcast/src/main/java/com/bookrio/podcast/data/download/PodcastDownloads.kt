package com.bookrio.podcast.data.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.bookrio.data.local.dao.PodcastDownloadDao
import com.bookrio.data.local.dao.PodcastEpisodeDao
import com.bookrio.data.local.entity.PodcastDownloadEntity
import com.bookrio.data.local.entity.PodcastDownloadStatus
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline episode downloads through Android [DownloadManager].
 *
 * Shelf never requests broad storage permissions: files land in app-specific
 * `Android/data/<pkg>/files/Podcasts`. A download row is only marked
 * DOWNLOADED after the output exists and is non-empty.
 */
class PodcastDownloads(
    private val context: Context,
    private val downloadDao: PodcastDownloadDao,
    private val episodeDao: PodcastEpisodeDao
) {

    private val manager: DownloadManager? get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    /** Enqueues a DownloadManager request and records QUEUED state. */
    suspend fun enqueue(episode: PodcastEpisodeEntity): Boolean = withContext(Dispatchers.IO) {
        val dm = manager ?: return@withContext false
        val url = episode.enclosureUrl.takeIf { it.isNotBlank() } ?: return@withContext false

        val fileName = buildFileName(episode)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(episode.title)
            setMimeType(episode.enclosureMimeType ?: "audio/mpeg")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PODCASTS, fileName)
        }

        return@withContext runCatching {
            val now = System.currentTimeMillis()
            downloadDao.upsert(
                PodcastDownloadEntity(
                    episodeId = episode.id,
                    status = PodcastDownloadStatus.QUEUED,
                    requestedAt = now,
                    failureReason = null
                )
            )
            val id = dm.enqueue(request)
            downloadDao.updateStatus(episode.id, PodcastDownloadStatus.QUEUED, id, null)
            true
        }.getOrElse {
            downloadDao.updateStatus(episode.id, PodcastDownloadStatus.FAILED, null, "enqueue")
            false
        }
    }

    /**
     * Reconciles rows still marked QUEUED/DOWNLOADING against DownloadManager.
     * Called on podcast screen load; no polling loop, no wakeups.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val dm = manager ?: return@withContext
        val active = runCatching { downloadDao.getActive() }.getOrDefault(emptyList())
        for (row in active) {
            val dmId = row.downloadManagerId ?: continue
            val info = query(dm, dmId) ?: continue
            when (info.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = info.localUri
                    val file = uriToFile(uri)
                    val valid = file != null && file.exists() && file.length() > 0L
                    if (valid) {
                        downloadDao.upsert(
                            row.copy(
                                status = PodcastDownloadStatus.DOWNLOADED,
                                localUri = uri,
                                completedAt = System.currentTimeMillis(),
                                downloadedBytes = file!!.length(),
                                totalBytes = info.totalBytes.takeIf { it > 0 },
                                failureReason = null
                            )
                        )
                    } else {
                        downloadDao.upsert(
                            row.copy(
                                status = PodcastDownloadStatus.FAILED,
                                failureReason = "empty"
                            )
                        )
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    downloadDao.upsert(
                        row.copy(
                            status = PodcastDownloadStatus.FAILED,
                            failureReason = info.reason?.toString() ?: "failed"
                        )
                    )
                }
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> {
                    downloadDao.upsert(
                        row.copy(
                            status = PodcastDownloadStatus.DOWNLOADING,
                            downloadedBytes = info.bytesDownloaded.takeIf { it > 0L } ?: row.downloadedBytes,
                            totalBytes = info.totalBytes.takeIf { it > 0L } ?: row.totalBytes
                        )
                    )
                }
            }
        }
    }

    /**
     * Removes a local download. Episode metadata, feed, playback history and
     * progress are intentionally left untouched.
     */
    suspend fun remove(episodeId: Long): Boolean = withContext(Dispatchers.IO) {
        val row = downloadDao.getByEpisode(episodeId) ?: return@withContext true
        val dm = manager
        val dmId = row.downloadManagerId
        val removed = if (dm != null && dmId != null) {
            runCatching { dm.remove(dmId) > 0 }.getOrDefault(false)
        } else {
            true
        }
        // Delete the local file if we have one.
        val file = uriToFile(row.localUri)
        val fileGone = file?.let { !it.exists() || it.delete() } ?: true
        if (!removed && !fileGone) {
            downloadDao.upsert(row.copy(status = PodcastDownloadStatus.FAILED, failureReason = "remove"))
            return@withContext false
        }
        downloadDao.upsert(
            PodcastDownloadEntity(
                episodeId = episodeId,
                status = PodcastDownloadStatus.NOT_DOWNLOADED
            )
        )
        true
    }

    suspend fun retry(episodeId: Long): Boolean {
        val episode = episodeDao.getById(episodeId) ?: return false
        return enqueue(episode)
    }

    private data class Info(
        val status: Int,
        val localUri: String?,
        val totalBytes: Long,
        val bytesDownloaded: Long,
        val reason: Int?
    )

    private fun query(dm: DownloadManager, id: Long): Info? {
        return runCatching {
            dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return@use null
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val total = if (totalCol >= 0) cursor.getLong(totalCol) else -1L
                val doneCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val done = if (doneCol >= 0) cursor.getLong(doneCol) else -1L
                val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                val reason = if (reasonCol >= 0) cursor.getInt(reasonCol) else null
                Info(status, localUri, total, done, reason)
            }
        }.getOrNull()
    }

    private fun uriToFile(uri: String?): File? {
        val raw = uri?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { File(Uri.parse(raw).path ?: return null) }.getOrNull()
    }

    private fun buildFileName(episode: PodcastEpisodeEntity): String {
        val ext = guessExtension(episode.enclosureMimeType, episode.enclosureUrl)
        return "podcast_${episode.id}.$ext"
    }

    private fun guessExtension(mime: String?, url: String): String {
        val m = mime?.lowercase()
        if (m != null) {
            when {
                m.contains("mpeg") -> return "mp3"
                m.contains("mp4") || m.contains("m4a") || m.contains("aac") -> return "m4a"
                m.contains("ogg") || m.contains("opus") -> return "ogg"
                m.contains("flac") -> return "flac"
                m.contains("wav") -> return "wav"
            }
        }
        val path = url.substringBefore('?')
        val candidate = path.substringAfterLast('.', "")
        return candidate.takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } } ?: "mp3"
    }
}