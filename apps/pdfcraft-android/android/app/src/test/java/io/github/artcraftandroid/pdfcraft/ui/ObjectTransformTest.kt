package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.artcraftandroid.pdfcraft.ui.viewer.*
import org.junit.Assert.*
import org.junit.Test

class ObjectTransformTest {
    private val page = Rect(0f, 0f, 612f, 792f)
    private val signature = Rect(100f, 200f, 280f, 254f)
    @Test fun moveStaysOnTheSamePageAndKeepsSize() {
        assertEquals(Rect(432f, 738f, 612f, 792f), movedObject(signature, Offset(1000f, 1000f), page))
        assertEquals(Rect(0f, 0f, 180f, 54f), movedObject(signature, Offset(-1000f, -1000f), page))
    }
    @Test fun resizeKeepsSignatureProportionsAndAnchor() {
        val r = scaledObject(signature, Offset(180f, 54f), page, 0.1f)
        assertEquals(Rect(100f, 200f, 460f, 308f), r)
        assertEquals(signature.width / signature.height, r.width / r.height, 0.001f)
    }
    @Test fun resizeCannotCrossPageEdgeOrBecomeNegative() {
        val big = scaledObject(signature, Offset(5000f, 5000f), page, 0.1f)
        assertTrue(big.right <= page.right && big.bottom <= page.bottom)
        val small = scaledObject(signature, Offset(-5000f, -5000f), page, 0.1f)
        assertEquals(signature.width * 0.1f, small.width, 0.001f)
        assertTrue(small.height > 0f)
    }
    @Test fun directHitWinsOverNearbyAndTopmostWinsTies() {
        val boxes = listOf(Rect(10f, 10f, 60f, 25f), Rect(10f, 35f, 60f, 50f))
        assertEquals(0, nearestObject(boxes, Offset(30f, 24f), 20f))
        assertEquals(1, nearestObject(boxes, Offset(30f, 30f), 20f))
        assertEquals(2, nearestObject(boxes + boxes[1], Offset(30f, 37f), 20f))
        assertNull(nearestObject(boxes, Offset(500f, 500f), 20f))
    }
    @Test fun nearbyThinTextIsSelectableWithoutClaimingDistantBlankSpace() {
        val boxes = listOf(Rect(10f, 10f, 90f, 14f))
        assertEquals(0, nearestObject(boxes, Offset(50f, 28f), 20f))
        assertNull(nearestObject(boxes, Offset(50f, 40f), 20f))
    }
    @Test fun mappingTouchAtDifferentZoomsGivesSamePageEdit() {
        for (pixelsPerPoint in listOf(0.5f, 1f, 4f)) {
            val pixels = Offset(25f, 40f) * pixelsPerPoint
            assertEquals(Rect(125f, 240f, 305f, 294f), movedObject(signature, pixels / pixelsPerPoint, page))
        }
    }
    @Test fun verticalAndHorizontalDragsHaveEqualEffectOnThinText() {
        val text = Rect(100f, 200f, 220f, 214f)
        assertEquals(scaledObject(text, Offset(60f, 0f), page, 0.1f), scaledObject(text, Offset(0f, 60f), page, 0.1f))
        assertEquals(scaledObject(text, Offset(-60f, 0f), page, 0.1f), scaledObject(text, Offset(0f, -60f), page, 0.1f))
        assertEquals(180f, scaledObject(text, Offset(0f, 60f), page, 0.1f).width, 0.001f)
    }

    @Test fun opposingDiagonalMotionIsContinuousAndReturnsToOrigin() {
        val r = Rect(100f, 100f, 200f, 120f)
        val a = scaledObject(r, Offset(20f, -19f), page, 0.1f)
        val b = scaledObject(r, Offset(20f, -21f), page, 0.1f)
        assertTrue(kotlin.math.abs(a.width - b.width) < 3f)
        assertEquals(r, scaledObject(r, Offset.Zero, page, 0.1f))
        assertEquals(r, scaledObject(r, Offset(20f, -20f), page, 0.1f))
    }
}
