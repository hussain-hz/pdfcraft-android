package io.github.hussainhz.mangatv.source

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * CBZ/ZIP chapters are unpacked once into the app cache so the reader can page through them
 * as plain image files. Only the most recent few archives are kept.
 */
object Archives {
    private const val KEEP = 6
    private const val DONE = ".complete"

    private fun dirFor(context: Context, key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "archives/$hash")
    }

    /** First page of an archive that was already unpacked, or null. Never does I/O-heavy work. */
    fun cachedCover(context: Context, key: String): File? {
        val dir = dirFor(context, key)
        if (!File(dir, DONE).exists()) return null
        return pagesIn(dir).firstOrNull()
    }

    suspend fun pages(
        context: Context,
        key: String,
        open: () -> InputStream,
        onProgress: (Int) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        val dir = dirFor(context, key)
        if (File(dir, DONE).exists()) {
            dir.setLastModified(System.currentTimeMillis())
            return@withContext pagesIn(dir)
        }
        dir.deleteRecursively()
        dir.mkdirs()
        var count = 0
        open().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    coroutineContext.ensureActive()
                    if (entry.isDirectory || !isImageName(entry.name) || entry.name.contains("__MACOSX")) continue
                    // Flatten nested paths but keep their order: "ch1/001.jpg" → "ch1~001.jpg".
                    val safe = entry.name.replace('\\', '/').trim('/').replace("/", "~").replace("..", "_")
                    File(dir, safe).outputStream().use { zip.copyTo(it) }
                    count++
                    onProgress(count)
                }
            }
        }
        File(dir, DONE).createNewFile()
        prune(context)
        pagesIn(dir)
    }

    private fun pagesIn(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && isImageName(it.name) }.orEmpty()
            .sortedWith(compareBy(NaturalOrder) { it.name })

    private fun prune(context: Context) {
        val all = File(context.cacheDir, "archives").listFiles()?.filter { it.isDirectory } ?: return
        all.sortedByDescending { it.lastModified() }.drop(KEEP).forEach { it.deleteRecursively() }
    }

    fun openUri(context: Context, uri: String): InputStream =
        context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("cannot open $uri")
}
