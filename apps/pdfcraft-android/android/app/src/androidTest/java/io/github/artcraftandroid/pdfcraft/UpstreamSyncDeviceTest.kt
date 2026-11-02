package io.github.artcraftandroid.pdfcraft

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.artcraftandroid.pdfcraft.ui.*
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpstreamSyncDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun model(store:ViewModelStore,saved:SavedStateHandle=SavedStateHandle()):AppViewModel {
        val vm=withContext(Dispatchers.Main) {AppViewModel(context.applicationContext as PdfcraftApplication,saved).also {store.put("vm",it)}}
        withTimeout(60000) {while(withContext(Dispatchers.Main) {vm.engine==null}) delay(50)}
        return vm
    }
    private fun photo(dir:File):File {
        val bitmap=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText("Recovery",20f,60f,Paint().apply {color=Color.BLACK;textSize=24f})
        return File(dir,"photo.png").also {f ->f.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test fun pendingRetakeSurvivesNewViewModelAndKeepsAllReviewSettings()=runBlocking {
        val dir=File(context.getExternalFilesDir(null),"upstream-recovery/${System.nanoTime()}").apply {mkdirs()}
        val input=photo(dir);val firstStore=ViewModelStore();val vm=model(firstStore)
        withContext(Dispatchers.Main) {vm.scan.importPhotos(listOf(Uri.fromFile(input),Uri.fromFile(input)))}
        assertTrue("Initial import failed: ${vm.messages.tryReceive().getOrNull()}",withTimeoutOrNull(10000) {while(withContext(Dispatchers.Main) {vm.scan.scanPages.size!=2 || vm.scan.receiving || vm.busy!=null}) delay(100);true} ?: false)
        val crop=floatArrayOf(.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f)
        val encoded=withContext(Dispatchers.Main) {
            vm.scan.update(0) {it.copy(corners=crop,filter=ScanFilter.Grayscale,strength=73,rotation=270)}
            vm.scan.scanName="استعادة المسح";vm.scan.makeSearchable=false
            vm.scan.retakeScan(1)
            requireNotNull(vm.scan.snapshot()).encode()
        }
        File(dir,"state.txt").writeText(encoded)
        val state=ScanSession.decode(encoded)!!;val pending=File(state.shot!!);input.copyTo(pending,true)
        withContext(Dispatchers.Main) {firstStore.clear()}
        val restoredStore=ViewModelStore()
        val restored=withContext(Dispatchers.Main) {
            AppViewModel(context.applicationContext as PdfcraftApplication,SavedStateHandle(mapOf("scan" to Bundle().apply {putString("scan",encoded)}))).also {
                restoredStore.put("vm",it);it.scan.onScanPhoto(true)
            }
        }
        try {
            assertTrue("Restoration did not complete",withTimeoutOrNull(10000) {while(withContext(Dispatchers.Main) {restored.scan.scanPages.size!=2 || restored.scan.receiving || restored.scan.scanPages.last().file.canonicalFile!=pending.canonicalFile}) delay(100);true} ?: false)
            val page=restored.scan.scanPages.first()
            assertArrayEquals(crop,page.corners,0f);assertEquals(ScanFilter.Grayscale,page.filter);assertEquals(73,page.strength);assertEquals(270,page.rotation)
            assertEquals("استعادة المسح",restored.scan.scanName);assertFalse(restored.scan.makeSearchable)
            assertTrue(input.exists())
        } finally {withContext(Dispatchers.Main) {restored.scan.discardScan();restoredStore.clear()}}
    }
    @Test fun recoverySkipsForeignPathsWithoutDeletingThem()=runBlocking {
        val dir=File(context.getExternalFilesDir(null),"upstream-foreign/${System.nanoTime()}").apply {mkdirs()};val source=photo(dir)
        val session=ScanSession(null,null,false,"safe",listOf(ScanSession.Page(source.path,ScanPage.WHOLE,null)))
        val store=ViewModelStore();val vm=model(store,SavedStateHandle(mapOf("scan" to Bundle().apply {putString("scan",session.encode())})))
        try {
            delay(500)
            assertTrue(vm.scan.scanPages.isEmpty())
            withContext(Dispatchers.Main) {vm.scan.discardScan()}
            assertTrue(source.exists())
        } finally {withContext(Dispatchers.Main) {store.clear()}}
    }
    @Test fun formCalculationsButtonScriptsAndAlertsWorkOnAndroid()=runBlocking {
        val dir=File(context.getExternalFilesDir(null),"upstream-js/${System.nanoTime()}").apply {mkdirs()}
        val blank=File(dir,"form.pdf");val pdf=PdfDocument()
        pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(612,792,1).create()));blank.outputStream().use {pdf.writeTo(it)};pdf.close()
        val store=ViewModelStore();val vm=model(store);val engine=requireNotNull(vm.engine)
        val id=engine.command {open(blank.path,null).id}
        try {
            listOf("a","b","total","double").forEachIndexed {index,name ->
                engine.tool("form_add_field",mapOf("doc" to id,"page" to 1,"type" to if(name=="double") "button" else "text",
                    "rect" to listOf(60,60+index*60,260,90+index*60),"name" to name))
            }
            engine.tool("form_set_script",mapOf("doc" to id,"field" to "total","event" to "calculate","script" to "event.value=Number(this.getField('a').value)+Number(this.getField('b').value);if(event.value>9)app.alert('Big');"))
            engine.tool("form_set_script",mapOf("doc" to id,"field" to "double","event" to "mouse_up","script" to "var f=this.getField('a');f.value=Number(f.value)*2;app.alert('a is now '+f.value);"))
            engine.tool("doc_save",mapOf("doc" to id,"path" to blank.path,"full" to true))
        } finally {engine.command {close(id,true)}}
        withContext(Dispatchers.Main) {vm.open(Uri.fromFile(blank))}
        withTimeout(60000) {while(withContext(Dispatchers.Main) {vm.document==null}) delay(50)}
        val doc=requireNotNull(vm.document)
        suspend fun value(name:String)=engine.command {widgets(doc.id).first {it.field==name}.value}
        try {
            withContext(Dispatchers.Main) {vm.forms.fillField(doc,"a","2")}
            assertTrue("a fill: value=${value("a")} alerts=${vm.forms.scriptAlerts}",withTimeoutOrNull(10000) {while(value("a")!="2") delay(50);true} ?: false)
            withContext(Dispatchers.Main) {vm.forms.fillField(doc,"b","3")}
            assertTrue("calculation: a=${value("a")} b=${value("b")} total=${value("total")} alerts=${vm.forms.scriptAlerts}",withTimeoutOrNull(10000) {while(value("total")!="5") delay(50);true} ?: false)
            val button=engine.command {widgets(doc.id).first {it.field=="double"}}
            // Verified through the normal viewer button path.
            withContext(Dispatchers.Main) {assertTrue(vm.forms.tapField(doc,PagePoint(0,(button.rect[0]+button.rect[2])/2,(button.rect[1]+button.rect[3])/2)))}
            val pressed=withTimeoutOrNull(10000) {while(withContext(Dispatchers.Main) {!vm.forms.scriptAlerts.contains("a is now 4")}) delay(50);true} ?: false
            assertTrue("button: a=${value("a")} total=${value("total")} alerts=${withContext(Dispatchers.Main){vm.forms.scriptAlerts.toList()}} message=${vm.messages.tryReceive().getOrNull()}",pressed)
            assertEquals("7",value("total"))
            withContext(Dispatchers.Main) {vm.forms.fillField(doc,"b","8")}
            withTimeout(10000) {while(withContext(Dispatchers.Main) {!vm.forms.scriptAlerts.contains("Big")}) delay(50)}
            assertEquals("12",value("total"))
            val saved=File(dir,"calculated.pdf");engine.tool("doc_save",mapOf("doc" to doc.id,"path" to saved.path,"full" to true))
            val reopened=engine.command {open(saved.path,null).id}
            try {assertEquals("12",engine.command {widgets(reopened).first {it.field=="total"}.value})} finally {engine.command {close(reopened,true)}}
        } finally {withContext(Dispatchers.Main) {store.clear()};engine.command {close(doc.id,true)}}
    }
}
