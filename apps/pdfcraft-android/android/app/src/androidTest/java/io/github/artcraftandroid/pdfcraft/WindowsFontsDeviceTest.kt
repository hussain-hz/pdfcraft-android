package io.github.artcraftandroid.pdfcraft

import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.artcraftandroid.pdfcraft.core.MobileSession
import io.github.artcraftandroid.pdfcraft.engine.*
import io.github.artcraftandroid.pdfcraft.ui.*
import io.github.artcraftandroid.pdfcraft.ui.viewer.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowsFontsDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog get()=readCoreFontCatalog(context.assets.open("core-font-catalog.json").bufferedReader().use {it.readText()})
    private val cache get()=File(context.getExternalFilesDir(null),"windows-font-qa").apply {mkdirs()}
    private suspend fun arial():Pair<CoreFontPackage,File> {
        val pack=catalog.single {it.family=="Arial"}
        withContext(Dispatchers.IO) {cacheCoreFontPackage(pack,cache)}
        return pack to cache
    }
    @Test fun allThirtyCoreFacesDownloadVerifyAndDecodeOnAndroid()=runBlocking {
        val packages=catalog
        withContext(Dispatchers.IO) {packages.forEach {cacheCoreFontPackage(it,cache)}}
        assertEquals(30,packages.sumOf {it.fonts.size})
        packages.forEach {assertTrue(it.file,corePackageInstalled(it,cache))}
        val session=MobileSession()
        val result=JSONObject(session.execute("text_font_files",JSONObject().put("directories",org.json.JSONArray().put(cache.path)).toString()))
        val fonts=result.getJSONArray("fonts")
        val names=(0 until fonts.length()).map {fonts.getJSONObject(it).getString("font")}.toSet()
        assertEquals(packages.flatMap {it.fonts.map {f ->f.face}}.toSet(),names)
        // A fully verified cache works with networking disabled.
        withContext(Dispatchers.IO) {packages.forEach {cacheCoreFontPackage(it,cache,transfer={_,_,_,_,_->error("offline")})}}
    }
    @Test fun importedMicrosoftFamilyIsSelectedAutomaticallyForNewTextAndSurvivesSave()=runBlocking {
        val (pack,dir)=arial()
        val zip=File(dir,"Arial-import.zip")
        ZipOutputStream(zip.outputStream()).use {out ->pack.fonts.take(2).forEach {face ->
            out.putNextEntry(ZipEntry("Arial/${face.name}"));out.write(pack.installedFile(dir,face).readBytes());out.closeEntry()
        }}
        val store=ViewModelStore()
        val vm=withContext(Dispatchers.Main) {AppViewModel(context.applicationContext as PdfcraftApplication,SavedStateHandle()).also {store.put("vm",it)}}
        withTimeout(60000) {while(withContext(Dispatchers.Main) {vm.engine==null}) delay(50)}
        val engine=requireNotNull(vm.engine)
        try {
            withContext(Dispatchers.Main) {vm.fontLibrary.importPicked(listOf(Uri.fromFile(zip)))}
            withTimeout(30000) {while(withContext(Dispatchers.Main) {vm.fontLibrary.working}) delay(50)}
            assertNull(vm.fontLibrary.problem);assertNotNull(vm.fontLibrary.message)
            assertTrue(vm.fonts.stored.any {it.face=="Arial-BoldMT"})
            val file=File(dir,"source.pdf");val pdf=PdfDocument()
            pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(612,792,1).create()));file.outputStream().use {pdf.writeTo(it)};pdf.close()
            val id=engine.command {open(file.path,null).id}
            try {
                val bold=pack.installedFile(dir,pack.fonts.single {it.face=="Arial-BoldMT"})
                engine.tool("page_add_text",mapOf("doc" to id,"page" to 1,"text" to "Original Arial","at" to listOf(60,80),"size" to 18,
                    "bold" to true,"color" to "#123456","font_path" to bold.path))
                engine.tool("doc_save",mapOf("doc" to id,"path" to file.path,"full" to true))
            } finally {engine.command {close(id,true)}}
            withContext(Dispatchers.Main) {vm.open(Uri.fromFile(file))}
            withTimeout(30000) {while(withContext(Dispatchers.Main) {vm.document==null}) delay(50)}
            val doc=requireNotNull(vm.document)
            android.util.Log.i("WindowsFontsQA","source document opened")
            vm.edit.loadParagraphs(doc,0)
            val source=vm.edit.paragraphs[0]!!.single()
            assertEquals("Arial-BoldMT",source.font)
            android.util.Log.i("WindowsFontsQA","source paragraph font ${source.font}, generation ${doc.info.generation}")
            withContext(Dispatchers.Main) {vm.edit.postAddText(TextPrompt(doc,PagePoint(0,60f,220f),source),"Auto Arial")}
            val ready=withTimeoutOrNull(30000) {while(withContext(Dispatchers.Main) {vm.edit.editSelection?.text!="Auto Arial"}) delay(50);true} ?: false
            assertTrue("Add text: state=${withContext(Dispatchers.Main) {listOf(vm.edit.addingText,vm.edit.addTextPrompt?.draft,vm.edit.editSelection?.text,doc.info.generation)}} messages=${vm.messages.tryReceive().getOrNull()} paragraphs=${engine.tool("text_paragraphs",mapOf("doc" to doc.id,"page" to 1))}",ready)
            val result=engine.tool("text_paragraphs",mapOf("doc" to doc.id,"page" to 1)).getJSONArray("paragraphs")
            val added=(0 until result.length()).map {result.getJSONObject(it)}.single {it.getString("text")=="Auto Arial"}
            assertEquals("Arial-BoldMT",added.getString("font"));assertEquals(18.0,added.getDouble("size"),.01);assertEquals("#123456",added.getString("color"))
            assertTrue("Save through the app failed: ${vm.messages.tryReceive().getOrNull()}",vm.save(doc))
            val saved=file
            val reopened=engine.command {open(saved.path,null).id}
            try {
                val after=engine.tool("text_paragraphs",mapOf("doc" to reopened,"page" to 1)).getJSONArray("paragraphs")
                assertTrue((0 until after.length()).map {after.getJSONObject(it)}.any {it.getString("text")=="Auto Arial" && it.getString("font")=="Arial-BoldMT"})
            } finally {engine.command {close(reopened,true)}}
            // Closing immediately must await the asynchronous face lookup and commit.
            withContext(Dispatchers.Main) {
                vm.edit.postAddText(TextPrompt(doc,PagePoint(0,60f,300f),source),"Close Arial")
                vm.closeDocument()
            }
            val closedOk=withTimeoutOrNull(30000) {while(withContext(Dispatchers.Main) {vm.document!=null}) delay(50);true} ?: false
            assertTrue("Close state=${withContext(Dispatchers.Main) {listOf(vm.edit.addingText,vm.edit.addTextPrompt?.draft,doc.closing,doc.closed,vm.readOnlyPrompt!=null,doc.info.generation)}} messages=${vm.messages.tryReceive().getOrNull()}",closedOk)
            val closed=engine.command {open(file.path,null).id}
            try {
                val after=engine.tool("text_paragraphs",mapOf("doc" to closed,"page" to 1)).getJSONArray("paragraphs")
                assertTrue((0 until after.length()).map {after.getJSONObject(it)}.any {it.getString("text")=="Close Arial" && it.getString("font")=="Arial-BoldMT"})
            } finally {engine.command {close(closed,true)}}
        } finally {withContext(Dispatchers.Main) {store.clear()}}
    }
    @Test fun unavailableSourceFaceKeepsDraftAndPreventsPrematureClose()=runBlocking {
        val file=File(cache,"missing-font.pdf");val pdf=PdfDocument()
        pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(612,792,1).create()));file.outputStream().use {pdf.writeTo(it)};pdf.close()
        val store=ViewModelStore()
        val vm=withContext(Dispatchers.Main) {AppViewModel(context.applicationContext as PdfcraftApplication,SavedStateHandle()).also {store.put("vm",it)}}
        try {
            withTimeout(60000) {while(withContext(Dispatchers.Main) {vm.engine==null}) delay(50)}
            withContext(Dispatchers.Main) {vm.open(Uri.fromFile(file))}
            withTimeout(30000) {while(withContext(Dispatchers.Main) {vm.document==null}) delay(50)}
            val doc=requireNotNull(vm.document)
            val source=Paragraph(0,1,"Source",floatArrayOf(10f,10f,100f,30f),"Unavailable-Exact-Face",18f,false,false,"#123456")
            withContext(Dispatchers.Main) {
                vm.edit.postAddText(TextPrompt(doc,PagePoint(0,60f,200f),source),"Keep this draft")
                vm.closeDocument()
            }
            withTimeout(30000) {while(withContext(Dispatchers.Main) {vm.edit.addingText || vm.edit.addTextPrompt==null}) delay(50)}
            assertSame(doc,vm.document);assertEquals("Keep this draft",vm.edit.addTextPrompt?.draft)
            assertEquals(0,requireNotNull(vm.engine).tool("text_paragraphs",mapOf("doc" to doc.id,"page" to 1)).getJSONArray("paragraphs").length())
            withContext(Dispatchers.Main) {vm.edit.cancelAddedText(requireNotNull(vm.edit.addTextPrompt));vm.closeDocument()}
            withTimeout(30000) {while(withContext(Dispatchers.Main) {vm.document!=null}) delay(50)}
        } finally {withContext(Dispatchers.Main) {store.clear()}}
    }

}
