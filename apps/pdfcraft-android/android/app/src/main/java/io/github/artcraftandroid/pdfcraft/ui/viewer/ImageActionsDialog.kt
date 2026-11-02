package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

@Composable
fun ImageActionsDialog(vm: AppViewModel, doc: OpenDocument, img: EditImage, onDismiss: () -> Unit, onCrop: () -> Unit) {
    val c = pdfColors
    AlertDialog(onDismissRequest = onDismiss, containerColor = c.menu, titleContentColor = c.text,
        title = { Text(stringResource(R.string.feature_image_tools)) },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = onCrop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feature_image_crop), color = c.toolAccent) }
                val actions = buildList {
                    if (img.hasCrop) add(R.string.feature_image_uncrop to "remove_crop")
                    add(R.string.feature_image_flip_h to "flip_horizontal")
                    add(R.string.feature_image_flip_v to "flip_vertical")
                    add(R.string.feature_image_front to "bring_to_front")
                    add(R.string.feature_image_forward to "bring_forward")
                    add(R.string.feature_image_backward to "send_backward")
                    add(R.string.feature_image_back to "send_to_back")
                }
                actions.forEach { (label, action) ->
                    TextButton(onClick = { onDismiss(); vm.edit.imageAction(doc, img, action) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(label), color = c.toolAccent)
                    }
                }
                if (img.raster) {
                    TextButton(onClick = { onDismiss(); vm.edit.replaceImage(doc, img) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.feature_image_replace), color = c.toolAccent)
                    }
                    TextButton(onClick = { onDismiss(); vm.features.exportImage(doc, img) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.feature_image_export), color = c.toolAccent)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_cancel), color = c.text) } })
}
