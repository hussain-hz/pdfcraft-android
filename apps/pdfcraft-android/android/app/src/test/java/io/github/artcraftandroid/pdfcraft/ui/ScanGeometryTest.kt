package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.*
import org.junit.Test

class ScanGeometryTest {
    @Test fun acceptsWholePhotoAndPerspectiveCrop() {
        assertTrue(validScanCorners(ScanPage.WHOLE))
        assertTrue(validScanCorners(floatArrayOf(.2f,.1f,.85f,.2f,.9f,.9f,.1f,.8f)))
    }
    @Test fun refusesCrossedTinyNonfiniteOrOutsideCrops() {
        assertFalse(validScanCorners(floatArrayOf(0f,0f,1f,1f,1f,0f,0f,1f)))
        assertFalse(validScanCorners(floatArrayOf(0f,0f,.001f,0f,.001f,.001f,0f,.001f)))
        assertFalse(validScanCorners(floatArrayOf(0f,0f,Float.NaN,0f,1f,1f,0f,1f)))
        assertFalse(validScanCorners(floatArrayOf(-1f,0f,1f,0f,1f,1f,0f,1f)))
        assertFalse(validScanCorners(floatArrayOf(0f,1f)))
    }
}
