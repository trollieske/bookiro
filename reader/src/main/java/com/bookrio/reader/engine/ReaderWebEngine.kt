package com.bookrio.reader.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bookrio.reader.pageturn.ReaderThemeColors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

private const val TAG = "ReaderWebEngine"

/** JS-bro-navn. Alt som eksponeres til leser-HTML-en går gjennom denne. */
private const val JS_NAME = "AndroidReader"

/** Samme base-URL som den tidligere offscreen-rendereren brukte (relative ressurser). */
private const val CONTENT_BASE_URL = "https://bookiro.app/r/"

/**
 * Markeringsdata fra tekstutvalg i leser-HTML-en. pageIndex er kapittel-lokal
 * (samme modell som [ReaderBookState.currentPage] og ReadingProgressEntity).
 */
data class HighlightData(
    val text: String,
    val colorInt: Int,
    val pageIndex: Int,
    val startPageOffset: Float,
    val endPageOffset: Float,
)

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

/**
 * Ren side-matematikk for kolonne-pagineringen. Samme formler som JS-en i
 * [buildReaderHtml] bruker på `scrollWidth` / `scrollLeft` / `clientWidth`:
 *
 *  - `pageCount = ceil((scrollWidth - 1) / stride)` — siste kolonne telles ikke
 *    dobbelt når scrollWidth er et eksakt multiplum av viewporten (±1 px slakk
 *    fra subpiksel-avrunding i WebView-en).
 *  - `page = round(scrollLeft / stride)` — gjeldende side leses FRA faktisk
 *    scrollX, ikke fra en egen tellevariabel.
 *
 * JVM-testbar og uten Android-avhengigheter.
 */
object ReaderPagination {

    fun pageCount(scrollWidthPx: Int, viewportWidthPx: Int): Int {
        if (viewportWidthPx <= 0) return 1
        val span = (scrollWidthPx - 1).coerceAtLeast(0)
        return maxOf(1, (span + viewportWidthPx - 1) / viewportWidthPx)
    }

    fun pageFromScrollX(scrollX: Int, viewportWidthPx: Int, pageCount: Int): Int {
        if (viewportWidthPx <= 0 || pageCount <= 0) return 0
        val raw = (scrollX.toFloat() / viewportWidthPx.toFloat()).roundToInt()
        return raw.coerceIn(0, pageCount - 1)
    }

    fun scrollXForPage(page: Int, viewportWidthPx: Int, pageCount: Int): Int {
        if (viewportWidthPx <= 0 || pageCount <= 0) return 0
        return page.coerceIn(0, pageCount - 1) * viewportWidthPx
    }
}

/**
 * Leser-HTML for den direkte, on-screen WebView-en.
 *
 * - Kapittel-HTML ligger i `#content-wrapper`, paginert med CSS multi-column:
 *   `column-width: 100vw` + `column-gap: 0` → hver kolonne er nøyaktig
 *   viewportbredden, og `scrollWidth` er nøyaktig antall sider × viewportbredde.
 * - Sidevending = programmatisk horisontal scroll (`scrollLeft = page × stride`).
 *   Ingen transform, ingen bitmap, ingen offscreen WebView, ingen capture.
 * - Sideantallet leses fra `scrollWidth` og gjeldende side fra `scrollX`.
 */
internal fun buildReaderHtml(
    content: String,
    fontSizeSp: Int,
    theme: ReaderThemeColors,
    lang: String,
    cssQuoteBorder: Float,
    generation: Long = 0L,
): String {
    return """
        <!DOCTYPE html>
        <html lang="${lang.ifEmpty { "en" }}">
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
        <style>
          *, *::before, *::after { box-sizing: border-box; }
          html, body {
            margin: 0; padding: 0; height: 100%; width: 100%;
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
          /* Horisontal kolonne-scroller: hver kolonne = 100vw, ingen gap.
             En side = nøyaktig én viewportbredde med scroll. */
          #content-wrapper {
            display: block;
            height: 100% !important;
            width: 100% !important;
            margin: 0;
            padding: 0;
            column-width: 100vw !important;
            column-gap: 0 !important;
            column-fill: auto;
            overflow-x: auto;
            overflow-y: hidden;
            word-wrap: break-word;
            overflow-wrap: break-word;
            hyphens: auto;
            -webkit-hyphens: auto;
            text-align: justify;
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
            var GEN = $generation;
            var wrapper = document.getElementById('content-wrapper');

            function stride() {
                return Math.max(1, (wrapper && wrapper.clientWidth) || window.innerWidth || 1);
            }
            function pageCount() {
                if (!wrapper) return 1;
                var s = stride();
                // Samme formel som den gamle målingen: en eksakt multiplum-bredde
                // skal ikke gi en tom ekstra side.
                return Math.max(1, Math.ceil((wrapper.scrollWidth - 1) / s));
            }
            function pageFromScroll() {
                if (!wrapper) return 0;
                var s = stride();
                return Math.max(0, Math.min(pageCount() - 1, Math.round(wrapper.scrollLeft / s)));
            }
            function scrollToPage(p) {
                if (!wrapper) return;
                var s = stride();
                var target = Math.max(0, Math.min(pageCount() - 1, Math.round(p)));
                wrapper.scrollLeft = target * s;
            }
            function report() {
                if (!wrapper) return;
                try {
                    AndroidReader.onPagination(GEN, pageCount(), stride(), wrapper.scrollWidth);
                } catch (e) {}
            }
            window.ReaderPage = {
                pageCount: pageCount,
                currentPage: pageFromScroll,
                scrollToPage: scrollToPage,
                remeasure: function() { requestAnimationFrame(function() { report(); }); }
            };

            // Gjeldende side leses FRA scrollX: programmatiske sidevendinger og
            // bruker-drag holdes i synk med ViewModel-ens kapittel-lokale sideindeks.
            // Etter at scrollen har roet seg (90 ms) snappes det til nærmeste kolonne.
            var scrollTimer = null;
            if (wrapper) {
                wrapper.addEventListener('scroll', function() {
                    if (scrollTimer) { clearTimeout(scrollTimer); }
                    scrollTimer = setTimeout(function() {
                        var s = stride();
                        var raw = wrapper.scrollLeft;
                        var p = Math.max(0, Math.min(pageCount() - 1, Math.round(raw / s)));
                        var exact = p * s;
                        if (Math.abs(raw - exact) > 1) { wrapper.scrollLeft = exact; }
                        try { AndroidReader.onPageSettled(GEN, p); } catch (e) {}
                    }, 90);
                }, { passive: true });
            }

            // Tap-soner: venstre 28 % = forrige side, høyre 28 % = neste side,
            // midten = vis/skjul kontroller. Samme terskler som den gamle leseren.
            // Tekstutvalg og markeringspaletten vinner alltid over sidevending:
            // et aktivt utvalg (eller et tapp like etter) svelger klikket.
            var lastSelectionAt = 0;
            document.addEventListener('click', function(ev) {
                try {
                    var sel = window.getSelection();
                    if (sel && sel.toString().trim().length > 0) { lastSelectionAt = Date.now(); return; }
                    if (Date.now() - lastSelectionAt < 700) return;
                    var t = ev.target;
                    if (t && t.closest && t.closest('.__hl_float')) return;
                    var w = window.innerWidth || 1;
                    AndroidReader.onTap(GEN, (ev.clientX || 0) / w);
                } catch (e) {}
            }, false);

            // Markeringspalett (uendret UX fra den gamle leseren, men sidene
            // regnes nå ut fra scrollLeft + kolonnebredde).
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
                    var w = stride();
                    var sl = wrapper ? wrapper.scrollLeft : 0;
                    var rect = sel.getRangeAt(0).getBoundingClientRect();
                    var startX = sl + rect.left;
                    var endX = sl + rect.right;
                    var page = Math.max(0, Math.min(pageCount() - 1, Math.floor(startX / w)));
                    var startFrac = Math.max(0, Math.min(1, (startX - page * w) / w));
                    var endFrac = Math.max(0, Math.min(1, (endX - page * w) / w));
                    try {
                        AndroidReader.onHighlight(text, cInt, page, startFrac, Math.max(startFrac, endFrac));
                    } catch (e) {}
                    sel.removeAllRanges();
                    ui.style.display = 'none';
                });
            }
            function hideIfOutside(e){ if (ui.style.display === 'none') return; var r = ui.getBoundingClientRect(); if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) ui.style.display = 'none'; }
            document.addEventListener('selectionchange', function(){
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0 || sel.isCollapsed || sel.toString().trim().length === 0) { ui.style.display = 'none'; return; }
                lastSelectionAt = Date.now();
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

            // Paginering først etter at fonter og bilder har landet (med timeout):
            // et for tidlig sidetall ville endret seg under lesingen.
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
                .then(function() { report(); })
                .catch(function() { report(); });
            window.addEventListener('load', function() { report(); }, { once: true });
        })();
        </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Direkte (on-screen) WebView-renderer for leserens kapittel-HTML.
 *
 * Erstatter den gamle offscreen bitmap-pipelinen: kapittelet lastes i en
 * synlig WebView (Compose `AndroidView`), pagineres med CSS multi-column og
 * vendes ved programmatisk horisontal scroll. Ingen capture, ingen bitmap-cache,
 * ingen skjermbilde-pipeline.
 *
 * Eierskap: én instans per leseflate. Alle mutasjoner skjer på hovedtråden;
 * JS-bro-callbacks postes til hovedtråden og forkastes hvis de tilhører en
 * eldre generasjon (kapittel/font/tema-last).
 */
@SuppressLint("SetJavaScriptEnabled")
class ReaderWebEngine(context: Context) {

    /**
     * WebView-en er lazy: [bridge]-objektet (og generasjonsvakten) må være
     * initialisert før `addJavascriptInterface` kan bruke det.
     */
    val webView: WebView by lazy {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = false
                textZoom = 100
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
                displayZoomControls = false
                builtInZoomControls = false
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            }
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(bridge, JS_NAME)
        }
    }

    private val density: Float = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val cssQuoteBorder: Float = 3f / density
    private val activeGeneration = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastViewportWidth = 0
    private var lastViewportHeight = 0

    /** Nøkkelen til kapittelet/typografien som sist ble lastet (null = ingenting lastet). */
    var loadKey: String? by mutableStateOf<String?>(null)
        private set

    /** Nøkkelen som faktisk er ferdig målt (sidetallet er gyldig for denne). */
    var measuredLoadKey: String? by mutableStateOf<String?>(null)
        private set

    /** Sidetall for [measuredLoadKey]. 0 = ennå ikke målt. */
    var pageCount: Int by mutableStateOf(0)
        private set

    /** Tap i leseflaten: x-fraksjon 0..1 (venstre/høyre/midten tolkes av UI-en). */
    var onTapZone: ((Float) -> Unit)? = null

    /** Rapportert side fra faktisk scrollX (etter at scrollen har roet seg). */
    var onPageSettled: ((Int) -> Unit)? = null

    /** Tekstutvalg fra leser-HTML-en. */
    var onHighlight: ((HighlightData) -> Unit)? = null

    private val bridge = object {
        @JavascriptInterface
        fun onPagination(gen: Long, pages: Int, stride: Int, sw: Int) {
            mainHandler.post {
                if (gen != activeGeneration.get()) {
                    Log.d(TAG, "Pagination rejected: stale generation $gen (active=${activeGeneration.get()})")
                    return@post
                }
                val key = loadKey ?: return@post
                val safe = pages.coerceAtLeast(1)
                pageCount = safe
                measuredLoadKey = key
                Log.i(TAG, "Pagination ready: '$key' pages=$safe stride=$stride scrollWidth=$sw")
            }
        }

        @JavascriptInterface
        fun onPageSettled(gen: Long, page: Int) {
            mainHandler.post {
                if (gen != activeGeneration.get()) return@post
                onPageSettled?.invoke(page.coerceAtLeast(0))
            }
        }

        @JavascriptInterface
        fun onTap(gen: Long, xFraction: Float) {
            mainHandler.post {
                if (gen != activeGeneration.get()) return@post
                onTapZone?.invoke(xFraction)
            }
        }

        @JavascriptInterface
        fun onHighlight(text: String, colorInt: Int, pageIndex: Int, startOff: Double, endOff: Double) {
            mainHandler.post {
                runCatching {
                    onHighlight?.invoke(
                        HighlightData(
                            text = text,
                            colorInt = colorInt,
                            pageIndex = pageIndex,
                            startPageOffset = startOff.toFloat(),
                            endPageOffset = endOff.toFloat(),
                        )
                    )
                }
            }
        }
    }

    /**
     * Laster et kapittel (eller samme kapittel med ny typografi) i den synlige
     * WebView-en. En ny generasjon gjør alle tidligere JS-svar stale.
     */
    fun load(
        loadKey: String,
        content: String,
        fontSizeSp: Int,
        theme: ReaderThemeColors,
        lang: String = "en",
    ) {
        this.loadKey = loadKey
        val gen = activeGeneration.incrementAndGet()
        pageCount = 0
        measuredLoadKey = null

        val html = buildReaderHtml(sanitizeHtmlContent(content), fontSizeSp, theme, lang, cssQuoteBorder, gen)
        webView.setBackgroundColor(Color.parseColor(theme.bodyBg))
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (gen != activeGeneration.get()) return
                // Sikkerhetsnett: HTML-scriptet rapporterer selv etter at assets
                // har landet; dette dekker en eventuelt tapt første-rapport.
                view.evaluateJavascript("window.ReaderPage && ReaderPage.remeasure();", null)
            }

            /** Blokker ALL navigasjon: leseren skal aldri forlate kapittelet. */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        webView.loadDataWithBaseURL(CONTENT_BASE_URL, html, "text/html", "UTF-8", null)
    }

    /** Programmatisk sidevending: scroll til side × viewportbredde. */
    fun scrollToPage(page: Int) {
        val safe = page.coerceAtLeast(0)
        webView.evaluateJavascript("window.ReaderPage && ReaderPage.scrollToPage($safe);", null)
    }

    /** Rotasjon / vindusendring: re-mål kolonnene og rapporter nytt sidetall. */
    fun onViewportChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == lastViewportWidth && height == lastViewportHeight) return
        lastViewportWidth = width
        lastViewportHeight = height
        if (loadKey == null) return
        webView.post { webView.evaluateJavascript("window.ReaderPage && ReaderPage.remeasure();", null) }
    }

    fun release() {
        activeGeneration.incrementAndGet()
        loadKey = null
        measuredLoadKey = null
        pageCount = 0
        onTapZone = null
        onPageSettled = null
        onHighlight = null
        runCatching { webView.stopLoading() }
        runCatching { webView.removeJavascriptInterface(JS_NAME) }
        runCatching { webView.destroy() }
    }
}

internal fun sanitizeHtmlContent(raw: String) = raw
    .replace("&nbsp;", "\u00A0").replace("&mdash;", "—").replace("&ndash;", "–")
    .replace("&hellip;", "…").replace("&ldquo;", "“").replace("&rdquo;", "”")
    .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("--", "—")
    .replace(Regex("<p>\\s*</p>"), "").replace(Regex("(<br\\s*/?>\\s*){3,}"), "<br/><br/>")