package io.github.hussainhz.mangatv.source

import android.content.Context
import io.github.hussainhz.mangatv.data.BookKind
import io.github.hussainhz.mangatv.data.BookRef
import io.github.hussainhz.mangatv.data.Http
import io.github.hussainhz.mangatv.data.Listing
import io.github.hussainhz.mangatv.data.Location
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Resolves a [BookRef] into an ordered list of Coil image models, one per page. */
object BookLoader {

    suspend fun pages(context: Context, book: BookRef, onProgress: (Int) -> Unit = {}): List<Any> = when (book.kind) {
        BookKind.OPDS_PSE -> (0 until book.pageCount).map { Opds.pageUrl(book.address, it) }
        BookKind.LOCAL_DIR -> Local.dirPages(book.address)
        BookKind.SAF_DIR -> Local.safDirPages(context, book.treeUri!!, book.address)
        BookKind.LOCAL_ZIP -> Archives.pages(context, book.address, { File(book.address).inputStream() }, onProgress)
        BookKind.SAF_ZIP -> Archives.pages(context, book.address, { Archives.openUri(context, book.address) }, onProgress)
        BookKind.OPDS_FILE -> Archives.pages(context, book.address, {
            val response = Http.client.newCall(Request.Builder().url(book.address).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP ${response.code}")
            }
            response.body!!.byteStream()
        }, onProgress)
    }

    suspend fun list(context: Context, location: Location, title: String?): Listing = when (location) {
        is Location.Opds -> Opds.load(location.serverId, location.url)
        is Location.LocalDir -> Local.listDir(context, location.path)
        is Location.SafDir -> Local.listSaf(context, location.treeUri, location.documentId, title)
    }
}
