package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.text as appText

import androidx.core.graphics.toColorInt

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlin.math.roundToInt

/** A paragraph Edit PDF can change: its number on the page, text, box (points) and style. */
data class Paragraph(
    val page: Int,
    val number: Int,
    val text: String,
    val box: FloatArray,
    val font: String,
    val size: Float,
    val bold: Boolean,
    val italic: Boolean,
    val color: String?,
    val addedIndex: Int? = null,
    val placementBox: FloatArray? = null,
    val rows: List<InlineRow> = emptyList(),
)

/** A picture Edit PDF has selected: its number on the page and where it sits (points). */
data class EditImage(val page: Int, val number: Int, val rect: FloatArray, val visibleRect: FloatArray = rect, val raster: Boolean = true, val hasCrop: Boolean = false)

/** What Edit PDF is doing: picking a paragraph, or placing new text or an image. */
enum class EditMode { Select, AddText, AddImage }

/** The selection outline and its handles, as the reference draws them. */
private val SelectionBlue = Color(0xFF448AFF)

/** The text colours Edit PDF offers, in the reference's order. */
private val EditColors = listOf(
    "#ff0000" to R.string.prefs_color_red, "#ffff00" to R.string.prefs_color_yellow, "#0000ff" to R.string.prefs_color_blue,
    "#00ff00" to R.string.prefs_color_light_green, "#9900ff" to R.string.prefs_color_purple, "#00ffff" to R.string.prefs_color_cyan,
    "#009900" to R.string.prefs_color_green, "#ffffff" to R.string.prefs_color_white, "#000000" to R.string.prefs_color_black,
)

/** Which formatting panel is open over the bar. */
private enum class FormatPanel { None, Size, Color, Align, List }

/**
 * Edit PDF's bottom bar. With nothing selected: Organize pages, then Add text and Add image. With a
 * paragraph selected (or being typed in), its formatting: bold, italic, underline, size, font,
 * colour and alignment, each change applied to the page at once.
 */
@Composable
fun EditToolbar(vm: AppViewModel, doc: OpenDocument) {
    val para = vm.edit.editSelection
    if (para != null) EditFormatBar(vm, doc, para) else EditToolsBar(vm, doc)
}

@Composable
private fun EditToolsBar(vm: AppViewModel, doc: OpenDocument) {
    Row(
        // taller when a large font setting enlarges the labels, rather than cutting them off
        Modifier.fillMaxWidth().heightIn(min = BottomBarHeight).barTopLine().background(pdfColors.surface),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val textHint = stringResource(R.string.edit_tap_for_text)
        val imageHint = stringResource(R.string.edit_tap_for_image)
        EditBarItem(LucideIcons.LayoutGrid, stringResource(R.string.common_tool_organize), false, 96.dp) {
            vm.organizing = true
            doc.controller.pagesOpen = true
        }
        BarDividerLine()
        EditBarItem(LucideIcons.Type, stringResource(R.string.edit_add_text), vm.edit.editMode == EditMode.AddText, 70.dp) {
            vm.edit.editMode = if (vm.edit.editMode == EditMode.AddText) EditMode.Select else EditMode.AddText
            if (vm.edit.editMode == EditMode.AddText) vm.tell(textHint)
        }
        // as Add text: a mode, then a tap places the picture there
        EditBarItem(LucideIcons.ImagePlus, stringResource(R.string.edit_add_image), vm.edit.editMode == EditMode.AddImage, 78.dp) {
            vm.edit.editMode = if (vm.edit.editMode == EditMode.AddImage) EditMode.Select else EditMode.AddImage
            if (vm.edit.editMode == EditMode.AddImage) vm.tell(imageHint)
        }
    }
}

@Composable
private fun BarDividerLine() {
    Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(30.dp).background(pdfColors.divider))
}

@Composable
private fun EditBarItem(icon: ImageVector, label: String, selected: Boolean, width: Dp, onClick: () -> Unit) {
    val c = pdfColors
    Column(
        Modifier.width(width).heightIn(min = BottomBarHeight).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (selected) c.toolAccent else c.icon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (selected) c.toolAccent else c.label, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun EditFormatBar(vm: AppViewModel, doc: OpenDocument, para: Paragraph) {
    val c = pdfColors
    var textDialog by remember(para.page, para.number) { mutableStateOf(false) }
    if (textDialog) ParagraphTextDialog(vm, doc, para) { textDialog = false }
    var panel by remember(para.page, para.number) { mutableStateOf(FormatPanel.None) }
    fun toggle(p: FormatPanel) {
        panel = if (panel == p) FormatPanel.None else p
    }
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        when (panel) {
            FormatPanel.Size -> SizePanel(para) { vm.edit.styleParagraph(doc, para, mapOf("size" to it.toDouble())) }
            FormatPanel.Color -> ColorPanel(para.color) { vm.edit.styleParagraph(doc, para, mapOf("color" to it)) }
            FormatPanel.Align -> Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.feature_selected_lines), color = c.text, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Checkbox(checked = vm.edit.alignOnlySelected,
                        enabled = vm.edit.editing != null && vm.edit.selectedRows != null,
                        onCheckedChange = { vm.edit.alignOnlySelected = it })
                }
                AlignPanel(vm.edit.editAlign[para.page to para.number] ?: "left") { a ->
                    vm.edit.editAlign[para.page to para.number] = a
                    vm.edit.styleParagraph(doc, para, mapOf("align" to a))
                }
            }
            FormatPanel.List -> ListPanel(ListStyle.of(vm.edit.editDraft?.takeIf { vm.edit.editing != null } ?: para.text)) { vm.edit.listParagraph(doc, para, it) }
            FormatPanel.None -> Unit
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(BottomBarHeight).barTopLine(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatButton(LucideIcons.Type, stringResource(R.string.edit_paragraph_text), true, tintSelected = false) { textDialog = true }
            val underline = vm.edit.editUnderline[para.page to para.number] == true
            // the reference draws these as serif T's: heavy, slanted, underlined, and a small t beside a big one
            LetterButton("T", stringResource(R.string.edit_bold), para.bold, weight = FontWeight.Black) { vm.edit.styleParagraph(doc, para, mapOf("bold" to !para.bold)) }
            LetterButton("T", stringResource(R.string.edit_italic), para.italic, italic = true) { vm.edit.styleParagraph(doc, para, mapOf("italic" to !para.italic)) }
            LetterButton("T", stringResource(R.string.edit_underline), underline, underline = true) {
                vm.edit.editUnderline[para.page to para.number] = !underline
                vm.edit.styleParagraph(doc, para, mapOf("underline" to !underline))
            }
            LetterButton("tT", stringResource(R.string.edit_text_size), panel == FormatPanel.Size, small = true) { toggle(FormatPanel.Size) }
            val fontLabel = stringResource(R.string.edit_font)
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) { panel = FormatPanel.None; vm.fonts.show(doc, para) }.semantics { contentDescription = fontLabel },
                contentAlignment = Alignment.Center,
            ) { Text("Ag", color = if (vm.fonts.target != null) c.toolAccent else c.icon, fontSize = 20.sp, fontFamily = FontFamily.Serif) }
            val colourLabel = stringResource(R.string.edit_colour)
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) { toggle(FormatPanel.Color) }.semantics { contentDescription = colourLabel },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(24.dp).background(parseHex(para.color ?: "#000000"), CircleShape)
                        .then(if (panel == FormatPanel.Color) Modifier.drawBehind { drawCircle(c.toolAccent, size.minDimension / 2 + 3.dp.toPx(), style = Stroke(2.dp.toPx())) } else Modifier),
                )
            }
            FormatButton(alignIcon(vm.edit.editAlign[para.page to para.number] ?: "left"), stringResource(R.string.edit_align_text), true, tintSelected = panel == FormatPanel.Align || true) { toggle(FormatPanel.Align) }
            // bulleted and numbered lists, as the reference's phone toolbar offers
            FormatButton(LucideIcons.List, stringResource(R.string.edit_list), true, tintSelected = true) { toggle(FormatPanel.List) }
        }
    }
}

@Composable
private fun LetterButton(
    letters: String,
    description: String,
    selected: Boolean,
    weight: FontWeight = FontWeight.SemiBold,
    italic: Boolean = false,
    underline: Boolean = false,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val c = pdfColors
    Box(
        Modifier.size(44.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letters, color = if (selected) c.toolAccent else c.icon, fontSize = if (small) 19.sp else 24.sp, fontFamily = FontFamily.Serif, fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }
}

@Composable
private fun FormatButton(icon: ImageVector, description: String, selected: Boolean, tintSelected: Boolean = selected, onClick: () -> Unit) {
    val c = pdfColors
    Box(
        Modifier.size(44.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (tintSelected) c.toolAccent else c.icon, modifier = Modifier.size(26.dp)) }
}

/** Text size: "Text size: N pt" over a stepped slider between a small and a large A. */
@Composable
private fun SizePanel(para: Paragraph, onSize: (Float) -> Unit) {
    val c = pdfColors
    var size by remember(para.page, para.number, para.size) { mutableFloatStateOf(para.size.coerceIn(6f, 66f)) }
    Column(Modifier.fillMaxWidth().barTopLine().padding(top = 10.dp, bottom = 6.dp)) {
        Text(stringResource(R.string.edit_text_size_pt, size.roundToInt()), color = c.label, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("A", color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp))
            Slider(
                value = size, onValueChange = { size = ((it / 2f).roundToInt() * 2f).coerceIn(6f, 66f) }, onValueChangeFinished = { onSize(size) },
                valueRange = 6f..66f, steps = 29,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = c.textSecondary, inactiveTrackColor = c.textSecondary, activeTickColor = c.textSecondary, inactiveTickColor = c.textSecondary),
                modifier = Modifier.weight(1f),
            )
            Text("A", color = c.textSecondary, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
}

/** The faces the engine can set text in, each named in its own face; the paragraph's own comes first. */
@Composable
private fun FontPanel(current: String?, onFont: (String) -> Unit) {
    val c = pdfColors
    val faces = listOf(null to R.string.edit_font_original, "helvetica" to R.string.edit_font_sans, "times" to R.string.edit_font_serif, "courier" to R.string.edit_font_mono)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).barTopLine().padding(horizontal = 24.dp, vertical = 4.dp)) {
        items(faces.size) { i ->
            val (key, name) = faces[i]
            val chosen = key == current
            Row(
                Modifier.fillMaxWidth().height(42.dp).clickable(enabled = key != null) { if (key != null) onFont(key) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(name), color = if (key == null) c.textSecondary else c.label, fontSize = 17.sp, modifier = Modifier.weight(1f),
                    fontFamily = when (key) { "times" -> FontFamily.Serif; "courier" -> FontFamily.Monospace; else -> FontFamily.SansSerif },
                )
                if (chosen) Icon(LucideIcons.Check, null, tint = c.textSecondary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Nine colour swatches across the screen, the current one ticked. */
@Composable
private fun ColorPanel(current: String?, onColor: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().barTopLine().padding(horizontal = 12.dp, vertical = 12.dp)) {
        for ((hex, name) in EditColors) {
            val col = parseHex(hex)
            val label = stringResource(name)
            Box(
                Modifier.weight(1f).height(40.dp).background(col).clickable { onColor(hex) }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                if (current.equals(hex, ignoreCase = true)) Icon(LucideIcons.Check, null, tint = if (hex == "#ffffff" || hex == "#ffff00" || hex == "#00ffff" || hex == "#00ff00") Color.Black else Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** None, the bullets and the numberings, the paragraph's own one marked. */
@Composable
private fun ListPanel(current: ListStyle, onPick: (ListStyle) -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().barTopLine().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        for (style in ListStyle.entries) {
            val description = stringResource(listDescription(style))
            Box(
                Modifier.height(40.dp).widthIn(min = 40.dp).clickable { onPick(style) }.padding(horizontal = 6.dp).semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) { Text(style.label, color = if (style == current) c.toolAccent else c.icon, fontSize = 17.sp, fontWeight = if (style == current) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
private fun AlignPanel(current: String, onAlign: (String) -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().barTopLine().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        for (a in listOf("left", "right", "center", "justify")) {
            val description = stringResource(alignDescription(a))
            Box(
                Modifier.size(40.dp).clickable { onAlign(a) }.semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) { Icon(alignIcon(a), null, tint = if (a == current) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
        }
    }
}

/**
 * Edit PDF over the pages: every paragraph outlined in dashes; the selected one in solid blue with
 * corner handles, a menu over it (Edit text, Select all, Delete), dragged to move it or by its
 * handles to change its width; the paragraph being typed in, edited where it sits; and a selected
 * picture with its menu (turn left, turn right, delete), dragged to move or by a corner to resize.
 */
@Composable
fun BoxScope.EditOverlay(vm: AppViewModel, doc: OpenDocument) {
    val pages = doc.viewer.visiblePages()
    LaunchedEffect(pages, doc.info.generation) { for (p in pages) vm.edit.loadParagraphs(doc, p) }
    val accent = pdfColors.accent
    val selected = vm.edit.editSelection
    val editing = vm.edit.editing
    Canvas(Modifier.fillMaxSize()) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        // with a paragraph or picture selected, the reference shows only that one's outline
        if (selected == null && vm.edit.editImage == null) for (p in pages) {
            for (para in vm.edit.paragraphs[p].orEmpty()) {
                val a = doc.viewer.pageToScreen(p, para.box[0], para.box[1])
                val b = doc.viewer.pageToScreen(p, para.box[2], para.box[3])
                drawRect(accent.copy(alpha = 0.6f), topLeft = Offset(a.x - 3f, a.y - 3f), size = Size(b.x - a.x + 6f, b.y - a.y + 6f), style = Stroke(width = 1.dp.toPx(), pathEffect = dash))
            }
        }
    }
    if (selected != null) {
        if (editing != null && editing.page == selected.page && editing.number == selected.number) InlineEditor(vm, doc, editing) else SelectedParagraph(vm, doc, selected)
    }
    vm.edit.editImage?.let { SelectedImage(vm, doc, it) }
}

/** A box on screen for [box] (points) on [page], with the drag offset applied. */
private fun screenBox(doc: OpenDocument, page: Int, box: FloatArray, pad: Float): Pair<Offset, Offset> {
    val a = doc.viewer.pageToScreen(page, box[0], box[1])
    val b = doc.viewer.pageToScreen(page, box[2], box[3])
    return Offset(a.x - pad, a.y - pad) to Offset(b.x + pad, b.y + pad)
}

@Composable
private fun BoxScope.SelectedParagraph(vm: AppViewModel, doc: OpenDocument, para: Paragraph) {
    var active by remember(para) { mutableStateOf(false) }
    val rect = androidx.compose.ui.geometry.Rect(para.box[0], para.box[1], para.box[2], para.box[3])
    ObjectTransformOverlay(doc, para.page to para.number, para.page, rect,
        minFactor = 4f / para.size.coerceAtLeast(4f), maxFactor = 500f / para.size.coerceAtLeast(1f),
        onMove = { dx, dy, finished -> vm.edit.transformParagraph(doc, para, dx, dy, 1f, finished) },
        onScale = { factor, finished -> vm.edit.transformParagraph(doc, para, 0f, 0f, factor, finished) },
        onActive = { active = it })
    if (!active) {
        val a = doc.viewer.pageToScreen(para.page, rect.left, rect.top)
        val b = doc.viewer.pageToScreen(para.page, rect.right, rect.bottom)
        FloatingMenu(a, b, doc.viewer.padTop, doc.viewer.viewW) {
            MenuText(appText(R.string.edit_edit_text)) { vm.edit.startInlineEdit(para, selectAll = false) }
            MenuSplit()
            if (para.rows.isEmpty()) {
                MenuText(appText(R.string.edit_select_all)) { vm.edit.startInlineEdit(para, selectAll = true) }
                MenuSplit()
            }
            MenuText(appText(R.string.common_delete)) { vm.edit.deleteParagraph(doc, para) }
        }
    }
}

/** The dark menu the reference floats over a selection: centred above it, or below when there's no room. */
@Composable
private fun BoxScope.FloatingMenu(a: Offset, b: Offset, top: Float, viewWidth: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    var menu by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(density) { 12.dp.toPx() }
    val edge = with(density) { 8.dp.toPx() }
    val x = ((a.x + b.x) / 2f - menu.width / 2f).coerceIn(edge, maxOf(edge, viewWidth - menu.width - edge))
    val above = a.y - gap - menu.height
    val y = if (above > top) above else b.y + gap
    Row(
        Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(x.roundToInt(), y.roundToInt()) }.onSizeChanged { menu = it }
            .shadow(6.dp, RoundedCornerShape(4.dp)).background(Color(0xFF303030), RoundedCornerShape(4.dp)).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun MenuText(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxHeightRow().clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun MenuIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun MenuSplit() {
    Box(Modifier.width(1.dp).height(48.dp).background(Color(0xFF4B4B4B)))
}

private fun Modifier.fillMaxHeightRow(): Modifier = this.height(48.dp)

/**
 * Typing in a paragraph where it sits: its text in its colour, size and face over the page's own
 * colour, outlined in blue. Tapping elsewhere or Done writes it into the page.
 */
@Composable
private fun BoxScope.InlineEditor(vm: AppViewModel, doc: OpenDocument, para: Paragraph) {
    if (para.rows.isNotEmpty()) {
        PositionedInlineEditor(vm, doc, para)
        return
    }
    val density = LocalDensity.current
    val (a, b) = screenBox(doc, para.page, para.box, with(density) { 3.dp.toPx() })
    val ppp = doc.viewer.pixelsPerPoint(para.page)
    val focus = remember { FocusRequester() }
    var value by remember(para) {
        val draft = vm.edit.editDraft ?: para.text
        mutableStateOf(TextFieldValue(draft, if (vm.edit.editSelectAll) TextRange(0, draft.length) else TextRange(draft.length)))
    }
    LaunchedEffect(para) { runCatching { focus.requestFocus() } }
    val ime = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density)
    LaunchedEffect(ime) {
        if (ime <= 0) return@LaunchedEffect
        val v = doc.viewer
        // the formatting bar (and a panel) sit on the keyboard: keep the box's bottom clear of them
        val clear = v.viewH - ime - with(density) { (BottomBarHeight + 64.dp).toPx() }
        if (b.y > clear) v.setScroll(v.scrollX, v.scrollY + (b.y - clear))
    }
    LaunchedEffect(value.text, value.selection) {
        vm.edit.editDraft = value.text
        vm.edit.selectedRows = io.github.artcraftandroid.pdfcraft.ui.selectedTextLines(value.text, value.selection.start, value.selection.end)
    }
    // misspelled words, checked a moment after typing stops, underlined in red (as the reference does)
    val context = androidx.compose.ui.platform.LocalContext.current
    val speller = remember { SpellCheck(context) }
    var typos by remember(para) { mutableStateOf<List<Typo>>(emptyList()) }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    LaunchedEffect(value.text) {
        kotlinx.coroutines.delay(600)
        typos = speller.check(value.text)
    }
    var paper by remember(para) { mutableStateOf<Color?>(null) }
    LaunchedEffect(para) { paper = vm.edit.paperColor(doc, para) }
    val paragraphLabel = stringResource(R.string.edit_paragraph_text)
    val fontPx = para.size * ppp
    val sp = with(density) { (fontPx / fontScale).toSp() }
    val family = editorFamily(vm.edit.editFont[para.page to para.number] ?: para.font, rememberEditorFonts(vm, doc, para.page))
    with(density) {
        Box(
            Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(a.x.roundToInt(), a.y.roundToInt()) }.width((b.x - a.x).toDp()).heightIn(min = (b.y - a.y).toDp())
                .background(paper ?: Color.White).drawBehind { drawRect(SelectionBlue, size = size, style = Stroke(2.dp.toPx())) }
                .padding(3.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    vm.edit.editDraft = it.text
                    vm.edit.selectedRows = io.github.artcraftandroid.pdfcraft.ui.selectedTextLines(it.text, it.selection.start, it.selection.end)
                },
                textStyle = TextStyle(
                    color = parseHex(para.color ?: "#000000"), fontSize = sp, lineHeight = sp * 1.2f, fontFamily = family,
                    textDirection = androidx.compose.ui.text.style.TextDirection.Content,
                    textAlign = if (inlineRtl(value.text)) TextAlign.Right else TextAlign.Left,
                    fontWeight = if (para.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (para.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (vm.edit.editUnderline[para.page to para.number] == true) TextDecoration.Underline else null,
                ),
                cursorBrush = SolidColor(SelectionBlue),
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = paragraphLabel }
                    .drawWithContent {
                        drawContent()
                        val l = layout ?: return@drawWithContent
                        for (t in typos) {
                            if (t.end > l.layoutInput.text.length) continue
                            for (line in l.getLineForOffset(t.start)..l.getLineForOffset(t.end - 1)) {
                                val x0 = if (line == l.getLineForOffset(t.start)) l.getHorizontalPosition(t.start, true) else l.getLineLeft(line)
                                val x1 = if (line == l.getLineForOffset(t.end - 1)) l.getHorizontalPosition(t.end, true) else l.getLineRight(line)
                                drawSquiggle(x0, x1, l.getLineBottom(line) - 1.dp.toPx())
                            }
                        }
                    },
            )
        }
        // the word at the cursor, if misspelled: its suggestions above the box, a tap puts one in
        val at = value.selection.start
        val typo = typos.firstOrNull { value.selection.collapsed && at in it.start..it.end && it.suggestions.isNotEmpty() }
        if (typo != null) {
            Row(
                Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(a.x.roundToInt(), (a.y - 48.dp.toPx()).roundToInt()) }
                    .background(Color(0xFF2C2C2C), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (word in typo.suggestions) {
                    Text(
                        word, color = Color.White, fontSize = 15.sp,
                        modifier = Modifier.clickable {
                            val t = value.text.replaceRange(typo.start, typo.end, word)
                            value = TextFieldValue(t, TextRange(typo.start + word.length))
                        }.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** A red wavy line from [x0] to [x1] at [y], under a misspelled word. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSquiggle(x0: Float, x1: Float, y: Float) {
    val step = 2.dp.toPx()
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(x0, y)
        var x = x0
        var up = true
        while (x < x1) {
            val nx = minOf(x + step, x1)
            lineTo(nx, if (up) y - step / 1.5f else y)
            x = nx
            up = !up
        }
    }
    drawPath(path, Color(0xFFE34850), style = Stroke(1.dp.toPx()))
}

@Composable
private fun BoxScope.SelectedImage(vm: AppViewModel, doc: OpenDocument, img: EditImage) {
    val density = LocalDensity.current
    val ppp = doc.viewer.pixelsPerPoint(img.page).coerceAtLeast(0.01f)
    var rect by remember(img) { mutableStateOf(img.rect.copyOf()) }
    var cropping by remember(img) { mutableStateOf(false) }
    var dragging by remember(img) { mutableStateOf(false) }
    var menu by remember(img) { mutableStateOf(false) }
    val (a, b) = screenBox(doc, img.page, rect, 0f)
    val handles = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)
    Canvas(Modifier.fillMaxSize()) {
        drawRect(SelectionBlue, topLeft = a, size = Size(b.x - a.x, b.y - a.y), style = Stroke(2.dp.toPx()))
        handles.forEach { (x, y) ->
            val point = Offset(if (x < 0) a.x else if (x > 0) b.x else (a.x + b.x) / 2,
                if (y < 0) a.y else if (y > 0) b.y else (a.y + b.y) / 2)
            drawCircle(Color.White, 6.dp.toPx(), point)
            drawCircle(SelectionBlue, 6.dp.toPx(), point, style = Stroke(2.dp.toPx()))
        }
    }
    with(density) {
        if (!cropping) Box(Modifier.align(AbsoluteAlignment.TopLeft)
            .absoluteOffset { IntOffset(a.x.roundToInt(), a.y.roundToInt()) }
            .size((b.x-a.x).toDp(), (b.y-a.y).toDp()).pointerInput(img) {
                detectDragGestures(onDragStart = { dragging = true }, onDragCancel = { dragging = false; rect = img.rect.copyOf() },
                    onDragEnd = { dragging = false; vm.edit.placeImage(doc, img, rect) }) { change, d ->
                    change.consume()
                    rect = floatArrayOf(rect[0]+d.x/ppp, rect[1]+d.y/ppp, rect[2]+d.x/ppp, rect[3]+d.y/ppp)
                }
            })
        handles.forEach { (x, y) ->
            val px = if (x < 0) a.x else if (x > 0) b.x else (a.x+b.x)/2
            val py = if (y < 0) a.y else if (y > 0) b.y else (a.y+b.y)/2
            val label = stringResource(if (cropping) R.string.feature_image_crop else R.string.feature_resize_handle)
            Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset((px-20.dp.toPx()).roundToInt(), (py-20.dp.toPx()).roundToInt()) }
                .size(40.dp).semantics { contentDescription = label }.pointerInput(img, cropping, x, y) {
                    detectDragGestures(onDragStart = { dragging = true }, onDragCancel = { dragging = false; rect = if (cropping) img.visibleRect.copyOf() else img.rect.copyOf() },
                        onDragEnd = { dragging = false; if (!cropping) vm.edit.placeImage(doc, img, rect) }) { change, d ->
                        change.consume()
                        val next = io.github.artcraftandroid.pdfcraft.ui.imageHandleRect(rect, x, y, d.x/ppp, d.y/ppp)
                        if (cropping) {
                            next[0] = next[0].coerceAtLeast(img.visibleRect[0]); next[1] = next[1].coerceAtLeast(img.visibleRect[1])
                            next[2] = next[2].coerceAtMost(img.visibleRect[2]); next[3] = next[3].coerceAtMost(img.visibleRect[3])
                        }
                        rect = next
                    }
                })
        }
    }
    if (!dragging) FloatingMenu(a, b, doc.viewer.padTop, doc.viewer.viewW) {
        if (cropping) {
            MenuText(appText(R.string.feature_cancel)) { cropping = false; rect = img.rect.copyOf() }
            MenuText(appText(R.string.feature_apply)) {
                vm.edit.imageAction(doc, img, "crop", mapOf("rect" to rect.map { it.toDouble() }))
            }
        } else {
            MenuIconButton(LucideIcons.RotateCcw, appText(R.string.edit_turn_left)) { vm.edit.turnImage(doc, img, 3) }
            MenuIconButton(LucideIcons.RotateCw, appText(R.string.edit_turn_right)) { vm.edit.turnImage(doc, img, 1) }
            MenuIconButton(LucideIcons.EllipsisVertical, appText(R.string.feature_image_tools)) { menu = true }
            MenuIconButton(LucideIcons.Trash2, appText(R.string.common_delete)) { vm.edit.deleteImage(doc, img) }
        }
    }
    if (menu) ImageActionsDialog(vm, doc, img, onDismiss = { menu = false }, onCrop = {
        menu = false; cropping = true; rect = img.visibleRect.copyOf()
        vm.tell(appText(R.string.feature_crop_hint))
    })
}

/** What a screen reader says for each list style's button. */
private fun listDescription(style: ListStyle): Int = when (style) {
    ListStyle.None -> R.string.edit_list_desc_none
    ListStyle.Bullet -> R.string.edit_list_desc_bullet
    ListStyle.Dash -> R.string.edit_list_desc_dash
    ListStyle.Number -> R.string.edit_list_desc_number
    ListStyle.Lower -> R.string.edit_list_desc_lower
    ListStyle.Upper -> R.string.edit_list_desc_upper
    ListStyle.Roman -> R.string.edit_list_desc_roman
}

/** What a screen reader says for each alignment's button. */
private fun alignDescription(a: String): Int = when (a) {
    "center" -> R.string.edit_align_center
    "right" -> R.string.edit_align_right
    "justify" -> R.string.edit_align_justify
    else -> R.string.edit_align_left
}

private fun alignIcon(a: String) = when (a) {
    "center" -> LucideIcons.AlignCenter
    "right" -> LucideIcons.AlignRight
    "justify" -> LucideIcons.AlignJustify
    else -> LucideIcons.AlignLeft
}

/** The nearest of the engine's three faces to a PDF font name. */
fun baseFont(name: String): String {
    val n = name.lowercase()
    return when {
        "courier" in n || "mono" in n || "consol" in n -> "courier"
        "times" in n || "serif" in n && "sans" !in n || "georgia" in n || "garamond" in n || "minion" in n -> "times"
        else -> "helvetica"
    }
}

fun parseHex(s: String): Color = runCatching { Color(s.toColorInt()) }.getOrDefault(Color.Black)
