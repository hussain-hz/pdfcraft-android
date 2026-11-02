package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var session = 0L
    private var shotSession = 0L
    private var cameraStarted = false
    private val previewMutex = Mutex()
    var makeSearchable by mutableStateOf(true)
    var receiving by mutableStateOf(false); private set

    /** Scan's pages go into this document at this (1-based) position instead of a new PDF. */
    var insertScanAt: Pair<OpenDocument, Int>? = null

    fun openCameraIfNeeded() { if(!cameraStarted && scanPages.isEmpty() && !receiving) takeScanPhoto() }

    fun takeScanPhoto(retake: Int? = null) {
        cameraStarted = true
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "scan-${System.nanoTime()}.jpg")
        shotFile = f
        retakeIndex = retake
        shotSession = session
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
        val at = retakeIndex; retakeIndex = null
        val token = shotSession
        if (!ok || !f.isFile || f.length() == 0L) {
            f.delete()
            return
        }
        vm.viewModelScope.launch {
            receiving = true
            try { acceptPhoto(f, at, token) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { f.delete(); vm.tell(e.userMessage) }
            finally { receiving = false }
        }
    }

    /** Own bounded copies only; the user's source photographs are never altered. */
    fun importPhotos(uris: List<Uri>) {
        val token = session
        vm.jobs.job(text(R.string.scan_importing)) { _ ->
            receiving = true
            try {
                for (uri in uris) {
                    require(scanPages.size < 50) { text(R.string.scan_page_limit) }
                    val file = withContext(Dispatchers.IO) {
                        val out = File(app.cacheDir,"camera/scan-${System.nanoTime()}.img").apply { parentFile?.mkdirs() }
                        try {
                            requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                                out.outputStream().use { output ->
                                    val buffer = ByteArray(8192); var total = 0
                                    while(true) { val n=input.read(buffer); if(n<0) break; total+=n
                                        require(total <= 32*1024*1024) { text(R.string.scan_photo_limit) }; output.write(buffer,0,n) }
                                }
                            }
                            out
                        } catch(e: Exception) { out.delete(); throw e }
                    }
                    try { acceptPhoto(file,null,token) } catch(e: Exception) { file.delete(); throw e }
                }
            } finally { receiving = false }
        }
    }

    private suspend fun acceptPhoto(file: File, at: Int?, token: Long) {
        require(file.length() <= 32*1024*1024) { text(R.string.scan_photo_limit) }
        val bitmap = withContext(Dispatchers.IO) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath,bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { text(R.string.scan_photo_invalid) }
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 1
                while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize > 1600) inSampleSize *= 2
            }
            val decoded = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath,options)) { text(R.string.scan_photo_invalid) }
            orientScanThumbnail(file,decoded).asImageBitmap()
        }
        if (token != session) { file.delete(); return }
        require(at != null || scanPages.size < 50) { text(R.string.scan_page_limit) }
        val previous = at?.let { scanPages.getOrNull(it) }
        val page = ScanPage(file,bitmap,filter=previous?.filter ?: ScanFilter.AutoColor,strength=previous?.strength ?: ScanFilter.AutoColor.level)
        if(at != null && at in scanPages.indices) { scanPages[at]=page; previous?.file?.delete() } else scanPages.add(page)
        if(scanName.isBlank()) scanName="Scan "+java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss",java.util.Locale.US).format(java.util.Date())
        if(vm.toolScreen != ToolScreen.Scan) vm.toolScreen=ToolScreen.Scan
        val found = vm.engine?.let { engine ->
            try { engine.tool("photo_find_document",mapOf("path" to file.absolutePath)) }
            catch(e: CancellationException) { throw e } catch(_: Exception) { null }
        }?.takeIf { it.optBoolean("found") }?.optJSONArray("corners")?.takeIf { it.length()==8 }
            ?.let { a -> FloatArray(8) { a.optDouble(it).toFloat() } }?.takeIf(::validScanCorners)
        val index=scanPages.indexOfFirst { it.file==file }
        if(token==session && found!=null && index>=0) {
            val current=scanPages[index]
            // Late edge detection must not overwrite a crop the user already adjusted.
            scanPages[index]=current.copy(found=found,corners=if(current.corners === page.corners) found else current.corners)
        }
    }

    fun update(index: Int, transform: (ScanPage)->ScanPage) { scanPages.getOrNull(index)?.let { scanPages[index]=transform(it) } }
    fun move(index: Int, direction: Int): Int {
        val next=index+direction
        if(index !in scanPages.indices || next !in scanPages.indices) return index
        val page=scanPages.removeAt(index); scanPages.add(next,page); return next
    }
    fun delete(index: Int) { if(index in scanPages.indices) scanPages.removeAt(index).file.delete() }
    fun applyToAll(index: Int, look: Boolean, rotation: Boolean, autoCrop: Boolean) {
        val source=scanPages.getOrNull(index) ?: return
        scanPages.indices.forEach { i ->
            val page=scanPages[i]
            scanPages[i]=page.copy(filter=if(look)source.filter else page.filter,strength=if(look)source.strength else page.strength,
                rotation=if(rotation)source.rotation else page.rotation,
                corners=if(autoCrop && i!=index) page.found?.copyOf() ?: page.corners else page.corners)
        }
    }

    /** Non-cancellable ownership region prevents a native temporary document leaking on a rapid slider change. */
    internal suspend fun preview(page: ScanPage): ImageBitmap = previewMutex.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable + Dispatchers.IO) {
            val engine=requireNotNull(vm.engine)
            val input=File(app.cacheDir,"scan-preview-${System.nanoTime()}.jpg")
            var id: Long?=null
            try {
                input.outputStream().use { check(page.thumb.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.JPEG,94,it)) }
                val doc=engine.tool("doc_create",mapOf("from" to "images","paths" to listOf(input.absolutePath))).getLong("doc"); id=doc
                enhanceScanPage(engine,doc,0,page)
                val info=engine.command { document(doc.toULong()) }.pages.first()
                val scale=minOf(1200f/info.width,1600f/info.height,1.5f)
                engine.command { renderPage(doc.toULong(),0u,scale,null) }.toBitmap().asImageBitmap()
            } finally {
                id?.let { runCatching { engine.tool("doc_close",mapOf("doc" to it,"discard_changes" to true)) } }
                input.delete()
            }
        }
    }

    fun discardScan() {
        session++; cameraStarted=false
        scanPages.forEach { it.file.delete() }
        scanPages.clear()
        scanName = ""
        insertScanAt = null
        vm.toolScreen = null
    }

    /** Original-resolution processing; a failure keeps the photographs and review settings. */
    fun saveScan() = vm.jobs.job(text(R.string.msg_busy_saving_scan)) { engine ->
        val pages=scanPages.map { it.copy(corners=it.corners.copyOf()) }
        require(pages.isNotEmpty())
        val target=insertScanAt
        val created=engine.tool("doc_create",mapOf("from" to "images","paths" to pages.map { it.file.absolutePath },"name" to text(R.string.common_tool_scan)))
        val id=created.getLong("doc")
        val out=vm.jobs.tempFile("pdf")
        try {
            pages.forEachIndexed { index,page ->
                vm.busy=text(R.string.msg_busy_cleaning_page,index+1,pages.size)
                enhanceScanPage(engine,id,index,page)
            }
            var searchable=false
            if(makeSearchable) {
                withContext(Dispatchers.IO) { engine.prepareOcrModels(app) }
                if(engine.tool("ocr_status").optBoolean("available",false)) {
                    vm.busy=text(R.string.msg_busy_recognizing)
                    engine.tool("ocr_recognize",mapOf("doc" to id)); searchable=true
                }
            }
            engine.tool("doc_save",mapOf("doc" to id,"path" to out.absolutePath,"full" to true))
            if(target!=null) {
                target.first.saveMutex.withLock {
                    check(vm.document === target.first && !target.first.closed && !target.first.closing)
                    engine.tool("page_insert_file",mapOf("doc" to target.first.id,"path" to out.absolutePath,"at" to target.second))
                    vm.refresh(target.first)
                }
            } else {
                val stamp=java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss",java.util.Locale.US).format(java.util.Date())
                val name=DocumentFiles.safeName(scanName.trim().ifEmpty { "Scan $stamp" })
                val uri=withContext(Dispatchers.IO) { vm.files.saveToDownloads(out,name) }
                vm.tell(text(R.string.msg_saved_downloads,name))
                vm.open(uri)
            }
            // Clean up only after the result was delivered successfully.
            pages.forEach { it.file.delete() }
            scanPages.clear(); scanName=""; insertScanAt=null; vm.toolScreen=null; session++; cameraStarted=false
            if(makeSearchable && !searchable) vm.tell(text(R.string.scan_saved_without_ocr))
        } finally {
            withContext(NonCancellable) { runCatching { engine.tool("doc_close",mapOf("doc" to id,"discard_changes" to true)) } }
            out.delete()
        }
    }
}
