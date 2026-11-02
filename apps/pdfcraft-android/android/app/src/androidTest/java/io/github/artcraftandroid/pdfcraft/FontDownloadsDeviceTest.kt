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
    @Test fun missingSubsetIsRecoveredAutomaticallyWithoutChangingFaceOrStyle() = runBlocking {
        val directory = File(context.getExternalFilesDir(null), "font-auto-qa/${System.nanoTime()}").apply { mkdirs() }
        val catalog = readFontCatalog(context.assets.open("font-catalog.json").bufferedReader().use { it.readText() })
        val face = catalog.single { it.id == "ScheherazadeNew-Bold" }
        val full = withContext(Dispatchers.IO) { cacheDocumentFont(face, directory) }
        val source = File(directory, "subset.pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(612,792,1).create())
            page.canvas.drawText("Original", 60f, 100f, android.graphics.Paint().apply {
                typeface = android.graphics.Typeface.createFromFile(full); textSize = 22f; color = android.graphics.Color.rgb(36,87,173)
            })
            pdf.finishPage(page)
            source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val application = context.applicationContext as PdfcraftApplication
        val store = androidx.lifecycle.ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            io.github.artcraftandroid.pdfcraft.ui.AppViewModel(application).also { store.put("auto-font",it); it.open(android.net.Uri.fromFile(source)) }
        }
        try {
            kotlinx.coroutines.withTimeout(60000) {
                while (withContext(Dispatchers.Main) { vm.document == null }) kotlinx.coroutines.delay(50)
            }
            val doc = withContext(Dispatchers.Main) { requireNotNull(vm.document) }
            val engine = requireNotNull(vm.engine)
            val original = engine.tool("text_paragraphs", mapOf("doc" to doc.id,"page" to 1)).getJSONArray("paragraphs").getJSONObject(0)
            assertEquals(face, matchingOriginalFont(original.getString("font"),catalog))
            val draft = "Original Zxy"
            // A genuinely subset source must refuse first, without changing the document.
            try {
                engine.tool("text_edit",mapOf("doc" to doc.id,"page" to 1,"paragraph" to 1,"text" to draft,"preserve_font" to true))
                fail("The fixture must require the full original font")
            } catch (e: Exception) { assertTrue(e.message, needsOriginalFont(e)) }
            withContext(Dispatchers.Main) {
                vm.edit.loadParagraphs(doc,0)
                vm.edit.replaceParagraphText(doc, requireNotNull(vm.edit.paragraphs[0]).first(), draft)
            }
            kotlinx.coroutines.withTimeout(60000) {
                while (engine.tool("text_paragraphs",mapOf("doc" to doc.id,"page" to 1)).getJSONArray("paragraphs").getJSONObject(0).getString("editor_text") != draft) {
                    kotlinx.coroutines.delay(100)
                }
            }
            val saved = File(directory,"recovered.pdf")
            engine.tool("doc_save",mapOf("doc" to doc.id,"path" to saved.absolutePath,"full" to true))
            val reopened = engine.command { open(saved.absolutePath,null).id }
            val paragraph = engine.tool("text_paragraphs",mapOf("doc" to reopened,"page" to 1)).getJSONArray("paragraphs").getJSONObject(0)
            assertEquals(draft,paragraph.getString("editor_text"))
            assertEquals(face,matchingOriginalFont(paragraph.getString("font"),catalog))
            assertEquals(original.getString("color"),paragraph.getString("color"))
            assertEquals(original.getDouble("size"),paragraph.getDouble("size"),.01)
            engine.command { close(reopened, true) }
            assertTrue(File(vm.fonts.directory,"${face.id}.ttf").isFile)
        } finally {
            withContext(Dispatchers.Main) { vm.fonts.close(); store.clear() }
        }
    }

    @Test fun phoneFontRecoversSubsetWithoutDownloadOrImport() = runBlocking {
        val directory = File(context.getExternalFilesDir(null), "font-auto-qa/${System.nanoTime()}").apply { mkdirs() }
        val catalog = readFontCatalog(context.assets.open("font-catalog.json").bufferedReader().use { it.readText() })
        val engineForDiscovery = (context.applicationContext as PdfcraftApplication).engine.await().getOrThrow()
        val roots = phoneFontDirectories()
        assertTrue(roots.contains("/system/fonts"))
        val system = engineForDiscovery.tool("text_font_files", mapOf("directories" to roots)).getJSONArray("fonts")
        val candidates = List(system.length()) { system.getJSONObject(it) }
        val face = requireNotNull(candidates.firstOrNull { it.getString("font") == "NotoNaskhArabic-Bold" }) {
            "Missing expected static phone face: " + candidates.map { it.getString("font") }
        }.getString("font")
        val full = File(candidates.first { it.getString("font") == face }.getString("path"))
        val source = File(directory, "subset.pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(612,792,1).create())
            page.canvas.drawText("نص عربي", 60f, 100f, android.graphics.Paint().apply {
                typeface = android.graphics.Typeface.createFromFile(full); textSize = 22f; color = android.graphics.Color.rgb(36,87,173)
            })
            pdf.finishPage(page)
            source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val application = context.applicationContext as PdfcraftApplication
        val store = androidx.lifecycle.ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            io.github.artcraftandroid.pdfcraft.ui.AppViewModel(application).also { store.put("auto-font",it); it.open(android.net.Uri.fromFile(source)) }
        }
        try {
            kotlinx.coroutines.withTimeout(60000) {
                while (withContext(Dispatchers.Main) { vm.document == null }) kotlinx.coroutines.delay(50)
            }
            val doc = withContext(Dispatchers.Main) { requireNotNull(vm.document) }
            val engine = requireNotNull(vm.engine)
            val original = engine.tool("text_paragraphs", mapOf("doc" to doc.id,"page" to 1)).getJSONArray("paragraphs").getJSONObject(0)
            assertEquals(io.github.artcraftandroid.pdfcraft.ui.viewer.editorFaceKey(face), io.github.artcraftandroid.pdfcraft.ui.viewer.editorFaceKey(original.getString("font")))
            assertNull(matchingOriginalFont(original.getString("font"),catalog))
            val draft = original.getString("editor_text") + " جديد"
            // A genuinely subset source must refuse first, without changing the document.
            try {
                engine.tool("text_edit",mapOf("doc" to doc.id,"page" to 1,"paragraph" to 1,"text" to draft,"preserve_font" to true))
                fail("The fixture must require the full original font")
            } catch (e: Exception) { assertTrue(e.message, needsOriginalFont(e)) }
            withContext(Dispatchers.Main) {
                vm.edit.loadParagraphs(doc,0)
                vm.edit.replaceParagraphText(doc, requireNotNull(vm.edit.paragraphs[0]).first(), draft)
            }
            kotlinx.coroutines.withTimeout(60000) {
                while (engine.tool("text_paragraphs",mapOf("doc" to doc.id,"page" to 1)).getJSONArray("paragraphs").getJSONObject(0).getString("editor_text") != draft) {
                    kotlinx.coroutines.delay(100)
                }
            }
            val saved = File(directory,"recovered.pdf")
            engine.tool("doc_save",mapOf("doc" to doc.id,"path" to saved.absolutePath,"full" to true))
            val reopened = engine.command { open(saved.absolutePath,null).id }
            val paragraph = engine.tool("text_paragraphs",mapOf("doc" to reopened,"page" to 1)).getJSONArray("paragraphs").getJSONObject(0)
            assertEquals(draft,paragraph.getString("editor_text"))
            assertEquals(io.github.artcraftandroid.pdfcraft.ui.viewer.editorFaceKey(face),io.github.artcraftandroid.pdfcraft.ui.viewer.editorFaceKey(paragraph.getString("font")))
            assertEquals(original.getString("color"),paragraph.getString("color"))
            assertEquals(original.getDouble("size"),paragraph.getDouble("size"),.01)
            engine.command { close(reopened, true) }
            assertTrue(vm.fonts.phone.any { it.face == face && it.file.absolutePath.startsWith("/system/fonts/") })
            assertFalse(vm.fonts.directory.listFiles().orEmpty().any { it.name.contains("NotoNaskhArabic") })
        } finally {
            withContext(Dispatchers.Main) { vm.fonts.close(); store.clear() }
        }
    }

    @Test fun completeCollectionDownloadsAndRemainsUsableOffline() = runBlocking {
        val directory = File(context.getExternalFilesDir(null), "font-group-qa/${System.nanoTime()}").apply { mkdirs() }
        val catalog = readFontCatalog(context.assets.open("font-catalog.json").bufferedReader().use { it.readText() })
        val progress = mutableListOf<Float>()
        val files = withContext(Dispatchers.IO) { cacheDocumentFonts(catalog,directory,{ progress += it }) }
        assertEquals(6,files.size)
        assertEquals(3,catalog.groupBy { it.family }.size)
        assertEquals(1f,progress.last(),0f)
        assertTrue(progress.zipWithNext().all { (a,b) -> b >= a })
        val session = MobileSession()
        try {
            files.zip(catalog).forEach { (file,font) ->
                assertEquals(font.sha256,fontDigest(file.readBytes()))
                assertTrue(File(directory,"${font.id}-OFL.txt").isFile)
                assertTrue(call(session,"text_font_check","path" to file.absolutePath,"text" to if(font.arabic) "نص عربي" else "Sample").getBoolean("supported"))
            }
            assertEquals(files,cacheDocumentFonts(catalog,directory,transfer = { _,_,_,_,_ -> error("offline") }))
        } finally { session.close() }
    }

    @Test fun firstRunPreparesPhoneAndDownloadedFontsWithoutOpeningAPdf() = runBlocking {
        val directory = File(context.getExternalFilesDir(null),"font-startup-qa/${System.nanoTime()}")
        val store = androidx.lifecycle.ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            io.github.artcraftandroid.pdfcraft.ui.AppViewModel(context.applicationContext as PdfcraftApplication).also { store.put("startup-fonts",it) }
        }
        try {
            kotlinx.coroutines.withTimeout(60000) { while(withContext(Dispatchers.Main) { vm.engine == null }) kotlinx.coroutines.delay(50) }
            val manager = withContext(Dispatchers.Main) {
                io.github.artcraftandroid.pdfcraft.ui.DocumentFonts(vm,directory).also { it.initialize() }
            }
            kotlinx.coroutines.withTimeout(60000) {
                while(withContext(Dispatchers.Main) { manager.installingDefaults || manager.stored.size < manager.catalog.size || manager.phone.isEmpty() }) kotlinx.coroutines.delay(100)
            }
            assertFalse(manager.defaultsFailed)
            assertNull(vm.document)
            manager.catalog.forEach { font -> assertEquals(font.sha256,fontDigest(File(directory,"${font.id}.ttf").readBytes())) }
            val choices = io.github.artcraftandroid.pdfcraft.ui.installedDocumentFonts(manager.phone,manager.stored)
            assertTrue(choices.first().system)
            assertEquals(choices.map { io.github.artcraftandroid.pdfcraft.ui.viewer.editorFaceKey(it.font.face) }.distinct().size,choices.size)
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }

}
