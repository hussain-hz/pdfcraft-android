package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
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

    @Test fun groupDownloadResumesAfterFailureAndReusesCompletedFacesOffline() = runBlocking {
        val directory = Files.createTempDirectory("font-group-cache").toFile()
        val terms = "OFL".toByteArray()
        val data = mapOf("Sample-Regular" to "regular".toByteArray(),"Sample-Bold" to "bold".toByteArray())
        val fonts = data.map { (id,bytes) -> DownloadableFont(id,"Sample",id.substringAfter('-'),false,
            "ofl/sample/$id.ttf",fontDigest(bytes),bytes.size,"ofl/sample/OFL.txt",fontDigest(terms),"a".repeat(40)) }
        val progress = mutableListOf<Float>()
        val transfer: FontTransfer = { url,_,_,_,_ ->
            if (url.endsWith("OFL.txt")) terms else data.getValue(url.substringAfterLast('/').removeSuffix(".ttf"))
        }
        try {
            try {
                cacheDocumentFonts(fonts,directory,transfer = { url,a,b,c,d ->
                    if (url.endsWith("Sample-Bold.ttf")) error("connection lost")
                    transfer(url,a,b,c,d)
                })
                fail("Must report interrupted family")
            } catch (_: IllegalStateException) { }
            assertTrue(java.io.File(directory,"Sample-Regular.ttf").isFile)
            assertFalse(java.io.File(directory,"Sample-Bold.ttf").isFile)
            val files = cacheDocumentFonts(fonts,directory,{progress += it},transfer = { url,a,b,c,d ->
                assertFalse("Completed regular must not download twice",url.endsWith("Sample-Regular.ttf"))
                transfer(url,a,b,c,d)
            })
            assertEquals(2,files.size)
            assertEquals(1f,progress.last(),0f)
            assertTrue(progress.zipWithNext().all { (a,b) -> b >= a })
            assertEquals(files,cacheDocumentFonts(fonts,directory,transfer = { _,_,_,_,_ -> error("offline") }))
        } finally { directory.deleteRecursively() }
    }

    @Test fun installedListKeepsSystemFirstAndDeduplicatesPrivateCopies() {
        fun font(face: String, family: String = face) = StoredDocumentFont(java.io.File("/$face.ttf"),family,face)
        val system = listOf(font("Zeta-Bold"),font("Alpha-Regular"))
        val extra = listOf(font("ABCDEF+Alpha-Regular"),font("Amiri-Bold"))
        val result = installedDocumentFonts(system,extra)
        assertEquals(listOf("Alpha-Regular","Zeta-Bold","Amiri-Bold"),result.map { it.font.face })
        assertEquals(listOf(true,true,false),result.map { it.system })
    }

    @Test fun simultaneousStartupAndManualDownloadsShareOneVerifiedWrite() = runBlocking {
        val directory = Files.createTempDirectory("font-concurrent-cache").toFile()
        val bytes = "font data".toByteArray(); val terms = "OFL licence".toByteArray()
        val font = DownloadableFont("Concurrent-Regular","Concurrent","Regular",false,"ofl/concurrent/Concurrent.ttf",
            fontDigest(bytes),bytes.size,"ofl/concurrent/OFL.txt",fontDigest(terms),"a".repeat(40))
        var calls = 0
        val transfer: FontTransfer = { url,_,_,_,_ -> calls++; kotlinx.coroutines.delay(10); if(url.endsWith("OFL.txt")) terms else bytes }
        try {
            val jobs = List(2) { async { cacheDocumentFont(font,directory,transfer=transfer) } }
            assertEquals(jobs[0].await(),jobs[1].await())
            assertEquals(2,calls)
            assertArrayEquals(bytes,java.io.File(directory,"${font.id}.ttf").readBytes())
        } finally { directory.deleteRecursively() }
    }

}
