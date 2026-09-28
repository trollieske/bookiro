package com.bookrio.reader.engine

import com.bookrio.reader.pageturn.readerThemeColors
import com.bookrio.reader.ui.PAGE_BITMAP_PAINT_FLAGS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Rendering-kvalitet: gjenerert leser-CSS, generasjonsvakt og bitmap-tregnflagg.
 * Ren JVM (ingen Android-runtime-kall) — konstanter/funksjoner uten sideeffekter.
 */
class ReaderRenderQualityTest {

    // 1) Generert leser-CSS: stabile bilde-begrensninger før capture/paginering
    @Test
    fun `generert leser-CSS inneholder stabile bilde-begrensninger`() {
        val html = buildReaderHtml(
            content = "<p>tekst</p>",
            fontSizeSp = 18,
            theme = readerThemeColors("sepia"),
            lang = "en",
            cssQuoteBorder = 3f,
        )

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

    // 2) Lenkefarging: Shelf legger aldri til egen a-farge (blå lenker er
    //    kildens eller UA-default) — ingen overstyrt linkfarge i leser-CSS
    @Test
    fun `generert leser-CSS overstyrer aldri lenkefarging`() {
        val html = buildReaderHtml(
            content = "<p><a href=\"https://shelf.app/r/\">lenke</a></p>",
            fontSizeSp = 18,
            theme = readerThemeColors("light"),
            lang = "en",
            cssQuoteBorder = 3f,
        )
        Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
            .let { css ->
                assertFalse("ingen a-farging i Shelf-CSS", Regex("\\ba\\s*,|\\ba\\s*\\{|a:\\s*link|a:\\s*visited").containsMatchIn(css))
            }
    }

    // 3) Generasjonsvakt: stale asynkrone WebView-callbacks skal forkastes
    @Test
    fun `generasjonsvakt forkaster stale callbacks`() {
        val active = AtomicLong(5L)
        val gate = RenderGenerationGate(active)

        assertTrue("callback for aktiv generasjon aksepteres", gate.accepts(5L))
        assertFalse("callback for eldre generasjon forkastes", gate.accepts(4L))
        assertFalse("callback for fremtidig generasjon forkastes", gate.accepts(6L))

        // Etter en ny prepare-generasjon er alle tidligere callbacks stale
        active.incrementAndGet()
        assertFalse("5 er stale etter ny generasjon", gate.accepts(5L))
        assertTrue("6 er nå aktiv", gate.accepts(6L))
    }

    // 4) Bitmap-tegning: felles Paint skal bruke filtering + dithering + antialiasing
    //    (android.graphics.Paint-flaggverdier: ANTI_ALIAS = 1, FILTER_BITMAP = 2, DITHER = 4;
    //    brukes som litteraler siden enhetstestens stub-jar ikke garanterer konstanttypene)
    @Test
    fun `bitmap-tregnflagg inkluderer filtering dithering og antialiasing`() {
        val antiAlias = 1
        val filterBitmap = 2
        val dither = 4
        assertEquals(
            "PAGE_BITMAP_PAINT_FLAGS skal være ANTI_ALIAS | FILTER_BITMAP | DITHER",
            antiAlias or filterBitmap or dither,
            PAGE_BITMAP_PAINT_FLAGS,
        )
        assertTrue("FILTER_BITMAP_FLAG må være satt", (PAGE_BITMAP_PAINT_FLAGS and filterBitmap) != 0)
        assertTrue("DITHER_FLAG må være satt", (PAGE_BITMAP_PAINT_FLAGS and dither) != 0)
        assertTrue("ANTI_ALIAS_FLAG må være satt", (PAGE_BITMAP_PAINT_FLAGS and antiAlias) != 0)
    }
}
