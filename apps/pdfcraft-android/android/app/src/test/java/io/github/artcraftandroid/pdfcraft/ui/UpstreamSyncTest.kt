package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.*
import org.junit.Assert.*
import org.junit.Test

class UpstreamSyncTest {
    private val crop=floatArrayOf(.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f)
    @Test fun scanStateRetainsAllCustomReviewSettingsAndPendingCamera() {
        val session=ScanSession("/cache/camera/next.jpg",1,false,"ملف عربي",listOf(
            ScanSession.Page("/cache/camera/1.jpg",crop,crop,ScanFilter.Grayscale,74,270),
            ScanSession.Page("/cache/camera/2.jpg",ScanPage.WHOLE,null,ScanFilter.Original,0,0)),false,true)
        val restored=requireNotNull(ScanSession.decode(session.encode()))
        assertEquals(session.shot,restored.shot);assertEquals(1,restored.retake);assertEquals("ملف عربي",restored.name)
        assertEquals(ScanFilter.Grayscale,restored.pages[0].filter);assertEquals(74,restored.pages[0].strength);assertEquals(270,restored.pages[0].rotation)
        assertArrayEquals(crop,restored.pages[0].corners,0f);assertFalse(restored.searchable);assertTrue(restored.forInsert)
        assertNull(restored.pages[1].found)
    }
    @Test fun scanStateRejectsCorruptionAndSupportsOldStates() {
        assertNull(ScanSession.decode("x".repeat(130000)));assertNull(ScanSession.decode("not a scan"))
        val old=ScanSession.decode("pdfcraft-scan 1\npage\t/cache/camera/1.jpg\t0,0,1,0,1,1,0,1\t\n")!!
        assertEquals(ScanFilter.AutoColor,old.pages.single().filter)
        val state=ScanSession(null,null,false,"first\nsecond\tthird",emptyList())
        assertEquals("first second third",ScanSession.decode(state.encode())!!.name)
        assertTrue(ScanSession.decode("pdfcraft-scan 2\npage\t/a\tNaN,0,1,0,1,1,0,1\t\tOriginal\t0\t0")!!.pages.isEmpty())
    }
    @Test fun zoomStepsClampAndPageCoordinatesStayOnTheAnchoredPage() {
        val state=ViewerState(listOf(PageSize(600f,800f),PageSize(600f,800f)))
        state.setViewport(900f,1200f);state.zoomStep(1,Offset.Zero)
        assertEquals(1.25f,state.zoom,.001f)
        state.zoomStep(1000,Offset.Zero);assertFalse(state.canZoomIn)
        state.zoomStep(-1000,Offset.Zero);assertFalse(state.canZoomOut)
        val point=state.screenToPageOf(0,Offset(-10000f,100000f))!!
        assertEquals(0f,point.x,.001f);assertEquals(800f,point.y,.001f)
        assertNull(state.screenToPageOf(999,Offset.Zero))
    }
}
