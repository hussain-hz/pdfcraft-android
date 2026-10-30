package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The most files Create PDF converts at once. */
private const val MAX_CREATE_FILES = 12

/** Tool jobs (Compress, Combine, Protect, Crop, Export, Create PDF, Extract pages): each runs on the phone and saves its result into Downloads. */
class ToolJobs internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()
    private val appPrefs = vm.getApplication<Application>().getSharedPreferences("app", Context.MODE_PRIVATE)

    /** The files gathered for Combine, in order. */
    val combineFiles = mutableStateListOf<PickedFile>()

    /** A copy of a picked file in the app's cache, under its own name. */
    internal fun copyIn(uri: Uri): File {
        val dir = File(app.cacheDir, "picked/${System.nanoTime()}").apply { mkdirs() }
        val out = File(dir, vm.files.displayNameRaw(uri))
        app.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { text(R.string.msg_named_file_unreadable, out.name) }
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** Run [block] with the busy dialog showing [message]; failures are told in a snackbar. */
    internal fun job(message: String, block: suspend (Engine) -> Unit) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            vm.busy = message
            try {
                block(eng)
            } catch (e: Exception) {
                vm.tell(e.userMessage)
            } finally {
                vm.busy = null
            }
        }
    }

    /** Put [file] into Downloads as [name] and say so, with Open. */
    private suspend fun deliver(file: File, name: String, mime: String = "application/pdf") {
        val uri = withContext(Dispatchers.IO) { vm.files.saveToDownloads(file, name, mime).also { file.delete() } }
        val size = withContext(Dispatchers.IO) { vm.files.size(uri) }
        val saved = size?.let { text(R.string.msg_saved_downloads_size, name, formatSize(it)) } ?: text(R.string.msg_saved_downloads, name)
        if (mime == "application/pdf") vm.tell(saved, text(R.string.common_open)) { vm.open(uri) }
        else vm.tell(saved, text(R.string.common_open)) {
            vm.intents.trySend(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }
    }

    internal fun tempFile(ext: String) = File(app.cacheDir, "out-${System.nanoTime()}.$ext")

    /** Compress PDF at [level] (0 high, 1 medium, 2 low compression). */
    fun compress(doc: OpenDocument, level: Int) = job(text(R.string.msg_busy_compressing)) { eng ->
        val (ppi, above, quality) = when (level) {
            0 -> Triple(72, 108, 40)
            2 -> Triple(300, 450, 80)
            else -> Triple(150, 225, 60)
        }
        val images = mapOf("downsample" to true, "ppi" to ppi, "above_ppi" to above, "compression" to "jpeg", "quality" to quality)
        val out = tempFile("pdf")
        eng.tool("doc_optimize", mapOf(
            "doc" to doc.id, "path" to out.absolutePath, "color" to images, "gray" to images,
            "discard_thumbnails" to true, "remove_unreferenced_dests" to true,
        ))
        vm.toolScreen = null
        if (out.length() >= doc.info.fileSize.toLong()) {
            // as the reference: nothing is saved when the file can't get any smaller
            out.delete()
            vm.tell(text(R.string.msg_compress_no_gain))
            return@job
        }
        deliver(out, "${doc.title}_compressed.pdf")
    }

    /** Combine the gathered files, in order, into [name].pdf. */
    fun combine(name: String) = job(text(R.string.msg_busy_combining)) { eng ->
        val paths = withContext(Dispatchers.IO) { combineFiles.map { copyIn(it.uri).absolutePath } }
        val out = tempFile("pdf")
        eng.tool("doc_combine", mapOf("paths" to paths, "out" to out.absolutePath))
        vm.toolScreen = null
        combineFiles.clear()
        deliver(out, DocumentFiles.safeName(name))
    }

    /**
     * A protected copy of [doc] with [password] as its open password. The copy is made from a
     * second, temporary engine document, so the open one keeps its file and history.
     */
    fun protect(doc: OpenDocument, password: String) = job(text(R.string.msg_busy_setting_password)) { eng ->
        vm.save(doc)
        val out = tempFile("pdf")
        val copy = eng.tool("doc_open", mapOf("path" to doc.file.absolutePath, "password" to doc.password)).optLong("doc")
        try {
            eng.tool("doc_protect", mapOf("doc" to copy, "open_password" to password))
            eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
        } finally {
            runCatching { eng.tool("doc_close", mapOf("doc" to copy, "discard_changes" to true)) }
        }
        vm.toolScreen = null
        deliver(out, "${doc.title}_protected.pdf")
    }

    /** Crop [page] (or every page) to [rect] (points from the page's top-left). */
    fun crop(doc: OpenDocument, page: Int?, rect: FloatArray) {
        val args = mutableMapOf<String, Any?>("rect" to rect.map { it.toDouble() })
        if (page != null) args["pages"] = listOf(page + 1)
        vm.runTool(doc, "page_set_box", args) {
            vm.toolScreen = null
            vm.tell(text(if (page == null) R.string.msg_crop_all else R.string.msg_crop_page))
        }
    }

    /** Export [doc] as a Word, RTF, Excel or PowerPoint document. */
    fun exportDocument(doc: OpenDocument, ext: String) = job(text(R.string.msg_busy_exporting_to, ext.uppercase())) { eng ->
        val out = tempFile(ext)
        eng.tool("doc_export_office", mapOf("doc" to doc.id, "path" to out.absolutePath))
        vm.toolScreen = null
        val mime = when (ext) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "rtf" -> "application/rtf"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "text/html"
        }
        deliver(out, "${doc.title}.$ext", mime)
    }

    /** Every page of [doc] as an image (200 dpi) in Pictures/PdfCraft. */
    /** Export to images: the format chosen, while its pages are being picked in the page grid. */
    var imageExport by mutableStateOf<String?>(null)

    /** The image format last exported to, offered first next time (as the reference remembers it). */
    val lastImageFormat: String get() = appPrefs.getString("imageFormat", "jpg") ?: "jpg"

    /** Export to images: remember the format, then pick the pages in the page grid (Save or Share there). */
    fun exportImages(doc: OpenDocument, ext: String) {
        appPrefs.edit { putString("imageFormat", ext) }
        vm.toolScreen = null
        imageExport = ext
        vm.organizing = true
        doc.controller.pagesOpen = true
    }

    /** Pictures of [pages] (0-based) at 200 dpi, saved to Pictures/PdfCraft or handed to the share sheet. */
    fun writeImages(doc: OpenDocument, ext: String, pages: List<Int>, share: Boolean) = job(text(R.string.msg_busy_exporting_images)) { eng ->
        val mime = if (ext == "png") "image/png" else "image/jpeg"
        val made = mutableListOf<Pair<File, String>>()
        for ((n, i) in pages.withIndex()) {
            vm.busy = text(R.string.msg_busy_exporting_page, n + 1, pages.size)
            val img = withContext(eng.renders) { eng.session.renderPage(doc.id, i.toUInt(), 200f / 72f, null) }
            val out = tempFile(ext)
            withContext(Dispatchers.IO) {
                val bmp = img.toBitmap()
                // JPEG has no transparency: put the page on white
                val flat = if (ext == "png") bmp else createBitmap(bmp.width, bmp.height).also {
                    val c = android.graphics.Canvas(it)
                    c.drawColor(android.graphics.Color.WHITE)
                    c.drawBitmap(bmp, 0f, 0f, null)
                }
                out.outputStream().use { flat.compress(if (ext == "png") android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            }
            made += out to "${doc.title}_${i + 1}.$ext"
        }
        imageExport = null
        vm.organizing = false
        doc.controller.pagesOpen = false
        if (share) {
            val uris = try {
                withContext(Dispatchers.IO) { vm.files.shareableAll(made) }
            } finally {
                made.forEach { it.first.delete() }
            }
            vm.intents.trySend(Intent.createChooser(vm.sendIntent(uris, mime), null))
        } else {
            withContext(Dispatchers.IO) {
                for ((f, name) in made) {
                    vm.files.saveToPictures(f, name, mime)
                    f.delete()
                }
            }
            vm.tell(plural(R.plurals.msg_images_saved, made.size, made.size))
        }
    }

    /** Create PDF from picked files (images, text, Office documents, PDFs), combined in order, then open it. */
    internal fun createFrom(picked: List<Uri>) = job(text(R.string.msg_busy_creating_pdf)) { eng ->
        // at most 12 files at once, as the reference allows
        if (picked.size > MAX_CREATE_FILES) vm.tell(plural(R.plurals.msg_create_first_files, MAX_CREATE_FILES, MAX_CREATE_FILES))
        val uris = picked.take(MAX_CREATE_FILES)
        val paths = withContext(Dispatchers.IO) { uris.map { copyIn(it).absolutePath } }
        val out = tempFile("pdf")
        eng.tool("doc_create_multiple", mapOf("paths" to paths, "out" to out.absolutePath, "mode" to "combine"))
        val name = vm.files.displayNameRaw(uris.first()).substringBeforeLast('.') + ".pdf"
        val uri = withContext(Dispatchers.IO) { vm.files.saveToDownloads(out, DocumentFiles.safeName(name)).also { out.delete() } }
        vm.tell(text(R.string.msg_saved_downloads, name))
        vm.open(uri)
    }

    /** Pages [pages] (0-based) of [doc] as a new PDF in Downloads. */
    fun extractPages(doc: OpenDocument, pages: List<Int>) = job(text(R.string.msg_busy_extracting)) { eng ->
        val out = tempFile("pdf")
        eng.tool("page_extract", mapOf("doc" to doc.id, "pages" to pages.map { it + 1 }, "out" to out.absolutePath))
        deliver(out, "Pages from ${doc.title}.pdf")
    }
}
