package io.github.artcraftandroid.pdfcraft

import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.artcraftandroid.pdfcraft.core.MobileSession
import io.github.artcraftandroid.pdfcraft.engine.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontDownloadsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun call(session: MobileSession, name: String, vararg args: Pair<String, Any?>) = JSONObject(session.execute(name, JSONObject(args.toMap()).toString()))
    private fun blank(directory: File): File {
        val file = File(directory, "blank.pdf")
        val pdf = PdfDocument()
        try {
            pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(612,792,1).create()))
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }

    @Test fun officialDownloadsSupportCoverageApplySaveReopenAndUndo() = runBlocking {
        val directory = File(context.getExternalFilesDir(null), "font-download-qa/${System.nanoTime()}").apply { mkdirs() }
        val catalog = readFontCatalog(context.assets.open("font-catalog.json").bufferedReader().use { it.readText() })
        assertEquals(6, catalog.size)
        val arabic = catalog.first { it.id == "Amiri-Regular" }
        val english = catalog.first { it.id == "FiraSans-Regular" }
        val a = withContext(Dispatchers.IO) { cacheDocumentFont(arabic, directory) }
        val b = withContext(Dispatchers.IO) { cacheDocumentFont(english, directory) }
        assertEquals(arabic.sha256, fontDigest(a.readBytes()))
        assertEquals(english.sha256, fontDigest(b.readBytes()))
        val offline: FontTransfer = { _,_,_,_,_ -> error("network unavailable") }
        assertEquals(a, cacheDocumentFont(arabic, directory, transfer = offline))
        val session = MobileSession()
        try {
            assertTrue(call(session,"text_font_check","path" to a.absolutePath,"text" to "نص عربي").getBoolean("supported"))
            assertFalse(call(session,"text_font_check","path" to b.absolutePath,"text" to "نص عربي").getBoolean("supported"))
            val doc = session.open(blank(directory).absolutePath,null).id
            call(session,"page_add_text","doc" to doc.toLong(),"page" to 1,"text" to "نص عربي أصلي","at" to listOf(60,60),"size" to 22,"color" to "#2457AD")
            val generation = session.document(doc).generation
            call(session,"text_font_check","path" to a.absolutePath,"text" to "نص عربي معدل")
            assertEquals(generation,session.document(doc).generation)
            call(session,"content_font","doc" to doc.toLong(),"page" to 1,"index" to 1,"path" to a.absolutePath,"text" to "نص عربي معدل")
            assertEquals(0,call(session,"content_list","doc" to doc.toLong(),"page" to 1).getInt("count"))
            val out = File(directory,"amiri-edited.pdf")
            call(session,"doc_save","doc" to doc.toLong(),"path" to out.absolutePath,"full" to true)
            val reopened = session.open(out.absolutePath,null).id
            val para = call(session,"text_paragraphs","doc" to reopened.toLong(),"page" to 1).getJSONArray("paragraphs").getJSONObject(0)
            assertEquals("نص عربي معدل",para.getString("editor_text"))
            assertEquals("#2457AD",para.getString("color"))
            assertEquals(22.0,para.getDouble("size"),.01)
            assertTrue(para.getString("font").contains("Amiri"))
            assertTrue(session.renderPage(reopened,0u,1f,null).rgba.isNotEmpty())
            call(session,"edit_undo","doc" to doc.toLong())
            assertEquals("نص عربي أصلي",call(session,"content_list","doc" to doc.toLong(),"page" to 1).getJSONArray("items").getJSONObject(0).getString("text"))
            call(session,"edit_redo","doc" to doc.toLong())
            assertEquals(0,call(session,"content_list","doc" to doc.toLong(),"page" to 1).getInt("count"))
        } finally { session.documents().forEach { session.close(it,true) } }
    }

    @Test fun invalidFontsAreRefusedWithoutChangingTheDocument() {
        val directory = File(context.cacheDir,"font-invalid-qa/${System.nanoTime()}").apply { mkdirs() }
        val invalid = File(directory,"broken.ttf").apply { writeText("not a font") }
        val session = MobileSession()
        try {
            val doc = session.open(blank(directory).absolutePath,null).id
            val before = session.document(doc)
            try { call(session,"text_font_check","path" to invalid.absolutePath,"text" to "text"); fail("invalid font") }
            catch (e: io.github.artcraftandroid.pdfcraft.core.MobileException) { assertTrue(e.message.orEmpty().isNotBlank()) }
            assertEquals(before.generation,session.document(doc).generation)
            assertEquals(before.undo,session.document(doc).undo)
        } finally { session.documents().forEach { session.close(it,true) } }
    }
}
