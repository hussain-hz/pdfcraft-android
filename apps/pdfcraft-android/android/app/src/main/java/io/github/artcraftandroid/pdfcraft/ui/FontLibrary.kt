package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.*
import io.github.artcraftandroid.pdfcraft.text
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Settings font management has no document target and never changes the selected text. */
class FontLibrary internal constructor(private val vm:AppViewModel) {
    private val app:Application get()=vm.getApplication()
    val catalog by lazy {readCoreFontCatalog(app.assets.open("core-font-catalog.json").bufferedReader().use {it.readText()})}
    val license by lazy {app.assets.open("core-fonts-license.txt").bufferedReader().use {it.readText()}}
    private val directory get()=vm.fonts.directory
    var installed by mutableIntStateOf(0);private set
    var working by mutableStateOf(false);private set
    var progress by mutableStateOf<Float?>(null);private set
    var current by mutableStateOf("");private set
    var problem by mutableStateOf<String?>(null);private set
    var message by mutableStateOf<String?>(null);private set
    private var job:Job?=null
    var licenseAccepted by mutableStateOf(app.getSharedPreferences("app",0).getBoolean("coreFontsAccepted",false)); private set
    private var operation=0L
    var settingsRequested=false
    fun openSettings() {settingsRequested=true;vm.fonts.close();vm.settingsOpen=true}

    private fun work(action:suspend ()->Unit) {
        if(working) return
        val token=++operation;working=true;progress=null;problem=null;message=null
        job=vm.viewModelScope.launch {
            try {action()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {if(token==operation) problem=text(R.string.font_library_failed)}
            finally {if(token==operation) {working=false;progress=null;current=""}}
        }
    }
    private suspend fun refreshStats() {
        installed=withContext(Dispatchers.IO) {catalog.sumOf {p ->p.fonts.count {face ->
            val f=p.installedFile(directory,face);f.isFile && f.length()==face.size.toLong() && fontDigest(f.readBytes())==face.sha256
        }}}
        vm.fonts.refreshLibrary()
    }
    fun refresh()=work {refreshStats()}
    fun cancel() {operation++;job?.cancel();working=false;progress=null;current="";message=text(R.string.font_library_cancelled);vm.viewModelScope.launch {runCatching {refreshStats()}}}
    fun pick() {if(!working) vm.pickFiles(FilePickKind.ImportFontLibrary)}

    fun acceptAndInstall() {licenseAccepted=true;app.getSharedPreferences("app",0).edit {putBoolean("coreFontsAccepted",true)};installCore()}

    fun installCore(transfer:FontTransfer=::fetchCoreFontBytes) = work {
        val token=operation;val total=catalog.sumOf {it.size}.toFloat();var completed=0
        for(p in catalog) {
            current=p.family
            withContext(Dispatchers.IO) {cacheCoreFontPackage(p,directory,{if(token==operation) progress=(completed+it*p.size)/total},transfer)}
            completed+=p.size
        }
        refreshStats();message=text(R.string.font_library_installed,installed)
    }

    fun importPicked(uris:List<Uri>)=work {
        require(uris.size in 1..100)
        val engine=vm.engine ?: error("Engine unavailable")
        val stage=withContext(Dispatchers.IO) {File.createTempFile("font-library-", "", app.cacheDir).apply {delete();mkdirs()}}
        try {
            val candidates=withContext(Dispatchers.IO) {
                val all=mutableListOf<File>()
                uris.forEachIndexed {index,uri ->
                    val source=File(stage,"source-$index.tmp")
                    try {
                        requireNotNull(app.contentResolver.openInputStream(uri)).use {copyFontImport(it,source)}
                        all.addAll(stageFontImport(source,stage))
                        require(all.size<=512 && all.sumOf {it.length()}<=128L*1024*1024) {"Font import is too large"}
                        progress=(index+1f)/uris.size
                    } finally {source.delete()}
                }
                all.distinctBy {it.name}
            }
            val discovered=engine.tool("text_font_files",mapOf("directories" to listOf(stage.absolutePath))).getJSONArray("fonts")
            val valid=(0 until discovered.length()).map {File(discovered.getJSONObject(it).getString("path")).canonicalPath}.toSet()
            val accepted=candidates.filter {it.canonicalPath in valid}
            require(accepted.isNotEmpty()) {"No usable font files"}
            withContext(Dispatchers.IO) {documentFontCacheMutex.withLock {
                for(file in accepted) {ensureActive();val out=File(directory,file.name)
                    if(!out.isFile) atomicFontWrite(out,file.readBytes())
                }
            }}
            refreshStats();message=text(R.string.font_library_imported,accepted.size,candidates.size-accepted.size)
        } finally {withContext(NonCancellable+Dispatchers.IO) {stage.deleteRecursively()}}
    }
}
