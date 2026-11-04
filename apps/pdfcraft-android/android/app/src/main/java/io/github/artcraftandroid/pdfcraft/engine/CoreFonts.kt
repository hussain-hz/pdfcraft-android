package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

data class CoreFontFace(val name: String, val size: Int, val sha256: String, val face: String)
data class CoreFontPackage(val file: String, val family: String, val size: Int, val sha256: String, val fonts: List<CoreFontFace>) {
    val url get() = "https://downloads.sourceforge.net/project/corefonts/the%20fonts/final/$file"
    fun installedFile(directory: File, face: CoreFontFace) = File(directory, "core-fonts/${file.removeSuffix(".exe")}/${face.name}")
}

fun readCoreFontCatalog(json: String): List<CoreFontPackage> {
    val array = JSONObject(json).getJSONArray("packages")
    return List(array.length()) { i ->
        val item = array.getJSONObject(i); val faces = item.getJSONArray("fonts")
        CoreFontPackage(item.getString("file"), item.getString("family"), item.getInt("size"), item.getString("sha256"),
            List(faces.length()) { n -> faces.getJSONObject(n).let { CoreFontFace(it.getString("name"), it.getInt("size"), it.getString("sha256"), it.getString("face")) } }).also { p ->
            require(p.file.matches(Regex("[a-z0-9]+32\\.exe")) && p.size in 1..2*1024*1024 && p.sha256.matches(Regex("[a-f0-9]{64}")))
            require(p.fonts.size in 1..4 && p.fonts.distinctBy { it.name.lowercase() }.size == p.fonts.size)
            require(p.fonts.all { it.name.matches(Regex("[a-zA-Z0-9]+\\.[tT][tT][fF]")) && it.size in 1..MAX_DOCUMENT_FONT_BYTES && it.sha256.matches(Regex("[a-f0-9]{64}")) })
        }
    }.also { require(it.size in 1..11 && it.distinctBy { p -> p.file }.size == it.size) }
}

private fun verified(file: File, size: Int, digest: String): Boolean = file.isFile && file.length() == size.toLong() && fontDigest(file.readBytes()) == digest
fun corePackageInstalled(p: CoreFontPackage, directory: File) = p.fonts.all { verified(p.installedFile(directory, it), it.size, it.sha256) }

/** SourceForge mirrors may redirect; every hop stays HTTPS on the distribution service. */
fun trustedCoreFontUrl(uri: URI): Boolean = uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1,443) &&
    (uri.host == "downloads.sourceforge.net" || uri.host == "sourceforge.net" || uri.host?.endsWith(".dl.sourceforge.net") == true) &&
    (uri.path.startsWith("/project/corefonts/") || uri.path.startsWith("/projects/corefonts/"))

suspend fun fetchCoreFontBytes(url: String, sha256: String, limit: Int, expected: Int?, progress: (Float) -> Unit): ByteArray {
    var uri = URI(url)
    repeat(6) {
        require(trustedCoreFontUrl(uri)) { "Untrusted font download address" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=15000; connection.readTimeout=15000; connection.instanceFollowRedirects=false
            connection.setRequestProperty("User-Agent", "PdfCraft Android Core Fonts installer")
            val code=connection.responseCode
            if (code in listOf(301,302,303,307,308)) {
                uri=uri.resolve(requireNotNull(connection.getHeaderField("Location"))); return@repeat
            }
            require(code==200) { "HTTP $code" }; require(connection.contentLengthLong<=limit) { "Font package is too large" }
            val bytes=connection.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                while(true) { coroutineContext.ensureActive(); val n=input.read(buffer); if(n<0) break
                    require(out.size()+n<=limit) { "Font package is too large" }; out.write(buffer,0,n)
                    progress(out.size().toFloat()/(expected ?: limit))
                }; out.toByteArray()
            }
            require((expected==null || bytes.size==expected) && fontDigest(bytes)==sha256) { "Font package verification failed" }
            return bytes
        } finally { connection.disconnect() }
    }
    error("Too many font download redirects")
}

typealias CoreFontExtractor = (ByteArray, List<String>) -> List<Pair<String,ByteArray>>

/** Per-package resume; originals are kept unchanged, and no executable is ever launched. */
suspend fun cacheCoreFontPackage(p: CoreFontPackage, directory: File, progress: (Float)->Unit = {},
    transfer: FontTransfer = ::fetchCoreFontBytes,
    extractor: CoreFontExtractor = { bytes,names -> io.github.artcraftandroid.pdfcraft.core.extractCoreFontArchive(bytes,names).map { it.name to it.bytes } }): Unit =
    documentFontCacheMutex.withLock {
        if(corePackageInstalled(p,directory)) { progress(1f); return@withLock }
        val originals=File(directory,"core-font-packages").apply { mkdirs() }; val archive=File(originals,p.file)
        val bytes=if(verified(archive,p.size,p.sha256)) archive.readBytes() else transfer(p.url,p.sha256,2*1024*1024,p.size) {progress(it*.8f)}
        require(bytes.size==p.size && fontDigest(bytes)==p.sha256) { "Font package verification failed" }
        coroutineContext.ensureActive()
        val decoded=extractor(bytes,p.fonts.map {it.name}).toMap()
        require(decoded.size==p.fonts.size && p.fonts.all {face -> decoded[face.name]?.let {it.size==face.size && fontDigest(it)==face.sha256}==true }) { "Extracted font verification failed" }
        atomicFontWrite(archive,bytes)
        p.fonts.forEachIndexed {index,face ->
            coroutineContext.ensureActive(); atomicFontWrite(p.installedFile(directory,face),requireNotNull(decoded[face.name]))
            progress(.8f+.2f*(index+1)/p.fonts.size)
        }
    }

internal fun atomicFontWrite(file:File,bytes:ByteArray) {
    file.parentFile?.mkdirs(); val part=File.createTempFile("font-", ".part", file.parentFile)
    try { part.writeBytes(bytes); java.nio.file.Files.move(part.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    finally {part.delete()}
}
