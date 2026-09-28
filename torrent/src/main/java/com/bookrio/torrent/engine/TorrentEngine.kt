package com.bookrio.torrent.engine

import android.content.Context
import android.util.Log
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.local.entity.TorrentDownloadEntity
import com.bookrio.data.local.entity.TorrentPriorityEntity
import com.bookrio.data.local.entity.TorrentSourceTypeEntity
import com.bookrio.library.data.BookImportRepository
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.dispatchers.DispatcherProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import java.io.File
import java.security.MessageDigest

data class TorrentRuntimeStats(
    val downloadId: Long,
    val progressPercent: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val peersConnected: Int = 0,
    val seedsConnected: Int = 0,
    val etaSeconds: Long? = null,
    val status: DownloadStatusEntity = DownloadStatusEntity.RUNNING,
    val trackerStatus: String = "…",
    val trackers: List<TrackerDiagnostic> = emptyList(),
    val isPrivate: Boolean = false,
    val dhtEnabled: Boolean = true,
    val pexEnabled: Boolean = true,
    val lsdEnabled: Boolean = true,
    val errorMessage: String? = null,
    val completedFiles: List<String> = emptyList()
)

class TorrentEngine(
    private val context: Context,
    private val db: ShelfDatabase,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) {

    companion object {
        private const val TAG = "TorrentEngine"

        /** Honest, stable client identity. Never spoof another torrent client. */
        const val APP_USER_AGENT = "Vierel/1.0"
        const val APP_HANDSHAKE_VERSION = "Vierel 1.0"
        const val PEER_FINGERPRINT = "-VR1000-"
        const val LIBTORRENT_VERSION = "2.1.0"

        private const val REANNOUNCE_MIN_INTERVAL_MS = 60_000L

        @Volatile
        private var INSTANCE: TorrentEngine? = null

        fun getInstance(
            context: Context,
            db: ShelfDatabase = ShelfDatabase.getInstance(context),
            dispatchers: DispatcherProvider = DefaultDispatcherProvider
        ): TorrentEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorrentEngine(context.applicationContext, db, dispatchers).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private var running = false

    private val _activeStats = MutableStateFlow<Map<Long, TorrentRuntimeStats>>(emptyMap())
    val activeStats: StateFlow<Map<Long, TorrentRuntimeStats>> = _activeStats.asStateFlow()

    private val _totalDownloadSpeed = MutableStateFlow(0L)
    val totalDownloadSpeed: StateFlow<Long> = _totalDownloadSpeed.asStateFlow()

    private val _totalUploadSpeed = MutableStateFlow(0L)
    val totalUploadSpeed: StateFlow<Long> = _totalUploadSpeed.asStateFlow()

    private val importRepo by lazy { BookImportRepository(context, db, dispatchers) }

    // One shared SessionManager for all torrents
    @Volatile
    private var sessionManager: SessionManager? = null

    // Map from torrent infoHash string -> DB download ID
    private val hashToId = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Thread-safe map of "<infohash>|<masked tracker url>" -> sanitized diagnostic
    private val trackerDiagnostics =
        java.util.concurrent.ConcurrentHashMap<String, TrackerDiagnostic>()

    /** Rate-limits manual reannounce per download so the tracker is not spammed. */
    private val lastReannounceAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    // Hold strong reference to TorrentInfo objects to prevent GC from freeing C++ pointers
    private val torrentInfoMap = java.util.concurrent.ConcurrentHashMap<String, TorrentInfo>()

    private fun maskPasskey(url: String?): String = TrackerDiagnostics.maskUrl(url)

    private fun diagKey(hash: String?, url: String?): String? {
        if (hash.isNullOrBlank()) return null
        return "$hash|${TrackerDiagnostics.maskUrl(url)}"
    }

    private fun updateTrackerDiagnostic(hash: String?, url: String?, diagnostic: TrackerDiagnostic) {
        val key = diagKey(hash, url) ?: return
        trackerDiagnostics[key] = diagnostic
    }

    fun start() {
        if (running) return
        running = true
        scope.launch { initSession() }
        scope.launch { mainLoop() }
        scope.launch { statsTickerLoop() }
    }

    fun stop() {
        running = false
        // Stop seeding when the process is going away; Android cannot guarantee
        // background seeding without a foreground service.
        runCatching {
            sessionManager?.let { sm ->
                hashToId.keys.forEach { hash ->
                    runCatching { sm.find(Sha1Hash.parseHex(hash))?.pause() }
                }
            }
        }
        try {
            sessionManager?.stop()
        } catch (_: Exception) {}
        runCatching { scope.coroutineContext.cancelChildren() }
    }

    private suspend fun initSession() = withContext(dispatchers.io) {
        try {
            val sm = SessionManager()
            sm.addListener(object : AlertListener {
                override fun types(): IntArray? = null
                override fun alert(alert: Alert<*>) {
                    when (alert) {
                        is TrackerAnnounceAlert -> {
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            val url = alert.trackerUrl()
                            updateTrackerDiagnostic(
                                hash, url,
                                TrackerDiagnostic(
                                    url = maskPasskey(url),
                                    state = TrackerState.ANNOUNCING,
                                    lastAttemptAt = System.currentTimeMillis()
                                )
                            )
                        }
                        is TrackerReplyAlert -> {
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            val url = alert.trackerUrl()
                            updateTrackerDiagnostic(
                                hash, url,
                                TrackerDiagnostic(
                                    url = maskPasskey(url),
                                    state = TrackerState.OK,
                                    peers = alert.numPeers(),
                                    lastAttemptAt = System.currentTimeMillis()
                                )
                            )
                        }
                        is TrackerErrorAlert -> {
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            val url = alert.trackerUrl()
                            val raw = alert.errorMessage() ?: alert.message()
                            updateTrackerDiagnostic(
                                hash, url,
                                TrackerDiagnostic(
                                    url = maskPasskey(url),
                                    state = TrackerDiagnostics.classify(raw),
                                    reason = TrackerDiagnostics.sanitizeReason(raw),
                                    lastAttemptAt = System.currentTimeMillis()
                                )
                            )
                        }
                        is TrackerWarningAlert -> {
                            val hash = alert.handle()?.infoHash()?.toHex()?.uppercase()
                            val url = alert.trackerUrl()
                            val raw = alert.message()
                            updateTrackerDiagnostic(
                                hash, url,
                                TrackerDiagnostic(
                                    url = maskPasskey(url),
                                    state = TrackerState.WARNING,
                                    reason = TrackerDiagnostics.sanitizeReason(raw),
                                    lastAttemptAt = System.currentTimeMillis()
                                )
                            )
                        }
                        is AddTorrentAlert -> {
                            val handle = alert.handle()
                            if (handle != null && handle.isValid) {
                                val isPriv = runCatching { handle.torrentFile()?.isPrivate() }.getOrNull() ?: false
                                runCatching {
                                    applyPrivateTorrentFlags(handle, isPriv)
                                    applySequentialPriorityFlags(handle)
                                }
                                runCatching { handle.resume() }
                                runCatching { handle.forceReannounce() }
                            }
                        }
                        is MetadataReceivedAlert -> {
                            val handle = alert.handle()
                            if (handle != null && handle.isValid) {
                                val isPriv = runCatching { handle.torrentFile()?.isPrivate() }.getOrNull() ?: false
                                runCatching {
                                    applyPrivateTorrentFlags(handle, isPriv)
                                    applySequentialPriorityFlags(handle)
                                }
                            }
                        }
                        else -> {}
                    }
                }
            })
            sm.start()
            try { sm.startDht() } catch (_: Throwable) {}

            val sp = SettingsPack()
            // 1. Broader alert mask so we receive metadata_received, stats, progress in addition to error/tracker.
            //    0x1f = error | peer | portmap | storage | tracker
            //    0x40 = status_notification, 0x80 = progress_notification, 0x400 = dht_notification
            sp.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.alert_mask.swigValue(),
                0x1f or 0x40 or 0x80 or 0x400
            )

            // 2. Honest client identity. Never spoof another torrent client: a
            //    false fingerprint is a tracker-policy violation and makes
            //    tracker diagnostics impossible to trust.
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.anonymous_mode.swigValue(), false)
            // HTTPS tracker certificate validation MUST stay enabled in release.
            // A tracker with an untrusted certificate is shown as a sanitized
            // diagnosis instead of being silently accepted.
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.validate_https_trackers.swigValue(), true)
            // Do not force announce fan-out; let libtorrent follow the torrent's
            // own tracker tiers so a private infohash is not announced widely.
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), false)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), false)

            // 2b. Peer-wire encryption stays enabled for compatibility; this is
            //     not a security bypass.
            sp.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.in_enc_policy.swigValue(),
                1  // pe_settings::enc_policy::enabled - accept both encrypted and plaintext
            )
            sp.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.out_enc_policy.swigValue(),
                1  // pe_settings::enc_policy::enabled - try encrypted first, accept plaintext
            )
            sp.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.allowed_enc_level.swigValue(),
                3  // pe_settings::enc_level::both - allow both plaintext and RC4
            )
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.prefer_rc4.swigValue(), true)

            // 3. Honest, stable, versioned identity (see the overhaul brief).
            sp.setString(
                org.libtorrent4j.swig.settings_pack.string_types.user_agent.swigValue(),
                "$APP_USER_AGENT libtorrent/$LIBTORRENT_VERSION"
            )
            sp.setString(
                org.libtorrent4j.swig.settings_pack.string_types.peer_fingerprint.swigValue(),
                PEER_FINGERPRINT
            )
            sp.setString(
                org.libtorrent4j.swig.settings_pack.string_types.handshake_client_version.swigValue(),
                APP_HANDSHAKE_VERSION
            )

            // 4. Listening port (avoid ISP/router default port blocks)
            sp.setString(org.libtorrent4j.swig.settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:62473,[::]:62473")

            // 5. Session-wide DHT, LSD, UPnP, NAT-PMP (per-torrent disable for private torrents below)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(), true)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true)
            sp.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true)

            // 6. Max active connections
            sp.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_downloads.swigValue(), 20)
            sp.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_seeds.swigValue(), 20)
            sp.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_limit.swigValue(), 40)

            sm.applySettings(sp)
            sessionManager = sm

            // Re-add any pending/running downloads from DB
            val pending = db.torrentDownloadDao().getAllOnce()
                .filter { (it.status == DownloadStatusEntity.PENDING || it.status == DownloadStatusEntity.RUNNING) && !it.isPaused }
            for (dl in pending) {
                val runningDl = dl.copy(status = DownloadStatusEntity.RUNNING)
                db.torrentDownloadDao().update(runningDl)
                addToSession(sm, runningDl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init libtorrent session", e)
        }
    }

    private fun extractAnnounceUrlsFromBencode(bytes: ByteArray): List<String> {
        val urls = mutableListOf<String>()
        try {
            val str = String(bytes, Charsets.ISO_8859_1)
            var pos = 0
            while (pos < str.length) {
                val announceIdx = str.indexOf("announce", pos)
                if (announceIdx == -1) break
                val colonIdx = str.indexOf(':', announceIdx + 8)
                if (colonIdx != -1 && colonIdx - (announceIdx + 8) in 1..6) {
                    val lenStr = str.substring(announceIdx + 8, colonIdx)
                    val len = lenStr.toIntOrNull()
                    if (len != null && colonIdx + 1 + len <= str.length) {
                        val url = str.substring(colonIdx + 1, colonIdx + 1 + len)
                        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("udp://")) {
                            if (!urls.contains(url)) urls.add(url)
                        }
                    }
                }
                pos = announceIdx + 8
            }
        } catch (_: Exception) {}
        return urls
    }

    private suspend fun addToSession(sm: SessionManager, dl: TorrentDownloadEntity) = withContext(dispatchers.io) {
        try {
            val saveDir = if (dl.savePath.contains("emulated") || dl.savePath.contains("/storage/")) {
                defaultSaveDir()
            } else {
                File(dl.savePath)
            }
            if (!saveDir.exists()) saveDir.mkdirs()

            when (dl.sourceType) {
                TorrentSourceTypeEntity.MAGNET -> {
                    // Never inject public fallback trackers: for a private magnet
                    // that would leak the infohash before the private flag is known.
                    sm.download(dl.sourceData, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    val hash = dl.infoHash ?: extractInfoHashFromMagnet(dl.sourceData)
                    if (hash != null) hashToId[hash.uppercase()] = dl.id
                }
                TorrentSourceTypeEntity.TORRENT_FILE -> {
                    val torrentBytes = dl.sourceData.fromBase64()
                    val ti = TorrentInfo(torrentBytes)
                    val hash = ti.infoHash().toHex().uppercase()
                    torrentInfoMap[hash] = ti
                    val isPriv = runCatching { ti.isPrivate() }.getOrNull() ?: false

                    try { sm.download(ti, saveDir) } catch (_: Throwable) {}
                    hashToId[hash] = dl.id
                    db.torrentDownloadDao().update(dl.copy(infoHash = hash, status = DownloadStatusEntity.RUNNING, isPaused = false))

                    val handle = try { sm.find(Sha1Hash.parseHex(hash)) } catch (_: Exception) { null }
                    if (handle != null && handle.isValid) {
                        try { applySequentialPriorityFlags(handle) } catch (_: Throwable) {}
                        try { applyPrivateTorrentFlags(handle, isPriv) } catch (_: Throwable) {}
                        try { handle.forceReannounce() } catch (_: Throwable) {}
                    }
                }
                TorrentSourceTypeEntity.INFO_HASH -> {
                    val magnet = buildMagnetFromHash(dl.sourceData, dl.displayName, dl.trackersJson)
                    sm.download(magnet, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    hashToId[dl.sourceData.uppercase()] = dl.id
                }
                else -> {
                    // HTTP_URL or unknown â€” treat as magnet/url string
                    sm.download(dl.sourceData, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    val hash = dl.infoHash
                    if (hash != null) hashToId[hash.uppercase()] = dl.id
                    Log.w(TAG, "Unknown source type ${dl.sourceType} for dl=${dl.id}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "addToSession failed for dl=${dl.id}", e)
        }
    }

    suspend fun addFromMagnet(
        magnetUri: String,
        saveDir: File = defaultSaveDir(),
        autoImport: Boolean = true,
        priority: TorrentPriorityEntity = TorrentPriorityEntity.NORMAL
    ): Long = withContext(dispatchers.io) {
        val clean = magnetUri.trim()
        val infoHash = extractInfoHashFromMagnet(clean)
        val existing = infoHash?.let { db.torrentDownloadDao().getByInfoHash(it) }
        if (existing != null) {
            val updated = existing.copy(
                sourceData = clean,
                status = DownloadStatusEntity.RUNNING,
                isPaused = false,
                errorMessage = null
            )
            db.torrentDownloadDao().update(updated)
            sessionManager?.let { addToSession(it, updated) }
            return@withContext existing.id
        }

        val displayName = extractNameFromMagnet(clean) ?: "Magnet: ${infoHash?.take(8) ?: "ukjent"}"

        val entity = TorrentDownloadEntity(
            sourceType = TorrentSourceTypeEntity.MAGNET,
            sourceData = clean,
            infoHash = infoHash,
            displayName = displayName,
            savePath = saveDir.absolutePath,
            status = DownloadStatusEntity.RUNNING,
            priority = priority,
            autoImport = autoImport,
            isSequential = true,
            isFirstLastPiecePriority = true,
            wifiOnly = false,
            batteryMinPercent = 20
        )
        val id = db.torrentDownloadDao().insert(entity)
        // Immediately add to session if running
        sessionManager?.let { addToSession(it, entity.copy(id = id)) }
        id
    }

    suspend fun addFromTorrentFile(
        torrentFile: File,
        saveDir: File = defaultSaveDir(),
        autoImport: Boolean = true,
        priority: TorrentPriorityEntity = TorrentPriorityEntity.NORMAL
    ): Long = withContext(dispatchers.io) {
        val bytes = torrentFile.readBytes()
        val ti = runCatching { TorrentInfo(bytes) }.getOrNull()
        val infoHash = ti?.infoHash()?.toHex()?.uppercase()
        val displayName = ti?.name()?.takeIf { it.isNotBlank() }
            ?: parseTorrentName(bytes)
            ?: torrentFile.nameWithoutExtension

        val existing = infoHash?.let { db.torrentDownloadDao().getByInfoHash(it) }
        if (existing != null) {
            val updated = existing.copy(
                sourceData = bytes.toBase64(),
                displayName = displayName,
                status = DownloadStatusEntity.RUNNING,
                isPaused = false,
                errorMessage = null
            )
            db.torrentDownloadDao().update(updated)
            sessionManager?.let { addToSession(it, updated) }
            return@withContext existing.id
        }

        val entity = TorrentDownloadEntity(
            sourceType = TorrentSourceTypeEntity.TORRENT_FILE,
            sourceData = bytes.toBase64(),
            infoHash = infoHash,
            displayName = displayName,
            savePath = saveDir.absolutePath,
            status = DownloadStatusEntity.RUNNING,
            priority = priority,
            autoImport = autoImport,
            isSequential = true,
            isFirstLastPiecePriority = true,
            wifiOnly = false,
            batteryMinPercent = 20
        )
        val id = db.torrentDownloadDao().insert(entity)
        sessionManager?.let { addToSession(it, entity.copy(id = id)) }
        id
    }

    suspend fun addFromInfoHash(
        infoHash: String,
        trackers: List<String> = emptyList(),
        displayName: String? = null,
        saveDir: File = defaultSaveDir(),
        autoImport: Boolean = true
    ): Long = withContext(dispatchers.io) {
        val existing = db.torrentDownloadDao().getByInfoHash(infoHash)
        if (existing != null) return@withContext existing.id

        val entity = TorrentDownloadEntity(
            sourceType = TorrentSourceTypeEntity.INFO_HASH,
            sourceData = infoHash,
            infoHash = infoHash,
            displayName = displayName ?: "Torrent: ${infoHash.take(8)}",
            savePath = saveDir.absolutePath,
            status = DownloadStatusEntity.PENDING,
            autoImport = autoImport,
            isSequential = true,
            trackersJson = if (trackers.isNotEmpty()) trackers.joinToString(",") else null,
            wifiOnly = true,
            batteryMinPercent = 20
        )
        val id = db.torrentDownloadDao().insert(entity)
        sessionManager?.let { addToSession(it, entity.copy(id = id)) }
        id
    }

    suspend fun startDownload(id: Long) = withContext(dispatchers.io) {
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        if (dl.status == DownloadStatusEntity.RUNNING) return@withContext
        db.torrentDownloadDao().update(
            dl.copy(status = DownloadStatusEntity.RUNNING, isPaused = false, errorMessage = null)
        )
        // Re-add to session
        sessionManager?.let { sm ->
            // Try to find existing handle first
            val hash = dl.infoHash
            if (hash != null) {
                val handle = sm.find(Sha1Hash.parseHex(hash))
                if (handle != null && handle.isValid) {
                    handle.resume()
                    return@withContext
                }
            }
            addToSession(sm, dl.copy(status = DownloadStatusEntity.RUNNING))
        }
    }

    suspend fun pauseDownload(id: Long) = withContext(dispatchers.io) {
        db.torrentDownloadDao().setPaused(id, true)
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        if (dl.status == DownloadStatusEntity.RUNNING) {
            db.torrentDownloadDao().update(dl.copy(status = DownloadStatusEntity.PAUSED, isPaused = true))
        }
        // Pause in session
        val hash = dl.infoHash ?: return@withContext
        try {
            sessionManager?.find(Sha1Hash.parseHex(hash))?.pause()
        } catch (_: Exception) {}
    }

    suspend fun resumeDownload(id: Long) = withContext(dispatchers.io) {
        db.torrentDownloadDao().setPaused(id, false)
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        if (dl.status == DownloadStatusEntity.PAUSED || dl.status == DownloadStatusEntity.PENDING) {
            db.torrentDownloadDao().update(dl.copy(status = DownloadStatusEntity.RUNNING, isPaused = false))
        }
        val hash = dl.infoHash ?: return@withContext
        try {
            sessionManager?.find(Sha1Hash.parseHex(hash))?.resume()
        } catch (_: Exception) {}
    }

    suspend fun cancelDownload(id: Long) = withContext(dispatchers.io) {
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        db.torrentDownloadDao().cancel(id)
        val hash = dl.infoHash ?: return@withContext
        try {
            val handle = sessionManager?.find(Sha1Hash.parseHex(hash))
            if (handle != null && handle.isValid) {
                sessionManager?.remove(handle)
                hashToId.remove(hash.uppercase())
            }
        } catch (_: Exception) {}
    }

    suspend fun deleteDownload(id: Long, withFiles: Boolean = false) = withContext(dispatchers.io) {
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
        cancelDownload(id)
        if (withFiles) {
            runCatching { File(dl.savePath).deleteRecursively() }
        }
        db.torrentDownloadDao().delete(dl)
    }

    suspend fun pauseAll() = withContext(dispatchers.io) { db.torrentDownloadDao().pauseAll() }
    suspend fun resumeAll() = withContext(dispatchers.io) { db.torrentDownloadDao().resumeAll() }

    /**
     * Safe manual reannounce with a minimum interval. Returns false when the
     * request was rate-limited or the torrent is not in the session.
     */
    suspend fun reannounce(id: Long): Boolean = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val last = lastReannounceAt[id] ?: 0L
        if (now - last < REANNOUNCE_MIN_INTERVAL_MS) return@withContext false
        val dl = db.torrentDownloadDao().getById(id) ?: return@withContext false
        val hash = dl.infoHash ?: return@withContext false
        val handle = runCatching { sessionManager?.find(Sha1Hash.parseHex(hash)) }.getOrNull()
            ?: return@withContext false
        if (!handle.isValid) return@withContext false
        runCatching { handle.forceReannounce() }
        lastReannounceAt[id] = now
        true
    }

    /** Persists the user's seeding policy for one torrent. */
    suspend fun setSeedPolicy(id: Long, policy: com.bookrio.data.local.entity.TorrentSeedPolicyEntity) =
        withContext(dispatchers.io) {
            val dl = db.torrentDownloadDao().getById(id) ?: return@withContext
            db.torrentDownloadDao().update(dl.copy(seedPolicy = policy))
        }

    // -------- internals --------

    private suspend fun mainLoop() {
        while (running) {
            try {
                tickMain()
            } catch (_: Exception) {}
            delay(2000L)
        }
    }

    private suspend fun statsTickerLoop() {
        while (running) {
            try {
                pollTorrentStats()
            } catch (_: Exception) {}
            delay(1000L)
        }
    }

    private suspend fun tickMain() {
        val sm = sessionManager ?: return
        val activeDls = db.torrentDownloadDao().getAllOnce()
            .filter { !it.isPaused && (it.status == DownloadStatusEntity.RUNNING || it.status == DownloadStatusEntity.PENDING) }

        for (dl in activeDls) {
            val hash = dl.infoHash
            val alreadyInSession = if (hash != null) {
                try { sm.find(Sha1Hash.parseHex(hash))?.isValid == true } catch (_: Exception) { false }
            } else false
            if (!alreadyInSession) {
                db.torrentDownloadDao().update(dl.copy(status = DownloadStatusEntity.RUNNING, isPaused = false))
                addToSession(sm, dl)
            }
        }
    }

    private suspend fun pollTorrentStats() {
        val sm = sessionManager ?: return
        val allDls = db.torrentDownloadDao().getAllOnce()
            .filter { it.status == DownloadStatusEntity.RUNNING || it.status == DownloadStatusEntity.PENDING }

        val newStats = mutableMapOf<Long, TorrentRuntimeStats>()

        for (dl in allDls) {
            val hash = dl.infoHash ?: continue
            val handle = try { sm.find(Sha1Hash.parseHex(hash)) } catch (_: Exception) { null }
            if (handle == null || !handle.isValid) continue

            if (!dl.isPaused) {
                try { handle.resume() } catch (_: Throwable) {}
            }
            val status = handle.status()
            val progress = status.progress()
            val dlSpeed = status.downloadPayloadRate().toLong()
            val ulSpeed = status.uploadPayloadRate().toLong()
            val seeds = status.numSeeds()
            val peers = status.numPeers()
            val totalBytes = handle.torrentFile()?.totalSize() ?: dl.totalSizeBytes.coerceAtLeast(1L)
            val downloaded = (progress * totalBytes).toLong()
            val eta = if (dlSpeed > 0) (totalBytes - downloaded) / dlSpeed else null

            val trackers = runCatching { handle.trackers() }.getOrNull() ?: emptyList()
            val diagnostics = trackers.map { tr ->
                val masked = maskPasskey(tr.url())
                val stored = trackerDiagnostics[diagKey(hash, tr.url())]
                (stored ?: TrackerDiagnostic(url = masked, state = TrackerState.ANNOUNCING))
                    .copy(url = masked, tier = tr.tier(), verified = tr.isVerified())
            }
            val isPriv = runCatching { handle.torrentFile()?.isPrivate() }.getOrNull() ?: false
            val primaryState = diagnostics.firstOrNull {
                it.state in setOf(
                    TrackerState.AUTH_REJECTED, TrackerState.POLICY_REJECTED,
                    TrackerState.TLS_ERROR, TrackerState.ERROR, TrackerState.DNS_ERROR,
                    TrackerState.TIMEOUT
                )
            } ?: diagnostics.firstOrNull { it.state == TrackerState.WARNING }
                ?: diagnostics.firstOrNull { it.state == TrackerState.OK }
                ?: diagnostics.firstOrNull()

            val stat = TorrentRuntimeStats(
                downloadId = dl.id,
                progressPercent = progress,
                downloadedBytes = downloaded,
                totalBytes = totalBytes,
                downloadSpeedBps = dlSpeed,
                uploadSpeedBps = ulSpeed,
                seedsConnected = seeds,
                peersConnected = peers,
                etaSeconds = eta,
                status = DownloadStatusEntity.RUNNING,
                trackerStatus = primaryState?.state?.name ?: "NONE",
                trackers = diagnostics,
                isPrivate = isPriv,
                dhtEnabled = !isPriv,
                pexEnabled = !isPriv,
                lsdEnabled = !isPriv
            )
            newStats[dl.id] = stat

            // Update DB
            db.torrentDownloadDao().update(
                dl.copy(
                    status = DownloadStatusEntity.RUNNING,
                    progressPercent = progress,
                    downloadedBytes = downloaded,
                    totalSizeBytes = totalBytes,
                    downloadSpeedBps = dlSpeed,
                    uploadSpeedBps = ulSpeed,
                    seedsConnected = seeds,
                    peersConnected = peers,
                    lastUpdatedAt = System.currentTimeMillis()
                )
            )

            // Check completion
            if (progress >= 1f) {
                onTorrentCompleted(dl, handle)
            }
        }

        _activeStats.value = newStats
        _totalDownloadSpeed.value = newStats.values.sumOf { it.downloadSpeedBps }
        _totalUploadSpeed.value = newStats.values.sumOf { it.uploadSpeedBps }
    }

    private suspend fun onTorrentCompleted(dl: TorrentDownloadEntity, handle: TorrentHandle) {
        val now = System.currentTimeMillis()
        val policy = TorrentSeedPolicy.fromEntityName(dl.seedPolicy?.name)
            ?: TorrentSeedPolicy.SEED_UNTIL_STOPPED
        val final = dl.copy(
            status = DownloadStatusEntity.COMPLETED,
            progressPercent = 1f,
            completedAt = now,
            lastUpdatedAt = now,
            downloadSpeedBps = 0L,
            isPaused = policy == TorrentSeedPolicy.STOP_WHEN_DOWNLOADED,
            seedingFinishedAt = if (policy == TorrentSeedPolicy.STOP_WHEN_DOWNLOADED) now else null
        )
        db.torrentDownloadDao().update(final)

        // Seeding follows the user's explicit policy. No handle is removed here:
        // for STOP_WHEN_DOWNLOADED it is paused, otherwise it keeps seeding until
        // the user stops it or the process ends.
        if (policy == TorrentSeedPolicy.STOP_WHEN_DOWNLOADED) {
            runCatching { handle.pause() }
        }
        if (dl.autoImport) {
            importCompleted(final)
        }
    }

    private suspend fun importCompleted(entity: TorrentDownloadEntity) {
        val dir = File(entity.savePath)
        if (!dir.exists()) return

        Log.i(TAG, "Importing from: ${dir.absolutePath}")
        val imported = importRepo.importDirectoryOrArchive(
            target = dir,
            source = ImportSourceEntity.TORRENT_DOWNLOAD
        )
        Log.i(TAG, "Imported ${imported.size} books from torrent '${entity.displayName}'")

        db.torrentDownloadDao().update(
            entity.copy(
                importedBookIdsJson = imported.joinToString(","),
                importStatus = "IMPORTED:${imported.size}"
            )
        )
    }

    fun defaultSaveDir(): File {
        return File(context.filesDir, "shelf_torrents").apply { mkdirs() }
    }

    private fun extractInfoHashFromMagnet(magnet: String): String? {
        val xt = "xt=urn:btih:"
        val idx = magnet.indexOf(xt)
        if (idx < 0) return null
        val after = magnet.substring(idx + xt.length)
        val end = after.indexOfFirst { it == '&' || it == ' ' }.let { if (it < 0) after.length else it }
        val hash = after.substring(0, end)
        return if (hash.length == 40 || hash.length == 32) hash.uppercase() else null
    }

    private fun extractNameFromMagnet(magnet: String): String? {
        val dn = "dn="
        val idx = magnet.indexOf(dn)
        if (idx < 0) return null
        val after = magnet.substring(idx + dn.length)
        val end = after.indexOfFirst { it == '&' || it == ' ' }.let { if (it < 0) after.length else it }
        return runCatching { java.net.URLDecoder.decode(after.substring(0, end), "UTF-8") }.getOrNull()
    }

    private fun buildMagnetFromHash(hash: String, name: String?, trackersJson: String?): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:$hash")
        if (!name.isNullOrBlank()) sb.append("&dn=").append(java.net.URLEncoder.encode(name, "UTF-8"))
        trackersJson?.split(",")?.forEach { tracker ->
            sb.append("&tr=").append(java.net.URLEncoder.encode(tracker.trim(), "UTF-8"))
        }
        return sb.toString()
    }

    private fun computeTorrentInfoHash(bytes: ByteArray): String? {
        return runCatching {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(bytes)
            md.digest().joinToString("") { "%02X".format(it) }
        }.getOrNull()
    }

    private fun parseTorrentName(bytes: ByteArray): String? {
        val s = bytes.decodeToString(throwOnInvalidSequence = false)
        val nameIdx = s.indexOf("4:name")
        if (nameIdx < 0) return null
        val after = s.substring(nameIdx + 6)
        val lenMatch = "^(\\d+):".toRegex().find(after) ?: return null
        val len = lenMatch.groupValues[1].toIntOrNull() ?: return null
        val start = lenMatch.range.last + 1
        return after.substring(start, (start + len).coerceAtMost(after.length))
    }

    private fun ByteArray.toBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)

    /**
     * Applies per-torrent privacy flags.
     *
     * PRIVATE TORRENTS (info_dict private=1) must not leak peer information via
     * DHT / Local Service Discovery / Peer Exchange, otherwise many private
     * trackers return zero peers or ban the client. We never inject fallback
     * trackers, so only authorized trackers are ever announced to.
     *
     * PUBLIC TORRENTS keep DHT/LSD/PEX for decentralized peer discovery.
     */
    private fun applyPrivateTorrentFlags(handle: TorrentHandle, isPrivate: Boolean) {
        runCatching {
            if (isPrivate) {
                handle.setFlags(TorrentFlags.DISABLE_DHT)
                handle.setFlags(TorrentFlags.DISABLE_LSD)
                handle.setFlags(TorrentFlags.DISABLE_PEX)
            } else {
                handle.unsetFlags(TorrentFlags.DISABLE_DHT)
                handle.unsetFlags(TorrentFlags.DISABLE_LSD)
                handle.unsetFlags(TorrentFlags.DISABLE_PEX)
            }
        }
    }

    private fun applySequentialPriorityFlags(handle: TorrentHandle) {
        runCatching { handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
    }
}



