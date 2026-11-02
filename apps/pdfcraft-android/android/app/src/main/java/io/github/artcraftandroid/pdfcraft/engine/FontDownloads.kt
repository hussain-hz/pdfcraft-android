package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

const val MAX_DOCUMENT_FONT_BYTES = 16 * 1024 * 1024

data class DownloadableFont(val id: String, val family: String, val style: String, val arabic: Boolean,
    val path: String, val sha256: String, val size: Int, val licensePath: String, val licenseSha256: String, val revision: String) {
    val url get() = "https://raw.githubusercontent.com/google/fonts/$revision/$path"
    val licenseUrl get() = "https://raw.githubusercontent.com/google/fonts/$revision/$licensePath"
    val sourceUrl get() = "https://github.com/google/fonts/blob/$revision/$licensePath"
}

fun readFontCatalog(json: String): List<DownloadableFont> {
    val root = JSONObject(json)
    val revision = root.getString("revision")
    require(revision.matches(Regex("[0-9a-f]{40}")))
    val fonts = root.getJSONArray("fonts")
    return List(fonts.length()) { i ->
        val f = fonts.getJSONObject(i)
        DownloadableFont(f.getString("id"), f.getString("family"), f.getString("style"), f.getBoolean("arabic"),
            f.getString("path"), f.getString("sha256"), f.getInt("size"), f.getString("licensePath"), f.getString("licenseSha256"), revision).also {
            require(it.id.matches(Regex("[A-Za-z0-9-]+")) && it.path.startsWith("ofl/") && !it.path.contains(".."))
            require(it.licensePath.startsWith("ofl/") && !it.licensePath.contains(".."))
            require(it.sha256.matches(Regex("[0-9a-f]{64}")) && it.licenseSha256.matches(Regex("[0-9a-f]{64}")))
            require(it.size in 1..MAX_DOCUMENT_FONT_BYTES)
        }
    }
}

fun fontDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Pinned official source only; bounded transfer, digest validation, cooperative cancellation. */
suspend fun fetchFontBytes(url: String, sha256: String, limit: Int, expected: Int? = null, progress: (Float) -> Unit = {}): ByteArray {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host == "raw.githubusercontent.com" && uri.path.startsWith("/google/fonts/"))
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = false
        require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
        require(connection.contentLengthLong <= limit) { "Font download is too large" }
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= limit) { "Font download is too large" }
                output.write(buffer, 0, read)
                progress(output.size().toFloat() / (expected ?: limit))
            }
            output.toByteArray()
        }
        require(expected == null || bytes.size == expected) { "Incomplete font download" }
        require(fontDigest(bytes) == sha256) { "Font download verification failed" }
        return bytes
    } finally { connection.disconnect() }
}

typealias FontTransfer = suspend (String, String, Int, Int?, (Float) -> Unit) -> ByteArray

/** Verified private cache; a valid font and licence require no network on subsequent uses. */
suspend fun cacheDocumentFont(font: DownloadableFont, directory: File, progress: (Float) -> Unit = {}, transfer: FontTransfer = ::fetchFontBytes): File {
    val out = File(directory, "${font.id}.ttf")
    val license = File(directory, "${font.id}-OFL.txt")
    val valid = out.isFile && out.length() == font.size.toLong() && fontDigest(out.readBytes()) == font.sha256
    if (valid && license.isFile && license.length() <= 64 * 1024 && fontDigest(license.readBytes()) == font.licenseSha256) return out
    val bytes = transfer(font.url, font.sha256, MAX_DOCUMENT_FONT_BYTES, font.size, progress)
    val terms = transfer(font.licenseUrl, font.licenseSha256, 64 * 1024, null, {})
    coroutineContext.ensureActive()
    require(bytes.size == font.size && fontDigest(bytes) == font.sha256 && fontDigest(terms) == font.licenseSha256) { "Font download verification failed" }
    directory.mkdirs()
    val part = File(directory, "${font.id}.part")
    try {
        part.writeBytes(bytes); license.writeBytes(terms)
        if (out.exists()) java.nio.file.Files.move(part.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        else check(part.renameTo(out))
    } finally { part.delete() }
    return out
}

/** Subset prefixes are metadata; family/weight aliases must never select a different face. */
fun matchingOriginalFont(source: String, fonts: List<DownloadableFont>): DownloadableFont? {
    fun key(name: String) = name.substringAfterLast('+').filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
    val wanted = key(source)
    return fonts.singleOrNull { key(it.id) == wanted }
}

fun needsOriginalFont(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it.message?.contains("preserving this font", ignoreCase = true) == true }

/** A family or the whole catalog is one resumable operation; completed verified faces are reused. */
suspend fun cacheDocumentFonts(fonts: List<DownloadableFont>, directory: File, progress: (Float) -> Unit = {},
    transfer: FontTransfer = ::fetchFontBytes): List<File> {
    require(fonts.isNotEmpty() && fonts.distinctBy { it.id }.size == fonts.size)
    val total = fonts.sumOf { it.size.toLong() }.toFloat()
    var completed = 0L
    return fonts.map { font ->
        coroutineContext.ensureActive()
        val file = cacheDocumentFont(font, directory, { progress((completed + it.coerceIn(0f, 1f) * font.size) / total) }, transfer)
        completed += font.size
        progress(completed / total)
        file
    }
}
