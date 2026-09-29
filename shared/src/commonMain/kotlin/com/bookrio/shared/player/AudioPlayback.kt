package com.bookrio.shared.player

import kotlinx.coroutines.flow.StateFlow

/**
 * Which subsystem currently owns the single audio output of the app.
 *
 * There is exactly ONE audio player per app process ([AudioPlayers.shared]); an
 * audiobook and a podcast can therefore never sound at the same time. Starting a
 * new item replaces (and pauses) whatever was playing.
 */
enum class AudioOwner { AUDIOBOOK, PODCAST }

/**
 * A single item to play. [id] is a bookId for [AudioOwner.AUDIOBOOK] and an
 * episodeId for [AudioOwner.PODCAST].
 */
data class AudioRequest(
    val owner: AudioOwner,
    val id: Long,
    /** Absolute file path OR http(s) URL. */
    val uri: String,
    val title: String,
    val artist: String? = null,
    val artworkUrl: String? = null,
    val startPositionMs: Long = 0L,
    val durationMs: Long? = null,
)

/**
 * State of the one shared audio owner. [ended] becomes true once the current item
 * reaches its natural end and is reset by play/seek.
 */
data class AudioPlayerState(
    val request: AudioRequest? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val ended: Boolean = false,
    val error: String? = null,
)

interface AudioPlayer {
    val state: StateFlow<AudioPlayerState>

    /** Pauses/replaces whatever was playing; [request] becomes the new owner. */
    fun play(request: AudioRequest)
    fun pause()
    fun resume()
    fun toggle()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)

    /** Stop and clear the current request. */
    fun stop()
}

internal expect fun createAudioPlayer(): AudioPlayer

object AudioPlayers {
    /** The one and only audio owner for the whole app. */
    val shared: AudioPlayer by lazy { createAudioPlayer() }
}