package io.github.artcraftandroid.pdfcraft.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HeadingStyle(val font: String, val size: Double, val bold: Boolean, val italic: Boolean, val sample: String) {
    fun arguments(): Map<String, Any> = mapOf("font" to font, "size" to size, "bold" to bold, "italic" to italic)
}

/** New engine tools share the existing document mutation lock, history and refresh path. */
class DocumentFeatures internal constructor(private val vm: AppViewModel) {
    private var commentTarget: OpenDocument? = null
    var headingDocument by mutableStateOf<OpenDocument?>(null)
    var headingStyles by mutableStateOf<List<HeadingStyle>>(emptyList())

    fun pickComments(doc: OpenDocument) {
        commentTarget = doc
        vm.pickFiles(FilePickKind.ImportComments)
    }

    fun importComments(uri: Uri) {
        val doc = commentTarget ?: return
        commentTarget = null
        if (doc.closed || doc.closing) return
        vm.viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { vm.jobs.copyIn(uri) }
                vm.runTool(doc, "doc_import_data", mapOf("path" to file.absolutePath),
                    onFinished = { file.delete() }, onDone = { vm.tell(text(R.string.feature_import_comments_done)) })
            } catch (e: Exception) { vm.tell(e.userMessage) }
        }
    }

    fun loadHeadingStyles(doc: OpenDocument) = vm.jobs.job(text(R.string.feature_bookmarks_create)) { engine ->
        if (doc.closed || doc.closing) return@job
        val styles = engine.tool("text_styles", mapOf("doc" to doc.id)).optJSONArray("styles")
        headingStyles = (0 until (styles?.length() ?: 0)).mapNotNull { i ->
            styles?.optJSONObject(i)?.let { HeadingStyle(it.optString("font"), it.optDouble("size"),
                it.optBoolean("bold"), it.optBoolean("italic"), it.optString("sample")) }
        }
        headingDocument = doc
    }

    fun createHeadings(doc: OpenDocument, styles: List<HeadingStyle>?, onCreated: () -> Unit = {}) {
        if (styles != null && styles.size !in 1..9) return
        vm.runTool(doc, if (styles == null) "bookmark_from_structure" else "bookmark_from_text_styles",
            if (styles == null) emptyMap() else mapOf("levels" to styles.map { it.arguments() }),
            onDone = { headingDocument = null; onCreated(); vm.tell(text(R.string.feature_bookmarks_done)) })
    }

    fun exportImage(doc: OpenDocument, image: EditImage) = vm.jobs.job(text(R.string.msg_busy_exporting_images)) { engine ->
        val target = vm.jobs.tempFile("image").let { File(it.parentFile, it.nameWithoutExtension) }
        val result = engine.tool("image_save", mapOf("doc" to doc.id, "page" to image.page + 1,
            "image" to image.number, "path" to target.absolutePath))
        val file = File(result.getString("path"))
        val ext = file.extension.lowercase()
        vm.jobs.deliver(file, "${doc.title}_image_${image.number}.$ext", if (ext in setOf("jpg", "jpeg")) "image/jpeg" else "image/png")
    }
}
