package com.bookrio.shared.player

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.roundToLong

private const val MIN_SPEED = 0.25f
private const val MAX_SPEED = 3.0f
private const val SKIP_SECONDS = 15.0
private const val SKIP_MS = 15_000L
private const val MILLIS_PER_SECOND = 1000.0
private const val TIMESCALE = 1000

/**
 * The iOS side of [AudioPlayers.shared]: exactly ONE [AVPlayer] instance owned by
 * this object, so an audiobook and a podcast can never sound at the same time.
 * [play] replaces (and thereby pauses) whatever the player was doing.
 *
 * Uses AVFoundation + MediaPlayer: playback audio session, periodic time observer,
 * natural-end notification, lock-screen now-playing info and remote commands.
 * AVPlayer mutations are marshalled onto the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioPlayer : AudioPlayer {

    private val stateFlow = MutableStateFlow(AudioPlayerState())
    override val state: StateFlow<AudioPlayerState> = stateFlow.asStateFlow()

    /** The one and only AVPlayer of the whole app. */
    private val player = AVPlayer()

    private var timeObserver: Any? = null
    private var endObserver: NSObjectProtocol? = null
    private var audioSessionConfigured = false
    private var remoteCommandsConfigured = false
    private var speed = 1f

    override fun play(request: AudioRequest) {
        onMain {
            // Hand the single player over: stop/replace whatever sounded before.
            player.pause()
            detachEndObserver()

            stateFlow.value = AudioPlayerState(
                request = request,
                isPlaying = false,
                positionMs = request.startPositionMs.coerceAtLeast(0L),
                durationMs = request.durationMs?.coerceAtLeast(0L) ?: 0L,
                speed = speed,
            )

            val url = resolveUrl(request.uri)
            if (url == null || isMissingLocalFile(request.uri)) {
                player.replaceCurrentItemWithPlayerItem(null)
                fail("Cannot play ${request.uri.ifBlank { request.title }}")
                return@onMain
            }

            val item = AVPlayerItem(AVURLAsset(url, null))
            attachEndObserver(item)
            player.replaceCurrentItemWithPlayerItem(item)
            if (request.startPositionMs > 0L) {
                player.seekToTime(CMTimeMakeWithSeconds(request.startPositionMs / MILLIS_PER_SECOND, TIMESCALE))
            }

            ensureAudioSetup()
            player.play()
            player.rate = speed
            stateFlow.value = stateFlow.value.copy(isPlaying = true, ended = false, error = null)
            updateNowPlaying()
        }
    }

    override fun pause() {
        onMain { doPause() }
    }

    override fun resume() {
        onMain { doResume() }
    }

    override fun toggle() {
        onMain { if (stateFlow.value.isPlaying) doPause() else doResume() }
    }

    override fun seekTo(positionMs: Long) {
        onMain { doSeek(positionMs) }
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        this.speed = clamped
        onMain {
            stateFlow.value = stateFlow.value.copy(speed = clamped)
            if (stateFlow.value.isPlaying) player.rate = clamped
            updateNowPlaying()
        }
    }

    override fun stop() {
        onMain {
            player.pause()
            detachEndObserver()
            player.replaceCurrentItemWithPlayerItem(null)
            stateFlow.value = AudioPlayerState(speed = speed)
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        }
    }

    // ---- main-thread internals -----------------------------------------------------

    private fun doPause() {
        if (stateFlow.value.request == null) return
        player.pause()
        stateFlow.value = stateFlow.value.copy(isPlaying = false)
        updateNowPlaying()
    }

    private fun doResume() {
        if (stateFlow.value.request == null) return
        if (player.currentItem == null) return
        if (stateFlow.value.ended) {
            player.seekToTime(CMTimeMakeWithSeconds(0.0, TIMESCALE))
            stateFlow.value = stateFlow.value.copy(positionMs = 0L, ended = false)
        }
        ensureAudioSetup()
        player.play()
        player.rate = speed
        stateFlow.value = stateFlow.value.copy(isPlaying = true, ended = false)
        updateNowPlaying()
    }

    private fun doSeek(positionMs: Long) {
        if (stateFlow.value.request == null || player.currentItem == null) return
        val target = positionMs.coerceAtLeast(0L)
        player.seekToTime(CMTimeMakeWithSeconds(target / MILLIS_PER_SECOND, TIMESCALE))
        stateFlow.value = stateFlow.value.copy(positionMs = target, ended = false)
        updateNowPlaying()
    }

    private fun onTick(time: CValue<CMTime>) {
        val current = stateFlow.value
        if (current.request == null) return

        val item = player.currentItem
        if (item != null && item.status == AVPlayerItemStatusFailed) {
            player.pause()
            fail(item.error?.localizedDescription ?: "Playback failed")
            return
        }

        val seconds = CMTimeGetSeconds(time)
        if (seconds.isFinite() && seconds >= 0.0) {
            val duration = readDurationMs() ?: current.durationMs
            stateFlow.value = current.copy(
                positionMs = (seconds * MILLIS_PER_SECOND).roundToLong(),
                durationMs = duration,
            )
        }
    }

    private fun fail(message: String) {
        stateFlow.value = stateFlow.value.copy(isPlaying = false, ended = false, error = message)
        updateNowPlaying()
    }

    private fun resolveUrl(uri: String): NSURL? {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> NSURL.URLWithString(trimmed)
            lower.startsWith("file://") -> NSURL.URLWithString(trimmed)
            else -> NSURL.fileURLWithPath(trimmed)
        }
    }

    private fun isMissingLocalFile(uri: String): Boolean {
        val trimmed = uri.trim()
        return when {
            trimmed.startsWith("/") -> !NSFileManager.defaultManager.fileExistsAtPath(trimmed)
            trimmed.startsWith("file://") -> {
                val path = NSURL.URLWithString(trimmed)?.path ?: return true
                !NSFileManager.defaultManager.fileExistsAtPath(path)
            }
            else -> false
        }
    }

    private fun readDurationMs(): Long? {
        val item = player.currentItem ?: return null
        val seconds = CMTimeGetSeconds(item.duration)
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return (seconds * MILLIS_PER_SECOND).roundToLong()
    }

    private fun attachEndObserver(item: AVPlayerItem) {
        detachEndObserver()
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification,
            item,
            NSOperationQueue.mainQueue,
        ) { _ ->
            val current = stateFlow.value
            stateFlow.value = current.copy(
                isPlaying = false,
                ended = true,
                positionMs = if (current.durationMs > 0L) current.durationMs else current.positionMs,
            )
            updateNowPlaying()
        }
    }

    private fun detachEndObserver() {
        val observer = endObserver ?: return
        NSNotificationCenter.defaultCenter.removeObserver(observer)
        endObserver = null
    }

    private fun ensureAudioSetup() {
        if (!audioSessionConfigured) {
            audioSessionConfigured = true
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        }
        if (timeObserver == null) {
            timeObserver = player.addPeriodicTimeObserverForInterval(
                CMTimeMake(1, 1),
                dispatch_get_main_queue(),
            ) { time -> onTick(time) }
        }
        if (!remoteCommandsConfigured) {
            remoteCommandsConfigured = true
            configureRemoteCommands()
        }
    }

    private fun configureRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()

        center.playCommand.setEnabled(true)
        center.playCommand.addTargetWithHandler {
            onMain { doResume() }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.pauseCommand.setEnabled(true)
        center.pauseCommand.addTargetWithHandler {
            onMain { doPause() }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.togglePlayPauseCommand.setEnabled(true)
        center.togglePlayPauseCommand.addTargetWithHandler {
            onMain { if (stateFlow.value.isPlaying) doPause() else doResume() }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.changePlaybackPositionCommand.setEnabled(true)
        center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val seconds = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
            if (seconds != null && seconds.isFinite() && seconds >= 0.0) {
                onMain { doSeek((seconds * MILLIS_PER_SECOND).roundToLong()) }
            }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.skipForwardCommand.setEnabled(true)
        center.skipForwardCommand.setPreferredIntervals(listOf(SKIP_SECONDS))
        center.skipForwardCommand.addTargetWithHandler {
            onMain { doSeek(stateFlow.value.positionMs + SKIP_MS) }
            MPRemoteCommandHandlerStatusSuccess
        }

        center.skipBackwardCommand.setEnabled(true)
        center.skipBackwardCommand.setPreferredIntervals(listOf(SKIP_SECONDS))
        center.skipBackwardCommand.addTargetWithHandler {
            onMain { doSeek((stateFlow.value.positionMs - SKIP_MS).coerceAtLeast(0L)) }
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun updateNowPlaying() {
        val current = stateFlow.value
        val request = current.request
        val center = MPNowPlayingInfoCenter.defaultCenter()
        if (request == null) {
            center.nowPlayingInfo = null
            return
        }
        val durationMs = if (current.durationMs > 0L) {
            current.durationMs
        } else {
            request.durationMs?.coerceAtLeast(0L) ?: 0L
        }
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to request.title,
            MPMediaItemPropertyPlaybackDuration to durationMs / MILLIS_PER_SECOND,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to current.positionMs / MILLIS_PER_SECOND,
            MPNowPlayingInfoPropertyPlaybackRate to if (current.isPlaying) speed.toDouble() else 0.0,
        )
        request.artist?.takeIf { it.isNotBlank() }?.let { info[MPMediaItemPropertyArtist] = it }
        center.nowPlayingInfo = info
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }
}

internal actual fun createAudioPlayer(): AudioPlayer = IosAudioPlayer()