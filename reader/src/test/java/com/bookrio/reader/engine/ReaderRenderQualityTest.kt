package com.bookrio.reader.engine

import com.bookrio.reader.pageturn.readerThemeColors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rendering-kvalitet for den DIREKTE WebView-leseren: generert leser-CSS og
 * paginerings-kontrakten (CSS multi-column + scroll-bro). Ren JVM (ingen
 * Android-runtime-kall) — konstanter/funksjoner uten sideeffekter.
 *
 * Merk: dette er markup/CSS-kontrakter, ikke en device-test. Faktisk
 * WebView-oppførsel (kolonnebredde, scrollWidth, utvalg) må verifiseres på
 * enhet.
 */
class ReaderRenderQualityTest {

    private fun html(theme: String = "sepia", content: String = "<p>tekst</p>"): String =
        buildReaderHtml(
            content = content,
            fontSizeSp = 18,
            theme = readerThemeColors(theme),
            lang = "en",
            cssQuoteBorder = 3f,
            generation = 7L,
        )

    // 1) Generert leser-CSS: stabile bilde-begrensninger (uendret fra den gamle
    //    leser-CSS-en, gjenbrukt av den direkte WebView-en)
    @Test
    fun `generert leser-CSS inneholder stabile bilde-begrensninger`() {
        val html = html()

        // Medie-elementene skal begrenses til kolonnebredden og beholde aspect ratio
        assertTrue(
            "img/svg/image/video/iframe skal ha max-width: 100%",
            Regex("img\\s*,\\s*svg\\s*,\\s*image\\s*,\\s*video\\s*,\\s*iframe\\s*\\{[^}]*max-width:\\s*100%", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(html),
        )
        assertTrue(
            "img/svg/image/video/iframe skal ha height: auto (aspect ratio bevares)",
            Regex("img\\s*,\\s*svg\\s*,\\s*image\\s*,\\s*video\\s*,\\s*iframe\\s*\\{[^}]*height:\\s*auto", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(html),
        )
        assertTrue(
            "img/svg/image/video/iframe skal ha box-sizing: border-box",
            Regex("img\\s*,\\s*svg\\s*,\\s*image\\s*,\\s*video\\s*,\\s*iframe\\s*\\{[^}]*box-sizing:\\s*border-box", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(html),
        )
        // Ett bilde skal aldri splittes over to genererte sider
        assertTrue(
            "img/svg skal ha break-inside: avoid",
            Regex("img\\s*,\\s*svg\\s*\\{[^}]*break-inside:\\s*avoid", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(html),
        )
        assertTrue(
            "img/svg skal ha page-break-inside: avoid",
            Regex("img\\s*,\\s*svg\\s*\\{[^}]*page-break-inside:\\s*avoid", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(html),
        )
        // Ingen crop/tvingede faste høyder
        assertFalse("ingen max-height crop av bilder", html.contains("max-height"))
        assertFalse("ingen fast height på bilder", Regex("img[^}]*height:\\s*\\d+px").containsMatchIn(html))
        // Ingen enhets-/modellspesifikk CSS
        assertFalse("ingen OnePlus/modelldeteksjon i CSS", html.contains("oneplus", ignoreCase = true))
        assertFalse("ingen enhetsspesifikke px-offsets i CSS", html.contains("overflow-y: scroll"))
    }

    // 2) Lenkefarging: Bookiro legger aldri til egen a-farge (blå lenker er
    //    kildens eller UA-default) — ingen overstyrt linkfarge i leser-CSS
    @Test
    fun `generert leser-CSS overstyrer aldri lenkefarging`() {
        val html = html(content = "<p><a href=\"https://bookiro.app/r/\">lenke</a></p>")
        Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
            .let { css ->
                assertFalse("ingen a-farging i Bookiro-CSS", Regex("\\ba\\s*,|\\ba\\s*\\{|a:\\s*link|a:\\s*visited").containsMatchIn(css))
            }
    }

    // 3) Paginering: kapittelet er en horisontal kolonne-scroller — hver kolonne
    //    er nøyaktig viewportbredden, så side N ligger på scrollLeft = N × vw.
    @Test
    fun `generert leser-CSS paginerer med eksakt viewportbrede kolonner`() {
        val html = html()
        val css = Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]

        assertTrue(
            "#content-wrapper skal ha column-width: 100vw",
            Regex("#content-wrapper\\s*\\{[^}]*column-width:\\s*100vw", RegexOption.DOT_MATCHES_ALL).containsMatchIn(css),
        )
        assertTrue(
            "#content-wrapper skal ha column-gap: 0 (sidebredde == viewport)",
            Regex("#content-wrapper\\s*\\{[^}]*column-gap:\\s*0", RegexOption.DOT_MATCHES_ALL).containsMatchIn(css),
        )
        assertTrue(
            "#content-wrapper skal ha column-fill: auto",
            Regex("#content-wrapper\\s*\\{[^}]*column-fill:\\s*auto", RegexOption.DOT_MATCHES_ALL).containsMatchIn(css),
        )
        assertTrue(
            "#content-wrapper skal være horisontal scroller (overflow-x: auto)",
            Regex("#content-wrapper\\s*\\{[^}]*overflow-x:\\s*auto", RegexOption.DOT_MATCHES_ALL).containsMatchIn(css),
        )
        // Ingen gammel transform-basert sidevending igjen
        assertFalse("ingen translateX-sidevending i CSS", css.contains("translateX"))
        assertFalse("ingen will-change: transform", css.contains("will-change"))
    }

    // 4) Paginerings-broen: sidetall fra scrollWidth og side fra scrollX
    @Test
    fun `generert leser-HTML eksponerer scroll-basert paginerings-bro`() {
        val html = html()
        assertTrue("sidetallet leses fra scrollWidth", html.contains("Math.ceil((wrapper.scrollWidth - 1) / s)"))
        assertTrue("gjeldende side leses fra scrollX", html.contains("Math.round(wrapper.scrollLeft / s)"))
        assertTrue("sidevending er programmatisk scroll", html.contains("wrapper.scrollLeft = target * s"))
        assertTrue("sidetall rapporteres til Kotlin", html.contains("AndroidReader.onPagination"))
        assertTrue("scroll-endringer rapporteres til Kotlin", html.contains("AndroidReader.onPageSettled"))
        assertTrue("tap-soner rapporteres til Kotlin", html.contains("AndroidReader.onTap"))
        assertTrue("markering rapporteres til Kotlin", html.contains("AndroidReader.onHighlight"))
        assertTrue("generasjonen er bakt inn i HTML-en", html.contains("var GEN = 7"))
        // Ingen bitmap-/capture-rester i markupen
        assertFalse("ingen postVisualStateCallback", html.contains("postVisualStateCallback"))
        assertFalse("ingen drawIntoCanvas", html.contains("drawIntoCanvas"))
    }
}