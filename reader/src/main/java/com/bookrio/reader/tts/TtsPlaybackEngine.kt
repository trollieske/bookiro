package com.bookrio.reader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.bookrio.reader.R
import com.bookrio.reader.engine.ReaderChapter

data class TtsChapter(val index: Int, val title: String, val paragraphs: List<String>)

data class TtsState(
    val isPlaying: Boolean = false,
    val currentChapter: Int = 0,
    val currentParagraph: Int = 0,
    val totalParagraphs: Int = 0,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val error: String? = null,
    val isReady: Boolean = false
)

class TtsPlaybackEngine(
    ctx: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val appCtx = ctx.applicationContext

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state

    private var chapters: List<TtsChapter> = emptyList()
    private var utteranceCounter = 0L
    private var lastSpeakJob: Job? = null

    private var tts: TextToSpeech? = null
    private var initStatus: Int = TextToSpeech.ERROR

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            initStatus = status
            _state.value = _state.value.copy(
                isReady = status == TextToSpeech.SUCCESS,
                error = if (status == TextToSpeech.SUCCESS) null else "Tilkobling til stemmetjeneste mislyktes"
            )
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.forLanguageTag("no-NO")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        _state.value = _state.value.copy(isPlaying = true, error = null)
                        runCatching {
                            (appCtx as? com.bookrio.core.di.AppDependenciesProvider)
                                ?.readingTracker?.updateTtsPlaybackState(true)
                        }
                    }
                    override fun onDone(utteranceId: String) {
                        scope.launch { playNextParagraph() }
                    }
                    override fun onError(utteranceId: String?) {
                        _state.value = _state.value.copy(error = appCtx.getString(R.string.rdr_error_tts_playback))
                    }
                })
            }
        }
    }

    fun setChapters(readerChapters: List<ReaderChapter>, chapterHtmlContents: List<String>) {
        chapters = readerChapters.zip(chapterHtmlContents) { ch, html ->
            val paragraphs = htmlToParagraphs(html)
            TtsChapter(ch.index, ch.title, paragraphs)
        }
        val total = chapters.sumOf { it.paragraphs.size }
        _state.value = _state.value.copy(totalParagraphs = total)
    }

    fun play() {
        if (!state.value.isReady) {
            _state.value = _state.value.copy(error = appCtx.getString(R.string.rdr_error_tts_not_ready))
            return
        }
        if (chapters.isEmpty()) {
            _state.value = _state.value.copy(error = appCtx.getString(R.string.rdr_error_no_text_chapter))
            return
        }
        _state.value = _state.value.copy(isPlaying = true)
        runCatching {
            (appCtx as? com.bookrio.core.di.AppDependenciesProvider)?.readingTracker?.updateTtsPlaybackState(true)
        }
        val chap = chapters.getOrNull(state.value.currentChapter) ?: return
        val paraIdx = state.value.currentParagraph.coerceAtMost(chap.paragraphs.size - 1)
        speak(chap.paragraphs.getOrNull(paraIdx).orEmpty())
    }

    fun pause() {
        tts?.stop()
        _state.value = _state.value.copy(isPlaying = false)
        runCatching {
            (appCtx as? com.bookrio.core.di.AppDependenciesProvider)?.readingTracker?.updateTtsPlaybackState(false)
        }
    }

    fun toggle() {
        if (state.value.isPlaying) pause() else play()
    }

    fun next() {
        val nextIdx = (state.value.currentParagraph + 1).let { p ->
            val chap = chapters.getOrNull(state.value.currentChapter)
            if (chap != null && p < chap.paragraphs.size) p to state.value.currentChapter
            else 0 to (state.value.currentChapter + 1).coerceAtMost(chapters.size - 1)
        }
        _state.value = _state.value.copy(
            currentParagraph = nextIdx.first,
            currentChapter = nextIdx.second
        )
        if (state.value.isPlaying) {
            tts?.stop()
            play()
        }
    }

    fun previous() {
        val prevIdx = (state.value.currentParagraph - 1).let { p ->
            when {
                p >= 0 -> p to state.value.currentChapter
                else -> {
                    val prevChap = (state.value.currentChapter - 1).coerceAtLeast(0)
                    (chapters.getOrNull(prevChap)?.paragraphs?.lastIndex ?: 0) to prevChap
                }
            }
        }
        _state.value = _state.value.copy(
            currentParagraph = prevIdx.first,
            currentChapter = prevIdx.second
        )
        if (state.value.isPlaying) {
            tts?.stop()
            play()
        }
    }

    fun jumpToChapter(chapterIndex: Int, paragraphIndex: Int = 0) {
        _state.value = _state.value.copy(
            currentChapter = chapterIndex.coerceIn(0, chapters.size - 1),
            currentParagraph = paragraphIndex.coerceAtLeast(0)
        )
        if (state.value.isPlaying) {
            tts?.stop()
            play()
        }
    }

    fun setSpeechRate(rate: Float) {
        val r = rate.coerceIn(0.25f, 4.0f)
        tts?.setSpeechRate(r)
        _state.value = _state.value.copy(speechRate = r)
    }

    fun setPitch(p: Float) {
        val r = p.coerceIn(0.25f, 2.5f)
        tts?.setPitch(r)
        _state.value = _state.value.copy(pitch = r)
    }

    fun release() {
        scope.launch(ioDispatcher) {
            runCatching {
                tts?.stop()
                tts?.shutdown()
                tts = null
            }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) {
            scope.launch { playNextParagraph() }
            return
        }
        utteranceCounter += 1
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_${utteranceCounter}")
    }

    private suspend fun playNextParagraph() = withContext(Dispatchers.Main) {
        val chap = chapters.getOrNull(state.value.currentChapter)
        if (chap == null) {
            pause()
            return@withContext
        }
        val nextPara = state.value.currentParagraph + 1
        if (nextPara < chap.paragraphs.size) {
            _state.value = _state.value.copy(currentParagraph = nextPara)
            speak(chap.paragraphs[nextPara])
        } else {
            val nextChap = state.value.currentChapter + 1
            if (nextChap < chapters.size) {
                _state.value = _state.value.copy(currentChapter = nextChap, currentParagraph = 0)
                speak(chapters[nextChap].paragraphs.getOrNull(0).orEmpty())
            } else {
                _state.value = _state.value.copy(isPlaying = false)
            }
        }
    }

    companion object {
        fun htmlToParagraphs(html: String): List<String> {
            val body = html.substringAfter("<body", missingDelimiterValue = html)
                .substringAfter('>')
                .substringBeforeLast("</body>")
            val blocks = body.split(Regex("""(?i)</(p|div|h[1-6]|li|blockquote)>"""))
            val clean = blocks.mapNotNull { b ->
                val stripped = android.text.Html.fromHtml(b, android.text.Html.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                stripped.ifBlank { null }
            }
            return if (clean.isEmpty()) {
                listOf(html.substring(0, minOf(html.length, 8000)))
            } else {
                clean.windowed(
                    size = minOf(10, clean.size),
                    step = minOf(10, clean.size),
                    partialWindows = true
                ).map { w -> w.joinToString("\n\n") }
            }
        }
    }
}
