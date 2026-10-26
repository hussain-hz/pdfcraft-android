package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.R

import io.github.artcraftandroid.pdfcraft.text as appText

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import kotlin.math.roundToInt

/** Page-space operations: independent of zoom, density, scrolling and interface direction. */
internal fun movedObject(r: Rect, delta: Offset, page: Rect): Rect {
    val x = (r.left + delta.x).coerceIn(page.left, maxOf(page.left, page.right - r.width))
    val y = (r.top + delta.y).coerceIn(page.top, maxOf(page.top, page.bottom - r.height))
    return Rect(x, y, x + r.width, y + r.height)
}

internal fun scaledObject(r: Rect, delta: Offset, page: Rect, minFactor: Float, maxFactor: Float = Float.MAX_VALUE): Rect {
    if (r.width <= 0f || r.height <= 0f) return r
    // Equal sensitivity on both axes, including thin text: vertical drags must not disappear.
    val weight = kotlin.math.abs(delta.x) + kotlin.math.abs(delta.y)
    val dominant = if (weight == 0f) 0f else if (delta.x * delta.y >= 0f) {
        if (kotlin.math.abs(delta.x) >= kotlin.math.abs(delta.y)) delta.x else delta.y
    } else (delta.x * kotlin.math.abs(delta.x) + delta.y * kotlin.math.abs(delta.y)) / weight
    val wanted = 1f + dominant / maxOf(r.width, r.height)
    val limit = minOf(maxFactor, (page.right - r.left) / r.width, (page.bottom - r.top) / r.height).coerceAtLeast(0.01f)
    val factor = wanted.coerceIn(minOf(minFactor, limit), limit)
    return Rect(r.left, r.top, r.left + r.width * factor, r.top + r.height * factor)
}

/** Prefer a direct hit, then the closest nearby object, with the topmost winning ties. */
internal fun nearestObject(boxes: List<Rect>, point: Offset, tolerance: Float): Int? =
    boxes.indices.reversed().filter { boxes[it].width > 0f && boxes[it].height > 0f }.minByOrNull { i ->
        val r = boxes[i]
        val dx = maxOf(r.left - point.x, 0f, point.x - r.right)
        val dy = maxOf(r.top - point.y, 0f, point.y - r.bottom)
        dx * dx + dy * dy
    }?.takeIf { i ->
        val r = boxes[i]
        val dx = maxOf(r.left - point.x, 0f, point.x - r.right)
        val dy = maxOf(r.top - point.y, 0f, point.y - r.bottom)
        dx * dx + dy * dy <= tolerance * tolerance
    }

/** Finger-sized controls after placement; one completed gesture is one undoable engine edit. */
@Composable
fun BoxScope.ObjectTransformOverlay(
    doc: OpenDocument,
    identity: Any,
    page: Int,
    rect: Rect,
    minFactor: Float = 0.1f,
    maxFactor: Float = Float.MAX_VALUE,
    onMove: (Float, Float, () -> Unit) -> Unit,
    onScale: (Float, () -> Unit) -> Unit,
    onActive: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val pageSize = doc.viewer.pages.getOrNull(page) ?: return
    val bounds = Rect(0f, 0f, pageSize.width, pageSize.height)
    val ppp = doc.viewer.pixelsPerPoint(page).coerceAtLeast(0.01f)
    var draft by remember(identity, rect) { mutableStateOf(rect) }
    var active by remember(identity, rect) { mutableStateOf(false) }
    var pending by remember(identity, rect) { mutableStateOf(false) }
    var handlePoint by remember(identity, rect) { mutableStateOf<Offset?>(null) }
    val a = doc.viewer.pageToScreen(page, draft.left, draft.top)
    val b = doc.viewer.pageToScreen(page, draft.right, draft.bottom)
    val frame = Rect(a, b)
    val viewport = Rect(0f, doc.viewer.padTop, doc.viewer.viewW, doc.viewer.viewH - doc.viewer.padBottom)
    if (!frame.overlaps(viewport)) return
    val accent = Color(0xFF448AFF)
    Canvas(Modifier.fillMaxSize()) {
        if (active || pending) {
            doc.images.latestBase(page)?.let { base ->
                val sx = (rect.left * base.scale).roundToInt().coerceIn(0, base.bitmap.width - 1)
                val sy = (rect.top * base.scale).roundToInt().coerceIn(0, base.bitmap.height - 1)
                val sw = (rect.width * base.scale).roundToInt().coerceIn(1, base.bitmap.width - sx)
                val sh = (rect.height * base.scale).roundToInt().coerceIn(1, base.bitmap.height - sy)
                drawImage(base.bitmap, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
                    dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                    dstSize = IntSize(frame.width.roundToInt().coerceAtLeast(1), frame.height.roundToInt().coerceAtLeast(1)), alpha = 0.85f)
            }
        }
        drawRect(accent.copy(alpha = if (active) 0.12f else 0.04f), frame.topLeft, frame.size)
        drawRect(accent, frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
    }
    fun finish(scale: Boolean) {
        active = false
        handlePoint = null
        onActive(false)
        if (draft == rect) return
        pending = true
        val finished = { pending = false; draft = rect }
        if (scale) onScale(draft.width / rect.width, finished) else onMove(draft.left - rect.left, draft.top - rect.top, finished)
    }
    fun cancel() { draft = rect; handlePoint = null; active = false; onActive(false) }
    fun accessibleMove(dx: Float, dy: Float): Boolean {
        if (pending) return false
        draft = movedObject(rect, Offset(dx, dy), bounds); finish(false); return true
    }
    fun accessibleScale(delta: Float): Boolean {
        if (pending) return false
        draft = scaledObject(rect, Offset(maxOf(rect.width, rect.height) * delta, 0f), bounds, minFactor, maxFactor)
        finish(true); return true
    }
    // The document remains scrollable outside this touch target. A tap does not open the keyboard.
    val touch = with(density) { 48.dp.toPx() }
    val body = Rect(frame.center.x - maxOf(frame.width, touch) / 2, frame.center.y - maxOf(frame.height, touch) / 2,
        frame.center.x + maxOf(frame.width, touch) / 2, frame.center.y + maxOf(frame.height, touch) / 2).intersect(viewport)
    Box(Modifier.align(AbsoluteAlignment.TopLeft).zIndex(1f).absoluteOffset { IntOffset(body.left.roundToInt(), body.top.roundToInt()) }
        .size(with(density) { body.width.toDp() }, with(density) { body.height.toDp() })
        .semantics {
            contentDescription = appText(R.string.transform_drag_selected)
            customActions = listOf(
                CustomAccessibilityAction(appText(R.string.transform_move_right)) { accessibleMove(5f, 0f) },
                CustomAccessibilityAction(appText(R.string.transform_move_left)) { accessibleMove(-5f, 0f) },
                CustomAccessibilityAction(appText(R.string.transform_move_up)) { accessibleMove(0f, -5f) },
                CustomAccessibilityAction(appText(R.string.transform_move_down)) { accessibleMove(0f, 5f) },
                CustomAccessibilityAction(appText(R.string.transform_enlarge)) { accessibleScale(0.1f) },
                CustomAccessibilityAction(appText(R.string.transform_shrink)) { accessibleScale(-0.1f) })
        }
        .pointerInput(identity, rect, ppp, pending) {
            var total = Offset.Zero
            detectDragGestures(onDragStart = { total = Offset.Zero; if (!pending) { active = true; onActive(true) } },
                onDragEnd = { if (!pending) finish(false) }, onDragCancel = { if (!pending) cancel() }) { change, delta ->
                change.consume()
                if (!pending) { total += delta / ppp; draft = movedObject(rect, total, bounds) }
            }
        })
    // A single resize handle; movement uses the item's body, without a separate move button.
    val desired = handlePoint ?: (frame.bottomRight + Offset(touch / 2, touch / 2))
    val center = Offset(desired.x.coerceIn(touch * 0.75f, maxOf(touch * 0.75f, doc.viewer.viewW - touch * 0.75f)),
        desired.y.coerceIn(doc.viewer.padTop + touch / 2, maxOf(doc.viewer.padTop + touch / 2, doc.viewer.viewH - doc.viewer.padBottom - touch / 2)))
    val currentCenter by rememberUpdatedState(center)
    Canvas(Modifier.fillMaxSize()) { drawLine(accent, frame.bottomRight, center, 1.dp.toPx()) }
    Box(Modifier.align(AbsoluteAlignment.TopLeft).zIndex(2f).absoluteOffset { IntOffset((center.x - touch / 2).roundToInt(), (center.y - touch / 2).roundToInt()) }
        .size(48.dp)
        .semantics { contentDescription = appText(R.string.transform_resize_selected) }
        .pointerInput(identity, rect, ppp, pending) {
            var total = Offset.Zero
            var anchor = Offset.Zero
            detectDragGestures(onDragStart = {
                total = Offset.Zero; anchor = currentCenter
                if (!pending) { active = true; handlePoint = anchor; onActive(true) }
            }, onDragEnd = { if (!pending) finish(true) }, onDragCancel = { if (!pending) cancel() }) { change, delta ->
                change.consume()
                if (!pending) {
                    total += delta / ppp
                    handlePoint = anchor + total * ppp
                    draft = scaledObject(rect, total, bounds, minFactor, maxFactor)
                }
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Icon(LucideIcons.Maximize2, null, tint = accent, modifier = Modifier.size(22.dp))
        }
    }
}
