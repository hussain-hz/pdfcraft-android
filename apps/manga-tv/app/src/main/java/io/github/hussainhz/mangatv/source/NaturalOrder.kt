package io.github.hussainhz.mangatv.source

/** Sorts "page 2" before "page 10", case-insensitively. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (cmp != 0) return cmp
        }
        if (ca.size != cb.size) return ca.size - cb.size
        return a.compareTo(b) // deterministic tie-break, e.g. "001" vs "1"
    }
}

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")
private val ZIP_EXT = setOf("cbz", "zip")

fun isImageName(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT &&
    !name.substringAfterLast('/').startsWith(".")

fun isZipName(name: String) = name.substringAfterLast('.', "").lowercase() in ZIP_EXT &&
    !name.startsWith(".")
