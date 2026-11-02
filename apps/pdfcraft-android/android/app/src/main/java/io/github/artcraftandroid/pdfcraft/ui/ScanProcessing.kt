package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import kotlin.math.abs

/** Normalized crop corners must form a useful convex quadrilateral within the photo. */
internal fun validScanCorners(q: FloatArray): Boolean {
    if (q.size != 8 || q.any { !it.isFinite() || it !in 0f..1f }) return false
    var sign = 0; var area = 0f
    for (i in 0..3) {
        val j = (i + 1) % 4; val k = (i + 2) % 4
        val cross = (q[j*2]-q[i*2])*(q[k*2+1]-q[j*2+1]) - (q[j*2+1]-q[i*2+1])*(q[k*2]-q[j*2])
        val next = if(cross > .00001f) 1 else if(cross < -.00001f) -1 else return false
        if(sign != 0 && sign != next) return false
        sign = next
        area += q[i*2]*q[j*2+1] - q[j*2]*q[i*2+1]
    }
    return abs(area) >= .005f
}

/** Shared by the bounded preview and the original-resolution export: same crop and look. */
internal suspend fun enhanceScanPage(engine: Engine, doc: Long, index: Int, page: ScanPage) {
    require(validScanCorners(page.corners)) { text(R.string.scan_crop_invalid) }
    require(page.strength in 0..100 && page.rotation in listOf(0,90,180,270))
    if(!page.corners.contentEquals(ScanPage.WHOLE) || page.filter != ScanFilter.Original) {
        val size = engine.tool("doc_info",mapOf("doc" to doc)).getJSONArray("pages").getJSONObject(index)
        val corners = page.corners.mapIndexed { k,v -> v * size.getDouble(if(k%2==0) "width" else "height") }
        engine.tool("page_enhance_camera",mapOf("doc" to doc,"page" to index+1,"corners" to corners,
            "mode" to page.filter.mode,"level" to if(page.filter == ScanFilter.Original) 0 else page.strength))
    }
    if(page.rotation != 0) engine.tool("page_rotate",mapOf("doc" to doc,"pages" to listOf(index+1),"degrees" to page.rotation))
}

/** Normalize every EXIF orientation for a bounded thumbnail; full export reads the untouched original. */
internal fun orientScanThumbnail(file: java.io.File, bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
    val orientation = runCatching { androidx.exifinterface.media.ExifInterface(file).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)
    val matrix = android.graphics.Matrix()
    when(orientation) {
        2 -> matrix.setScale(-1f,1f)
        3 -> matrix.setRotate(180f)
        4 -> matrix.setScale(1f,-1f)
        5 -> matrix.setValues(floatArrayOf(0f,1f,0f,1f,0f,0f,0f,0f,1f))
        6 -> matrix.setRotate(90f)
        7 -> matrix.setValues(floatArrayOf(0f,-1f,0f,-1f,0f,0f,0f,0f,1f))
        8 -> matrix.setRotate(270f)
        else -> return bitmap
    }
    return android.graphics.Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also { if(it !== bitmap) bitmap.recycle() }
}
