package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import android.graphics.Typeface
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.EditorFont
import io.github.artcraftandroid.pdfcraft.engine.*
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** A font choice captures its document and draft; a delayed picker cannot affect another file. */
data class FontTarget(val doc: OpenDocument, val para: Paragraph, val draft: String, val generation: ULong)
data class StoredDocumentFont(val file: File, val family: String, val face: String)
data class FontPreview(val font: StoredDocumentFont?, val typeface: Typeface?, val standard: String? = null, val matching: Boolean = false)
enum class FontSheet { Library, Downloads, Preview }

class DocumentFonts internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()
    val directory: File get() = File(app.filesDir, "document-fonts").apply { mkdirs() }
    val catalog by lazy { readFontCatalog(app.assets.open("font-catalog.json").bufferedReader().use { it.readText() }) }
    var target by mutableStateOf<FontTarget?>(null); private set
    var sheet by mutableStateOf(FontSheet.Library); private set
    var preview by mutableStateOf<FontPreview?>(null); private set
    var stored by mutableStateOf<List<StoredDocumentFont>>(emptyList()); private set
    var working by mutableStateOf(false); private set
    var applying by mutableStateOf(false); private set
    var progress by mutableStateOf<Float?>(null); private set
    var problem by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    @Volatile private var operation = 0L

    private fun capture(doc: OpenDocument, para: Paragraph) = FontTarget(doc, para,
        vm.edit.editDraft?.takeIf { vm.edit.editing?.page == para.page && vm.edit.editing?.number == para.number }
            ?: vm.edit.paragraphDraft(doc, para), doc.info.generation)

    fun show(doc: OpenDocument, para: Paragraph, downloads: Boolean = false) {
        close()
        target = capture(doc, para)
        sheet = if (downloads) FontSheet.Downloads else FontSheet.Library
        work { loadStored() }
    }
    fun standard(doc: OpenDocument, para: Paragraph, font: String) {
        close(); target = capture(doc, para); preview = FontPreview(null, null, font); sheet = FontSheet.Preview
    }
    fun close() {
        if (applying) return
        operation++; job?.cancel(); working = false; progress = null; problem = null; target = null; preview = null
    }
    fun library() { sheet = FontSheet.Library; preview = null; work { loadStored() } }
    fun downloads() { sheet = FontSheet.Downloads; preview = null; problem = null }
    fun pick() {
        if (applying) return
        operation++; job?.cancel(); working = false; problem = null
        vm.pickFiles(FilePickKind.ImportFont)
    }

    private fun work(action: suspend () -> Unit) {
        val token = ++operation
        job?.cancel(); problem = null; working = true; progress = null
        job = vm.viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (operation == token) problem = e.userMessage }
            finally { if (operation == token) { working = false; progress = null; applying = false } }
        }
    }
    private suspend fun loadStored() {
        val engine = vm.engine ?: return
        val result = engine.tool("text_font_files", mapOf("directories" to listOf(directory.absolutePath))).getJSONArray("fonts")
        stored = List(result.length()) { i -> result.getJSONObject(i).let { StoredDocumentFont(File(it.getString("path")), it.getString("family"), it.getString("font")) } }
    }
    fun importPicked(uri: Uri) {
        if (target == null) return
        work {
            val file = withContext(Dispatchers.IO) {
                val bytes = app.contentResolver.openInputStream(uri)?.use { input -> input.readBytesBounded() }
                    ?: error(text(R.string.font_invalid))
                val out = File(directory, "import-${fontDigest(bytes)}.ttf")
                if (!out.isFile) out.writeBytes(bytes)
                out
            }
            try { prepare(file) } catch (e: Exception) {
                if (e !is CancellationException && stored.none { it.file.canonicalPath == file.canonicalPath })
                    withContext(Dispatchers.IO) { file.delete() }
                throw e
            }
        }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer); if (read < 0) break
            require(out.size() + read <= MAX_DOCUMENT_FONT_BYTES) { text(R.string.font_too_large) }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
    private suspend fun prepare(file: File) {
        loadStored()
        val font = stored.firstOrNull { it.file.canonicalPath == file.canonicalPath } ?: withContext(Dispatchers.IO) {
            val digest = fontDigest(file.readBytes())
            stored.firstOrNull { it.file.length() == file.length() && fontDigest(it.file.readBytes()) == digest }
        } ?: error(text(R.string.font_invalid))
        val t = target ?: return
        val supported = vm.engine?.tool("text_font_check", mapOf("path" to file.absolutePath, "text" to t.draft))?.optBoolean("supported") == true
        require(supported) { text(R.string.font_unsupported) }
        val face = withContext(Dispatchers.Default) {
            documentEditorTypefaces(listOf(EditorFont("selected", font.face, file.readBytes()))).values.firstOrNull()
                ?: error(text(R.string.font_invalid))
        }
        val source = editorFaceKey(t.para.font)
        val matching = source == editorFaceKey(font.face)
        preview = FontPreview(font, face, matching = matching); sheet = FontSheet.Preview
    }
    fun choose(font: StoredDocumentFont) = work { prepare(font.file) }
    fun download(font: DownloadableFont) = work {
        val token = operation
        val out = try {
            withContext(Dispatchers.IO) { cacheDocumentFont(font, directory, { if (operation == token) progress = it.coerceIn(0f, 1f) }) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw IllegalStateException(text(R.string.font_download_failed), e) }
        prepare(out)
    }

    fun apply() {
        val t = target ?: return; val choice = preview ?: return
        work {
            applying = true
            val engine = vm.engine ?: return@work
            t.doc.saveMutex.withLock {
                check(vm.document === t.doc && !t.doc.closed && !t.doc.closing && engine.command { document(t.doc.id).generation } == t.generation) { text(R.string.font_document_changed) }
                val base = mapOf("doc" to t.doc.id, "page" to t.para.page + 1)
                try {
                if (choice.standard != null) {
                    engine.tool(if (t.para.addedIndex == null) "text_edit" else "content_update", base +
                        (if (t.para.addedIndex == null) mapOf("paragraph" to t.para.number) else mapOf("index" to t.para.addedIndex)) +
                        mapOf("font" to choice.standard, "bold" to t.para.bold, "italic" to t.para.italic, "text" to t.draft))
                } else {
                    val font = requireNotNull(choice.font)
                    if (t.para.addedIndex != null && !choice.matching) {
                        engine.tool("content_font", base + mapOf("index" to t.para.addedIndex, "path" to font.file.absolutePath, "text" to t.draft))
                    } else {
                        val imported = engine.tool("text_font_import", base + ("path" to font.file.absolutePath))
                        if (choice.matching) {
                            if (t.draft != t.para.text) engine.tool(if (t.para.addedIndex == null) "text_edit" else "content_update", base +
                                (if (t.para.addedIndex == null) mapOf("paragraph" to t.para.number, "preserve_font" to true) else mapOf("index" to t.para.addedIndex)) + ("text" to t.draft))
                        } else {
                            val resource = imported.optString("resource").takeIf { it.isNotBlank() }
                                ?: engine.command { editorFonts(t.doc.id, t.para.page.toUInt()) }.firstOrNull {
                                    !it.resource.startsWith("PCApproved") && editorFaceKey(it.face) == editorFaceKey(font.face)
                                }?.resource ?: error(text(R.string.font_invalid))
                            engine.tool("text_edit", base + mapOf("paragraph" to t.para.number, "font_resource" to resource, "text" to t.draft))
                        }
                    }
                }
                vm.edit.editing = null; vm.edit.editDraft = null; vm.edit.editSelection = null; vm.edit.historyChanged()
                } finally {
                    vm.refresh(t.doc)
                    target = target?.copy(generation = t.doc.info.generation)
                }
            }
            target = null; preview = null
            vm.tell(text(R.string.font_applied))
        }
    }
}
