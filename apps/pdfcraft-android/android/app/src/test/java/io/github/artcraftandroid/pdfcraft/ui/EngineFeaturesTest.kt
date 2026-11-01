package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.*
import org.junit.Test

class EngineFeaturesTest {
    @Test fun markdownUsesItsOwnMimeType() {
        assertEquals("text/markdown", documentMime("md"))
        assertEquals("text/markdown", documentMime("markdown"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", documentMime("docx"))
    }

    @Test fun selectedLinesHandleReversedSelectionAndNewlineBoundaries() {
        val text = "العنوان\nالسطر الثاني\nالثالث"
        assertEquals(0..0, selectedTextLines(text, 0, 8))
        assertEquals(1..1, selectedTextLines(text, 8, 19))
        assertEquals(0..1, selectedTextLines(text, 19, 0))
        assertEquals(2..2, selectedTextLines(text, text.length, text.length))
    }

    @Test fun resizeEdgesAreIndependentAndNeverCross() {
        val rect = floatArrayOf(10f, 20f, 110f, 70f)
        assertArrayEquals(floatArrayOf(10f, 20f, 160f, 70f), imageHandleRect(rect, 1, 0, 50f, 80f), .001f)
        assertArrayEquals(floatArrayOf(106f, 66f, 110f, 70f), imageHandleRect(rect, -1, -1, 200f, 200f), .001f)
        assertArrayEquals(floatArrayOf(10f, 20f, 110f, 70f), rect, .001f)
    }

    @Test fun monochromeCompressionIsLosslessAndCanKeepResolution() {
        assertEquals("jbig2", monoOptions(MonoCompression.Jbig2, false)["compression"])
        assertEquals(false, monoOptions(MonoCompression.Jbig2, false)["downsample"])
        assertEquals(450, monoOptions(MonoCompression.Group4, true)["above_ppi"])
        assertEquals(300, monoOptions(MonoCompression.Group4, true)["ppi"])
    }
}
