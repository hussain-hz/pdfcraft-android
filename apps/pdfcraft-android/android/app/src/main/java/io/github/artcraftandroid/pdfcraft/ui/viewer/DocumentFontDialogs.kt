package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.*

@Composable
fun DocumentFontDialogs(vm: AppViewModel, doc: OpenDocument) {
    val manager = vm.fonts
    val target = manager.target?.takeIf { it.doc === doc } ?: return
    val colors = pdfColors
    val licenseLabel = stringResource(R.string.font_source_license)
    val regularLabel = stringResource(R.string.font_regular)
    val boldLabel = stringResource(R.string.font_bold)
    val preview = manager.preview
    val title = when (manager.sheet) {
        FontSheet.Downloads -> R.string.font_downloads
        FontSheet.Installed -> R.string.font_list
        FontSheet.Preview -> R.string.font_preview
    }
    AlertDialog(onDismissRequest = manager::close, containerColor = colors.menu, titleContentColor = colors.text,
        textContentColor = colors.textSecondary, title = { Text(stringResource(title)) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 470.dp).then(if (manager.sheet == FontSheet.Installed && !manager.working) Modifier else Modifier.verticalScroll(rememberScrollState())), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (manager.sheet != FontSheet.Installed) Text(stringResource(R.string.font_original, target.para.font), color = colors.textSecondary, fontSize = 13.sp)
            manager.problem?.let { Text(it, color = colors.toolAccent) }
            if (manager.working) {
                Text(if (manager.progress == null) stringResource(R.string.font_working)
                    else stringResource(R.string.font_download_progress, ((manager.progress ?: 0f) * 100).toInt()))
                val progress = manager.progress
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else when (manager.sheet) {
                FontSheet.Installed -> InstalledFontPicker(vm, doc, manager)
                FontSheet.Downloads -> {
                    Text(stringResource(R.string.font_catalog_hint), fontSize = 13.sp)
                    TextButton(onClick = { manager.downloadGroup(manager.catalog) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.font_download_all), color = colors.toolAccent)
                    }
                    TextButton(onClick=vm.fontLibrary::openSettings, modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.font_library_core_title), color=colors.toolAccent)
                    }
                    manager.catalog.sortedBy { !it.arabic }.groupBy { it.family }.forEach { (family, fonts) ->
                        val font = fonts.first()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { manager.downloadGroup(fonts) }, modifier = Modifier.weight(1f)) {
                                Column {
                                    Text(family, color = colors.text)
                                    Text(fonts.joinToString("، ") { if (it.style == "Bold") boldLabel else regularLabel }, color = colors.textSecondary, fontSize = 12.sp)
                                    Text(if (fonts.all { java.io.File(manager.directory, "${it.id}.ttf").isFile && java.io.File(manager.directory, "${it.id}-OFL.txt").isFile }) stringResource(R.string.font_downloaded)
                                        else "${fonts.sumOf { it.size } / 1024} KB", color = colors.toolAccent, fontSize = 12.sp)
                                }
                            }
                            TextButton(onClick = { vm.intents.trySend(Intent(Intent.ACTION_VIEW, font.sourceUrl.toUri())) },
                                modifier = Modifier.semantics { contentDescription = licenseLabel }) {
                                Text("OFL", color = colors.toolAccent, fontSize = 12.sp)
                            }
                        }
                    }
                }
                FontSheet.Preview -> if (preview != null) {
                    Text(preview.font?.face ?: when (preview.standard) {
                        "times" -> stringResource(R.string.edit_font_serif)
                        "courier" -> stringResource(R.string.edit_font_mono)
                        else -> stringResource(R.string.edit_font_sans)
                    }, color = colors.text)
                    Text(stringResource(if (preview.matching) R.string.font_matching else R.string.font_replacement), fontSize = 13.sp)
                    val ink = target.para.color?.let(::parseHex) ?: Color.Black
                    val family = preview.typeface?.let { FontFamily(it) } ?: editorFamily(preview.standard.orEmpty(), emptyMap())
                    Box(Modifier.fillMaxWidth().background(if (ink.luminance() > .7f) Color(0xFF242424) else Color.White).padding(14.dp)) {
                        Text(target.draft, color = ink, fontFamily = family, fontSize = target.para.size.coerceIn(12f, 28f).sp,
                            fontWeight = if (preview.standard != null && target.para.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (preview.standard != null && target.para.italic) FontStyle.Italic else FontStyle.Normal,
                            style = TextStyle(textDirection = TextDirection.Content))
                    }
                    TextButton(onClick = manager::installed) { Text(stringResource(R.string.font_list), color = colors.toolAccent) }
                }
            }
        } },
        confirmButton = {
            if (manager.sheet == FontSheet.Preview && preview != null) TextButton(onClick = manager::apply, enabled = !manager.working) {
                Text(stringResource(if (preview.matching) R.string.font_load_original else R.string.font_apply_replacement), color = colors.toolAccent)
            }
        },
        dismissButton = { TextButton(onClick = manager::close, enabled = !manager.applying) { Text(stringResource(if (manager.working) R.string.font_cancel_work else R.string.feature_cancel), color = colors.text) } })
}
