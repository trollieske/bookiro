@file:OptIn(ExperimentalForeignApi::class)

package com.bookrio.shared.reader

import kotlin.math.floor
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIActivityIndicatorView
import platform.UIKit.UIActivityIndicatorViewStyleMedium
import platform.UIKit.UIColor
import platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior
import platform.UIKit.UIViewController
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * Asynchronous, per-chapter pagination for the iOS EPUB reader — the iOS mirror
 * of the Android `HtmlPageRenderer.prepare()` measurement.
 *
 * Chapters are measured serially in an off-screen-but-attached WKWebView that
 * loads the exact same reader document the pages render ([buildEpubReaderHtml],
 * `#content-wrapper` with `column-width: 100vw`). JavaScript waits (bounded) for
 * fonts/images, measures `#content-wrapper.scrollWidth` and reports the column
 * count back through a `WKScriptMessageHandler`; the whole book is measured so
 * the reader knows a global page grid for progress/TOC, but the first page is
 * shown as soon as its chapter is measured.
 *
 * [measureView] must be inserted below the (opaque) page controller, so it is
 * laid out but never visible.
 */
internal class EpubHtmlPaginator(
    private val chapters: List<EpubChapter>,
    private val onChapterMeasured: (chapter: Int, pages: Int) -> Unit,
) {

    val measureView: WKWebView

    private val counts = arrayOfNulls<Int>(chapters.size)
    private val queue = ArrayDeque<Int>()
    private val messageHandler: MeasureMessageHandler
    private val navigationDelegate: MeasureNavigationDelegate
    private var activeChapter = -1
    private var generation = 0L
    private var configured = false
    private var disposed = false

    // Last applied configuration; every change invalidates the page grid.
    private var widthPx = 0
    private var heightPx = 0
    private var fontSizeSp = 16
    private var lineHeightPct = 140
    private var theme = "light"
    private var colors = epubReaderColors(theme)

    init {
        messageHandler = MeasureMessageHandler { body -> handleMessage(body) }
        navigationDelegate = MeasureNavigationDelegate { handleNavigationFinished() }
        val configuration = WKWebViewConfiguration()
        configuration.suppressesIncrementalRendering = true
        configuration.userContentController.addScriptMessageHandler(messageHandler, "shelfMeasure")
        measureView = WKWebView(CGRectMake(0.0, 0.0, 1.0, 1.0), configuration)
        measureView.navigationDelegate = navigationDelegate
        measureView.userInteractionEnabled = false
        // Invisible, but still laid out and running JS: the page-curl child views
        // can be inset by a few points, and an opaque measuring view would show
        // through at the page edges (a ghost column of text).
        measureView.alpha = 0.0
        measureView.backgroundColor = UIColor.clearColor
        measureView.scrollView.scrollEnabled = false
        measureView.scrollView.bounces = false
        measureView.scrollView.contentInsetAdjustmentBehavior =
            UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
    }

    /**
     * Applies the page viewport and reader settings. Returns true when anything
     * changed and the whole book has to be re-measured. [priorityChapter] (when
     * valid) is measured first, so a font/theme/rotation change does not make the
     * reader wait for every earlier chapter.
     */
    fun configure(
        widthPx: Int,
        heightPx: Int,
        fontSizeSp: Int,
        lineHeightPct: Int,
        theme: String,
        priorityChapter: Int = -1,
    ): Boolean {
        val unchanged = configured &&
            widthPx == this.widthPx && heightPx == this.heightPx &&
            fontSizeSp == this.fontSizeSp && lineHeightPct == this.lineHeightPct && theme == this.theme
        if (unchanged) return false
        configured = true
        this.widthPx = widthPx
        this.heightPx = heightPx
        this.fontSizeSp = fontSizeSp
        this.lineHeightPct = lineHeightPct
        this.theme = theme
        colors = epubReaderColors(theme)
        generation += 1
        activeChapter = -1
        for (i in counts.indices) counts[i] = null
        queue.clear()
        if (!disposed && widthPx > 0 && heightPx > 0) {
            for (i in chapters.indices) queue.addLast(i)
            if (priorityChapter in chapters.indices) {
                queue.remove(priorityChapter)
                queue.addFirst(priorityChapter)
            }
            measureNext()
        }
        return true
    }

    fun countOf(chapter: Int): Int? = counts.getOrNull(chapter)

    fun knownTotalPages(): Int {
        var total = 0
        for (count in counts) total += count ?: 0
        return total
    }

    /** Moves [chapter] to the front of the measuring queue (e.g. a TOC jump). */
    fun prioritize(chapter: Int) {
        if (disposed || chapter < 0 || chapter >= counts.size) return
        if (counts[chapter] != null || chapter == activeChapter) return
        queue.remove(chapter)
        queue.addFirst(chapter)
        if (activeChapter < 0) measureNext()
    }

    /** The complete reader document for one page of one chapter. */
    fun html(chapter: Int, pageOffset: Int): String {
        val document = chapters.getOrNull(chapter) ?: return ""
        val content = document.html.ifBlank { epubFallbackHtml(document.text) }
        return buildEpubReaderHtml(
            content = content,
            fontSizeSp = fontSizeSp,
            lineHeightPct = lineHeightPct,
            colors = colors,
            pageOffset = pageOffset,
        )
    }

    fun shutdown() {
        if (disposed) return
        disposed = true
        generation += 1
        activeChapter = -1
        queue.clear()
        measureView.stopLoading()
        measureView.navigationDelegate = null
        measureView.configuration.userContentController.removeScriptMessageHandlerForName("shelfMeasure")
    }

    private fun measureNext() {
        if (disposed) return
        while (queue.isNotEmpty()) {
            val chapter = queue.removeFirst()
            if (counts[chapter] != null) continue
            val gen = generation
            activeChapter = chapter
            measureView.setFrame(CGRectMake(0.0, 0.0, widthPx.toDouble(), heightPx.toDouble()))
            measureView.loadHTMLString(html(chapter, 0), null)
            scheduleTimeout(gen, chapter)
            return
        }
        activeChapter = -1
    }

    private fun scheduleTimeout(gen: Long, chapter: Int) {
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, MEASURE_TIMEOUT_NANOS),
            dispatch_get_main_queue(),
        ) {
            if (gen == generation) finishMeasurement(gen, chapter, 1)
        }
    }

    private fun handleNavigationFinished() {
        val chapter = activeChapter
        if (disposed || chapter < 0) return
        val gen = generation
        measureView.evaluateJavaScript(measureScript(gen)) { _, error ->
            if (error != null) finishMeasurement(gen, chapter, 1)
        }
    }

    private fun handleMessage(body: String) {
        val parts = body.split('|')
        if (parts.size < 2) return
        val gen = parts[0].toLongOrNull() ?: return
        val pages = parts[1].toIntOrNull() ?: return
        finishMeasurement(gen, activeChapter, pages)
    }

    private fun finishMeasurement(gen: Long, chapter: Int, pages: Int) {
        if (disposed || gen != generation) return
        if (chapter < 0 || chapter >= counts.size || counts[chapter] != null) return
        val measured = pages.coerceAtLeast(1)
        counts[chapter] = measured
        activeChapter = -1
        onChapterMeasured(chapter, measured)
        measureNext()
    }
}

/** Wraps the JS→native measurement report (a single `gen|pages|stride` string). */
private class MeasureMessageHandler(private val onBody: (String) -> Unit) :
    NSObject(), WKScriptMessageHandlerProtocol {

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val body = didReceiveScriptMessage.body as? String ?: return
        onBody(body)
    }
}

private class MeasureNavigationDelegate(private val onFinished: () -> Unit) :
    NSObject(), WKNavigationDelegateProtocol {

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onFinished()
    }
}

/**
 * One page of a chapter: a WKWebView with the reader HTML whose
 * `#content-wrapper` is already translated to the page's column. Scrolling and
 * bouncing are disabled — the page is a fixed window into the chapter.
 */
internal class EpubHtmlPageViewController(
    val chapterIndex: Int,
    val localPageIndex: Int,
    pageHtml: String,
    private val pageColor: UIColor,
) : UIViewController(null, null) {

    private val webView: WKWebView = run {
        val configuration = WKWebViewConfiguration()
        configuration.suppressesIncrementalRendering = true
        WKWebView(CGRectMake(0.0, 0.0, 1.0, 1.0), configuration)
    }

    init {
        webView.loadHTMLString(pageHtml, null)
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = pageColor
        webView.backgroundColor = pageColor
        webView.scrollView.scrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.bouncesZoom = false
        webView.scrollView.showsHorizontalScrollIndicator = false
        webView.scrollView.showsVerticalScrollIndicator = false
        webView.scrollView.scrollsToTop = false
        webView.scrollView.contentInsetAdjustmentBehavior =
            UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
        view.addSubview(webView)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        webView.setFrame(pageBounds())
    }

    /** Integral CSS viewport so every page measures/offsets against the same grid. */
    private fun pageBounds(): CValue<CGRect> = CGRectMake(
        0.0,
        0.0,
        floor(CGRectGetWidth(view.bounds)).coerceAtLeast(1.0),
        floor(CGRectGetHeight(view.bounds)).coerceAtLeast(1.0),
    )
}

/** Shown until the first real (measured) page exists — never appear without a page. */
internal class EpubPlaceholderViewController(private val pageColor: UIColor) : UIViewController(null, null) {

    private val spinner = UIActivityIndicatorView(
        activityIndicatorStyle = UIActivityIndicatorViewStyleMedium,
    )

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = pageColor
        spinner.startAnimating()
        view.addSubview(spinner)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val width = CGRectGetWidth(view.bounds)
        val height = CGRectGetHeight(view.bounds)
        spinner.setFrame(CGRectMake((width - 24.0) / 2.0, (height - 24.0) / 2.0, 24.0, 24.0))
    }
}

/** Parses a `#rrggbb` CSS colour into a UIColor (same palette as Android). */
internal fun epubColorFromHex(hex: String, alpha: Double = 1.0): UIColor {
    val cleaned = hex.trim().removePrefix("#")
    val value = cleaned.toLongOrNull(16) ?: 0L
    val red = ((value shr 16) and 0xFF).toDouble() / 255.0
    val green = ((value shr 8) and 0xFF).toDouble() / 255.0
    val blue = (value and 0xFF).toDouble() / 255.0
    return UIColor(red = red, green = green, blue = blue, alpha = alpha)
}

private const val MEASURE_TIMEOUT_NANOS = 8_000_000_000L

private fun measureScript(gen: Long): String = """
    (function() {
      var GEN = $gen;
      function measure() {
        var wrapper = document.getElementById('content-wrapper');
        var sw = wrapper ? wrapper.scrollWidth : document.documentElement.scrollWidth;
        var stride = window.innerWidth || document.documentElement.clientWidth || 1;
        return Math.max(1, Math.ceil((sw - 1) / (stride || 1)));
      }
      function report(pages) {
        try { window.webkit.messageHandlers.shelfMeasure.postMessage(GEN + '|' + pages + '|' + (window.innerWidth || 0)); } catch (e) {}
      }
      function settle() {
        var done = false;
        function finish() { if (done) return; done = true; report(measure()); }
        function waitImages() {
          var timer = setTimeout(finish, 2000);
          try {
            var pending = Array.prototype.slice.call(document.images || []).filter(function(im){ return !im.complete; });
            if (pending.length === 0) { clearTimeout(timer); finish(); return; }
            var left = pending.length;
            function one() { left -= 1; if (left <= 0) { clearTimeout(timer); finish(); } }
            pending.forEach(function(im) {
              im.addEventListener('load', one, {once: true});
              im.addEventListener('error', one, {once: true});
            });
          } catch (e) { clearTimeout(timer); finish(); }
        }
        var fontTimer = setTimeout(finish, 1200);
        try {
          if (document.fonts && document.fonts.ready && document.fonts.ready.then) {
            document.fonts.ready.then(function(){ clearTimeout(fontTimer); waitImages(); },
                                      function(){ clearTimeout(fontTimer); waitImages(); });
          } else { clearTimeout(fontTimer); waitImages(); }
        } catch (e) { clearTimeout(fontTimer); waitImages(); }
      }
      setTimeout(settle, 50);
    })();
""".trimIndent()