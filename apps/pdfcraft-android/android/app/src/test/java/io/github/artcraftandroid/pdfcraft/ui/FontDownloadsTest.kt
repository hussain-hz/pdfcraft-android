package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FontDownloadsTest {
    @Test fun verifiedCacheWorksOfflineAndRepairsCorruptDownloads() = runBlocking {
        val directory = Files.createTempDirectory("pdfcraft-font-cache").toFile()
        try {
            val bytes = "font data".toByteArray(); val terms = "OFL licence".toByteArray()
            val font = DownloadableFont("Sample-Regular", "Sample", "Regular", false, "ofl/sample/Sample.ttf",
                fontDigest(bytes), bytes.size, "ofl/sample/OFL.txt", fontDigest(terms), "a".repeat(40))
            var calls = 0
            val transfer: FontTransfer = { url, _, _, _, _ -> calls++; if (url.endsWith("OFL.txt")) terms else bytes }
            val out = cacheDocumentFont(font, directory, transfer = transfer)
            assertArrayEquals(bytes, out.readBytes()); assertEquals(2, calls)
            val offline: FontTransfer = { _, _, _, _, _ -> error("No network available") }
            assertEquals(out, cacheDocumentFont(font, directory, transfer = offline))
            out.writeText("corrupted")
            assertEquals(out, cacheDocumentFont(font, directory, transfer = transfer))
            assertEquals(4, calls); assertArrayEquals(bytes, out.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidTransferNeverInstallsAFont() = runBlocking {
        val directory = Files.createTempDirectory("pdfcraft-font-integrity").toFile()
        try {
            val bytes = "expected".toByteArray()
            val font = DownloadableFont("Sample-Regular", "Sample", "Regular", false, "ofl/sample/Sample.ttf",
                fontDigest(bytes), bytes.size, "ofl/sample/OFL.txt", fontDigest(bytes), "a".repeat(40))
            try {
                cacheDocumentFont(font, directory, transfer = { _, _, _, _, _ -> "tampered".toByteArray() })
                fail("Reject mismatched data")
            } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("verification")) }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun automaticLookupRequiresAnUnambiguousExactFace() {
        fun font(id: String) = DownloadableFont(id, "Sample", "Regular", false, "ofl/sample/$id.ttf",
            "a".repeat(64), 1, "ofl/sample/OFL.txt", "b".repeat(64), "c".repeat(40))
        val regular = font("Amiri-Regular"); val bold = font("Amiri-Bold")
        assertEquals(bold, matchingOriginalFont("ABCDEF+Amiri-Bold", listOf(regular, bold)))
        assertEquals(regular, matchingOriginalFont("Amiri Regular", listOf(regular, bold)))
        assertNull(matchingOriginalFont("Amiri", listOf(regular, bold)))
        assertNull(matchingOriginalFont("Amiri-Italic", listOf(regular, bold)))
        assertNull(matchingOriginalFont("Arial", listOf(regular, bold)))
        assertNull(matchingOriginalFont("Amiri-Bold", listOf(bold, bold)))
    }

    @Test fun automaticRecoveryDoesNotInterceptUnrelatedErrors() {
        assertTrue(needsOriginalFont(IllegalStateException("complete source font is needed for preserving this font")))
        assertTrue(needsOriginalFont(Exception("wrapper", Exception("preserving this font"))))
        assertFalse(needsOriginalFont(Exception("document is read-only")))
        assertFalse(needsOriginalFont(Exception("font is invalid")))
    }

}
