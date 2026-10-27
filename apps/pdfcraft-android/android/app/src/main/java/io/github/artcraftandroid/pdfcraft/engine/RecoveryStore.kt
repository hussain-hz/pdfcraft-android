package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A durable marker protects the engine's working copy until publication succeeds. */
class RecoveryStore {
    private fun marker(file: File) = File(file.parentFile, ".${file.name}.recovery")
    fun pending(file: File): Boolean = file.isFile && marker(file).isFile
    fun mark(file: File) {
        val target = marker(file)
        if (target.isFile) return
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(byteArrayOf(1)); it.fd.sync() }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    fun publish(file: File, write: () -> Unit) {
        mark(file)
        write()
        complete(file)
    }
    fun complete(file: File) { Files.deleteIfExists(marker(file).toPath()) }
}

/** Never remove a recent hand-off: receivers can read granted URIs asynchronously. */
internal fun cleanOldShares(root: File, now: Long = System.currentTimeMillis()) {
    val day = 24 * 60 * 60 * 1000L
    val dirs = root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.lastModified() }
    var bytes = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    for (dir in dirs) {
        val age = now - dir.lastModified()
        if (age > 7 * day || (age > day && bytes > 256L * 1024 * 1024)) {
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) bytes -= size
        }
    }
}
