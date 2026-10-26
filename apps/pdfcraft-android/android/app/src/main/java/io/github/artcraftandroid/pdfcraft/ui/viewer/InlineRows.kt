package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.R

import io.github.artcraftandroid.pdfcraft.text as appText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import org.json.JSONArray
import kotlin.math.roundToInt

/** Offsets are UTF-16, matching Compose selection offsets, not Rust UTF-8 bytes. */
data class InlineRun(val start: Int, val end: Int, val font: String, val size: Float,
    val bold: Boolean, val italic: Boolean, val rgb: List<Float>)
data class InlineRow(val text: String, val rect: List<Float>, val baseline: Float, val runs: List<InlineRun>)

fun readInlineRows(array: JSONArray?): List<InlineRow> = (0 until (array?.length() ?: 0)).mapNotNull { i ->
    val row = array?.optJSONObject(i) ?: return@mapNotNull null
    val rect = row.optJSONArray("rect") ?: return@mapNotNull null
    val text = row.optString("text")
    val spans = row.optJSONArray("runs")
    val runs = (0 until (spans?.length() ?: 0)).mapNotNull { k ->
        val s = spans?.optJSONObject(k) ?: return@mapNotNull null
        val color = s.optJSONArray("color")
        InlineRun(s.optInt("start"), s.optInt("end"), s.optString("font"), s.optDouble("size", 12.0).toFloat(),
            s.optBoolean("bold"), s.optBoolean("italic"), List(3) { color?.optDouble(it, 0.0)?.toFloat() ?: 0f })
            .takeIf { it.start >= 0 && it.end > it.start && it.end <= text.length }
    }
    if (rect.length() != 4 || runs.isEmpty()) null else
        InlineRow(text, List(4) { rect.optDouble(it).toFloat() }, row.optDouble("baseline").toFloat(), runs)
}.let { rows -> if (rows.size == (array?.length() ?: 0)) rows else emptyList() }

fun inlineRtl(text: String): Boolean {
    for (ch in text) when (Character.getDirectionality(ch)) {
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
    }
    return false
}

/** Surviving characters retain their style; typed text inherits the insertion-point style. */
fun remapInlineRuns(old: String, new: String, runs: List<InlineRun>): List<InlineRun> {
    if (old == new || runs.isEmpty()) return runs
    val prefix = old.zip(new).takeWhile { it.first == it.second }.size
    val suffix = old.reversed().zip(new.reversed()).take((old.length.coerceAtMost(new.length) - prefix).coerceAtLeast(0))
        .takeWhile { it.first == it.second }.size
    val insertion = runs.lastOrNull { it.start < prefix && it.end >= prefix }
        ?: runs.firstOrNull { prefix >= it.start && prefix < it.end } ?: runs.first()
    val styles = new.indices.map { index ->
        val source = when {
            index < prefix -> index
            index >= new.length - suffix -> old.length - (new.length - index)
            else -> -1
        }
        runs.firstOrNull { source in it.start until it.end } ?: insertion
    }
    val result = mutableListOf<InlineRun>()
    var at = 0
    while (at < new.length) {
        val style = styles[at].copy(start = 0, end = 0)
        var end = at + 1
        while (end < new.length && styles[end].copy(start = 0, end = 0) == style) end++
        result += style.copy(start = at, end = end)
        at = end
    }
    return result
}

private fun inlineFamily(name: String): FontFamily = when {
    name.contains("Mono", true) || name.contains("Courier", true) -> FontFamily.Monospace
    name.contains("Serif", true) || name.contains("Times", true) || name.contains("Naskh", true) -> FontFamily.Serif
    else -> FontFamily.SansSerif
}

/** Fixed source baselines keep unrelated rows still while one row is typed in. */
@Composable
fun BoxScope.PositionedInlineEditor(vm: AppViewModel, doc: OpenDocument, para: Paragraph) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val ppp = doc.viewer.pixelsPerPoint(para.page)
    val values = remember(para) { mutableStateListOf(*para.rows.map { TextFieldValue(it.text) }.toTypedArray()) }
    val styles = remember(para) { mutableStateListOf(*para.rows.map { it.runs }.toTypedArray()) }
    var paper by remember(para) { mutableStateOf<Color?>(null) }
    LaunchedEffect(para) { paper = vm.paperColor(doc, para) }
    var focusedRow by remember(para) { mutableIntStateOf(-1) }
    val ime = WindowInsets.ime.getBottom(density)
    LaunchedEffect(ime, focusedRow) {
        if (ime > 0 && focusedRow >= 0) {
            val row = para.rows[focusedRow]
            val bottom = doc.viewer.pageToScreen(para.page, row.rect[0], row.rect[3]).y
            val clear = doc.viewer.viewH - ime - with(density) { 96.dp.toPx() }
            if (bottom > clear) doc.viewer.setScroll(doc.viewer.scrollX, doc.viewer.scrollY + bottom - clear)
        }
    }
    // Populate immediately; do not depend on an asynchronous effect at commit time.
    for ((index, row) in para.rows.withIndex()) {
        var focused by remember(para, index) { mutableStateOf(false) }
        var firstBaseline by remember(para, index) { mutableFloatStateOf(row.runs.maxOf { it.size } * ppp * .8f) }
        val point = doc.viewer.pageToScreen(para.page, row.rect[0], row.baseline)
        val width = ((row.rect[2] - row.rect[0]) * ppp).coerceAtLeast(24f)
        val current = values[index]
        val visible = focused || current.text != row.text
        val first = styles[index].firstOrNull() ?: row.runs.first()
        fun annotated(text: String, scale: Float) = buildAnnotatedString {
            append(text)
            for (run in styles[index]) if (run.start < run.end && run.end <= length) {
                addStyle(SpanStyle(color = if (visible) Color(run.rgb[0], run.rgb[1], run.rgb[2]) else Color.Transparent,
                    fontSize = with(density) { (run.size * ppp * scale).toSp() }, fontFamily = inlineFamily(run.font),
                    fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal), run.start, run.end)
            }
        }
        val naturalWidth = measurer.measure(annotated(current.text, 1f), softWrap = false).size.width
        val scale = ((width - 2f).coerceAtLeast(1f) / naturalWidth.coerceAtLeast(1)).coerceAtMost(1f)
        val transform = VisualTransformation { input ->
            TransformedText(annotated(input.text, scale), OffsetMapping.Identity)
        }
        BasicTextField(
            value = current,
            onValueChange = { next ->
                styles[index] = remapInlineRuns(current.text, next.text, styles[index])
                values[index] = next
                vm.editDraft = values.joinToString("\n") { it.text }
            },
            singleLine = true,
            visualTransformation = transform,
            textStyle = TextStyle(fontSize = with(density) { (first.size * ppp * scale).toSp() },
                fontFamily = inlineFamily(first.font), color = if (visible) Color(first.rgb[0], first.rgb[1], first.rgb[2]) else Color.Transparent,
                textAlign = if (inlineRtl(current.text)) TextAlign.Right else TextAlign.Left,
                textDirection = TextDirection.Content,
                platformStyle = PlatformTextStyle(includeFontPadding = false)),
            cursorBrush = SolidColor(Color(0xFF448AFF)),
            onTextLayout = { firstBaseline = it.firstBaseline },
            modifier = Modifier.align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(point.x.roundToInt(), (point.y - firstBaseline).roundToInt()) }
                .width(with(density) { width.toDp() })
                .background(if (visible) paper ?: Color.White else Color.Transparent)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) focusedRow = index }
                .semantics { contentDescription = appText(R.string.edit_paragraph_row, index + 1) },
        )
    }
}
