package com.bookrio.data.gamification.engine

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bookrio.core.gamification.ReadingTrackerFacade
import com.bookrio.core.gamification.model.SessionSource as FacadeSessionSource
import com.bookrio.data.local.dao.ReadingRhythmDao
import com.bookrio.data.local.entity.ReadingSessionEntity
import com.bookrio.data.local.entity.SessionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class ReadingTrackerEngine(
    private val rhythmDao: ReadingRhythmDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : DefaultLifecycleObserver, ReadingTrackerFacade {

    private val _goalMetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val goalMetEvents: SharedFlow<Unit> = _goalMetEvents.asSharedFlow()

    private var currentSessionId: String? = null
    private var currentBookId: String = "unknown"
    private var currentSource: SessionSource = SessionSource.READER
    private var sessionStartEpochMs: Long = 0L

    private var lastUserInteractionUptimeMs: Long = 0L
    private var accumulatedSessionSeconds: Long = 0L
    private var isAppInForeground: Boolean = true
    private var isTtsPlaying: Boolean = false

    private var tickerJob: Job? = null
    private val IDLE_THRESHOLD_MS = 60_000L

    override fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        startHeartbeat()
    }

    override fun cleanup() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        tickerJob?.cancel()
        flushPendingTime()
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
        flushPendingTime()
    }

    @Synchronized
    override fun startSession(bookId: String, source: FacadeSessionSource) {
        val entitySource = when (source) {
            FacadeSessionSource.READER -> SessionSource.READER
            FacadeSessionSource.TTS -> SessionSource.TTS
        }
        flushPendingTime()
        currentBookId = bookId
        currentSource = entitySource
        currentSessionId = UUID.randomUUID().toString()
        sessionStartEpochMs = System.currentTimeMillis()
        accumulatedSessionSeconds = 0L
        lastUserInteractionUptimeMs = SystemClock.uptimeMillis()
    }

    @Synchronized
    override fun onUserInteraction() {
        lastUserInteractionUptimeMs = SystemClock.uptimeMillis()
    }

    @Synchronized
    override fun updateTtsPlaybackState(isPlaying: Boolean) {
        isTtsPlaying = isPlaying
        if (isPlaying) {
            lastUserInteractionUptimeMs = SystemClock.uptimeMillis()
        }
    }

    @Synchronized
    override fun endSession() {
        flushPendingTime()
        val sId = currentSessionId ?: return
        val endEpoch = System.currentTimeMillis()
        val duration = accumulatedSessionSeconds

        scope.launch {
            rhythmDao.insertSession(
                ReadingSessionEntity(
                    sessionId = sId,
                    bookId = currentBookId,
                    startedAtEpochMs = sessionStartEpochMs,
                    endedAtEpochMs = endEpoch,
                    activeSeconds = duration,
                    sourceType = currentSource,
                    isCompleted = true
                )
            )
        }

        currentSessionId = null
        accumulatedSessionSeconds = 0L
    }

    private fun startHeartbeat() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                processTick()
            }
        }
    }

    @Synchronized
    private fun processTick() {
        if (currentSessionId == null) return

        val nowUptime = SystemClock.uptimeMillis()
        val isReaderActive = isAppInForeground && (nowUptime - lastUserInteractionUptimeMs <= IDLE_THRESHOLD_MS)
        val isTtsActive = isTtsPlaying

        if (isReaderActive || isTtsActive) {
            accumulatedSessionSeconds += 1L

            if (accumulatedSessionSeconds % 10L == 0L) {
                commitChunk(10L)
            }
        }
    }

    @Synchronized
    private fun flushPendingTime() {
        val remainder = accumulatedSessionSeconds % 10L
        if (remainder > 0L) {
            commitChunk(remainder)
        }
    }

    private fun commitChunk(seconds: Long) {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val epochNow = System.currentTimeMillis()

        scope.launch {
            val profile = rhythmDao.getProfile()
            val targetMinutes = profile?.dailyTargetMinutes ?: 15
            val goalNewlyReached = rhythmDao.recordActiveTime(
                date = todayStr,
                additionalSeconds = seconds,
                targetMinutes = targetMinutes,
                epochNow = epochNow
            )

            if (goalNewlyReached) {
                _goalMetEvents.emit(Unit)
            }
        }
    }
}

