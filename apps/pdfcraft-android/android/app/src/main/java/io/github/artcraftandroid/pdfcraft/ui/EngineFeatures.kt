package io.github.artcraftandroid.pdfcraft.ui

/** Values accepted by the native tool API, kept separate from translated labels. */
enum class MonoCompression(val wire: String) { Group4("ccitt_g4"), Jbig2("jbig2"), Retain("retain") }

internal fun monoOptions(compression: MonoCompression, downsample: Boolean): Map<String, Any> =
    mapOf("compression" to compression.wire, "downsample" to downsample, "ppi" to 300, "above_ppi" to 450)

internal fun documentMime(ext: String): String = when (ext) {
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "rtf" -> "application/rtf"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "md", "markdown" -> "text/markdown"
    else -> "text/html"
}

/** An end exactly at a newline does not select the next paragraph. Offsets are UTF-16. */
internal fun selectedTextLines(text: String, start: Int, end: Int): IntRange {
    val from = minOf(start, end).coerceIn(0, text.length)
    val until = maxOf(start, end).coerceIn(0, text.length)
    val last = if (until > from) until - 1 else from
    return text.take(from).count { it == '\n' }..text.take(last).count { it == '\n' }
}

/** Drag handles are in document coordinates, independent of the interface's RTL direction. */
internal fun imageHandleRect(rect: FloatArray, x: Int, y: Int, dx: Float, dy: Float): FloatArray = rect.copyOf().also {
    if (x < 0) it[0] = minOf(rect[0] + dx, rect[2] - 4f)
    if (x > 0) it[2] = maxOf(rect[2] + dx, rect[0] + 4f)
    if (y < 0) it[1] = minOf(rect[1] + dy, rect[3] - 4f)
    if (y > 0) it[3] = maxOf(rect[3] + dy, rect[1] + 4f)
}
