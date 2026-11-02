package io.github.artcraftandroid.pdfcraft

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.artcraftandroid.pdfcraft.core.MobileSession
import io.github.artcraftandroid.pdfcraft.core.engineVersion
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the same native tools as the UI, on Android rather than the host test runner. */
@RunWith(AndroidJUnit4::class)
class EngineToolsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var session: MobileSession
    private lateinit var dir: File
    private fun call(name: String, vararg args: Pair<String, Any?>): JSONObject =
        JSONObject(session.execute(name, JSONObject(args.toMap()).toString()))

    @Before fun setUp() {
        session = MobileSession()
        dir = File(context.getExternalFilesDir(null), "engine-017-qa/${System.nanoTime()}").apply { mkdirs() }
        assertEquals("0.17.0", engineVersion())
    }

    @After fun closeDocuments() { session.documents().forEach { session.close(it, true) } }

    private fun blank(pages: Int = 1): ULong {
        val file = File(dir, "blank-${System.nanoTime()}.pdf")
        val pdf = PdfDocument()
        try {
            repeat(pages) { i ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(612, 792, i + 1).create())
                page.canvas.drawColor(Color.WHITE)
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return session.open(file.absolutePath, null).id
    }

    private fun text(doc: ULong, value: String, size: Int = 16, y: Int = 60) =
        call("page_add_text", "doc" to doc.toLong(), "page" to 1, "text" to value,
            "at" to listOf(60, y), "width" to 480, "size" to size)

    private fun save(doc: ULong, name: String): File = File(dir, name).also {
        call("doc_save", "doc" to doc.toLong(), "path" to it.absolutePath, "full" to true)
    }

    /** Turn generated test text into ordinary source content, without changing its glyphs/fonts.
     * Equal-length private-key renames preserve PDF byte offsets. Not a production file operation. */
    private fun ordinarySource(doc: ULong): ULong {
        val file = save(doc, "source-${doc}.pdf")
        val bytes = file.readBytes()
        val data = bytes.toString(Charsets.ISO_8859_1)
        assertTrue("fixture must contain the added-object marker", data.contains("/PCAdded"))
        val source = data.replace("/PCAdded", "/QAAdded").replace("/PCMark", "/QAMark").toByteArray(Charsets.ISO_8859_1)
        assertEquals(bytes.size, source.size)
        file.writeBytes(source)
        return session.open(file.absolutePath, null).id.also {
            assertEquals(0, call("content_list", "doc" to it.toLong(), "page" to 1).getJSONArray("items").length())
        }
    }

    private fun picture(name: String = "picture.png", color: Int = Color.BLUE): File {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        for (x in 0 until 30) for (y in 0 until 20) bitmap.setPixel(x, y, Color.RED)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun addPicture(doc: ULong, file: File = picture()) {
        call("page_add_image", "doc" to doc.toLong(), "page" to 1, "path" to file.absolutePath,
            "rect" to listOf(80, 180, 280, 340))
    }

    @Test fun arabicEditingSaveReopenAndUndo() {
        val doc = blank()
        text(doc, "اختبار العربية", 24)
        val items = call("content_list", "doc" to doc.toLong(), "page" to 1).getJSONArray("items")
        val index = items.getJSONObject(0).getInt("index")
        call("content_update", "doc" to doc.toLong(), "page" to 1, "index" to index, "text" to "تم تعديل النص العربي")
        val saved = save(doc, "arabic-edited.pdf")
        val reopened = session.open(saved.absolutePath, null).id
        assertTrue(call("text_lines", "doc" to reopened.toLong(), "page" to 1).toString().contains("تم تعديل النص العربي"))
        session.renderPage(reopened, 0u, .5f, null).toBitmap().let { image ->
            File(dir, "arabic-render.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        call("edit_undo", "doc" to doc.toLong())
        assertTrue(call("content_list", "doc" to doc.toLong(), "page" to 1).toString().contains("اختبار العربية"))
    }

    @Test fun documentFontsRemainOriginalWhileEditingAndAfterReopen() {
        val fontFile = File(dir, "Inter-Regular.ttf")
        context.resources.openRawResource(R.font.inter_regular).use { input -> fontFile.outputStream().use { input.copyTo(it) } }
        for ((before, after) in listOf("Original English text" to "Edited English text", "نص عربي أصلي" to "نص عربي معدل")) {
            var doc = blank()
            text(doc, before, 22)
            if (before.startsWith("Original")) {
                val resource = call("text_font_import", "doc" to doc.toLong(), "page" to 1, "path" to fontFile.absolutePath).getString("resource")
                call("text_paragraphs", "doc" to doc.toLong(), "page" to 1)
                call("text_edit", "doc" to doc.toLong(), "page" to 1, "paragraph" to 1, "font_resource" to resource, "text" to before)
            }
            doc = ordinarySource(doc)
            fun paragraph(id: ULong) = call("text_paragraphs", "doc" to id.toLong(), "page" to 1).getJSONArray("paragraphs").getJSONObject(0)
            val originalFont = paragraph(doc).getString("font")
            save(doc, "ui-original.pdf").copyTo(File(context.getExternalFilesDir(null),
                if (before.startsWith("Original")) "font-ui-english.pdf" else "font-ui-arabic.pdf"), overwrite = true)
            val generation = session.document(doc).generation
            val fonts = session.editorFonts(doc, 0u)
            assertTrue("embedded document font available for typing: $originalFont", fonts.isNotEmpty())
            val previews = io.github.artcraftandroid.pdfcraft.ui.viewer.documentEditorTypefaces(fonts)
            assertTrue(previews.containsKey(io.github.artcraftandroid.pdfcraft.ui.viewer.editorFaceKey(originalFont)))
            assertEquals(generation, session.document(doc).generation)
            call("text_edit", "doc" to doc.toLong(), "page" to 1, "paragraph" to 1, "text" to after, "preserve_font" to true)
            val reopened = session.open(save(doc, "font-preserved-${doc}.pdf").absolutePath, null).id
            assertEquals(io.github.artcraftandroid.pdfcraft.ui.viewer.editorFaceKey(originalFont),
                io.github.artcraftandroid.pdfcraft.ui.viewer.editorFaceKey(paragraph(reopened).getString("font")))
            assertEquals(after, paragraph(reopened).getString("editor_text"))
            assertTrue(session.renderPage(reopened, 0u, 1f, null).rgba.isNotEmpty())
        }
        // Independent reference: the preview face must measure like the packaged source Inter,
        // rather than Android's default sans-serif used by the old editor.
        val bytes = fontFile.readBytes()
        val faces = io.github.artcraftandroid.pdfcraft.ui.viewer.documentEditorTypefaces(listOf(
            io.github.artcraftandroid.pdfcraft.core.EditorFont("F1", "ABCDEF+Inter-Regular", bytes)))
        val actual = android.graphics.Paint().apply { textSize = 40f; typeface = faces.getValue("interregular") }.measureText("Wiim original")
        val expected = android.graphics.Paint().apply { textSize = 40f; typeface = context.resources.getFont(R.font.inter_regular) }.measureText("Wiim original")
        assertEquals(expected, actual, .1f)
    }

    @Test fun unavailableOriginalFontDoesNotSilentlyChangeTheSavedParagraph() {
        val generated = blank()
        text(generated, "Original English text", 22)
        val doc = ordinarySource(generated)
        call("text_paragraphs", "doc" to doc.toLong(), "page" to 1)
        val before = session.document(doc)
        try {
            call("text_edit", "doc" to doc.toLong(), "page" to 1, "paragraph" to 1, "text" to "نص عربي", "preserve_font" to true)
            fail("Must not substitute Noto for the original Helvetica without an explicit font choice")
        } catch (e: io.github.artcraftandroid.pdfcraft.core.MobileException) {
            assertTrue(e.message.orEmpty().contains("complete source font"))
        }
        assertEquals(before.generation, session.document(doc).generation)
        assertEquals(before.undo, session.document(doc).undo)
        val reopened = session.open(save(doc, "refused-font-change.pdf").absolutePath, null).id
        assertTrue(session.pagePlainText(reopened, 0u).contains("Original English text"))
        assertFalse(session.pagePlainText(reopened, 0u).contains("نص عربي"))
    }

    @Test fun typingRetainsColorSizeWeightItalicAndRowFormattingAfterReopen() {
        for (arabic in listOf(false, true)) {
            val original = if (arabic) "النص العربي الأول\nالسطر الثاني" else "Original first line\nSecond line"
            val edited = if (arabic) original.replace("الأول", "أول") else original.replace("Original", "Edited")
            val generated = blank()
            text(generated, original, 22)
            val doc = ordinarySource(generated)
            fun paragraph(id: ULong) = call("text_paragraphs", "doc" to id.toLong(), "page" to 1).getJSONArray("paragraphs").getJSONObject(0)
            paragraph(doc)
            val fontArgs: Array<Pair<String, Any?>> = if (arabic) {
                val bold = session.editorFonts(doc, 0u).first { it.resource.startsWith("PCApproved") && it.resource.endsWith("Bold") }
                val file = File(dir, "Arabic-Bold.ttf").apply { writeBytes(bold.bytes) }
                val resource = call("text_font_import", "doc" to doc.toLong(), "page" to 1, "path" to file.absolutePath).getString("resource")
                arrayOf("font_resource" to resource)
            } else arrayOf("font" to "courier", "bold" to true, "italic" to true)
            call("text_edit", "doc" to doc.toLong(), "page" to 1, "paragraph" to 1, "text" to original,
                "size" to 22, "width" to 480, "color" to if (arabic) "#2457AD" else "#AA3344", *fontArgs)
            val before = paragraph(doc)
            val firstRun = before.getJSONArray("editor_rows").getJSONObject(0).getJSONArray("runs").getJSONObject(0)
            assertTrue(firstRun.getBoolean("bold"))
            assertEquals(!arabic, firstRun.getBoolean("italic"))
            assertEquals(2, before.getJSONArray("editor_rows").length())
            call("text_edit", "doc" to doc.toLong(), "page" to 1, "paragraph" to 1, "text" to edited, "preserve_font" to true)
            val reopened = session.open(save(doc, "format-preserved-${arabic}.pdf").absolutePath, null).id
            val after = paragraph(reopened)
            assertEquals(edited, after.getString("editor_text"))
            for (key in listOf("font", "size", "color")) assertEquals("$key / Arabic=$arabic", before.get(key), after.get(key))
            val a = before.getJSONArray("editor_rows")
            val b = after.getJSONArray("editor_rows")
            assertEquals(a.length(), b.length())
            for (row in 0 until a.length()) {
                assertEquals(a.getJSONObject(row).getDouble("baseline"), b.getJSONObject(row).getDouble("baseline"), .01)
                val sourceRun = a.getJSONObject(row).getJSONArray("runs").getJSONObject(0)
                val runs = b.getJSONObject(row).getJSONArray("runs")
                for (i in 0 until runs.length()) for (key in listOf("font", "size", "color", "bold", "italic"))
                    assertEquals("run $row/$i $key", sourceRun.get(key).toString(), runs.getJSONObject(i).get(key).toString())
            }
        }
    }

    @Test fun retypingAddedTextKeepsItsColorAndFormattingMetadata() {
        for (arabic in listOf(false, true)) {
            val doc = blank()
            val original = if (arabic) "نص عربي ملون" else "Colored English text"
            text(doc, original, 26)
            call("content_update", "doc" to doc.toLong(), "page" to 1, "index" to 1,
                "font" to "courier", "bold" to true, "italic" to true, "color" to "#2457AD", "align" to "center", "char_spacing" to .2)
            fun item(id: ULong) = call("content_list", "doc" to id.toLong(), "page" to 1).getJSONArray("items").getJSONObject(0)
            val before = item(doc)
            assertEquals("#2457AD", before.getString("color"))
            call("content_update", "doc" to doc.toLong(), "page" to 1, "index" to 1,
                "text" to if (arabic) "نص عربي جديد" else "Edited English text")
            val file = save(doc, "added-format-${arabic}.pdf")
            val reopened = session.open(file.absolutePath, null).id
            val after = item(reopened)
            for (key in listOf("font", "size", "color", "bold", "italic", "char_spacing"))
                assertEquals("$key / Arabic=$arabic", before.get(key), after.get(key))
            file.copyTo(File(context.getExternalFilesDir(null),
                if (arabic) "font-ui-arabic-colored.pdf" else "font-ui-english-colored.pdf"), overwrite = true)
        }
    }

    @Test fun imageCropFlipReplaceResizeExportAndHistory() {
        val doc = blank()
        addPicture(doc)
        fun edit(action: String, vararg extra: Pair<String, Any?>) = call("image_edit", "doc" to doc.toLong(), "page" to 1, "image" to 1, "action" to action, *extra)
        edit("crop", "rect" to listOf(100, 200, 260, 320))
        assertTrue(call("page_images", "doc" to doc.toLong(), "page" to 1).getJSONArray("images").getJSONObject(0).getBoolean("has_editor_crop"))
        edit("flip_horizontal")
        edit("flip_vertical")
        edit("rotate", "quarters" to 1)
        edit("move", "rect" to listOf(120, 200, 360, 300))
        edit("remove_crop")
        assertFalse(call("page_images", "doc" to doc.toLong(), "page" to 1).getJSONArray("images").getJSONObject(0).getBoolean("has_editor_crop"))
        edit("replace", "path" to picture("replacement.png", Color.GREEN).absolutePath)
        val exported = call("image_save", "doc" to doc.toLong(), "page" to 1, "image" to 1, "path" to File(dir, "exported-image").absolutePath)
        assertTrue(File(exported.getString("path")).length() > 0)
        assertNotNull(session.document(doc).undo)
        call("edit_undo", "doc" to doc.toLong())
        call("edit_redo", "doc" to doc.toLong())
        val again = session.open(save(doc, "images.pdf").absolutePath, null).id
        assertEquals(1, call("page_images", "doc" to again.toLong(), "page" to 1).getInt("count"))
        assertTrue(session.renderPage(again, 0u, .3f, null).rgba.isNotEmpty())
    }

    @Test fun imageStackingKeepsBothImagesAndCanUndo() {
        val doc = blank()
        addPicture(doc)
        addPicture(doc, picture("second.png", Color.GREEN))
        for (action in listOf("bring_to_front", "send_to_back", "bring_forward", "send_backward")) {
            // Start each action from the matching end of a fresh drawing order.
            val n = if (action in listOf("bring_to_front", "bring_forward")) 1 else 2
            call("image_edit", "doc" to doc.toLong(), "page" to 1, "image" to n, "action" to action)
            assertEquals(2, call("page_images", "doc" to doc.toLong(), "page" to 1).getInt("count"))
            call("edit_undo", "doc" to doc.toLong())
        }
    }

    @Test fun markdownAndOfficeExportsContainText() {
        val doc = blank()
        text(doc, "Heading", 24)
        text(doc, "Bilingual document", y = 110)
        for (ext in listOf("md", "docx", "xlsx", "pptx", "rtf")) {
            val out = File(dir, "export.$ext")
            call("doc_export_office", "doc" to doc.toLong(), "path" to out.absolutePath)
            assertTrue("Empty $ext export", out.length() > 0)
            if (ext == "md") assertTrue(out.readText().contains("Bilingual document"))
        }
    }

    @Test fun markdownDownloadsAndRetainedSharingPreserveMimeAndContent() {
        val file = File(dir, "share-test.md").apply { writeText("# عنوان\n\nنص عربي قابل للمشاركة") }
        val bytes = file.readBytes()
        val files = DocumentFiles(context)
        val download = files.saveToDownloads(file, "PdfCraft-QA-share-${System.nanoTime()}.md", "text/markdown")
        assertEquals("text/markdown", context.contentResolver.getType(download))
        context.contentResolver.openInputStream(download).use { assertArrayEquals(bytes, it!!.readBytes()) }
        val share = files.shareableAll(listOf(file to "qa.md")).single()
        assertTrue(file.delete())
        context.contentResolver.openInputStream(share).use { assertArrayEquals(bytes, it!!.readBytes()) }
        context.contentResolver.delete(download, null, null)
    }

    @Test fun monochromeCompressionVariantsRemainReadable() {
        val doc = blank()
        val image = File(dir, "mono.png")
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = Color.BLACK }
        for (y in 0 until 1200 step 20) canvas.drawRect(0f, y.toFloat(), 1600f, y + 8f, paint)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        addPicture(doc, image)
        text(doc, "Compression test")
        for (format in listOf("ccitt_g4", "jbig2", "retain")) {
            val out = File(dir, "compressed-$format.pdf")
            call("doc_optimize", "doc" to doc.toLong(), "path" to out.absolutePath,
                "mono" to mapOf("compression" to format, "downsample" to true, "ppi" to 300, "above_ppi" to 450))
            val again = session.open(out.absolutePath, null).id
            assertTrue(session.renderPage(again, 0u, .2f, null).rgba.isNotEmpty())
        }
    }

    @Test fun commentsImportAndHeadingGenerationAreUndoable() {
        val source = blank()
        call("comment_add", "doc" to source.toLong(), "page" to 1, "type" to "note", "at" to listOf(80, 80), "contents" to "تعليق مستورد")
        val sourceFile = save(source, "comments-source.pdf")
        val target = blank()
        call("doc_import_data", "doc" to target.toLong(), "path" to sourceFile.absolutePath)
        assertTrue(session.comments(target).any { it.contents?.contains("تعليق مستورد") == true })
        call("edit_undo", "doc" to target.toLong())
        assertTrue(session.comments(target).isEmpty())
        text(target, "عنوان عربي", 24)
        text(target, "Body paragraph", 12, 120)
        val styles = call("text_styles", "doc" to target.toLong()).getJSONArray("styles")
        val first = (0 until styles.length()).map { styles.getJSONObject(it) }.maxBy { it.getDouble("size") }
        call("bookmark_from_text_styles", "doc" to target.toLong(), "levels" to listOf(mapOf("font" to first.getString("font"),
            "size" to first.getDouble("size"), "bold" to first.getBoolean("bold"), "italic" to first.getBoolean("italic"))))
        assertTrue(session.outline(target).any { it.title.contains("عنوان عربي") })
        call("edit_undo", "doc" to target.toLong())
        assertTrue(session.outline(target).isEmpty())
    }

    @Test fun deletePagesPersistsAndSurvivingPageRenders() {
        val doc = blank(3)
        text(doc, "Surviving page")
        call("page_delete", "doc" to doc.toLong(), "pages" to listOf(2, 3))
        val out = File(dir, "deleted-pages.pdf")
        call("doc_save", "doc" to doc.toLong(), "path" to out.absolutePath)
        val again = session.open(out.absolutePath, null).id
        assertEquals(1, session.document(again).pages.size)
        assertTrue(call("text_lines", "doc" to again.toLong(), "page" to 1).toString().contains("Surviving page"))
    }

    @Test fun paragraphBlankLinesAndPerLineAlignmentSurviveSave() {
        val doc = blank()
        text(doc, "السطر الأول\nالسطر الثاني\nالسطر الثالث")
        val item = call("content_list", "doc" to doc.toLong(), "page" to 1).getJSONArray("items").getJSONObject(0)
        call("content_update", "doc" to doc.toLong(), "page" to 1, "index" to item.getInt("index"),
            "text" to "السطر الأول\n\nالسطر الثاني\nالسطر الثالث", "aligns" to listOf(null, null, "right", null))
        val out = save(doc, "paragraph-lines.pdf")
        val again = session.open(out.absolutePath, null).id
        val items = call("content_list", "doc" to again.toLong(), "page" to 1).getJSONArray("items")
        assertTrue(items.toString().contains("\\n\\n"))
        assertTrue(session.renderPage(again, 0u, .3f, null).rgba.isNotEmpty())
    }

    @Test fun deletingOneRadioButtonKeepsTheOtherAndFormValuesSave() {
        val doc = blank()
        call("form_add_field", "doc" to doc.toLong(), "page" to 1, "type" to "text", "name" to "Name",
            "rect" to listOf(60, 60, 250, 90))
        for ((i, value) in listOf("A", "B").withIndex()) {
            call("form_add_field", "doc" to doc.toLong(), "page" to 1, "type" to "radio", "group" to "Choice",
                "export" to value, "rect" to listOf(60 + i * 40, 120, 80 + i * 40, 140))
        }
        call("form_fill", "doc" to doc.toLong(), "values" to mapOf("Name" to "Device test", "Choice" to "B"))
        call("form_delete_field", "doc" to doc.toLong(), "field" to "Choice", "widget" to 1)
        val again = session.open(save(doc, "form.pdf").absolutePath, null).id
        assertEquals(2, session.widgets(again).size)
        assertTrue(call("form_fields", "doc" to again.toLong()).toString().contains("Device test"))
    }

    @Test fun wordAttachmentAndVoiceCommentSurviveSave() {
        val word = File(dir, "Arabic-test.docx")
        ZipOutputStream(word.outputStream()).use { zip ->
            fun entry(name: String, xml: String) { zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray()); zip.closeEntry() }
            entry("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            entry("word/document.xml", """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>اختبار مرفق وورد</w:t></w:r></w:p></w:body></w:document>""")
        }
        val doc = blank()
        text(doc, "اختبار المرفقات والصوت", 24)
        addPicture(doc)
        call("attachment_add", "doc" to doc.toLong(), "path" to word.absolutePath)
        val wav = File(dir, "voice.wav")
        val buffer = ByteBuffer.allocate(444).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(436).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(400).put(ByteArray(400))
        wav.writeBytes(buffer.array())
        call("comment_add", "doc" to doc.toLong(), "page" to 1, "type" to "sound", "at" to listOf(60, 130), "path" to wav.absolutePath)
        val saved = save(doc, "PdfCraft-Engine-QA.pdf")
        val again = session.open(saved.absolutePath, null).id
        assertEquals(1, session.attachments(again).size)
        val attachment = File(dir, "extracted.docx")
        session.saveAttachment(again, 0u, attachment.absolutePath)
        assertArrayEquals(word.readBytes(), attachment.readBytes())
        val sound = session.comments(again).first { it.subtype == "Sound" }
        val extracted = File(dir, "extracted.wav")
        session.saveSound(again, 0u, sound.index, extracted.absolutePath)
        assertTrue(extracted.length() >= 44)
        val converted = File(dir, "word.pdf")
        call("doc_create_multiple", "paths" to listOf(word.absolutePath), "out" to converted.absolutePath)
        val office = session.open(converted.absolutePath, null).id
        assertTrue(session.renderPage(office, 0u, .3f, null).rgba.isNotEmpty())
        File(context.getExternalFilesDir(null), "PdfCraft-Engine-QA.pdf").writeBytes(saved.readBytes())
    }
}
