package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.HeadingStyle
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

@Composable
fun HeadingStylesDialog(vm: AppViewModel, doc: OpenDocument) {
    if (vm.features.headingDocument !== doc) return
    val c = pdfColors
    val chosen = remember(doc, vm.features.headingStyles) { mutableStateListOf<HeadingStyle>() }
    AlertDialog(onDismissRequest = { vm.features.headingDocument = null }, containerColor = c.menu,
        titleContentColor = c.text, textContentColor = c.text,
        title = { Text(stringResource(R.string.feature_bookmarks_create)) },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.feature_bookmarks_hint))
                TextButton(onClick = { vm.features.createHeadings(doc, null) }) {
                    Text(stringResource(R.string.feature_bookmarks_tagged), color = c.toolAccent)
                }
                if (vm.features.headingStyles.isEmpty()) Text(stringResource(R.string.feature_no_heading_styles))
                vm.features.headingStyles.forEach { style ->
                    val level = chosen.indexOf(style)
                    Text((if (level >= 0) "✓ ${level + 1}. " else "") + style.sample + "\n${style.font} · ${style.size}",
                        color = if (level >= 0) c.toolAccent else c.text,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (level >= 0) chosen.remove(style) else if (chosen.size < 9) chosen.add(style)
                        }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { vm.features.createHeadings(doc, chosen.toList()) }) {
            Text(stringResource(R.string.feature_create), color = c.toolAccent)
        } },
        dismissButton = { TextButton(onClick = { vm.features.headingDocument = null }) { Text(stringResource(R.string.feature_cancel), color = c.text) } })
}
