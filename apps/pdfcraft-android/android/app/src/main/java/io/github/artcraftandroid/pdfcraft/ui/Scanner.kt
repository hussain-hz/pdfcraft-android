package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.text
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Scan: taking pages with the camera app, and saving them as a PDF (or into the open one). The camera also serves Edit PDF's Add image. */
class Scanner internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    /** Photos for Scan: the activity has the camera app write each into this file. */
    val cameraShots = Channel<File>(Channel.BUFFERED)

    /** The pages scanned so far. */
    val scanPages = mutableStateListOf<ScanPage>()

    /** The scan's file name (without .pdf), set when its first page is taken and changeable before saving. */
    var scanName by mutableStateOf("")
    internal var shotFile: File? = null
    private var retakeIndex: Int? = null

    /** Scan's pages go into this document at this (1-based) position instead of a new PDF. */
    var insertScanAt: Pair<OpenDocument, Int>? = null

    fun takeScanPhoto(retake: Int? = null) {
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "scan-${System.nanoTime()}.jpg")
        shotFile = f
        retakeIndex = retake
        cameraShots.trySend(f)
    }

    fun retakeScan(index: Int) = takeScanPhoto(index)

    /** The camera app came back: [ok] when it took the photo. */
    fun onScanPhoto(ok: Boolean) {
        val f = shotFile ?: return
        shotFile = null
        if (vm.edit.imageShot) {
            vm.edit.onCameraPhoto(f, ok)
            return
        }
        if (!ok || !f.isFile || f.length() == 0L) {
            if (scanPages.isEmpty()) vm.toolScreen = null
            return
        }
        vm.viewModelScope.launch {
            val thumb = withContext(Dispatchers.IO) {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)?.let { rotateForExif(f, it) }?.asImageBitmap()
            } ?: return@launch
            val page = ScanPage(f, thumb)
            val r = retakeIndex
            retakeIndex = null
            if (r != null && r in scanPages.indices) scanPages[r] = page else scanPages.add(page)
            if (scanName.isBlank()) scanName = "Scan " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
            if (vm.toolScreen != ToolScreen.Scan) vm.toolScreen = ToolScreen.Scan
            // the document's edges in the photo, shown on the page and moved in Crop; the whole
            // photo is kept when none are found
            val found = vm.engine?.let { eng -> runCatching { eng.tool("photo_find_document", mapOf("path" to f.absolutePath)) }.getOrNull() }
                ?.takeIf { it.optBoolean("found") }?.optJSONArray("corners")?.takeIf { it.length() == 8 }
                ?.let { a -> FloatArray(8) { a.optDouble(it).toFloat() } }
            val at = scanPages.indexOfFirst { it.file == f }
            if (found != null && at >= 0) scanPages[at] = scanPages[at].copy(corners = found, found = found)
        }
    }

    fun discardScan() {
        scanPages.forEach { it.file.delete() }
        scanPages.clear()
        scanName = ""
        insertScanAt = null
        vm.toolScreen = null
    }

    /** Save the scanned pages as a PDF with [filter]'s look (or insert them where Organize asked). */
    fun saveScan(filter: ScanFilter) = vm.jobs.job(text(R.string.msg_busy_saving_scan)) { eng ->
        val pages = scanPages.toList()
        val paths = pages.map { it.file.absolutePath }
        val created = eng.tool("doc_create", mapOf("from" to "images", "paths" to paths, "name" to text(R.string.common_tool_scan)))
        val id = created.optLong("doc")
        try {
            // each page keeps the part inside its corners, straightened, with the look; a whole
            // photo in Original stays as it was taken
            val sizes = eng.tool("doc_info", mapOf("doc" to id)).optJSONArray("pages")
            for ((i, page) in pages.withIndex()) {
                if (page.corners.contentEquals(ScanPage.WHOLE) && filter == ScanFilter.Original) continue
                val size = sizes?.optJSONObject(i) ?: continue
                val (w, h) = size.optDouble("width") to size.optDouble("height")
                val corners = page.corners.mapIndexed { k, v -> v * if (k % 2 == 0) w else h }
                vm.busy = text(R.string.msg_busy_cleaning_page, i + 1, pages.size)
                runCatching { eng.tool("page_enhance_camera", mapOf("doc" to id, "page" to i + 1, "corners" to corners, "mode" to filter.mode, "level" to filter.level)) }
            }
            // the scan's words become text, searchable and selectable, when text recognition is installed
            withContext(Dispatchers.IO) { runCatching { eng.prepareOcrModels(app) } }
            if (runCatching { eng.tool("ocr_status") }.getOrNull()?.optBoolean("available", false) == true) {
                vm.busy = text(R.string.msg_busy_recognizing)
                runCatching { eng.tool("ocr_recognize", mapOf("doc" to id)) }
            }
            val out = vm.jobs.tempFile("pdf")
            eng.tool("doc_save", mapOf("doc" to id, "path" to out.absolutePath, "full" to true))
            val target = insertScanAt
            insertScanAt = null
            val chosen = scanName.trim()
            scanPages.forEach { it.file.delete() }
            scanPages.clear()
            scanName = ""
            vm.toolScreen = null
            if (target != null) {
                vm.runTool(target.first, "page_insert_file", mapOf("path" to out.absolutePath, "at" to target.second))
            } else {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
                val name = DocumentFiles.safeName(chosen.ifEmpty { "Scan $stamp" })
                val uri = withContext(Dispatchers.IO) { vm.files.saveToDownloads(out, name).also { out.delete() } }
                vm.tell(text(R.string.msg_saved_downloads, name))
                vm.open(uri)
            }
        } finally {
            runCatching { eng.tool("doc_close", mapOf("doc" to id, "discard_changes" to true)) }
        }
    }
}
