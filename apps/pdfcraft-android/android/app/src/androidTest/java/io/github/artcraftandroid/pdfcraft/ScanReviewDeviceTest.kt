package io.github.artcraftandroid.pdfcraft

import android.graphics.*
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.artcraftandroid.pdfcraft.engine.*
import io.github.artcraftandroid.pdfcraft.ui.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanReviewDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun photo(dir:File,name:String):File {
        val bitmap=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(Color.DKGRAY)
        val paint=Paint().apply {color=Color.rgb(220,210,180)}
        canvas.drawRect(60f,90f,540f,810f,paint)
        paint.color=Color.rgb(15,70,130); paint.textSize=32f
        canvas.drawText("SCAN REVIEW",100f,220f,paint)
        for(i in 0..8) canvas.drawRect(100f,280f+i*40,450f,290f+i*40,paint)
        return File(dir,name).also { file -> file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}; bitmap.recycle() }
    }
    private suspend fun model(store:ViewModelStore):AppViewModel {
        val vm=withContext(Dispatchers.Main) {AppViewModel(context.applicationContext as PdfcraftApplication, androidx.lifecycle.SavedStateHandle()).also {store.put("scan",it)}}
        withTimeout(60000) {while(withContext(Dispatchers.Main) {vm.engine==null}) delay(50)}
        return vm
    }
    @Test fun previewAndSavedPageShareCropRotationAndGrayscale()=runBlocking {
        val dir=File(context.getExternalFilesDir(null),"scan-review-qa/${System.nanoTime()}").apply {mkdirs()}
        val file=photo(dir,"source.png"); val before=file.readBytes()
        val bitmap=BitmapFactory.decodeFile(file.absolutePath)
        val page=ScanPage(file,bitmap.asImageBitmap(),floatArrayOf(.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f),filter=ScanFilter.Grayscale,strength=55,rotation=90)
        val store=ViewModelStore(); val vm=model(store); val engine=requireNotNull(vm.engine)
        try {
            val preview=vm.scan.preview(page)
            assertTrue(preview.width>preview.height)
            val id=engine.tool("doc_create",mapOf("from" to "images","paths" to listOf(file.absolutePath))).getLong("doc")
            try {
                enhanceScanPage(engine,id,0,page)
                val output=File(dir,"processed.pdf")
                engine.tool("doc_save",mapOf("doc" to id,"path" to output.absolutePath,"full" to true))
                val reopened=engine.command {open(output.absolutePath,null).id}
                try {
                    val rendered=engine.command {renderPage(reopened,0u,1f,null)}.toBitmap()
                    assertTrue(rendered.width>rendered.height)
                    assertEquals(preview.width.toDouble()/preview.height,rendered.width.toDouble()/rendered.height,.03)
                    val color=rendered.getPixel(rendered.width/2,rendered.height/2)
                    assertTrue(kotlin.math.abs(Color.red(color)-Color.blue(color))<=3)
                    assertArrayEquals(before,file.readBytes())
                    rendered.recycle()
                } finally {engine.command {close(reopened,true)}}
            } finally {engine.tool("doc_close",mapOf("doc" to id,"discard_changes" to true))}
        } finally {withContext(Dispatchers.Main) {store.clear()}}
    }
    @Test fun batchImportReorderAndApplyAllKeepEachPhotoAndOwnEdges()=runBlocking {
        val dir=File(context.getExternalFilesDir(null),"scan-batch-qa/${System.nanoTime()}").apply {mkdirs()}
        val first=photo(dir,"first.png"); val second=photo(dir,"second.png")
        val store=ViewModelStore(); val vm=model(store)
        try {
            withContext(Dispatchers.Main) {vm.scan.importPhotos(listOf(Uri.fromFile(first),Uri.fromFile(second)))}
            withTimeout(60000) {while(withContext(Dispatchers.Main) {vm.scan.scanPages.size!=2 || vm.scan.receiving || vm.busy!=null}) delay(100)}
            val source=vm.scan.scanPages[0].file; val other=vm.scan.scanPages[1].file
            val ownEdges=floatArrayOf(.2f,.1f,.8f,.1f,.9f,.9f,.1f,.9f)
            withContext(Dispatchers.Main) {
                vm.scan.update(0) {it.copy(filter=ScanFilter.LightText,strength=72,rotation=90)}
                vm.scan.update(1) {it.copy(found=ownEdges)}
                vm.scan.applyToAll(0,true,true,true)
                assertEquals(ScanFilter.LightText,vm.scan.scanPages[1].filter)
                assertEquals(72,vm.scan.scanPages[1].strength); assertEquals(90,vm.scan.scanPages[1].rotation)
                assertArrayEquals(ownEdges,vm.scan.scanPages[1].corners,0f)
                assertEquals(1,vm.scan.move(0,1)); assertEquals(source,vm.scan.scanPages[1].file)
                vm.scan.delete(0); assertFalse(other.exists())
                assertTrue(first.exists() && second.exists())
                vm.scan.discardScan()
                assertFalse(source.exists())
            }
        } finally {withContext(Dispatchers.Main) {store.clear()}}
    }
    @Test fun invalidCropSaveKeepsPhotosAndReviewSettings()=runBlocking {
        val dir=File(context.getExternalFilesDir(null),"scan-failure-qa/${System.nanoTime()}").apply {mkdirs()}
        val file=photo(dir,"keep.png"); val store=ViewModelStore(); val vm=model(store)
        try {
            withContext(Dispatchers.Main) {
                vm.scan.scanPages.add(ScanPage(file,BitmapFactory.decodeFile(file.absolutePath).asImageBitmap(),corners=FloatArray(8)))
                vm.scan.scanName="Failed scan keeps original"
                vm.scan.makeSearchable=false
                vm.scan.saveScan()
            }
            withTimeout(30000) {vm.messages.receive()}
            assertTrue(file.exists()); assertEquals(1,vm.scan.scanPages.size)
            assertEquals("Failed scan keeps original",vm.scan.scanName)
        } finally {withContext(Dispatchers.Main) {store.clear()}}
    }
    @Test fun mirroredExifIsAppliedToCropThumbnail() {
        val dir=File(context.getExternalFilesDir(null),"scan-exif-qa/${System.nanoTime()}").apply {mkdirs()}
        val file=File(dir,"mirror.jpg")
        val bitmap=Bitmap.createBitmap(2,3,Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0,0,Color.RED); bitmap.setPixel(1,0,Color.BLUE)
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)}
        ExifInterface(file).apply {setAttribute(ExifInterface.TAG_ORIENTATION,"2");saveAttributes()}
        val oriented=orientScanThumbnail(file,bitmap)
        assertEquals(Color.BLUE,oriented.getPixel(0,0));assertEquals(Color.RED,oriented.getPixel(1,0))
        oriented.recycle()
    }
}
