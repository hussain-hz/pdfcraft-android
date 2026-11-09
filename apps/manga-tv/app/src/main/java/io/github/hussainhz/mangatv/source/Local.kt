package io.github.hussainhz.mangatv.source

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import io.github.hussainhz.mangatv.data.BookKind
import io.github.hussainhz.mangatv.data.BookRef
import io.github.hussainhz.mangatv.data.Entry
import io.github.hussainhz.mangatv.data.Listing
import io.github.hussainhz.mangatv.data.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local libraries. A folder that directly holds images is a chapter; a .cbz/.zip file is a
 * chapter; any other folder is a series/collection to browse into.
 */
object Local {

    // ---------- Plain files (internal storage, USB drives) ----------

    fun storageRoots(context: Context): List<File> {
        val roots = linkedSetOf<File>()
        @Suppress("DEPRECATION")
        roots += Environment.getExternalStorageDirectory()
        // USB drives / SD cards show up as /storage/XXXX-XXXX.
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "self" && it.name != "emulated" }
            ?.let(roots::addAll)
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            // .../Android/data/<pkg>/files → volume root
            val root = dir.path.substringBefore("/Android/data")
            File(root).takeIf { it.canRead() }?.let(roots::add)
        }
        return roots.toList()
    }

    suspend fun listDir(context: Context, path: String?): Listing = withContext(Dispatchers.IO) {
        if (path == null) {
            return@withContext Listing(
                title = null,
                entries = storageRoots(context).map {
                    Entry(title = it.name.ifEmpty { it.path }, subtitle = it.path, folder = Location.LocalDir(it.path))
                },
            )
        }
        val dir = File(path)
        val children = dir.listFiles()?.filterNot { it.name.startsWith(".") }.orEmpty()
            .sortedWith(compareBy(NaturalOrder) { it.name })
        val entries = mutableListOf<Entry>()
        val ownImages = children.filter { it.isFile && isImageName(it.name) }
        if (ownImages.isNotEmpty()) {
            entries += Entry(
                title = dir.name,
                subtitle = null,
                cover = ownImages.first(),
                book = BookRef(BookKind.LOCAL_DIR, dir.name, dir.path, cover = ownImages.first().path, pageCount = ownImages.size),
            )
        }
        for (child in children) {
            when {
                child.isDirectory -> {
                    val inner = child.listFiles()?.filterNot { it.name.startsWith(".") }.orEmpty()
                    val images = inner.filter { it.isFile && isImageName(it.name) }.sortedWith(compareBy(NaturalOrder) { it.name })
                    val hasSubfolders = inner.any { it.isDirectory || isZipName(it.name) }
                    if (images.isNotEmpty() && !hasSubfolders) {
                        entries += Entry(
                            title = child.name,
                            cover = images.first(),
                            book = BookRef(BookKind.LOCAL_DIR, child.name, child.path, cover = images.first().path, pageCount = images.size),
                        )
                    } else if (inner.isNotEmpty()) {
                        entries += Entry(
                            title = child.name,
                            cover = images.firstOrNull() ?: findCover(child),
                            folder = Location.LocalDir(child.path),
                        )
                    }
                }
                child.isFile && isZipName(child.name) -> entries += Entry(
                    title = child.nameWithoutExtension,
                    cover = Archives.cachedCover(context, child.path),
                    book = BookRef(BookKind.LOCAL_ZIP, child.nameWithoutExtension, child.path),
                )
            }
        }
        Listing(title = dir.name, entries = entries)
    }

    /** A cover for a series folder: the first image of its first chapter folder, if cheap to find. */
    private fun findCover(dir: File): File? {
        val first = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.minWithOrNull(compareBy(NaturalOrder) { it.name }) ?: return null
        return first.listFiles()?.filter { it.isFile && isImageName(it.name) }
            ?.minWithOrNull(compareBy(NaturalOrder) { it.name })
    }

    fun dirPages(path: String): List<File> =
        File(path).listFiles()?.filter { it.isFile && isImageName(it.name) }.orEmpty()
            .sortedWith(compareBy(NaturalOrder) { it.name })

    // ---------- Storage Access Framework (folders picked with the system picker) ----------

    data class Doc(val id: String, val name: String, val mime: String) {
        val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    fun rootDocumentId(treeUri: String): String = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))

    fun documentUri(treeUri: String, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId)

    private fun children(context: Context, treeUri: String, docId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(treeUri), docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = mutableListOf<Doc>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                out += Doc(c.getString(0), name, c.getString(2).orEmpty())
            }
        }
        return out.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    suspend fun listSaf(context: Context, treeUri: String, docId: String, title: String?): Listing =
        withContext(Dispatchers.IO) {
            val kids = children(context, treeUri, docId)
            val entries = mutableListOf<Entry>()
            val ownImages = kids.filter { !it.isDir && isImageName(it.name) }
            if (ownImages.isNotEmpty()) {
                val cover = documentUri(treeUri, ownImages.first().id).toString()
                entries += Entry(
                    title = title ?: "",
                    cover = cover,
                    book = BookRef(BookKind.SAF_DIR, title ?: "", docId, cover = cover, pageCount = ownImages.size, treeUri = treeUri),
                )
            }
            for (kid in kids) {
                when {
                    kid.isDir -> {
                        val inner = children(context, treeUri, kid.id)
                        val images = inner.filter { !it.isDir && isImageName(it.name) }
                        val hasSub = inner.any { it.isDir || isZipName(it.name) }
                        val cover = images.firstOrNull()?.let { documentUri(treeUri, it.id).toString() }
                        entries += if (images.isNotEmpty() && !hasSub) Entry(
                            title = kid.name,
                            cover = cover,
                            book = BookRef(BookKind.SAF_DIR, kid.name, kid.id, cover = cover, pageCount = images.size, treeUri = treeUri),
                        ) else Entry(title = kid.name, cover = cover, folder = Location.SafDir(treeUri, kid.id))
                    }
                    isZipName(kid.name) -> {
                        val uri = documentUri(treeUri, kid.id).toString()
                        entries += Entry(
                            title = kid.name.substringBeforeLast('.'),
                            cover = Archives.cachedCover(context, uri),
                            book = BookRef(BookKind.SAF_ZIP, kid.name.substringBeforeLast('.'), uri, treeUri = treeUri),
                        )
                    }
                }
            }
            Listing(title = title, entries = entries)
        }

    suspend fun safDirPages(context: Context, treeUri: String, docId: String): List<Uri> = withContext(Dispatchers.IO) {
        children(context, treeUri, docId).filter { !it.isDir && isImageName(it.name) }
            .map { documentUri(treeUri, it.id) }
    }
}
