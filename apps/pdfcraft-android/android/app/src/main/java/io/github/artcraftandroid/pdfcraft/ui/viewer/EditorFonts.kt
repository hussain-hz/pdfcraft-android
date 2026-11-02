package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.graphics.Typeface
import android.graphics.fonts.Font
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily
import io.github.artcraftandroid.pdfcraft.core.EditorFont
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun editorFaceKey(name: String): String = name.substringAfterLast('+').filter { it.isLetterOrDigit() }.lowercase()

/** Source programs remain in memory and belong to this page, never to another document. */
internal fun documentEditorTypefaces(fonts: List<EditorFont>): Map<String, Typeface> =
    fonts.groupBy { editorFaceKey(it.face) }.mapNotNull { (face, sources) ->
        // Keep the source first; a matching full face supplies newly inserted missing glyphs.
        val families = sources.sortedBy { it.resource.startsWith("PCApproved") }.mapNotNull { source ->
            runCatching {
                val buffer = ByteBuffer.allocateDirect(source.bytes.size).apply { put(source.bytes); flip() }
                android.graphics.fonts.FontFamily.Builder(Font.Builder(buffer).build()).build()
            }.getOrNull()
        }
        families.firstOrNull()?.let { first ->
            val builder = Typeface.CustomFallbackBuilder(first)
            families.drop(1).take(63).forEach { builder.addCustomFallback(it) }
            face to builder.build()
        }
    }.toMap()

@Composable
internal fun rememberEditorFonts(vm: AppViewModel, doc: OpenDocument, page: Int): Map<String, FontFamily> {
    val families by produceState<Map<String, FontFamily>>(emptyMap(), doc.id, doc.info.generation, page) {
        val engine = vm.engine ?: return@produceState
        value = runCatching {
            val fonts = engine.command { editorFonts(doc.id, page.toUInt()) }
            withContext(Dispatchers.Default) { documentEditorTypefaces(fonts).mapValues { FontFamily(it.value) } }
        }.getOrDefault(emptyMap())
    }
    return families
}

internal fun editorFamily(name: String, fonts: Map<String, FontFamily>): FontFamily =
    fonts[editorFaceKey(name)] ?: when (baseFont(name)) {
        "times" -> FontFamily.Serif
        "courier" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
