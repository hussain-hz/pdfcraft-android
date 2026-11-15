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
internal fun documentEditorTypefaces(fonts: List<EditorFont>, arabic: Boolean = false): Map<String, Typeface> =
    fonts.groupBy { editorFaceKey(it.face) }.mapNotNull { (face, sources) ->
        // Arabic letters may exist in a PDF subset even after its joining rules were stripped.
        val candidates = if (arabic) sources.filter { hasArabicJoining(it.bytes) }
            .sortedBy { !it.resource.startsWith("PCApproved") }
        else sources.sortedBy { it.resource.startsWith("PCApproved") }
        val families = candidates.mapNotNull { source ->
            runCatching {
                val buffer = ByteBuffer.allocateDirect(source.bytes.size).apply { put(source.bytes); flip() }
                android.graphics.fonts.FontFamily.Builder(Font.Builder(buffer).build()).build()
            }.getOrNull()
        }
        families.firstOrNull()?.let { first ->
            val builder = Typeface.CustomFallbackBuilder(first)
            families.drop(1).take(63).forEach { builder.addCustomFallback(it) }
            face to builder.setSystemFallback("sans-serif").build()
        }
    }.toMap()

// Check the OpenType substitution features, since nominal character coverage is insufficient.
private fun hasArabicJoining(bytes: ByteArray): Boolean = runCatching {
    val sfnt = ByteBuffer.wrap(bytes)
    val count = sfnt.getShort(4).toInt() and 0xffff
    val entry = (0 until count).map { 12 + it * 16 }.firstOrNull { sfnt.getInt(it) == 0x47535542 }
        ?: return false // GSUB
    val start = sfnt.getInt(entry + 8)
    val size = sfnt.getInt(entry + 12)
    val table = ByteBuffer.wrap(bytes, start, size).slice()
    val features = table.getShort(6).toInt() and 0xffff
    val featureCount = table.getShort(features).toInt() and 0xffff
    val tags = (0 until featureCount).map { table.getInt(features + 2 + it * 6) }.toSet()
    0x696e6974 in tags && 0x6d656469 in tags && 0x66696e61 in tags // init, medi, fina
}.getOrDefault(false)

internal fun hasArabicScript(text: String): Boolean =
    text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.ARABIC }

internal data class EditorFontFamilies(
    val source: Map<String, FontFamily> = emptyMap(),
    val arabic: Map<String, FontFamily> = emptyMap(),
) {
    fun family(name: String, text: String): FontFamily =
        editorFamily(name, if (hasArabicScript(text)) arabic else source)
}

@Composable
internal fun rememberEditorFonts(vm: AppViewModel, doc: OpenDocument, page: Int): EditorFontFamilies {
    val families by produceState(EditorFontFamilies(), doc.id, doc.info.generation, page) {
        val engine = vm.engine ?: return@produceState
        value = runCatching {
            val fonts = engine.command { editorFonts(doc.id, page.toUInt()) }
            withContext(Dispatchers.Default) {
                EditorFontFamilies(
                    documentEditorTypefaces(fonts).mapValues { FontFamily(it.value) },
                    documentEditorTypefaces(fonts, arabic = true).mapValues { FontFamily(it.value) },
                )
            }
        }.getOrDefault(EditorFontFamilies())
    }
    return families
}

internal fun editorFamily(name: String, fonts: Map<String, FontFamily>): FontFamily =
    fonts[editorFaceKey(name)] ?: when (baseFont(name)) {
        "times" -> FontFamily.Serif
        "courier" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
