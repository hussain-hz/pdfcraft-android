package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.text as appText

import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri

import android.app.ActivityManager
import io.github.artcraftandroid.pdfcraft.ui.home.FileTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.engine.UserBookmark
import io.github.artcraftandroid.pdfcraft.engine.UserBookmarks
import io.github.artcraftandroid.pdfcraft.ui.viewer.NotePrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.VoicePrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.ReplyPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.StyledTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextMarkup
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.ToolPrefs
import io.github.artcraftandroid.pdfcraft.ui.viewer.engineType
import io.github.artcraftandroid.pdfcraft.ui.viewer.hex
import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.snapshotFlow
import io.github.artcraftandroid.pdfcraft.ui.viewer.LinkFlash
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.ReadAloud
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.PdfcraftApplication
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.RecentFile
import io.github.artcraftandroid.pdfcraft.engine.RecentFiles
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageImages
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/** A message for the snackbar, with an optional action. */
data class Message(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** A document waiting for its password before it opens. */
data class PasswordRequest(val uri: Uri, val file: File, val wrong: Boolean)

/** The app's state: the engine, the open document, Home's lists, and the messages. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as PdfcraftApplication
    val files = DocumentFiles(app)
    private val saver = io.github.artcraftandroid.pdfcraft.engine.DocumentSaver(files) { tell(it) }
    val recents = RecentFiles(File(app.filesDir, "recent.json"))

    // Features with their own state and actions (each reaches the rest of the app through this view model)
    val voice = VoiceComments(this)
    val signing = DigitalSigning(this)
    val edit = EditPdf(this)
    val forms = FormFilling(this)
    val library = FileLibrary(this)
    val updates = AppUpdater(app, viewModelScope) { intents.trySend(it) }
    val jobs = ToolJobs(this)
    val scan = Scanner(this)

    var engine by mutableStateOf<Engine?>(null)
        private set
    var engineProblem by mutableStateOf<String?>(null)
        private set

    /** The document in the viewer, if one is open. */
    var document by mutableStateOf<OpenDocument?>(null)
        private set

    var recentList by mutableStateOf<List<RecentFile>>(emptyList())
        private set

    var passwordRequest by mutableStateOf<PasswordRequest?>(null)
        private set

    /** A document is being opened (a spinner over Home). */
    var opening by mutableStateOf(false)
        private set

    val messages = Channel<Message>(Channel.BUFFERED)

    /** How many bytes of page images to keep: an eighth of the app's heap, between 32 and 192 MB. */
    private val imageBudget: Int = run {
        val am = app.getSystemService(ActivityManager::class.java)
        val heapMb = (am?.memoryClass ?: 128).coerceAtLeast(64)
        (heapMb / 8).coerceIn(32, 192) * 1024 * 1024
    }

    fun tell(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        messages.trySend(Message(text, actionLabel, action))
    }

    fun refreshRecents() {
        viewModelScope.launch { recentList = withContext(Dispatchers.IO) { recents.list() } }
    }

    /** Open the PDF at [uri] (picked, shared, or from Recent), asking for its password if it has one. */
    fun open(uri: Uri, password: String? = null) {
        if (opening || readOnlyPrompt != null) return
        // a file opened from another app comes to the front, not under Settings
        settingsOpen = false
        showLicences = false
        viewModelScope.launch {
            val eng = engine ?: application.engine.await().getOrNull() ?: run { tell(engineProblem ?: text(R.string.msg_engine_not_started)); return@launch }
            opening = true
            try {
                document?.let { current ->
                    if (current.controller.tool != ViewerTool.None) leaveTool(current)
                    if (!saveAndClose(eng, current)) {
                        afterReadOnly = { open(uri, password) }
                        readOnlyPrompt = current
                        return@launch
                    }
                    document = null
                }
                val file = withContext(Dispatchers.IO) {
                    files.keepAccess(uri)
                    files.workingCopy(uri)
                }
                openFile(eng, uri, file, password)
            } catch (e: java.io.FileNotFoundException) {
                // moved or deleted since it was opened: it leaves Recent
                withContext(Dispatchers.IO) { recents.remove(setOf(uri.toString())) }
                refreshRecents()
                tell(text(R.string.msg_file_moved))
            } catch (e: SecurityException) {
                // A file another app lent for one visit (Files, Downloads: "Open with"): Android
                // takes that access back later, so Recent can't read it again. PdfCraft's own copy
                // opens instead, read-only (changes are offered as a copy, as for any file it
                // can't write to); the original needs opening again from the app it's in.
                val copy = withContext(Dispatchers.IO) { files.existingCopy(uri) }
                if (copy != null) {
                    openFile(eng, uri, copy, password)
                    tell(text(R.string.msg_opened_own_copy))
                } else {
                    tell(text(R.string.msg_file_access_lost))
                }
            } catch (e: Exception) {
                tell(e.userMessage)
            } finally {
                opening = false
            }
        }
    }

    /** The password dialog's answer: try again with [password], or give up (null). */
    fun answerPassword(password: String?) {
        val req = passwordRequest ?: return
        passwordRequest = null
        if (password == null) return
        // OK with nothing typed: the field turns red, as a wrong password does
        if (password.isEmpty()) {
            passwordRequest = PasswordRequest(req.uri, req.file, wrong = true)
            return
        }
        val eng = engine ?: return
        viewModelScope.launch {
            opening = true
            try {
                openFile(eng, req.uri, req.file, password)
            } finally {
                opening = false
            }
        }
    }

    private suspend fun openFile(eng: Engine, uri: Uri, file: File, password: String?) {
        try {
            val info = eng.command { open(file.absolutePath, password) }
            val images = PageImages(eng, info.id, viewModelScope, imageBudget)
            val source = uri.takeIf { it.scheme == "content" || it.scheme == "file" }
            val doc = OpenDocument(info.id, info, source, file, images, password, eng, viewModelScope)
            val (recent, writable) = withContext(Dispatchers.IO) {
                recents.list().firstOrNull { it.uri == uri.toString() } to (source == null || files.canWrite(source))
            }
            doc.writable = writable
            doc.notWrittenBack = withContext(Dispatchers.IO) { files.recovery.pending(file) }
            if (doc.notWrittenBack) tell(appText(R.string.msg_recovered_changes))
            loadLinks(eng, doc)
            doc.controller.nightMode = savedNightMode
            if (savedPageMode != doc.viewer.mode) doc.viewer.switchMode(savedPageMode)
            // a file left in Reading Mode reopens in it, unless it was opened for a tool
            readingMode = recent?.reading == true && pendingTool == null
            document = doc
            if (doc.info.signed) signing.announceSignatures(doc)
            startPendingTool(doc)
            pendingPage = recent?.lastPage?.takeIf { it in 0 until info.pages.size }
            pendingSpot = recent?.takeIf { pendingPage != null && it.lastX >= 0f && it.lastY >= 0f }?.let { Triple(it.lastX, it.lastY, it.lastZoom) }
            withContext(Dispatchers.IO) {
                recents.touch(RecentFile(uri.toString(), info.name, System.currentTimeMillis(), info.pages.size, file.length(), recent?.lastPage ?: 0))
            }
            refreshRecents()
        } catch (e: MobileException.NeedsPassword) {
            passwordRequest = PasswordRequest(uri, file, wrong = false)
        } catch (e: MobileException.WrongPassword) {
            passwordRequest = PasswordRequest(uri, file, wrong = true)
        } catch (e: MobileException) {
            tell(e.userMessage)
        }
    }

    /** The page to show once the viewer has laid the document out (reopening where the reader left off). */
    var pendingPage: Int? = null

    /** With [pendingPage]: the spot (points) at the screen's top-left and the zoom it was left at. */
    var pendingSpot: Triple<Float, Float, Float>? = null

    /** Intents for the activity to start (share, open a link): the view model has no activity. */
    val intents = Channel<Intent>(Channel.BUFFERED)

    /** Print jobs for the activity: the file and its name. */
    val prints = Channel<Pair<File, String>>(Channel.BUFFERED)

    /** A document that couldn't be saved back to where it came from, waiting for Save a copy or Discard. */
    private var afterReadOnly: (() -> Unit)? = null
    var readOnlyPrompt by mutableStateOf<OpenDocument?>(null)
        private set

    /** Leave the viewer: changes are saved first, as the reference does (no prompt). */
    fun closeDocument() {
        val doc = document ?: return
        val eng = engine ?: return
        if (doc.controller.tool != ViewerTool.None) leaveTool(doc)
        viewModelScope.launch {
            // changes the file's place can't take (known read-only, or found so now) are offered as a copy
            if (!saveAndClose(eng, doc)) {
                readOnlyPrompt = doc
                return@launch
            }
            document = null
            readingMode = false
            refreshRecents()
        }
    }

    /** The read-only prompt's answer: keep the changes in a copy, or drop them. */
    fun answerReadOnly(saveCopy: Boolean?) {
        val doc = readOnlyPrompt ?: return
        readOnlyPrompt = null
        val next = afterReadOnly
        afterReadOnly = null
        val eng = engine ?: return
        when (saveCopy) {
            null -> return
            true -> saveCopyAs(doc, then = {
                document = null
                viewModelScope.launch {
                    closeQuietly(eng, doc)
                    withContext(Dispatchers.IO) { files.recovery.complete(doc.file) }
                    refreshRecents()
                    next?.invoke()
                }
            })
            false -> {
                document = null
                viewModelScope.launch {
                    closeQuietly(eng, doc)
                    withContext(Dispatchers.IO) { files.recovery.complete(doc.file) }
                    refreshRecents()
                    next?.invoke()
                }
            }
        }
    }

    private suspend fun saveAndClose(eng: Engine, doc: OpenDocument): Boolean {
        doc.closing = true
        try {
            val reading = readingMode
            val pending = pendingPage
            // the spot at the screen's top-left and the zoom, to reopen there (as the reference does)
            val v = doc.viewer
            val spot = if (pending == null && v.mode == io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Continuous) v.screenToPage(androidx.compose.ui.geometry.Offset(0f, v.padTop), nearest = true) else null
            val zoom = v.zoom
            withContext(Dispatchers.IO) {
                // in Reading Mode the page canvas has no layout: the page read is the pending one
                val page = pending ?: spot?.page ?: v.currentPage
                doc.source?.let { src ->
                    recents.update(src.toString()) {
                        it.copy(lastPage = page, reading = reading, lastX = spot?.x ?: -1f, lastY = spot?.y ?: -1f, lastZoom = if (spot != null) zoom else 1f)
                    }
                }
            }
            if (!save(doc)) { doc.closing = false; return false }
            // Recent shows the size the file has now, not the size it had when opened
            withContext(Dispatchers.IO) {
                doc.source?.let { src -> files.size(src)?.let { size -> recents.update(src.toString()) { it.copy(size = size) } } }
            }
            closeQuietly(eng, doc)
            return true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { tell(e.userMessage); return false }
        finally { if (!doc.closed) doc.closing = false }
    }

    private suspend fun closeQuietly(eng: Engine, doc: OpenDocument) {
        doc.closing = true
        doc.checkpoint?.cancel()
        doc.saveMutex.withLock { doc.closed = true; runCatching { eng.command { close(doc.id, true) } } }
        doc.images.close()
        doc.search.clear()
    }

    /** The app went to the background: save the open document, as the reference does. */
    fun onPause() {
        val doc = document ?: return
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        viewModelScope.launch { save(doc) }
    }

    /**
     * Save [doc]'s changes: into its working file (incrementally, so earlier signatures stay
     * valid), then back over the file it came from when the app may write there. Returns
     * whether the changes reached the original; [quiet] leaves a failure's message to the caller.
     */
    suspend fun save(doc: OpenDocument, quiet: Boolean = false): Boolean {
        val eng = engine ?: return false
        return saver.save(doc, eng, quiet)
    }

    /** Read the document's state from the engine again (after an edit, undo or save). */
    suspend fun refresh(doc: OpenDocument, metadata: io.github.artcraftandroid.pdfcraft.engine.MetadataRefresh = io.github.artcraftandroid.pdfcraft.engine.MetadataRefresh.All) {
        val eng = engine ?: return
        val info = runCatching { eng.command { document(doc.id) } }.getOrNull() ?: return
        val changed = info.generation != doc.info.generation
        doc.update(info)
        if (changed) {
            loadLinks(eng, doc, metadata)
            if (info.dirty) {
                doc.checkpoint?.cancel()
                doc.checkpoint = viewModelScope.launch {
                    delay(600)
                    try {
                        saver.checkpoint(doc, eng)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { tell(e.userMessage) }
                }
            }
        }
    }

    private suspend fun loadLinks(eng: Engine, doc: OpenDocument, metadata: io.github.artcraftandroid.pdfcraft.engine.MetadataRefresh = io.github.artcraftandroid.pdfcraft.engine.MetadataRefresh.All) {
        if (metadata == io.github.artcraftandroid.pdfcraft.engine.MetadataRefresh.All) doc.widgets = runCatching { eng.command { widgets(doc.id) } }.getOrDefault(emptyList())
        if (metadata != io.github.artcraftandroid.pdfcraft.engine.MetadataRefresh.Content) doc.comments = runCatching { eng.command { comments(doc.id) } }.getOrDefault(emptyList())
        if (document === doc) selectedFill = selectedFill?.let { selected ->
            doc.comments.firstOrNull { if (selected.name != null) it.name == selected.name else it.page == selected.page && it.index == selected.index }
        }
        if (metadata == io.github.artcraftandroid.pdfcraft.engine.MetadataRefresh.All) doc.links = runCatching {
            eng.command { (0 until doc.info.pages.size).associateWith { p -> links(doc.id, p.toUInt()) } }
        }.getOrDefault(emptyMap())
        signing.loadSignatures(eng, doc)
    }

    /**
     * Run engine tool [name] on [doc] (its `doc` argument is filled in), then refresh. [onDone]
     * gets the result; a refusal is told in a snackbar.
     */
    fun runTool(doc: OpenDocument, name: String, args: Map<String, Any?> = emptyMap(), onFinished: () -> Unit = {}, onDone: (JSONObject) -> Unit = {}) {
        if (doc.closed || doc.closing) { onFinished(); return }
        val eng = engine ?: run { onFinished(); return }
        viewModelScope.launch {
            try {
                val r = doc.saveMutex.withLock {
                    if (doc.closed) return@launch
                    val result = eng.tool(name, args + ("doc" to doc.id))
                    refresh(doc, io.github.artcraftandroid.pdfcraft.engine.metadataFor(name))
                    result
                }
                onDone(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tell(e.userMessage)
            } finally {
                onFinished()
            }
        }
    }

    /** Undo: inside a tool that keeps its own history (Draw) only that; else the document's last edit, named in a message. */
    fun undo(doc: OpenDocument) {
        doc.localUndo?.let { it.undo(); return }
        val label = doc.info.undo ?: return
        runTool(doc, "edit_undo") { tell(text(R.string.msg_undone, label)) }
    }

    fun redo(doc: OpenDocument) {
        doc.localUndo?.let { it.redo(); return }
        val label = doc.info.redo ?: return
        runTool(doc, "edit_redo") { tell(text(R.string.msg_redone, label)) }
    }

    /** Switch the viewer to [tool], unless the document forbids what it does. */
    fun enterTool(doc: OpenDocument, tool: ViewerTool) {
        val ctl = doc.controller
        if (ctl.tool == tool) {
            leaveTool(doc)
            return
        }
        // as the reference: no tool works on reflowed text; say so and stay in Reading Mode
        if (readingMode && tool != ViewerTool.None) {
            readingModeBlocked = tool
            return
        }
        val info = doc.info
        val blocked = when (tool) {
            ViewerTool.Comment, ViewerTool.Markup, ViewerTool.Draw, ViewerTool.Text -> !info.allowsAnnotation
            ViewerTool.FillSign -> !info.allowsFormFilling && !info.allowsAnnotation
            ViewerTool.Edit -> info.readOnlyReason != null || !info.allowsAssembly && info.encrypted
            ViewerTool.None -> false
        }
        if (blocked) {
            tell(info.readOnlyReason ?: text(R.string.msg_tool_blocked_security))
            return
        }
        if (ctl.tool != ViewerTool.None) leaveTool(doc)
        doc.selection.clear()
        ctl.switchTool(tool)
        if (tool == ViewerTool.Draw && toolPrefs.snapToShape && toolPrefs.takeSnapHint()) tell(text(R.string.msg_snap_to_shape_on))
        // the first time the reader comments without a name, ask for one (once)
        if (tool in setOf(ViewerTool.Comment, ViewerTool.Markup, ViewerTool.Draw, ViewerTool.Text) && toolPrefs.author.isBlank() && !toolPrefs.authorAsked) {
            toolPrefs.authorAsked = true
            authorPrompt = true
        }
    }

    /** Asking for the name comments are signed with. */
    var authorPrompt by mutableStateOf(false)

    /** Leave the active tool: whatever it holds (ink being drawn) is added first. */
    fun leaveTool(doc: OpenDocument) {
        doc.commitTool?.invoke()
        doc.commitTool = null
        if (doc.controller.tool == ViewerTool.Edit) {
            edit.commitInlineEdit(doc)
            edit.editSelection = null
            edit.editImage = null
            edit.editMode = io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.Select
        }
        if (signing.certPlacing === doc) signing.certPlacing = null
        doc.controller.tool = ViewerTool.None
    }

    /**
     * A tap at [at]: follow the link there, if any (a page of this document straight away, a
     * web address after asking). Returns whether there was one.
     */
    fun tapLink(doc: OpenDocument, at: PagePoint?): Boolean {
        if (at == null) return false
        val link = doc.links[at.page]?.firstOrNull { l ->
            l.rect.size == 4 && at.x in l.rect[0]..l.rect[2] && at.y in l.rect[1]..l.rect[3]
        } ?: return false
        doc.linkFlash = LinkFlash(at.page, link.rect.toFloatArray())
        viewModelScope.launch {
            delay(300)
            doc.linkFlash = null
        }
        val page = link.page
        val uri = link.uri
        when {
            page != null -> {
                doc.controller.rememberJump(doc.viewer.currentPage)
                doc.viewer.goToPage(page.toInt(), link.top)
            }
            uri != null -> {
                val parsed = uri.toUri()
                when (parsed.scheme?.lowercase()) {
                    "mailto", "tel" -> intents.trySend(Intent(Intent.ACTION_VIEW, parsed))
                    else -> linkPrompt = uri
                }
            }
        }
        return true
    }

    /** A web address waiting for "Open web page?". */
    var linkPrompt by mutableStateOf<String?>(null)
        internal set

    fun answerLink(open: Boolean) {
        val uri = linkPrompt ?: return
        linkPrompt = null
        if (open) intents.trySend(Intent(Intent.ACTION_VIEW, uri.toUri()))
    }

    /** Find ended: say so when nothing was found. */
    fun whenSearchDone(doc: OpenDocument) {
        viewModelScope.launch {
            snapshotFlow { doc.search.running }.first { !it }
            if (doc.search.hits.isEmpty() && doc.search.query.isNotEmpty()) {
                val eng = engine
                val textless = eng != null && (0 until minOf(doc.pageCount, 5)).all { p ->
                    runCatching { withContext(eng.commands) { eng.session.glyphCount(doc.id, p.toUInt()) } }.getOrDefault(1u) == 0u
                }
                if (textless) {
                    tell(text(R.string.msg_search_image_only), text(R.string.common_tool_recognize_text)) { recognizeText(doc) }
                } else {
                    tell(text(R.string.msg_search_no_matches, doc.search.query))
                }
            }
        }
    }

    /** Share a copy of [doc] through the system's share sheet (saved first, with the ink being drawn). */
    fun share(doc: OpenDocument) {
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        handOut {
            // the ink is queued in the engine: reading the document back waits for it, so the save has it
            refresh(doc)
            save(doc)
            val uri = withContext(Dispatchers.IO) { files.shareable(doc.file, doc.name) }
            sendIntent(listOf(uri)).putExtra(Intent.EXTRA_SUBJECT, text(R.string.msg_share_subject, doc.title))
        }
    }

    /**
     * Hand files to another app through the system's chooser: [make] copies them where other apps
     * may read them and returns the intent. A failure on the way, such as a file gone or a phone
     * too full for the copy, is told in a snackbar instead of closing the app.
     */
    internal fun handOut(make: suspend () -> Intent) {
        viewModelScope.launch {
            val intent = try {
                make()
            } catch (e: Exception) {
                tell(e.userMessage)
                null
            } ?: return@launch
            intents.trySend(Intent.createChooser(intent, null))
        }
    }

    /** A send intent for [uris], with them as its clip too so the share sheet can show them. */
    internal fun sendIntent(uris: List<Uri>, mime: String = "application/pdf"): Intent {
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.clipData = ClipData(null, arrayOf(mime), ClipData.Item(uris.first())).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Keys in the viewer, as the reference takes them: N or Page Down for the next page, P or Page
     * Up for the previous one, the arrows to scroll, Ctrl+P to print, Ctrl and + or − to zoom.
     */
    fun viewerKey(keyCode: Int, ctrl: Boolean): Boolean {
        if (!keyboardShortcuts) return false
        val doc = document ?: return false
        if (toolScreen != null || readingMode) return false
        val v = doc.viewer
        val center = androidx.compose.ui.geometry.Offset(v.viewW / 2f, v.viewH / 2f)
        when {
            ctrl && keyCode == android.view.KeyEvent.KEYCODE_P -> print(doc)
            ctrl && (keyCode == android.view.KeyEvent.KEYCODE_EQUALS || keyCode == android.view.KeyEvent.KEYCODE_PLUS || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ADD) -> v.zoomTo(v.zoom + 0.25f, center)
            ctrl && (keyCode == android.view.KeyEvent.KEYCODE_MINUS || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT) -> v.zoomTo(v.zoom - 0.25f, center)
            ctrl -> return false
            keyCode == android.view.KeyEvent.KEYCODE_N || keyCode == android.view.KeyEvent.KEYCODE_PAGE_DOWN -> v.goToPage(v.currentPage + 1)
            keyCode == android.view.KeyEvent.KEYCODE_P || keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP -> v.goToPage(v.currentPage - 1)
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN -> v.setScroll(v.scrollX, v.scrollY + v.viewH * 0.1f)
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP -> v.setScroll(v.scrollX, v.scrollY - v.viewH * 0.1f)
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> v.setScroll(v.scrollX + v.viewW * 0.1f, v.scrollY)
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> v.setScroll(v.scrollX - v.viewW * 0.1f, v.scrollY)
            else -> return false
        }
        return true
    }

    /** Print [doc] through Android's print framework. */
    fun print(doc: OpenDocument) {
        if (!doc.info.allowsPrinting) {
            tell(text(R.string.msg_print_disabled))
            return
        }
        viewModelScope.launch {
            save(doc)
            // the system's printing can't open a password-protected file: print an unlocked copy
            val file = if (doc.info.encrypted) unlockedCopy(doc) ?: doc.file else doc.file
            prints.trySend(file to doc.name)
        }
    }

    /** A temporary copy of [doc] without its password, for printing; null if the security can't be removed. */
    private suspend fun unlockedCopy(doc: OpenDocument): File? {
        val eng = engine ?: return null
        return runCatching {
            val out = jobs.tempFile("pdf")
            val copy = eng.tool("doc_open", mapOf("path" to doc.file.absolutePath, "password" to doc.password)).optLong("doc")
            try {
                eng.tool("doc_unprotect", mapOf("doc" to copy))
                eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
            } finally {
                runCatching { eng.tool("doc_close", mapOf("doc" to copy, "discard_changes" to true)) }
            }
            out
        }.getOrNull()
    }

    /** Where Save a copy is asked to write (the activity opens the system's file creator with this name). */
    val saveCopyRequests = Channel<String>(Channel.BUFFERED)
    private var afterCopy: (() -> Unit)? = null
    private var copyOf: OpenDocument? = null

    /** Save a copy of [doc] under a name and place the user picks. */
    fun saveCopyAs(doc: OpenDocument, then: (() -> Unit)? = null) {
        copyOf = doc
        afterCopy = then
        saveCopyRequests.trySend(doc.name)
    }

    /** The place chosen for Save a copy (null: cancelled). The document is saved first, then its file copied. */
    fun copyTo(target: Uri?) {
        if (library.rowCopyTo(target)) return
        val doc = copyOf ?: return
        copyOf = null
        val then = afterCopy
        afterCopy = null
        if (target == null) return
        viewModelScope.launch {
            try {
                doc.saveMutex.withLock {
                    withContext(Dispatchers.IO) { files.recovery.mark(doc.file) }
                    doc.notWrittenBack = true
                    if (doc.info.dirty) engine?.tool("doc_save", mapOf("doc" to doc.id))
                    withContext(Dispatchers.IO) { files.writeBack(doc.file, target) }
                }
                refresh(doc)
                tell(text(R.string.msg_saved_copy))
                then?.invoke()
            } catch (e: Exception) {
                tell(e.userMessage)
            }
        }
    }

    /** Export PDF (to Word, RTF, Excel, PowerPoint, or images of its pages). */
    fun openExport(doc: OpenDocument) {
        toolScreen = ToolScreen.Export(doc)
    }

    /** Read Aloud: the player over the viewer. */
    var readAloud by mutableStateOf<ReadAloud?>(null)
        private set

    /** The reading notification follows Read aloud: shown while it reads or is paused, gone when it stops. */
    private val readAloudNotice = viewModelScope.launch {
        snapshotFlow { readAloud to (readAloud?.playing == true) }.collect { (ra, playing) ->
            val ctx = getApplication<Application>()
            if (ra == null) {
                io.github.artcraftandroid.pdfcraft.ReadAloudService.controls = null
                io.github.artcraftandroid.pdfcraft.ReadAloudService.hide(ctx)
            } else {
                io.github.artcraftandroid.pdfcraft.ReadAloudService.controls = object : io.github.artcraftandroid.pdfcraft.ReadAloudService.Controls {
                    override fun play() { readAloud?.play() }
                    override fun pause() { readAloud?.pause() }
                    override fun skip(forward: Boolean) { readAloud?.skip(forward) }
                    override fun stop() { readAloud?.stop() }
                }
                io.github.artcraftandroid.pdfcraft.ReadAloudService.show(ctx, ra.title, playing)
            }
        }
    }

    fun startReadAloud(doc: OpenDocument, fromSelection: Boolean = false) {
        if (doc.info.encrypted) {
            tell(text(R.string.msg_read_aloud_encrypted))
            return
        }
        readAloud?.stop()
        val eng = engine ?: return
        val key = docKey(doc)
        val sel = if (fromSelection) doc.selection.selection?.range else null
        if (sel != null) {
            // from the selection's first word
            readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, sel.page.toInt(), { readAloud = null }, key, startGlyph = sel.start)
            return
        }
        val saved = ReadAloud.savedPosition(getApplication(), key)?.takeIf { it.first in 0 until doc.pageCount }
        if (saved != null) {
            readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, saved.first, { readAloud = null }, key, resumeAt = saved.second to saved.third)
            tell(text(R.string.msg_read_aloud_resumed), text(R.string.msg_read_aloud_start_over)) {
                ReadAloud.forgetPosition(getApplication(), key)
                readAloud?.stop()
                ReadAloud.forgetPosition(getApplication(), key)
                readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, 0, { readAloud = null }, key)
            }
            return
        }
        readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, doc.viewer.currentPage, { readAloud = null }, key)
    }

    // ---- Tools' remembered settings, bookmarks, stars --------------------------------------

    val toolPrefs = ToolPrefs(app)

    /** The viewer's bottom toolbar as the user arranged it (More tools ▸ Customize toolbar). */
    val quickToolbar = io.github.artcraftandroid.pdfcraft.ui.viewer.QuickToolbarPrefs(app)
    private val appPrefs = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
    internal val bookmarkStore = UserBookmarks(File(app.filesDir, "bookmarks.json"))

    /** The key a document's own data (bookmarks, stars, last page) is kept under: where it came from. */
    fun docKey(doc: OpenDocument): String = doc.source?.toString() ?: doc.file.absolutePath

    /** The reader's bookmarks of a document. */
    inner class Bookmarks {
        fun list(doc: OpenDocument) = bookmarkStore.list(docKey(doc))
        fun isBookmarked(doc: OpenDocument, page: Int) = bookmarkStore.has(docKey(doc), page)
        fun rename(doc: OpenDocument, b: UserBookmark, name: String) = bookmarkStore.rename(docKey(doc), b, name)
        fun remove(doc: OpenDocument, b: UserBookmark) = bookmarkStore.remove(docKey(doc), b)
        fun clear(doc: OpenDocument) = bookmarkStore.clear(docKey(doc))
    }

    val bookmarks = Bookmarks()

    /** Add a bookmark on the current page, or remove the one there. */
    fun toggleBookmark(doc: OpenDocument) {
        val (b, added) = bookmarkStore.toggle(docKey(doc), doc.viewer.currentPage)
        tell(text(if (added) R.string.msg_bookmark_added else R.string.msg_bookmark_removed, b.name))
    }

    /** Delete all bookmarks of a document: waiting for confirmation. */
    var confirmDeleteAllBookmarks by mutableStateOf<OpenDocument?>(null)

    fun deleteAllBookmarks(doc: OpenDocument) {
        bookmarks.clear(doc)
        tell(text(R.string.msg_bookmarks_cleared))
    }

    fun isStarred(doc: OpenDocument): Boolean = recentList.firstOrNull { it.uri == docKey(doc) }?.starred == true

    fun toggleStar(doc: OpenDocument) {
        val key = docKey(doc)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recents.update(key) { it.copy(starred = !it.starred) } }
            refreshRecents()
        }
    }

    fun setStarred(uri: String, starred: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recents.update(uri) { it.copy(starred = starred) } }
            refreshRecents()
        }
    }

    // ---- View settings ----------------------------------------------------------------------

    /** Reading Mode (text reflowed to the screen) is showing. Remembered for each file, as the reference does. */
    var readingMode by mutableStateOf(false)
        private set

    /** The page mode documents open in (View settings; remembered app-wide). */
    val savedPageMode: io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode
        get() = if (appPrefs.getString("pageMode", "continuous") == "single") io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.SinglePage
        else io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Continuous

    val savedNightMode: Boolean get() = appPrefs.getBoolean("nightMode", false)

    fun setPageMode(doc: OpenDocument, mode: io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode) {
        // leaving Reading Mode: a canvas laid out already followed the page read; else it shows it on layout
        if (readingMode && doc.viewer.viewW > 0f) pendingPage = null
        readingMode = false
        doc.viewer.switchMode(mode)
        appPrefs.edit { putString("pageMode", if (mode == io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.SinglePage) "single" else "continuous") }
    }

    fun setNightMode(doc: OpenDocument, on: Boolean) {
        doc.controller.nightMode = on
        appPrefs.edit { putBoolean("nightMode", on) }
    }

    fun openReadingMode(doc: OpenDocument) {
        if (doc.controller.tool != ViewerTool.None) leaveTool(doc)
        readingMode = true
    }

    /** A tool tried in Reading Mode, for the dialog that says it needs another view. */
    var readingModeBlocked by mutableStateOf<ViewerTool?>(null)

    /** Keep the screen on while reading (Preferences; released after half an hour without a touch). */
    var keepScreenOn by mutableStateOf(appPrefs.getBoolean("keepScreenOn", false))
        private set

    fun updateKeepScreenOn(on: Boolean) {
        keepScreenOn = on
        appPrefs.edit { putBoolean("keepScreenOn", on) }
    }

    /** The viewer's keyboard shortcuts (Preferences; off until turned on, as the reference has them). */
    var keyboardShortcuts by mutableStateOf(appPrefs.getBoolean("keyboardShortcuts", false))
        private set

    fun updateKeyboardShortcuts(on: Boolean) {
        keyboardShortcuts = on
        appPrefs.edit { putBoolean("keyboardShortcuts", on) }
    }

    /** Persisted appearance; earlier Light/Dark selections keep their original meaning. */
    var theme by mutableStateOf(runCatching { ThemeChoice.valueOf(appPrefs.getString("theme", "System") ?: "System") }.getOrDefault(ThemeChoice.System))
        private set

    fun setThemeChoice(t: ThemeChoice) {
        theme = t
        appPrefs.edit { putString("theme", t.name) }
    }

    /**
     * Shown with the viewer's bars by default. The optional Hide switch stores the inverse;
     * disabling status display keeps the camera/cutout band in the viewer's theme color.
     */
    var statusBarInDocuments by mutableStateOf(appPrefs.getBoolean("statusBarInDocuments", true))
        private set

    fun updateStatusBarInDocuments(on: Boolean) {
        statusBarInDocuments = on
        appPrefs.edit { putBoolean("statusBarInDocuments", on) }
    }

    // ---- Reading the document ---------------------------------------------------------------

    suspend fun outline(doc: OpenDocument) = engine?.let { e -> runCatching { e.command { outline(doc.id) } }.getOrNull() }.orEmpty()

    suspend fun attachments(doc: OpenDocument) = engine?.let { e -> runCatching { e.command { attachments(doc.id) } }.getOrNull() }.orEmpty()

    suspend fun comments(doc: OpenDocument) = engine?.let { e -> runCatching { e.command { comments(doc.id) } }.getOrNull() }.orEmpty()

    /** Open an embedded file: a PDF in the viewer, anything else in another app. */
    fun openAttachment(doc: OpenDocument, name: String) {
        val eng = engine ?: return
        viewModelScope.launch {
            try {
                val list = eng.command { attachments(doc.id) }
                val index = list.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return@launch
                val dir = File(getApplication<Application>().cacheDir, "attachments/${System.nanoTime()}").apply { mkdirs() }
                val out = File(dir, DocumentFiles.safeName(name).let { if (name.lowercase().endsWith(".pdf")) it else name.replace(Regex("[\\\\/:*?\"<>|]"), "_") })
                eng.command { saveAttachment(doc.id, index.toUInt(), out.absolutePath) }
                if (out.name.lowercase().endsWith(".pdf")) {
                    open(Uri.fromFile(out))
                } else {
                    val uri = withContext(Dispatchers.IO) { files.shareable(out, out.name) }
                    val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(out.extension.lowercase()) ?: "*/*"
                    intents.trySend(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                }
            } catch (e: Exception) {
                tell(e.userMessage)
            }
        }
    }

    // ---- Comments ----------------------------------------------------------------------------

    /** A note being written (Comment tool, or Add comment on a selection). */
    var notePrompt by mutableStateOf<NotePrompt?>(null)

    /** Free text being typed (Text tool). */
    var textPrompt by mutableStateOf<TextPrompt?>(null)

    /** A reply being written to a comment. */
    var replyPrompt by mutableStateOf<ReplyPrompt?>(null)

    internal val author: String? get() = toolPrefs.author.ifBlank { null }

    /** Mark text with [markup]: [rects] (points, one per line) on [page]. */
    fun addMarkup(doc: OpenDocument, markup: TextMarkup, page: Int, rects: List<FloatArray>, color: androidx.compose.ui.graphics.Color, opacity: Float, contents: String? = null) {
        val quads = rects.map { r -> listOf(r[0], r[1], r[2], r[1], r[0], r[3], r[2], r[3]).map { it.toDouble() } }
        runTool(
            doc, "comment_add",
            mapOf(
                "page" to page + 1, "type" to markup.engineType, "quads" to quads,
                "color" to color.hex(), "opacity" to opacity.toDouble(), "author" to author, "contents" to contents,
            ),
        )
    }

    /** Post a note at [at] (or, for a selection, a highlight carrying the note). */
    fun postNote(prompt: NotePrompt, text: String) {
        notePrompt = null
        val doc = prompt.doc
        val sel = prompt.selection
        if (sel != null) {
            addMarkup(doc, TextMarkup.Highlight, sel.range.page.toInt(), sel.rects, toolPrefs.color(StyledTool.Highlight), toolPrefs.opacity(StyledTool.Highlight), text)
            doc.selection.clear()
            return
        }
        val at = prompt.at ?: return
        runTool(
            doc, "comment_add",
            mapOf(
                "page" to at.page + 1, "type" to "note", "at" to listOf(at.x.toDouble(), at.y.toDouble()),
                "contents" to text, "color" to toolPrefs.color(StyledTool.Note).hex(), "author" to author,
            ),
        )
    }

    /** Place typed text at the prompt's point, in the Text tool's colour and size. */
    fun postText(prompt: TextPrompt, text: String) {
        textPrompt = null
        if (text.isBlank()) return
        val at = prompt.at
        val size = toolPrefs.textSize
        val page = prompt.doc.info.pages.getOrNull(at.page) ?: return
        // as the reference: lines that wrap where the page ends become real line breaks, and the box
        // is as wide as its longest line (measured as the engine draws it, so it doesn't wrap again)
        val (lines, width, height) = FreeTextLayout.layout(text, size, (page.width - at.x - 6f).coerceAtLeast(size * 4))
        val arabic = text.any { it.code in 0x0600..0x06ff || it.code in 0x0750..0x077f || it.code in 0x08a0..0x08ff }
        val wrapped = if (arabic) text else lines.joinToString("\n")
        val x1 = if (arabic) page.width - 2f else (at.x + width).coerceAtMost(page.width)
        val y1 = (at.y + height).coerceAtMost(page.height)
        runTool(
            prompt.doc, "comment_add",
            mapOf(
                "page" to at.page + 1, "type" to "textbox", "rect" to listOf(at.x, at.y, x1, y1).map { it.toDouble() },
                "contents" to wrapped, "auto_fit" to arabic, "color" to toolPrefs.color(StyledTool.FreeText).hex(), "font_size" to size.toDouble(), "author" to author,
            ),
        )
    }

    fun postReply(prompt: ReplyPrompt, text: String) {
        replyPrompt = null
        if (text.isBlank()) return
        runTool(prompt.doc, "comment_reply", mapOf("page" to prompt.comment.page.toInt() + 1, "index" to prompt.comment.index.toInt() + 1, "text" to text, "author" to author))
    }

    /** Put [text] on the clipboard (Android 13 and later confirm it themselves). */
    fun copyText(text: String) {
        val cm = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
        // the parameter `text` hides the text() function here: called by its full name
        cm?.setPrimaryClip(android.content.ClipData.newPlainText(io.github.artcraftandroid.pdfcraft.text(R.string.msg_clip_comment), text))
        if (android.os.Build.VERSION.SDK_INT < 33) tell(io.github.artcraftandroid.pdfcraft.text(R.string.msg_copied))
    }

    /** Copy the selected text (when the document allows copying). */
    fun copySelection(doc: OpenDocument) {
        if (!doc.info.allowsCopying) {
            tell(text(R.string.msg_copy_not_allowed))
            return
        }
        viewModelScope.launch {
            val text = doc.selection.text() ?: return@launch
            val cm = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText(io.github.artcraftandroid.pdfcraft.text(R.string.msg_clip_pdf_text), text))
            doc.selection.clear()
            if (android.os.Build.VERSION.SDK_INT < 33) tell(io.github.artcraftandroid.pdfcraft.text(R.string.msg_copied))
        }
    }

    // ---- Tool screens (Compress, Combine, Protect, Create, Crop, Recognize text, Scan, Redact, Organize) ----

    /** The full-screen tool over the app, if one is open. */
    var toolScreen by mutableStateOf<ToolScreen?>(null)

    /** The Pages screen is organizing (not just showing thumbnails). */
    var organizing by mutableStateOf(false)

    fun openCompress(doc: OpenDocument) { toolScreen = ToolScreen.Compress(doc) }
    fun openCombine(doc: OpenDocument?) {
        // like the reference, the open document is the first file
        if (doc != null && jobs.combineFiles.isEmpty()) {
            val uri = doc.source ?: Uri.fromFile(doc.file)
            jobs.combineFiles.add(PickedFile(uri, doc.name, doc.info.fileSize.toLong()))
        }
        toolScreen = ToolScreen.Combine(doc)
    }
    fun openProtect(doc: OpenDocument) { toolScreen = ToolScreen.Protect(doc) }
    /** Create PDF starts on its own page of sources (photos, files, a scan), as the reference's. */
    fun openCreate() { toolScreen = ToolScreen.Create }
    fun openCrop(doc: OpenDocument) { toolScreen = ToolScreen.Crop(doc) }
    fun startScan() { toolScreen = ToolScreen.Scan }

    /** Recognize text: make scanned pages searchable and selectable. */
    fun recognizeText(doc: OpenDocument) {
        val eng = engine ?: return
        viewModelScope.launch {
            busy = text(R.string.msg_busy_preparing_ocr)
            withContext(Dispatchers.IO) { runCatching { eng.prepareOcrModels(getApplication()) } }
            busy = null
            val status = runCatching { eng.tool("ocr_status") }.getOrNull()
            if (status?.optBoolean("available", false) != true) {
                tell(text(R.string.msg_ocr_not_installed))
                return@launch
            }
            busy = text(R.string.msg_busy_recognizing)
            try {
                val r = eng.tool("ocr_recognize", mapOf("doc" to doc.id))
                refresh(doc)
                val pages = r.optJSONArray("pages")
                val n = (0 until (pages?.length() ?: 0)).count { pages?.optJSONObject(it)?.has("skipped") == false }
                if (n > 0) {
                    success = SuccessBanner(text(R.string.msg_ocr_done)) { undo(doc) }
                } else {
                    tell(text(R.string.msg_ocr_nothing))
                }
            } catch (e: MobileException) {
                tell(e.userMessage)
            } finally {
                busy = null
            }
        }
    }

    /** A finished job's green banner over the viewer's bar, with Undo. */
    var success by mutableStateOf<SuccessBanner?>(null)

    /** A long-running job's message (a progress dialog over everything), or null. */
    var busy by mutableStateOf<String?>(null)

    // ---- Fill & Sign -------------------------------------------------------------------------

    /** The element a tap places. */
    var fillElement by mutableStateOf(io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement.Text)

    /** Fill & Sign text waiting to be typed. */
    var fillTextPrompt by mutableStateOf<TextPrompt?>(null)

    val signatures = io.github.artcraftandroid.pdfcraft.ui.viewer.Signatures(app.filesDir)

    /** Bumped when a saved signature is cleared or replaced, so the panel redraws. */
    var signaturesChanged by androidx.compose.runtime.mutableIntStateOf(0)

    /** A signature or initials being made. */
    var signaturePad by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePad?>(null)

    /** A signature taken from an image (Image tab), before Done. */
    var importedSignature by mutableStateOf<android.graphics.Bitmap?>(null)

    /** Place [element] at [at] on its page. */
    fun placeFill(doc: OpenDocument, element: io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement, at: PagePoint, text: String? = null) {
        if (element == FillElement.Box) {
            // a rounded box around what was tapped, in the Fill & Sign colour: drawn as one ink stroke
            val r = toolPrefs.fillTextSize
            runTool(doc, "comment_add", mapOf(
                "page" to at.page + 1, "type" to "ink",
                "strokes" to listOf(roundedBox(at.x, at.y, r * 4f, r * 2f, r * 0.6f)),
                "color" to androidx.compose.ui.graphics.Color(toolPrefs.fillColor).hex(), "width" to 1.5, "author" to author,
            ))
            return
        }
        val args = mutableMapOf<String, Any?>(
            "page" to at.page + 1, "type" to element.engineType, "at" to listOf(at.x.toDouble(), at.y.toDouble()), "author" to author,
        )
        when (element) {
            FillElement.Text -> args["text"] = text ?: return
            FillElement.Signature, FillElement.Initials -> args["path"] = signatures.fileFor(element == FillElement.Initials).absolutePath
            else -> Unit
        }
        runTool(doc, "fill_sign_add", args) { selectedFill = doc.comments.lastOrNull { it.fillSign && it.page.toInt() == at.page } }
    }

    /** The outline of a [w] x [h] box with corners of radius [r], centred on ([cx], [cy]), as stroke points. */
    private fun roundedBox(cx: Float, cy: Float, w: Float, h: Float, r: Float): List<List<Double>> {
        val pts = mutableListOf<List<Double>>()
        val l = cx - w / 2f
        val t = cy - h / 2f
        val corners = listOf(Triple(l + w - r, t + r, -90f), Triple(l + w - r, t + h - r, 0f), Triple(l + r, t + h - r, 90f), Triple(l + r, t + r, 180f))
        for ((x, y, start) in corners) {
            for (k in 0..6) {
                val a = Math.toRadians((start + k * 15f).toDouble())
                pts += listOf(x + r * Math.cos(a), y + r * Math.sin(a))
            }
        }
        pts += pts.first()
        return pts
    }

    fun postFillText(prompt: TextPrompt, text: String) {
        fillTextPrompt = null
        if (text.isBlank()) return
        placeFill(prompt.doc, io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement.Text, prompt.at, text)
    }

    /** A signature or initials was made: save it (if asked) and place it where the page was tapped. */
    fun finishSignature(pad: io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePad, bitmap: android.graphics.Bitmap, save: Boolean) {
        signaturePad = null
        importedSignature = null
        viewModelScope.launch {
            val target = if (save) signatures.fileFor(pad.initials) else File(getApplication<Application>().cacheDir, "sign-${System.nanoTime()}.png")
            withContext(Dispatchers.IO) { target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            signaturesChanged++
                fillElement = if (pad.initials) FillElement.Initials else FillElement.Signature
            val doc = pad.doc
            val at = pad.at
            if (doc != null && at != null) {
                runTool(doc, "fill_sign_add", mapOf(
                    "page" to at.page + 1, "type" to if (pad.initials) "initials" else "signature",
                    "at" to listOf(at.x.toDouble(), at.y.toDouble()), "path" to target.absolutePath, "author" to author,
                )) { selectedFill = doc.comments.lastOrNull { it.fillSign && it.page.toInt() == at.page } }
            } else {
                tell(text(if (pad.initials) R.string.msg_place_initials else R.string.msg_place_signature))
            }
        }
    }

    /** An image chosen for a signature: made transparent where it is near white, then shown on the pad. */
    fun importSignatureImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            importedSignature = withContext(Dispatchers.IO) {
                runCatching {
                    val src = getApplication<Application>().contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@runCatching null
                    val scale = minOf(1f, 1200f / maxOf(src.width, src.height))
                    val bmp = src.scale((src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
                        .copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    for (i in px.indices) {
                        val p = px[i]
                        val l = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
                        // paper becomes transparent, ink keeps its strength
                        val a = ((235 - l) * 255 / 120).coerceIn(0, 255)
                        px[i] = (a shl 24) or (p and 0xFFFFFF)
                    }
                    bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    bmp
                }.getOrNull()
            }
            if (importedSignature == null) tell(text(R.string.msg_image_unreadable))
        }
    }

    /** A tool's refusal without the tool's name in front, as a sentence. */
    internal fun toolMessage(e: Throwable, tool: String): String =
        e.userMessage.removePrefix("$tool: ").replaceFirstChar { it.uppercase() }.let { if (it.lastOrNull()?.isLetterOrDigit() == true) "$it." else it }

    fun transformFill(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo,
        dx: Float, dy: Float, factor: Float, finished: () -> Unit) {
        val args = if (factor == 1f) mapOf("move" to listOf(dx.toDouble(), dy.toDouble())) else {
            val rect = listOf(c.rect[0], c.rect[1], c.rect[0] + (c.rect[2] - c.rect[0]) * factor, c.rect[1] + (c.rect[3] - c.rect[1]) * factor)
            mapOf("rect" to rect.map { it.toDouble() }) +
                (c.fontSize?.let { mapOf("font_size" to (it * factor).toDouble()) } ?: emptyMap())
        }
        runTool(doc, "comment_edit", commentArgs(c) + args, onFinished = finished) {
            selectedFill = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        }
    }

    /** The menu a long press on an empty part of a page opens. */
    var pageMenu by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.PageMenu?>(null)

    /**
     * A long press where no word is: on a picture, Edit PDF with the picture selected (the
     * reference's picture menu leads to editing it); elsewhere, the page menu at that point.
     */
    fun longPressPage(doc: OpenDocument, at: PagePoint, screen: androidx.compose.ui.geometry.Offset) {
        val eng = engine ?: return
        viewModelScope.launch {
            val img = edit.imageUnder(doc, at)
            if (img != null) {
                enterTool(doc, io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool.Edit)
                if (doc.controller.tool == io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool.Edit) edit.editImage = img
                return@launch
            }
            val glyphs = runCatching { withContext(eng.commands) { eng.session.glyphCount(doc.id, at.page.toUInt()) } }.getOrDefault(1u)
            pageMenu = io.github.artcraftandroid.pdfcraft.ui.viewer.PageMenu(doc, at, screen, pageHasText = glyphs > 0u)
        }
    }

    // ---- File pickers -----------------------------------------------------------------------

    /** File pickers for the activity: what the files are for, the types, and whether several may be picked. */
    val filePicks = Channel<FilePickKind>(Channel.BUFFERED)

    private var insertFileAt: Pair<OpenDocument, Int>? = null

    fun pickFiles(kind: FilePickKind) {
        filePicks.trySend(kind)
    }

    /** Files picked for [kind]. */
    fun onFilesPicked(kind: FilePickKind, uris: List<Uri>) {
        if (uris.isEmpty()) return
        when (kind) {
            FilePickKind.Combine -> viewModelScope.launch {
                val picked = withContext(Dispatchers.IO) { uris.map { u -> PickedFile(u, files.displayNameRaw(u), files.size(u)) } }
                jobs.combineFiles.addAll(picked.filter { p -> jobs.combineFiles.none { it.uri == p.uri } })
                if (toolScreen !is ToolScreen.Combine) toolScreen = ToolScreen.Combine(null)
            }
            FilePickKind.Create -> jobs.createFrom(uris)
            FilePickKind.Insert -> {
                val (doc, at) = insertFileAt ?: return
                insertFileAt = null
                viewModelScope.launch {
                    val f = withContext(Dispatchers.IO) { jobs.copyIn(uris.first()) }
                    runTool(doc, "page_insert_file", mapOf("path" to f.absolutePath, "at" to at))
                }
            }
            FilePickKind.Open -> open(uris.first())
            FilePickKind.DigitalId -> viewModelScope.launch {
                val uri = uris.first()
                val picked = withContext(Dispatchers.IO) { runCatching { jobs.copyIn(uri) }.getOrNull() }
                if (picked == null) tell(text(R.string.msg_that_file_unreadable))
                else signing.idImport = io.github.artcraftandroid.pdfcraft.ui.viewer.IdImport(picked, picked.name, wrong = false)
            }
        }
    }

    fun pickInsertFile(doc: OpenDocument, at: Int) {
        insertFileAt = doc to at
        pickFiles(FilePickKind.Insert)
    }

    // ---- Home, Files, Search -----------------------------------------------------------------

    var homeTab by mutableStateOf(io.github.artcraftandroid.pdfcraft.ui.home.HomeTab.Home)
    var settingsOpen by mutableStateOf(false)

    /** The file whose "..." sheet is open, and whether it was opened from Recent. */
    var fileSheet by mutableStateOf<Pair<io.github.artcraftandroid.pdfcraft.ui.home.FileRow, Boolean>?>(null)
    var showLicences by mutableStateOf(false)
    var filesShowLocal by mutableStateOf(false)

    /** Bumped when a preference shown on screen changes outside Compose state. */
    var prefsChanged by androidx.compose.runtime.mutableIntStateOf(0)

    /** Back from the access page (or the app resumed): look again. */
    fun onResumed() {
        library.onResumed()
        refreshRecents()
    }

    /** What to do with the next PDF opened from a tool tile (Home, Tools). */
    private var pendingTool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool? = null

    /** A tool that starts by choosing a PDF: pick one, open it, then start the tool. */
    fun toolForFile(tool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool) {
        pendingTool = tool
        pickFiles(FilePickKind.Open)
    }

    /** Open [row] and start [tool] on it. */
    fun openFor(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow, tool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool) {
        pendingTool = tool
        row.open()
    }

    /** A document just opened: start the tool it was opened for, if any. */
    private fun startPendingTool(doc: OpenDocument) {
        val t = pendingTool ?: return
        pendingTool = null
        when (t) {
            FileTool.Edit -> enterTool(doc, ViewerTool.Edit)
            FileTool.ReadAloud -> startReadAloud(doc)
            FileTool.FillSign -> enterTool(doc, ViewerTool.FillSign)
            FileTool.Comment -> enterTool(doc, ViewerTool.Comment)
            FileTool.Organize -> { organizing = true; doc.controller.pagesOpen = true }
            FileTool.Compress -> openCompress(doc)
            FileTool.Export -> openExport(doc)
            FileTool.Protect -> openProtect(doc)
            FileTool.Crop -> openCrop(doc)
            FileTool.Ocr -> recognizeText(doc)
        }
    }

    /** Photos: pick pictures and make them into a PDF (Create PDF from the photo picker). */
    val photoPicks = Channel<Unit>(Channel.CONFLATED)

    fun pickPhotosToPdf() {
        photoPicks.trySend(Unit)
    }

    fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) onFilesPicked(FilePickKind.Create, uris)
    }

    // ---- Selecting a comment ------------------------------------------------------------------

    /** The comment tapped: its panel shows over the viewer. */
    var selectedComment by mutableStateOf<Pair<OpenDocument, io.github.artcraftandroid.pdfcraft.core.CommentInfo>?>(null)

    /** A tap at [at]: select the comment there (the top one), if any. */
    fun tapComment(doc: OpenDocument, at: PagePoint?): Boolean {
        if (at == null) return false
        val hit = doc.comments.lastOrNull { c ->
            c.page.toInt() == at.page && c.replyTo == null && c.state == null && c.rect.size == 4 &&
                at.x in (c.rect[0] - 4)..(c.rect[2] + 4) && at.y in (c.rect[1] - 4)..(c.rect[3] + 4)
        } ?: return false
        selectedComment = doc to hit
        return true
    }

    private fun commentArgs(c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) = mapOf("page" to c.page.toInt() + 1, "index" to c.index.toInt() + 1)

    fun deleteComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) {
        selectedComment = null
        runTool(doc, "comment_delete", commentArgs(c))
    }

    fun recolorComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, color: androidx.compose.ui.graphics.Color) {
        runTool(doc, "comment_edit", commentArgs(c) + ("color" to color.hex())) { reselect(doc, c) }
    }

    /** Change a comment's opacity or line width (one undo step each). */
    fun styleComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, style: Map<String, Any?>) {
        runTool(doc, "comment_edit", commentArgs(c) + style) { reselect(doc, c) }
    }

    fun editCommentText(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, text: String) {
        runTool(doc, "comment_edit", commentArgs(c) + ("contents" to text)) { reselect(doc, c) }
    }

    fun moveComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, dx: Float, dy: Float) {
        runTool(doc, "comment_edit", commentArgs(c) + ("move" to listOf(dx.toDouble(), dy.toDouble()))) { reselect(doc, c) }
    }

    /** Put a comment in a new rectangle (points, top-left origin): a resize. */
    fun resizeComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, rect: List<Float>) {
        runTool(doc, "comment_edit", commentArgs(c) + ("rect" to rect.map { it.toDouble() })) { reselect(doc, c) }
    }

    /** Move or resize a Fill & Sign element, keeping it selected. */
    fun moveFill(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, dx: Float, dy: Float) {
        runTool(doc, "comment_edit", commentArgs(c) + ("move" to listOf(dx.toDouble(), dy.toDouble()))) {
            selectedFill = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        }
    }

    fun resizeFill(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, rect: List<Float>) {
        runTool(doc, "comment_edit", commentArgs(c) + ("rect" to rect.map { it.toDouble() })) {
            selectedFill = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        }
    }

    /** After an edit the comment is read again (same page and position). */
    private fun reselect(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) {
        val again = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        selectedComment = again?.let { doc to it }
    }

    /** The Fill & Sign element tapped (its menu shows over it). */
    var selectedFill by mutableStateOf<io.github.artcraftandroid.pdfcraft.core.CommentInfo?>(null)

    /** Make a Fill & Sign element [factor] times bigger, about its top-left corner. */
    fun scaleFill(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, factor: Float) {
        if (c.rect.size < 4) return
        transformFill(doc, c, 0f, 0f, factor) {}
    }

    /**
     * Page [page]'s text reflowed into paragraphs from its text layer: lines are joined until one
     * ends a sentence well short of the line width, or a heading-like short line stands alone.
     */
    suspend fun reflowParagraphs(doc: OpenDocument, page: Int): List<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph> {
        val eng = engine ?: return emptyList()
        val text = runCatching { eng.command { pagePlainText(doc.id, page.toUInt()) } }.getOrDefault("")
        val lines = text.lines().map { it.trim() }
        if (lines.none { it.isNotEmpty() }) return emptyList()
        val longest = lines.maxOf { it.length }.coerceAtLeast(1)
        val out = mutableListOf<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) {
                out += io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph(page, out.size + 1, current.toString().trim(), floatArrayOf(0f, 0f, 0f, 0f), "", 12f, false, false, null)
            }
            current.clear()
        }
        for (line in lines) {
            if (line.isEmpty()) {
                flush()
                continue
            }
            // a hyphen at a line's end joins the word across it
            if (current.endsWith("-")) current.setLength(current.length - 1) else if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            val short = line.length < longest * 0.6
            if (short && (line.last() in ".!?:" || line.length < longest * 0.35)) flush()
        }
        flush()
        return out
    }
    // Launch only after every state property and lazy delegate has been initialized.
    init {
        viewModelScope.launch {
            application.engine.await().onSuccess { engine = it }.onFailure { engineProblem = it.userMessage }
            refreshRecents()
            // the signers the reader trusts, before any signature is checked
            // (a document opened from another app meanwhile is checked again)
            engine?.let { eng ->
                if (withContext(Dispatchers.IO) { signing.trustedCerts.files().isNotEmpty() }) {
                    runCatching { signing.applyTrust(eng) }
                    document?.let { signing.loadSignatures(eng, it) }
                }
            }
        }
    }

}

/** A green banner saying a job worked, with the way to undo it. */
class SuccessBanner(val text: String, val undo: (() -> Unit)?)

/** [bmp] (decoded from [f]) turned upright as its EXIF orientation says. */
internal fun rotateForExif(f: File, bmp: android.graphics.Bitmap): android.graphics.Bitmap {
    val deg = runCatching {
        when (androidx.exifinterface.media.ExifInterface(f.absolutePath).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (deg == 0f) return bmp
    val m = android.graphics.Matrix().apply { postRotate(deg) }
    return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
