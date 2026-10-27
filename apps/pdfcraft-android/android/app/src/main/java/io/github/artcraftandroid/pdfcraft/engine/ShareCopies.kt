package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Prepare one share atomically from the receiver's perspective; failed batches leave no copies. */
internal fun copyShareFiles(
    directory: File,
    files: List<Pair<File, String>>,
    allocate: (FileOutputStream, Long) -> Unit,
): List<File> {
    // Never clean up a directory belonging to a previous successful hand-off.
    if (!directory.mkdir()) throw IOException("Cannot create share directory")
    val inputs = mutableListOf<FileInputStream>()
    try {
        // An allocator may reclaim cached source paths. Keep all sources open before reserving.
        files.forEach { (source, _) -> inputs += source.inputStream() }
        return files.mapIndexed { index, (_, name) ->
            val input = inputs[index]
            val copy = File(directory, name)
            FileOutputStream(copy).use { output ->
                allocate(output, input.channel.size())
                input.copyTo(output)
                // Allocation extends the file; don't retain a zero-filled tail if the source shrank.
                output.channel.truncate(output.channel.position())
            }
            copy
        }
    } catch (e: Exception) {
        directory.deleteRecursively()
        throw e
    } finally {
        inputs.asReversed().forEach { runCatching { it.close() } }
    }
}
