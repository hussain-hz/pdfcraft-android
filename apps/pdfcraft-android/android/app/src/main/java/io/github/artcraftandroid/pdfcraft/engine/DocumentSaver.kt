package io.github.artcraftandroid.pdfcraft.engine

import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes the whole save, including external publication and its recovery marker. */
class DocumentSaver(private val files: DocumentFiles, private val tell: (String) -> Unit) {
    suspend fun save(doc: OpenDocument, eng: Engine, quiet: Boolean = false): Boolean = doc.saveMutex.withLock {
        if (doc.closed) return@withLock false
        if (!doc.info.dirty && !doc.notWrittenBack) return@withLock true
        try {
            withContext(Dispatchers.IO) { files.recovery.mark(doc.file) }
            doc.notWrittenBack = true
            if (eng.command { document(doc.id) }.dirty) eng.tool("doc_save", mapOf("doc" to doc.id))
            doc.update(eng.command { document(doc.id) })
            val src = doc.source
            if (src?.scheme == "content" && !doc.writable) return@withLock false
            withContext(Dispatchers.IO) {
                files.recovery.publish(doc.file) {
                    if (src?.scheme == "content") files.writeBack(doc.file, src)
                    else if (src?.scheme == "file") {
                        val target = File(requireNotNull(src.path))
                        if (target.canonicalPath != doc.file.canonicalPath) atomicCopy(doc.file, target)
                    }
                }
            }
            doc.notWrittenBack = false
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            doc.writable = false
            doc.notWrittenBack = true
            if (!quiet) tell(e.userMessage)
            false
        }
    }

    suspend fun checkpoint(doc: OpenDocument, eng: Engine) = doc.saveMutex.withLock {
        if (doc.closed || !eng.command { document(doc.id) }.dirty) return@withLock
        withContext(Dispatchers.IO) { files.recovery.mark(doc.file) }
        doc.notWrittenBack = true
        eng.tool("doc_save", mapOf("doc" to doc.id))
        doc.update(eng.command { document(doc.id) })
    }
}

/** File URIs support an atomic rename; document providers instead keep a durable recovery copy. */
internal fun atomicCopy(source: File, target: File) {
    val tmp = File.createTempFile(".pdfcraft-", ".part", target.parentFile)
    try {
        source.inputStream().use { input -> java.io.FileOutputStream(tmp).use { output -> input.copyTo(output); output.fd.sync() } }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally { tmp.delete() }
}
