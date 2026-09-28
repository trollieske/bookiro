package com.bookrio.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fokuserte tester for invarianten mellom kildebitmap (HtmlPageRenderer) og
 * Canvas-destinasjon i leseren:
 *
 *  1. Padding påføres nøyaktig ÉN gang (effective = viewport − 2×pad).
 *  2. Kildebitmap og destinasjonsboks deler nøyaktig samme mål/aspektratio —
 *     aldri uavhengig X/Y-skalering (aldri «strukket» tekst).
 *  3. Målene er rene funksjoner av det stabile viewportet — aldri av
 *     kontroll-synlighet (showControls), som ikke finnes i noen input her.
 */
class ReaderContentBoxTest {

    private fun assertNoDistortion(box: ReaderContentBox) {
        // Kilde-destinasjon-identitet: destinasjonen er samme innholdsboks som rendereren.
        assertEquals(box.effectiveWidthPx, box.destinationWidthPx)
        assertEquals(box.contentHeightPx, box.destinationHeightPx)

        // Aspektratio-identitet: kilde AR == destinasjons-AR.
        assertEquals(box.sourceAspectRatio, box.destinationAspectRatio, 1e-6f)

        // Skaleringsfaktor X == Y (og == 1.0 siden mål er identiske).
        val scaleX = box.destinationWidthPx.toFloat() / box.effectiveWidthPx
        val scaleY = box.destinationHeightPx.toFloat() / box.contentHeightPx
        assertEquals(1f, scaleX, 1e-6f)
        assertEquals(1f, scaleY, 1e-6f)
        assertEquals(scaleX, scaleY, 1e-6f)
    }

    @Test
    fun `padding is applied exactly once to renderer and destination`() {
        val box = ReaderContentBox(
            viewportWidthPx = 1080,
            viewportHeightPx = 2400,
            hPadPx = 32,
            vPadPx = 48,
        )
        assertEquals(1080 - 2 * 32, box.effectiveWidthPx)
        assertEquals(2400 - 2 * 48, box.contentHeightPx)
        assertNoDistortion(box)
    }

    @Test
    fun `source and destination share aspect ratio for common device viewports`() {
        val viewports = listOf(
            1080 to 2400, // høy telefon
            720 to 1600,
            1440 to 3200,
            800 to 1280, // nettbrett
            2400 to 1080, // liggende
        )
        viewports.forEach { (w, h) ->
            val box = ReaderContentBox(w, h, hPadPx = 32, vPadPx = 48)
            assertEquals(
                box.effectiveWidthPx.toFloat() / box.contentHeightPx,
                box.destinationWidthPx.toFloat() / box.destinationHeightPx,
                1e-6f,
            )
            assertNoDistortion(box)
        }
    }

    @Test
    fun `dimensions depend only on viewport - never on any control-visibility input`() {
        // Samme viewport gir bit-for-bit samme mål/nøkkelgrunnlag uansett hvor mange
        // ganger det leses — showControls er ikke en del av noen beregning her.
        val a = ReaderContentBox(1080, 2400, 32, 48)
        val b = ReaderContentBox(1080, 2400, 32, 48)
        assertEquals(a.effectiveWidthPx, b.effectiveWidthPx)
        assertEquals(a.contentHeightPx, b.contentHeightPx)
        assertEquals(a.destinationWidthPx, b.destinationWidthPx)
        assertEquals(a.destinationHeightPx, b.destinationHeightPx)
        assertEquals(a.sizeKey(), b.sizeKey())
    }

    @Test
    fun `degenerate viewports clamp to at least one pixel without distortion`() {
        val box = ReaderContentBox(viewportWidthPx = 3, viewportHeightPx = 2, hPadPx = 32, vPadPx = 48)
        assertTrue(box.effectiveWidthPx >= 1)
        assertTrue(box.contentHeightPx >= 1)
        assertNoDistortion(box)
    }
}
