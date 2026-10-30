package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.LocalFiles
import io.github.artcraftandroid.pdfcraft.engine.LocalPdf
import io.github.artcraftandroid.pdfcraft.engine.RecentFile
import io.github.artcraftandroid.pdfcraft.engine.Thumbnails
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.home.FileRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home's files: Recent, Starred, On this device and the Search tab, and what a file row's menu does. */
class FileLibrary internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()
    private val appPrefs = vm.getApplication<Application>().getSharedPreferences("app", Context.MODE_PRIVATE)

    /** The Search tab's last searches, newest first (kept on the phone, as the reference keeps them). */
    var recentSearches by mutableStateOf(appPrefs.getString("recentSearches", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
        private set

    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        recentSearches = (listOf(t) + recentSearches.filterNot { it.equals(t, ignoreCase = true) }).take(10)
        appPrefs.edit { putString("recentSearches", recentSearches.joinToString("\n")) }
    }

    fun removeRecentSearch(q: String) {
        recentSearches = recentSearches - q
        appPrefs.edit { putString("recentSearches", recentSearches.joinToString("\n")) }
    }

    fun clearRecentSearches() {
        recentSearches = emptyList()
        appPrefs.edit { remove("recentSearches") }
    }

    /** Lists as a grid instead of rows (remembered). */
    private var gridViewState by mutableStateOf(appPrefs.getBoolean("gridView", false))
    var gridView: Boolean
        get() = gridViewState
        set(v) {
            gridViewState = v
            appPrefs.edit { putBoolean("gridView", v) }
        }

    /** Starred: 0 by date, 1 by name. */
    private var starSortState by mutableIntStateOf(appPrefs.getInt("starSort", 0))
    var starSort: Int
        get() = starSortState
        set(v) {
            starSortState = v
            appPrefs.edit { putInt("starSort", v) }
        }

    val thumbnails = Thumbnails(app)

    var localFiles by mutableStateOf<List<LocalPdf>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var hasFileAccess by mutableStateOf(LocalFiles.hasAccess(app))
        private set
    private var lastScan = 0L

    /** Look for the PDFs on the phone (at most every half minute unless [force]d). */
    fun scanLocal(force: Boolean = false) {
        hasFileAccess = LocalFiles.hasAccess(app)
        val now = System.currentTimeMillis()
        if (!force && now - lastScan < 30_000 && localFiles.isNotEmpty()) return
        lastScan = now
        vm.viewModelScope.launch {
            scanning = true
            localFiles = withContext(Dispatchers.IO) { LocalFiles.scan(app) }
            scanning = false
        }
    }

    /** Adding a folder to On this device: the activity opens the system's folder picker. */
    val accessRequests = Channel<Unit>(Channel.CONFLATED)

    fun requestFileAccess() {
        accessRequests.trySend(Unit)
    }

    /** The folders On this device lists, with their names. */
    fun localFolders(): List<Pair<Uri, String>> {
        return LocalFiles.folders(app).map { it to LocalFiles.folderName(app, it) }
    }

    /** A folder chosen in the picker: list its PDFs from now on. */
    fun addLocalFolder(tree: Uri) {
        LocalFiles.addFolder(app, tree)
        hasFileAccess = LocalFiles.hasAccess(app)
        scanLocal(force = true)
    }

    /** Stop listing [tree]. */
    fun removeLocalFolder(tree: Uri) {
        LocalFiles.removeFolder(app, tree)
        hasFileAccess = LocalFiles.hasAccess(app)
        scanLocal(force = true)
    }

    /** Back from the access page (or the app resumed): look again. */
    fun onResumed() {
        val had = hasFileAccess
        hasFileAccess = LocalFiles.hasAccess(app)
        if (hasFileAccess && !had) scanLocal(force = true)
    }

    /** What [uri]'s thumbnail is drawn from: the file itself, the app's working copy, or the document. */
    private fun thumbPathFor(uri: String): String? {
        val u = uri.toUri()
        return when (u.scheme) {
            "file" -> u.path
            else -> vm.files.existingCopy(u)?.absolutePath ?: uri
        }
    }

    fun recentRows(): List<FileRow> = vm.recentList.map { r ->
        FileRow(r.uri, r.name, thumbPathFor(r.uri), locationOf(r.uri), r.opened, r.size, r.starred) { vm.open(r.uri.toUri()) }
    }

    fun starredRows(): List<FileRow> = recentRows().filter { it.starred }.let { l ->
        if (starSort == 1) l.sortedBy { it.name.lowercase() } else l.sortedByDescending { it.date }
    }

    fun localRows(): List<FileRow> {
        // a file opened from another app is known by a different address than the scan's path,
        // so stars are matched by the file itself (name and size)
        val stars = vm.recentList.filter { it.starred }.flatMap { listOf(it.uri, sameFile(it.name, it.size)) }.toSet()
        return localFiles.map { f ->
            val key = f.uri.toString()
            FileRow(key, f.name, f.path ?: key, f.folder, f.modified, f.size, key in stars || sameFile(f.name, f.size) in stars) { vm.open(f.uri) }
        }
    }

    private fun sameFile(name: String, size: Long) = "$name|$size"

    /** Files whose names contain [query]: the phone's and Recent's, each file once. */
    fun searchFiles(query: String): List<FileRow> {
        val q = query.trim().lowercase()
        val all = localRows() + recentRows()
        return all.filter { q in it.name.lowercase() }.distinctBy { it.key }.distinctBy { sameFile(it.name, it.size) }
    }

    private fun locationOf(uri: String): String = runCatching { vm.files.displayLocation(uri.toUri()) }.getOrDefault(text(R.string.files_location_this_device))

    fun clearRecents() {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // starred files stay: the stars live with them
                val keep = vm.recents.list().filter { it.starred }
                vm.recents.clear()
                keep.reversed().forEach { vm.recents.touch(it) }
            }
            vm.refreshRecents()
        }
    }

    fun removeRecent(key: String) {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { vm.recents.remove(setOf(key)) }
            vm.refreshRecents()
        }
    }

    /** A file row's local file, for sharing and the like: the file itself, or a fresh copy. */
    private suspend fun localFileOf(row: FileRow): File? = withContext(Dispatchers.IO) {
        val u = row.key.toUri()
        runCatching { if (u.scheme == "file") File(requireNotNull(u.path)) else vm.files.workingCopy(u) }.getOrNull()
    }

    /** Files ticked in Home's lists (a long press starts it), keyed by where they are. */
    val selectedFiles = mutableStateMapOf<String, FileRow>()

    fun toggleSelected(row: FileRow) {
        if (selectedFiles.remove(row.key) == null) selectedFiles[row.key] = row
    }

    /** Several PDFs to the share sheet at once. */
    fun shareFiles(rows: List<FileRow>) {
        vm.handOut {
            val local = rows.mapNotNull { r -> localFileOf(r)?.let { it to r.name } }
            if (local.isEmpty()) error(text(R.string.msg_files_unreadable))
            vm.sendIntent(withContext(Dispatchers.IO) { vm.files.shareableAll(local) })
        }
    }

    /** Combine the ticked files, in the order shown. */
    fun combineSelected(rows: List<FileRow>) {
        vm.combineFiles.clear()
        rows.take(12).forEach { vm.combineFiles.add(PickedFile(it.key.toUri(), it.name, it.size)) }
        vm.toolScreen = ToolScreen.Combine(null)
    }

    fun removeRecents(rows: List<FileRow>) {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { vm.recents.remove(rows.map { it.key }.toSet()) }
            vm.refreshRecents()
        }
    }

    fun shareFile(row: FileRow) {
        vm.handOut {
            val f = localFileOf(row) ?: error(text(R.string.common_file_unreadable))
            vm.sendIntent(listOf(withContext(Dispatchers.IO) { vm.files.shareable(f, row.name) }))
        }
    }

    fun printFile(row: FileRow) {
        vm.viewModelScope.launch {
            val f = localFileOf(row) ?: return@launch vm.tell(text(R.string.common_file_unreadable))
            vm.prints.trySend(f to row.name)
        }
    }

    fun openWith(row: FileRow) {
        vm.handOut {
            val f = localFileOf(row) ?: error(text(R.string.common_file_unreadable))
            val uri = withContext(Dispatchers.IO) { vm.files.shareable(f, row.name) }
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun renameFile(row: FileRow, newName: String) {
        vm.viewModelScope.launch {
            val name = DocumentFiles.safeName(newName.trim())
            val u = row.key.toUri()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        u.scheme == "file" -> {
                            val f = File(requireNotNull(u.path))
                            val target = File(f.parentFile, name)
                            require(!target.exists()) { text(R.string.msg_rename_exists, name) }
                            require(f.renameTo(target)) { text(R.string.msg_rename_failed) }
                            vm.recents.remove(setOf(row.key))
                            vm.recents.touch(RecentFile(Uri.fromFile(target).toString(), name, System.currentTimeMillis(), 0, target.length()))
                            vm.bookmarkStore.move(row.key, Uri.fromFile(target).toString())
                        }
                        // what the tools saved to Downloads: MediaStore entries keep their address when renamed
                        u.authority == android.provider.MediaStore.AUTHORITY -> {
                            val values = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name) }
                            require(app.contentResolver.update(u, values, null, null) > 0) { text(R.string.msg_rename_not_here) }
                            vm.recents.update(row.key) { it.copy(name = name) }
                        }
                        else -> {
                            val renamed = android.provider.DocumentsContract.renameDocument(app.contentResolver, u, name)
                                ?: error(text(R.string.msg_rename_not_here))
                            vm.recents.remove(setOf(row.key))
                            vm.recents.touch(RecentFile(renamed.toString(), name, System.currentTimeMillis(), 0, vm.files.size(renamed) ?: 0))
                            vm.bookmarkStore.move(row.key, renamed.toString())
                        }
                    }
                }
            }
            ok.onFailure { vm.tell(it.userMessage) }
            vm.refreshRecents()
            scanLocal(force = true)
        }
    }

    fun duplicateFile(row: FileRow) {
        vm.viewModelScope.launch {
            val f = localFileOf(row) ?: return@launch vm.tell(text(R.string.common_file_unreadable))
            val copy = withContext(Dispatchers.IO) { File(app.cacheDir, "dup-${System.nanoTime()}.pdf").also { f.copyTo(it, overwrite = true) } }
            val base = row.name.removeSuffix(".pdf")
            val uri = withContext(Dispatchers.IO) { vm.files.saveToDownloads(copy, "$base copy.pdf").also { copy.delete() } }
            vm.tell(text(R.string.msg_duplicated, "$base copy.pdf"), text(R.string.common_open)) { vm.open(uri) }
            scanLocal(force = true)
        }
    }

    private var copyRow: FileRow? = null

    fun saveFileCopy(row: FileRow) {
        copyRow = row
        vm.saveCopyRequests.trySend(row.name)
    }

    /** The place chosen for a file row's Save a copy. */
    fun rowCopyTo(target: Uri?): Boolean {
        val row = copyRow ?: return false
        copyRow = null
        if (target == null) return true
        vm.viewModelScope.launch {
            val f = localFileOf(row) ?: return@launch vm.tell(text(R.string.common_file_unreadable))
            runCatching { withContext(Dispatchers.IO) { vm.files.writeBack(f, target) } }
                .onSuccess { vm.tell(text(R.string.msg_saved_copy)) }.onFailure { vm.tell(it.userMessage) }
        }
        return true
    }

    fun deleteFile(row: FileRow) {
        vm.viewModelScope.launch {
            val u = row.key.toUri()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        u.scheme == "file" -> require(File(requireNotNull(u.path)).delete()) { text(R.string.msg_delete_failed) }
                        u.authority == android.provider.MediaStore.AUTHORITY ->
                            require(app.contentResolver.delete(u, null, null) > 0) { text(R.string.msg_delete_failed) }
                        else -> require(android.provider.DocumentsContract.deleteDocument(app.contentResolver, u)) { text(R.string.msg_delete_failed) }
                    }
                    vm.recents.remove(setOf(row.key))
                }
            }
            ok.onFailure { vm.tell(it.userMessage) }.onSuccess { vm.tell(text(R.string.msg_file_deleted, row.name)) }
            vm.refreshRecents()
            scanLocal(force = true)
        }
    }
}
