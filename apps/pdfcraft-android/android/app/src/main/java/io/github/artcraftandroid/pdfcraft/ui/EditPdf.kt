package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage
import io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.ListStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.nearestObject
import io.github.artcraftandroid.pdfcraft.ui.viewer.readInlineRows
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edit PDF: the page's paragraphs and pictures, typing in them, and adding text and pictures. */
class EditPdf internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    var editMode by mutableStateOf(EditMode.Select)

    /** Each page's paragraphs (0-based page), read when Edit PDF shows the page. */
    val paragraphs = androidx.compose.runtime.mutableStateMapOf<Int, List<Paragraph>>()
    private var paragraphsGen = -1L

    /** The paragraph tapped once (outlined), and the one open in the editor. */
    var editSelection by mutableStateOf<Paragraph?>(null)
    var editing by mutableStateOf<Paragraph?>(null)

    suspend fun loadParagraphs(doc: OpenDocument, page: Int) {
        val eng = vm.engine ?: return
        if (paragraphsGen != doc.info.generation.toLong()) {
            paragraphs.clear()
            paragraphsGen = doc.info.generation.toLong()
        }
        if (paragraphs.containsKey(page)) return
        val r = runCatching { eng.tool("text_paragraphs", mapOf("doc" to doc.id, "page" to page + 1)) }.getOrNull() ?: return
        val arr = r.optJSONArray("paragraphs") ?: return
        val source = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val rect = o.optJSONArray("rect") ?: return@mapNotNull null
            val font = o.optString("font")
            Paragraph(
                // Editing must retain source row breaks. `text` is flattened for reading/search;
                // sending it back after typing causes the whole mixed-style block to reflow.
                page = page, number = o.optInt("paragraph"),
                text = if (o.has("editor_text") && !o.isNull("editor_text")) o.getString("editor_text") else o.optString("text"),
                box = FloatArray(4) { k -> rect.optDouble(k).toFloat() }, font = font, size = o.optDouble("size", 12.0).toFloat(),
                bold = if (o.has("bold")) o.optBoolean("bold") else font.contains("Bold", true) || font.contains("Black", true) || font.contains("Heavy", true),
                italic = if (o.has("italic")) o.optBoolean("italic") else font.contains("Italic", true) || font.contains("Oblique", true),
                color = o.optString("color").takeIf { it.startsWith("#") },
                rows = readInlineRows(o.optJSONArray("editor_rows")),
            )
        }
        // Keep added objects separate from extracted source paragraphs. In particular, Arabic
        // added text must be rewritten by content_update, which retains its shaping metadata.
        val inventory = runCatching { eng.tool("object_list", mapOf("doc" to doc.id, "page" to page + 1)).optJSONArray("objects") }.getOrNull()
        val sourceIndexes = (0 until (inventory?.length() ?: 0)).mapNotNull { i ->
            inventory?.optJSONObject(i)?.takeIf { it.optString("kind") == "text" }?.optInt("index")
        }.toSet()
        val added = runCatching { eng.tool("content_list", mapOf("doc" to doc.id, "page" to page + 1)).optJSONArray("items") }.getOrNull()
        val placed = (0 until (added?.length() ?: 0)).mapNotNull { i ->
            val o = added?.optJSONObject(i)?.takeIf { it.optInt("page") == page + 1 && it.optString("type") == "text" } ?: return@mapNotNull null
            val r = o.optJSONArray("rect") ?: return@mapNotNull null
            val ink = o.optJSONArray("ink_rect") ?: r
            val index = o.optInt("index")
            Paragraph(page, -index, o.optString("text"),
                FloatArray(4) { ink.optDouble(it).toFloat() }, o.optString("font"), o.optDouble("size", 12.0).toFloat(),
                o.optBoolean("bold"), o.optBoolean("italic"), null, addedIndex = index, placementBox = FloatArray(4) { r.optDouble(it).toFloat() })
        }
        paragraphs[page] = source.filter { inventory == null || it.number in sourceIndexes } + placed
        editSelection?.takeIf { it.page == page }?.let { sel -> editSelection = paragraphs[page]?.firstOrNull { it.number == sel.number } }
        editing?.takeIf { it.page == page }?.let { sel -> editing = paragraphs[page]?.firstOrNull { it.number == sel.number } }
    }

    /** A tap in Edit PDF: finish typing, place new text, or select the paragraph or picture under it. */
    fun editTap(doc: OpenDocument, at: PagePoint?) {
        editing?.let { commitInlineEdit(doc) }
        editImage = null
        if (at == null) {
            editSelection = null
            return
        }
        if (editMode == EditMode.AddText) {
            editMode = EditMode.Select
            editSelection = null
            addTextPrompt = TextPrompt(doc, at)
            return
        }
        if (editMode == EditMode.AddImage) {
            editMode = EditMode.Select
            editSelection = null
            addImageAt(doc, at)
            return
        }
        val candidates = paragraphs[at.page].orEmpty()
        val tolerance = 20f * app.resources.displayMetrics.density / doc.viewer.pixelsPerPoint(at.page).coerceAtLeast(0.01f)
        val index = nearestObject(candidates.map { androidx.compose.ui.geometry.Rect(it.box[0], it.box[1], it.box[2], it.box[3]) }, androidx.compose.ui.geometry.Offset(at.x, at.y), tolerance)
        val hit = index?.let { candidates[it] }
        editSelection = hit
        if (hit == null) selectImageAt(doc, at)
    }

    /** The picture selected in Edit PDF. */
    var editImage by mutableStateOf<EditImage?>(null)

    /** Formatting the engine doesn't report back, kept per paragraph (page to number) while editing. */
    val editFont = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, String>()
    val editAlign = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, String>()
    val editUnderline = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, Boolean>()

    /** Typing in a paragraph: whether it opened with everything selected, and the text so far. */
    var editSelectAll = false
    var editDraft: String? = null

    fun startInlineEdit(para: Paragraph, selectAll: Boolean) {
        editSelectAll = selectAll
        editDraft = para.text
        editSelection = para
        editing = para
    }

    /** Write the paragraph being typed in into the page (when it changed) and stop typing. */
    fun commitInlineEdit(doc: OpenDocument) {
        val para = editing ?: return
        val text = editDraft ?: para.text
        editing = null
        editDraft = null
        if (text != para.text) vm.runTool(doc, paragraphTool(para), paraArgs(para) + ("text" to text))
    }

    private fun paragraphTool(para: Paragraph) = if (para.addedIndex == null) "text_edit" else "content_update"
    private fun paraArgs(para: Paragraph) = mapOf<String, Any?>("page" to para.page + 1) +
        if (para.addedIndex == null) mapOf("paragraph" to para.number) else mapOf("index" to para.addedIndex)

    /** Make [para] a list of [style] (or plain again), with any typing so far. */
    fun listParagraph(doc: OpenDocument, para: Paragraph, style: ListStyle) {
        val typing = editing != null && editing?.number == para.number && editing?.page == para.page
        val base = if (typing) editDraft ?: para.text else para.text
        val text = ListStyle.apply(base, style)
        if (text == base) return
        if (typing) editDraft = text
        vm.runTool(doc, paragraphTool(para), paraArgs(para) + ("text" to text))
    }

    /** A formatting change to [para], applied to the page at once (with any typing so far). */
    fun styleParagraph(doc: OpenDocument, para: Paragraph, style: Map<String, Any?>) {
        val typed = editDraft?.takeIf { editing != null && it != para.text }
        if (para.addedIndex != null && style.keys.any { it !in setOf("size", "font", "bold", "italic", "color", "align") }) {
            vm.tell(text(R.string.msg_added_text_format_unsupported))
            return
        }
        vm.runTool(doc, paragraphTool(para), paraArgs(para) + style + (if (typed != null) mapOf("text" to typed) else emptyMap()))
    }

    /** Move [para] by ([dx], [dy]) points (right and down on screen). */
    fun moveParagraph(doc: OpenDocument, para: Paragraph, dx: Float, dy: Float) {
        vm.runTool(doc, "text_edit", paraArgs(para) + mapOf("dx" to dx.toDouble(), "dy" to -dy.toDouble()))
    }

    /** A touch transform preserves content and writes one history entry when the finger lifts. */
    fun transformParagraph(doc: OpenDocument, para: Paragraph,
        dx: Float, dy: Float, factor: Float, finished: () -> Unit) {
        val geometry = if (para.addedIndex != null) {
            val source = para.placementBox ?: para.box
            val left = source[0] + dx + (para.box[0] - source[0]) * (1f - factor)
            val top = source[1] + dy + (para.box[1] - source[1]) * (1f - factor)
            mapOf("rect" to listOf(left, top, left + (source[2] - source[0]) * factor,
                top + (source[3] - source[1]) * factor).map { it.toDouble() }) +
                (if (factor != 1f) mapOf("size" to (para.size * factor).toDouble()) else emptyMap())
        } else if (factor == 1f) mapOf("dx" to dx.toDouble(), "dy" to -dy.toDouble()) else
            mapOf("size" to (para.size * factor).toDouble(), "width" to ((para.box[2] - para.box[0]) * factor).toDouble())
        vm.runTool(doc, paragraphTool(para), paraArgs(para) + geometry, onFinished = finished)
    }

    /** Rewrap [para] to [width] points, moving it [shift] points right first (dragged from the left). */
    fun resizeParagraph(doc: OpenDocument, para: Paragraph, width: Float, shift: Float) {
        val args = paraArgs(para) + ("width" to width.toDouble()) + (if (shift != 0f) mapOf("dx" to shift.toDouble()) else emptyMap())
        vm.runTool(doc, "text_edit", args)
    }

    fun deleteParagraph(doc: OpenDocument, para: Paragraph) {
        editSelection = null
        editing = null
        if (para.addedIndex != null) vm.runTool(doc, "content_delete", paraArgs(para))
        else vm.runTool(doc, "text_edit", paraArgs(para) + ("text" to ""))
    }

    /** The page's colour just outside [para], for typing over it. */
    suspend fun paperColor(doc: OpenDocument, para: Paragraph): androidx.compose.ui.graphics.Color? {
        val eng = vm.engine ?: return null
        val x = (para.box[0] - 4f).coerceAtLeast(0f).toInt().toUInt()
        val y = (para.box[1] - 4f).coerceAtLeast(0f).toInt().toUInt()
        val img = runCatching {
            withContext(eng.thumbnails) { eng.session.renderPage(doc.id, para.page.toUInt(), 1f, io.github.artcraftandroid.pdfcraft.core.TileRect(x, y, 2u, 2u)) }
        }.getOrNull() ?: return null
        val px = img.rgba
        if (px.size < 4) return null
        return androidx.compose.ui.graphics.Color(px[0].toInt() and 0xff, px[1].toInt() and 0xff, px[2].toInt() and 0xff)
    }

    private fun selectImageAt(doc: OpenDocument, at: PagePoint) {
        vm.viewModelScope.launch { imageUnder(doc, at)?.let { editImage = it } }
    }

    /** The picture drawn under [at], topmost first, if any. */
    internal suspend fun imageUnder(doc: OpenDocument, at: PagePoint): EditImage? {
        val eng = vm.engine ?: return null
        val r = runCatching { eng.tool("page_images", mapOf("doc" to doc.id, "page" to at.page + 1)) }.getOrNull() ?: return null
        val arr = r.optJSONArray("images") ?: return null
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val rect = (o.optJSONArray("visible_rect") ?: o.optJSONArray("rect")) ?: continue
            val box = FloatArray(4) { k -> rect.optDouble(k).toFloat() }
            if (at.x in box[0]..box[2] && at.y in box[1]..box[3]) {
                val full = o.optJSONArray("rect") ?: rect
                return EditImage(at.page, o.optInt("image"), FloatArray(4) { k -> full.optDouble(k).toFloat() })
            }
        }
        return null
    }

    /** Put [img] in [rect] (points): a move or a resize. */
    fun placeImage(doc: OpenDocument, img: EditImage, rect: FloatArray) {
        editImage = img.copy(rect = rect)
        vm.runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "move", "rect" to rect.map { it.toDouble() }))
    }

    fun turnImage(doc: OpenDocument, img: EditImage, quarters: Int) {
        vm.runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "rotate", "quarters" to quarters)) {
            // the turned picture keeps its centre; read its new box
            selectImageAt(doc, PagePoint(img.page, (img.rect[0] + img.rect[2]) / 2f, (img.rect[1] + img.rect[3]) / 2f))
        }
    }

    fun deleteImage(doc: OpenDocument, img: EditImage) {
        editImage = null
        vm.runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "delete"))
    }

    /** New page text being typed in Edit PDF (Add text). */
    var addTextPrompt by mutableStateOf<TextPrompt?>(null)

    fun postAddText(prompt: TextPrompt, text: String) {
        addTextPrompt = null
        if (text.isBlank()) return
        vm.runTool(prompt.doc, "page_add_text", mapOf(
            "page" to prompt.at.page + 1, "text" to text, "at" to listOf(prompt.at.x.toDouble(), prompt.at.y.toDouble()),
            "size" to 12.0, "font" to "helvetica",
        )) {
            vm.viewModelScope.launch {
                loadParagraphs(prompt.doc, prompt.at.page)
                val at = prompt.at
                editSelection = paragraphs[at.page].orEmpty().filter { it.text == text }.minByOrNull {
                    kotlin.math.abs(it.box[0] - at.x) + kotlin.math.abs(it.box[1] - at.y)
                }
            }
        }
    }

    /** Add image: the activity picks a picture, then [addImage] places it on the current page. */
    val imagePicks = Channel<OpenDocument>(Channel.BUFFERED)
    private var imageFor: OpenDocument? = null

    /** Where the picture being picked goes (centred there); null: the current page as before. */
    private var imageAt: PagePoint? = null

    fun pickEditImage(doc: OpenDocument) {
        imageFor = doc
        imagePicks.trySend(doc)
    }

    /** Add image at a tapped point (Edit PDF's Add image mode, or the page menu): first, from where. */
    fun addImageAt(doc: OpenDocument, at: PagePoint) {
        imageSource = doc to at
    }

    /** Asking where the picture comes from (photos or the camera), as the reference's menu does. */
    var imageSource by mutableStateOf<Pair<OpenDocument, PagePoint>?>(null)

    fun addImageFrom(camera: Boolean) {
        val (doc, at) = imageSource ?: return
        imageSource = null
        imageAt = at
        if (!camera) {
            pickEditImage(doc)
            return
        }
        imageFor = doc
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "image-${System.nanoTime()}.jpg")
        vm.shotFile = f
        imageShot = true
        vm.cameraShots.trySend(f)
    }

    /** The camera's photo is for Add image, not a scan. */
    internal var imageShot = false

    fun addImage(uri: Uri?) {
        val doc = imageFor ?: return
        imageFor = null
        if (uri == null) return
        vm.viewModelScope.launch {
            try {
                val name = vm.files.displayName(uri).removeSuffix(".pdf")
                val f = File(app.cacheDir, "image-${System.nanoTime()}-${name}")
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { text(R.string.msg_picture_unreadable) }
                        f.outputStream().use { input.copyTo(it) }
                    }
                }
                if (name.lowercase().endsWith(".gif")) {
                    vm.tell(text(R.string.msg_gif_not_supported))
                    return@launch
                }
                val eng = vm.engine ?: return@launch
                val at = imageAt
                imageAt = null
                val args = mutableMapOf<String, Any?>("doc" to doc.id, "page" to (at?.page ?: doc.viewer.currentPage) + 1, "path" to f.absolutePath)
                // centred on the tapped point, a third of the page wide, kept on the page
                val page = at?.let { doc.info.pages.getOrNull(it.page) }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
                if (at != null && page != null && bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var w = page.width / 3f
                    var h = w * bounds.outHeight / bounds.outWidth
                    if (h > page.height / 2f) {
                        h = page.height / 2f
                        w = h * bounds.outWidth / bounds.outHeight
                    }
                    val x0 = (at.x - w / 2f).coerceIn(0f, (page.width - w).coerceAtLeast(0f))
                    val y0 = (at.y - h / 2f).coerceIn(0f, (page.height - h).coerceAtLeast(0f))
                    args["rect"] = listOf(x0, y0, x0 + w, y0 + h).map { it.toDouble() }
                }
                eng.tool("page_add_image", args)
                vm.refresh(doc)
            } catch (e: Exception) {
                vm.tell(e.userMessage)
            }
        }
    }

    /** The camera app came back with [f] for Add image: [ok] when it took the photo. */
    internal fun onCameraPhoto(f: File, ok: Boolean) {
        imageShot = false
        if (!ok || !f.isFile || f.length() == 0L) {
            imageFor = null
            imageAt = null
            return
        }
        // turned upright as the camera meant it (the page keeps no orientation tag), then placed
        vm.viewModelScope.launch {
            val upright = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let { rotateForExif(f, it) } ?: return@runCatching f
                    val out = File(f.parentFile, "upright-${f.name}")
                    out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    out
                }.getOrDefault(f)
            }
            addImage(Uri.fromFile(upright))
        }
    }
}
