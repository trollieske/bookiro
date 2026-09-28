package com.bookrio.reader.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bookrio.reader.pageturn.ReaderThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val TAG = "HtmlPageRenderer"

/**
 * Gated rendering-diagnostikk (AV som standard). Logger kun tekniske målinger:
 * generasjon, sideindeks, renderKey, bitmap-dimensjoner/config, innholds-flagg
 * (img/svg/picture/canvas/lenker som boolske flagg — aldri selve innholdet,
 * filnavn, URL-er eller base64-data), tidsstempler og readiness-status.
 */
private const val RENDER_DIAG = false

private fun renderDiag(msg: String) {
    if (RENDER_DIAG) Log.d(TAG, msg)
}

/**
 * Ren (JVM-testbar) generasjonsvakt: enhver asynkron WebView-callback (layout,
 * asset-klarhet, offset-applicering, visuell-ramme-capture) for generasjon
 * [reported] skal forkastes med mindre den stemmer med den aktive generasjonen.
 * En stale callback må aldri publisere bitmap/layout for en nyere side/konfig.
 */
internal class RenderGenerationGate(private val activeGeneration: AtomicLong) {
    fun accepts(reported: Long): Boolean = reported == activeGeneration.get()
}

/**
 * Reader-CSS for stabil bildepaginering (gjelder generert leser-CSS KUN —
 * EPUB-kildens egen CSS og lenkefarger berøres ikke):
 *  - alle medie-elementer begrenses til kolonnebredden og beholder aspect ratio
 *  - ett bilde skal aldri splittes over to genererte sider (kolonnebrudd)
 *  - ingen beskjæring, ingen tvungne høyder, ingen enhets-/modellspesifikk CSS
 */
internal const val STABLE_IMAGE_CSS = """
          img, svg, image, video, iframe {
            max-width: 100% !important;
            height: auto !important;
            box-sizing: border-box !important;
          }
          img, svg {
            display: block !important;
            margin: 0.8em auto !important;
            break-inside: avoid !important;
            page-break-inside: avoid !important;
          }
"""

data class HighlightData(
    val text: String,
    val colorInt: Int,
    val pageIndex: Int,
    val startPageOffset: Float,
    val endPageOffset: Float,
)

/**
 * Off-screen [WebView] renderer for professional ebook typography.
 */
@SuppressLint("SetJavaScriptEnabled")
class HtmlPageRenderer(
    private val context: Context,
    val pageWidth: Int,
    val pageHeight: Int,
    val onHighlightSaved: (HighlightData) -> Unit = {},
) {
    private val density: Float = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val cssPageWidth: Int = (pageWidth / density).toInt()
    private val cssQuoteBorder: Float = 3f / density

    private var webView: WebView? = null
    private var _totalPages: Int = 0
    val totalPages: Int get() = _totalPages

    private val activeGeneration = AtomicLong(0L)
    private val renderMutex = Mutex()

    private val _lastMetrics = MutableStateFlow("")
    val lastMetrics: StateFlow<String> = _lastMetrics.asStateFlow()

    private var pendingPrepareCont: CancellableContinuation<Int>? = null

    /** Venteende render: continuation + diagnostikk-nøkkel + start-tidsstempel. */
    private class PendingRender(
        val cont: CancellableContinuation<Bitmap>,
        val diagKey: String,
        val startMs: Long,
    )

    private val pendingRenders = ConcurrentHashMap<Pair<Long, Int>, PendingRender>()
    private val generationGate = RenderGenerationGate(activeGeneration)

    // Innholds-flagg for diagnostikk (beregnes én gang per prepare-generasjon;
    // kun boolske flagg — aldri rå innholdstekst, filnavn, URL-er eller bildedata).
    @Volatile private var contentHasImg = false
    @Volatile private var contentHasSvg = false
    @Volatile private var contentHasPicture = false
    @Volatile private var contentHasCanvas = false
    @Volatile private var contentHasLinks = false

    /** Readiness-utfall for gjeldende generasjon (fonts/images) før capture. */
    @Volatile private var lastReadiness = "unknown"

    private val jsInterface = object {
        @JavascriptInterface
        fun onAssetsReady(gen: Long, fonts: String, images: String, imageCount: Int) {
            Handler(Looper.getMainLooper()).post {
                if (!generationGate.accepts(gen)) {
                    Log.d(TAG, "onAssetsReady rejected: stale generation $gen (active=${activeGeneration.get()})")
                    return@post
                }
                lastReadiness = "fonts=$fonts;images=$images;imgCount=$imageCount"
                renderDiag("ASSETS-READY (gen=$gen): $lastReadiness")
            }
        }

        @JavascriptInterface
        fun onLayoutCalculated(gen: Long, totalPages: Int, sw: Int, stride: Int) {
            Handler(Looper.getMainLooper()).post {
                if (!generationGate.accepts(gen)) {
                    Log.d(TAG, "onLayoutCalculated rejected: stale generation $gen (active=${activeGeneration.get()})")
                    return@post
                }
                val cont = pendingPrepareCont
                pendingPrepareCont = null
                _lastMetrics.value = "sw=$sw, stride=$stride, pages=$totalPages"
                Log.i(TAG, "Layout calculated (Gen $gen): $totalPages pages [sw=$sw, stride=$stride]")
                _totalPages = totalPages
                if (cont?.isActive == true) cont.resume(totalPages)
            }
        }

        @JavascriptInterface
        fun onPageOffsetApplied(gen: Long, pageIndex: Int) {
            Handler(Looper.getMainLooper()).post {
                if (!generationGate.accepts(gen)) {
                    Log.d(TAG, "onPageOffsetApplied rejected: stale generation $gen (active=${activeGeneration.get()}, page=$pageIndex)")
                    return@post
                }
                val key = Pair(gen, pageIndex)
                val pending = pendingRenders.remove(key)
                if (pending != null && pending.cont.isActive) {
                    // Capture først ETTER at WebView'en har tegnet ≥ 1 visuell ramme med
                    // ny offset: postVisualStateCallback (API 23+, minSdk 26) fyres når
                    // den ventende visuelle oppdateringen er tegnet — ingen faste
                    // enhets-/produsent-sleeps. Callbacken revaktes mot generasjonen
                    // (stale visuell callback må aldri publisere bitmap for en nyere
                    // side/konfig), og det overordnede 3000 ms budgettet (se
                    // withTimeoutOrNull i renderPage) er den generiske sikkerhetsgrensen
                    // som beholder siste gyldige layout.
                    val view = webView
                    if (view == null) {
                        pending.cont.resume(Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888))
                        return@post
                    }
                    view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            captureAndResume(gen, pageIndex, pending.diagKey, pending.startMs, pending.cont)
                        }
                    })
                } else {
                    Log.d(TAG, "onPageOffsetApplied: No matching pending render for (gen=$gen, page=$pageIndex). activeKeys=${pendingRenders.keys}")
                }
            }
        }

        @JavascriptInterface
        fun onHighlightCreated(text: String, colorInt: Int, pageIndex: Int, startOff: Double, endOff: Double) {
            try {
                onHighlightSaved(
                    HighlightData(
                        text = text,
                        colorInt = colorInt,
                        pageIndex = pageIndex,
                        startPageOffset = startOff.toFloat(),
                        endPageOffset = endOff.toFloat()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Highlight bridge error", e)
            }
        }
    }

    /**
     * Capturerer nåværende WebView-tilstand og løser den ventende continuationen.
     * Kjøres på UI-tråden, kun etter ≥ 1 post-layout visuell ramme (se
     * postVisualStateCallback i onPageOffsetApplied), og er generasjonsvaktet:
     * en stale callback publisere aldri bitmap for en nyere side/konfig.
     */
    private fun captureAndResume(
        gen: Long,
        pageIndex: Int,
        diagKey: String,
        startMs: Long,
        cont: CancellableContinuation<Bitmap>,
    ) {
        if (!generationGate.accepts(gen) || !cont.isActive) {
            Log.d(TAG, "Capture rejected: stale generation $gen (active=${activeGeneration.get()}, page=$pageIndex)")
            return
        }
        try {
            val bmp = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bmp)
            webView?.draw(canvas)
            val endMs = SystemClock.uptimeMillis()
            if (RENDER_DIAG) {
                renderDiag(
                    "CAPTURE gen=$gen page=$pageIndex key='$diagKey' " +
                    "renderer=${pageWidth}x$pageHeight bmp=${bmp.width}x${bmp.height} cfg=${bmp.config} " +
                    "content[img=$contentHasImg svg=$contentHasSvg picture=$contentHasPicture " +
                    "canvas=$contentHasCanvas links=$contentHasLinks] " +
                    "ready=$lastReadiness startMs=$startMs endMs=$endMs durMs=${endMs - startMs}"
                )
            }
            cont.resume(bmp)
        } catch (e: Exception) {
            Log.e(TAG, "Render error (gen=$gen, page=$pageIndex)", e)
            if (cont.isActive) {
                cont.resume(Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888))
            }
        }
    }

    suspend fun prepare(
        htmlContent: String,
        fontSizeSp: Int,
        theme: ReaderThemeColors,
        lang: String = "en",
    ): Int = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(8000L) {
            renderMutex.withLock {
                val gen = activeGeneration.incrementAndGet()
                _totalPages = 0
                val wv = getOrCreateWebView(theme)
                val sanitized = sanitizeHtmlContent(htmlContent)

                // Diagnostikk-flagg (boolske, aldri rått innhold) for denne generasjonen.
                contentHasImg = "<img" in sanitized
                contentHasSvg = "<svg" in sanitized
                contentHasPicture = "<picture" in sanitized
                contentHasCanvas = "<canvas" in sanitized
                contentHasLinks = "href=" in sanitized

                suspendCancellableCoroutine { cont ->
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (!generationGate.accepts(gen)) {
                                if (cont.isActive) cont.resume(1)
                                return
                            }
                            view.evaluateJavascript(
                                """
                                 (function() {
                                     var GEN = $gen;
                                     function measure() {
                                         var wrapper = document.getElementById('content-wrapper');
                                         var sw = wrapper.scrollWidth;
                                         var stride = window.innerWidth;
                                         // Ceil ensures we don't cut off the last word of a chapter
                                         var cols = Math.max(1, Math.ceil((sw - 1) / (stride || 1)));
                                         return {cols: cols, sw: sw, stride: stride};
                                     }

                                     // Generisk, begrenset venting på font- og bilde-klarhet FØR
                                     // paginering: ingen enhets-/modellspecific sleeps. Ved timeout
                                     // fortsetter vi med siste gyldige layout (et ødelagt bilde
                                     // skal aldri blokkere lesingen for alltid).
                                     function fontsSettled(timeoutMs) {
                                         return new Promise(function(resolve) {
                                             var timer = setTimeout(function(){ resolve('timeout'); }, timeoutMs);
                                             try {
                                                 if (document.fonts && document.fonts.ready && document.fonts.ready.then) {
                                                     document.fonts.ready.then(
                                                         function(){ clearTimeout(timer); resolve('ok'); },
                                                         function(){ clearTimeout(timer); resolve('error'); });
                                                 } else { clearTimeout(timer); resolve('na'); }
                                             } catch (e) { clearTimeout(timer); resolve('error'); }
                                         });
                                     }
                                     function imageSettled(timeoutMs) {
                                         return new Promise(function(resolve) {
                                             var done = false;
                                             var timer = setTimeout(function(){ finish('timeout'); }, timeoutMs);
                                             var left = 0;
                                             function finish(s) { if (!done) { done = true; clearTimeout(timer); resolve(s); } }
                                             function one() { left -= 1; if (left <= 0) finish('all'); }
                                             try {
                                                 var imgs = Array.prototype.slice.call(document.images || []);
                                                 var pending = imgs.filter(function(im){ return !im.complete; });
                                                 left = pending.length;
                                                 if (left === 0) { finish('all'); return; }
                                                 for (var i = 0; i < pending.length; i++) {
                                                     pending[i].addEventListener('load', one, {once:true});
                                                     pending[i].addEventListener('error', one, {once:true});
                                                 }
                                             } catch (e) { finish('error'); }
                                         });
                                     }
                                     Promise.all([fontsSettled(1200), imageSettled(2000)])
                                         .then(function(res) {
                                             try { AndroidPageReady.onAssetsReady(GEN, res[0], res[1], (document.images || []).length); } catch (e) {}
                                             var m = measure();
                                             // Robust retry logic for complex EPUB structures:
                                             // én ekstra måling først når kolonnene ikke lot seg
                                             // beregne — samme generasjon, rapporteres kun én gang.
                                             if (m.cols <= 1 && document.body.innerText.length > 500) {
                                                 setTimeout(function() {
                                                     try {
                                                         var m2 = measure();
                                                         AndroidPageReady.onLayoutCalculated(GEN, m2.cols, m2.sw, m2.stride);
                                                     } catch (e) {
                                                         AndroidPageReady.onLayoutCalculated(GEN, m.cols, m.sw, m.stride);
                                                     }
                                                 }, 300);
                                             } else {
                                                 AndroidPageReady.onLayoutCalculated(GEN, m.cols, m.sw, m.stride);
                                             }
                                         })
                                         .catch(function() {
                                             // Aldri heng: fall tilbake til umiddelbar måling.
                                             try {
                                                 var m = measure();
                                                 AndroidPageReady.onLayoutCalculated(GEN, m.cols, m.sw, m.stride);
                                             } catch (e) {}
                                         });
                                 })();
                                """.trimIndent(), null
                            )
                        }
                    }
                    val html = buildReaderHtml(sanitized, fontSizeSp, theme, lang, cssQuoteBorder)
                    pendingPrepareCont = cont
                    wv.loadDataWithBaseURL("https://shelf.app/r/", html, "text/html", "UTF-8", null)
                    cont.invokeOnCancellation { if (activeGeneration.get() == gen) pendingPrepareCont = null }
                }
            }
        }
        result ?: 1
    }

    suspend fun renderPage(pageIndex: Int, diagKey: String = ""): Bitmap = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(3000L) {
            renderMutex.withLock {
                val gen = activeGeneration.get()
                val wv = webView ?: error("Not prepared")
                val startMs = SystemClock.uptimeMillis()
                suspendCancellableCoroutine { cont ->
                    val key = Pair(gen, pageIndex)
                    val entry = PendingRender(cont, diagKey, startMs)
                    pendingRenders[key] = entry
                    wv.evaluateJavascript(
                        """
                        (function(){
                          var el = document.getElementById('content-wrapper') || document.body;
                          var stride = window.innerWidth;
                          el.style.transform = 'translateX(' + (-( ${pageIndex} * stride )) + 'px)';
                          // Force browser reflow to ensure sharp text
                          var f = el.offsetHeight;
                          // Capture-signalet fyres først etter ≥ 1 post-layout visuell ramme:
                          // dobbel requestAnimationFrame (fallback 50 ms) — ingen faste
                          // enhets-/produsent-sleeps. Kotlin-siden venter deretter på
                          // postVisualStateCallback før bitmapmen faktisk captureres.
                          function fire() { AndroidPageReady.onPageOffsetApplied($gen, $pageIndex); }
                          try {
                            requestAnimationFrame(function() { requestAnimationFrame(fire); });
                          } catch (e) {
                            setTimeout(fire, 50);
                          }
                        })();
                        """.trimIndent(), null
                    )
                    cont.invokeOnCancellation {
                        pendingRenders.remove(key, entry)
                    }
                }
            }
        }
        result ?: Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
    }

    fun release() {
        activeGeneration.incrementAndGet()
        pendingRenders.values.forEach { it.cont.cancel() }
        pendingRenders.clear()
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
        webView = null
    }

    private fun getOrCreateWebView(theme: ReaderThemeColors): WebView {
        webView?.let { return it }
        val wv = WebView(context).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = true; allowContentAccess = true
                useWideViewPort = true; loadWithOverviewMode = false; textZoom = 100; cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false); displayZoomControls = false; layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            }
            wv.setBackgroundColor(Color.parseColor(theme.bodyBg))
            wv.isHorizontalScrollBarEnabled = false; wv.isVerticalScrollBarEnabled = false
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.addJavascriptInterface(jsInterface, "AndroidPageReady")
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("chromium", msg.message()); return true
                }
            }
            wv.measure(View.MeasureSpec.makeMeasureSpec(pageWidth, View.MeasureSpec.EXACTLY),
                       View.MeasureSpec.makeMeasureSpec(pageHeight, View.MeasureSpec.EXACTLY))
            wv.layout(0, 0, pageWidth, pageHeight)
        }
        val activity = context as? Activity
        val decor = activity?.window?.decorView as? ViewGroup
        if (wv.parent != null) (wv.parent as? ViewGroup)?.removeView(wv)
        decor?.addView(wv, ViewGroup.LayoutParams(pageWidth, pageHeight))
        wv.translationX = -10000f
        webView = wv
        return wv
    }

    private fun sanitizeHtmlContent(raw: String) = raw.replace("&nbsp;", "\u00A0").replace("&mdash;", "—").replace("&ndash;", "–")
        .replace("&hellip;", "…").replace("&ldquo;", "“").replace("&rdquo;", "”")
        .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("--", "—")
        .replace(Regex("<p>\\s*</p>"), "").replace(Regex("(<br\\s*/?>\\s*){3,}"), "<br/><br/>")

}

/**
 * Bygger hele leser-HTML-en. Top-nivå og internal for JVM-testbarhet.
 * KILDE-EPUB-ens CSS inkluderes som en del av [content]; Shelf legger aldri
 * til lenkefarging — blå lenker kommer fra kilden eller UA-default.
 */
internal fun buildReaderHtml(
    content: String,
    fontSizeSp: Int,
    theme: ReaderThemeColors,
    lang: String,
    cssQuoteBorder: Float,
): String {
        return """
        <!DOCTYPE html>
        <html lang="${lang.ifEmpty { "en" }}">
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
        <style>
          *, *::before, *::after { box-sizing: border-box; }
          html, body { 
            margin: 0; padding: 0; height: 100vh; width: 100vw; 
            overflow: hidden; background: ${theme.bodyBg}; 
            -webkit-text-size-adjust: none;
          }
          body { 
            color: ${theme.textColor}; 
            font-family: "Crimson Pro", "EB Garamond", "Palatino", "Georgia", serif; 
            font-size: ${fontSizeSp}px; 
            line-height: 1.6; 
            text-rendering: optimizeLegibility;
            -webkit-font-smoothing: antialiased;
          }
          #content-wrapper {
            display: block; 
            height: 100vh !important; 
            width: 100% !important;
            margin: 0;
            padding: 0;
            column-width: 100vw !important; 
            column-gap: 0 !important; 
            column-fill: auto;
            word-wrap: break-word; 
            overflow-wrap: break-word; 
            hyphens: auto; 
            -webkit-hyphens: auto; 
            text-align: justify;
            overflow: visible; 
            will-change: transform;
            orphans: 1;
            widows: 1;
          }
          h1, h2, h3 { color: ${theme.headingColor}; text-align: center !important; margin: 1.2em 0 0.6em !important; font-weight: 700 !important; line-height: 1.3; }
          h1 { font-size: 1.5em !important; }
          h2 { font-size: 1.3em !important; }
          h3 { font-size: 1.15em !important; }
          p { margin: 0 0 0.6em !important; text-align: justify !important; text-indent: 1.5em !important; line-height: 1.6 !important; }
$STABLE_IMAGE_CSS
          blockquote { border-left: ${cssQuoteBorder}px solid ${theme.headingColor}; padding-left: 1.2em; margin: 1.5em 0; font-style: italic; opacity: 0.9; }
          ::selection { background: rgba(255, 205, 90, 0.45); }
          .__hl_float { position: fixed; z-index: 9999; display: none; padding: 6px; background: rgba(30,30,32,0.96); border-radius: 10px; box-shadow: 0 4px 14px rgba(0,0,0,0.35); }
          .__hl_btn { display: inline-block; width: 22px; height: 22px; border-radius: 50%; margin: 0 3px; cursor: pointer; border: 2px solid rgba(255,255,255,0.7); }
        </style>
        </head>
        <body><div id="content-wrapper">$content</div>
        <script>
        (function() {
            var colors = [
                { hex: '#FFDD55', android: 0xFFFFFF7F & 0xFFFFFFFF },
                { hex: '#FF9AA2', android: 0xFFFF9AA2 & 0xFFFFFFFF },
                { hex: '#B5DEFF', android: 0xFFB5DEFF & 0xFFFFFFFF },
                { hex: '#C7CEEA', android: 0xFFC7CEEA & 0xFFFFFFFF },
                { hex: '#A0E7E5', android: 0xFFA0E7E5 & 0xFFFFFFFF },
                { hex: '#B4F8C8', android: 0xFFB4F8C8 & 0xFFFFFFFF }
            ];
            var ui = document.createElement('div');
            ui.className = '__hl_float';
            ui.innerHTML = colors.map(function(c){ return '<span class="__hl_btn" data-c="'+c.android+'" style="background:'+c.hex+'"></span>' }).join('');
            document.body.appendChild(ui);
            var btns = ui.querySelectorAll('.__hl_btn');
            for (var i = 0; i < btns.length; i++) {
                btns[i].addEventListener('click', function(ev){
                    ev.preventDefault();
                    ev.stopPropagation();
                    var sel = window.getSelection();
                    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) { ui.style.display = 'none'; return; }
                    var text = sel.toString();
                    if (!text || text.trim().length === 0) { ui.style.display = 'none'; return; }
                    var cInt = parseInt(this.getAttribute('data-c'), 10);
                    var pageWidth = window.innerWidth || document.documentElement.clientWidth || 1;
                    var rect = sel.getRangeAt(0).getBoundingClientRect();
                    var page = Math.max(0, Math.round(rect.left / pageWidth));
                    var startFrac = Math.max(0, Math.min(1, ((page * pageWidth) - rect.left + pageWidth) / pageWidth));
                    var endFrac = Math.max(0, Math.min(1, ((page * pageWidth) - rect.right + pageWidth) / pageWidth));
                    try { AndroidPageReady.onHighlightCreated(text, cInt, page, Math.min(startFrac, endFrac), Math.max(startFrac, endFrac)); } catch(e) {}
                    sel.removeAllRanges();
                    ui.style.display = 'none';
                });
            }
            function hideIfOutside(e){ if (ui.style.display === 'none') return; var r = ui.getBoundingClientRect(); if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) ui.style.display = 'none'; }
            document.addEventListener('selectionchange', function(){
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0 || sel.isCollapsed || sel.toString().trim().length === 0) { ui.style.display = 'none'; return; }
                var rect = sel.getRangeAt(0).getBoundingClientRect();
                ui.style.display = 'block';
                var top = rect.top - 48;
                if (top < 4) top = rect.bottom + 6;
                var left = rect.left + rect.width/2 - ui.offsetWidth/2;
                if (left < 4) left = 4;
                var maxL = (window.innerWidth || 360) - ui.offsetWidth - 4;
                if (left > maxL) left = maxL;
                ui.style.top = top + 'px';
                ui.style.left = left + 'px';
            });
            document.addEventListener('mousedown', hideIfOutside);
            document.addEventListener('scroll', function(){ ui.style.display = 'none'; }, true);
        })();
        </script>
        </body>
        </html>
    """.trimIndent()
}
