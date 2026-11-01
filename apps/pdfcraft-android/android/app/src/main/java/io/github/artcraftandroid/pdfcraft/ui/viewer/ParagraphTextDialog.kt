package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** A multiline editor also permits inserting empty lines in a paragraph with fixed source rows. */
@Composable
fun ParagraphTextDialog(vm: AppViewModel, doc: OpenDocument, para: Paragraph, onDismiss: () -> Unit) {
    val c = pdfColors
    var value by remember(para.page, para.number) { mutableStateOf(vm.edit.editDraft ?: para.text) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = c.menu, titleContentColor = c.text,
        title = { Text(stringResource(R.string.edit_paragraph_text)) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, minLines = 4,
            modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
            textStyle = TextStyle(color = c.text, textDirection = TextDirection.Content),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, unfocusedTextColor = c.text,
                cursorColor = c.toolAccent, focusedBorderColor = c.toolAccent)) },
        confirmButton = { TextButton(onClick = { vm.edit.replaceParagraphText(doc, para, value); onDismiss() }) {
            Text(stringResource(R.string.feature_apply), color = c.toolAccent)
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_cancel), color = c.text) } })
}
