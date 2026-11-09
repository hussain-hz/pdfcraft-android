package io.github.hussainhz.mangatv.data

import org.json.JSONObject

/** Where a listing comes from. */
sealed interface Location {
    /** An OPDS feed on a saved server. */
    data class Opds(val serverId: String, val url: String) : Location

    /** A plain directory on the device (internal storage, USB drive). `null` path = storage roots. */
    data class LocalDir(val path: String?) : Location

    /** A directory reached through the Storage Access Framework. */
    data class SafDir(val treeUri: String, val documentId: String) : Location
}

enum class BookKind { OPDS_PSE, OPDS_FILE, LOCAL_DIR, LOCAL_ZIP, SAF_DIR, SAF_ZIP }

/**
 * Everything needed to (re)open a book or chapter later, e.g. from "Continue reading".
 * [address] is a URL, a path or a document URI depending on [kind].
 */
data class BookRef(
    val kind: BookKind,
    val title: String,
    val address: String,
    val serverId: String? = null,
    val cover: String? = null,
    val pageCount: Int = 0,
    val treeUri: String? = null,
) {
    val key: String get() = "$kind|$serverId|$address"

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("title", title)
        .put("address", address)
        .putOpt("serverId", serverId)
        .putOpt("cover", cover)
        .put("pageCount", pageCount)
        .putOpt("treeUri", treeUri)

    companion object {
        fun fromJson(o: JSONObject) = BookRef(
            kind = BookKind.valueOf(o.getString("kind")),
            title = o.getString("title"),
            address = o.getString("address"),
            serverId = o.optString("serverId").ifEmpty { null },
            cover = o.optString("cover").ifEmpty { null },
            pageCount = o.optInt("pageCount"),
            treeUri = o.optString("treeUri").ifEmpty { null },
        )
    }
}

/** One tile in a listing: either a folder to open or a book to read. */
data class Entry(
    val title: String,
    val subtitle: String? = null,
    /** Any Coil model: URL string, File, content Uri… */
    val cover: Any? = null,
    val folder: Location? = null,
    val book: BookRef? = null,
)

data class Listing(
    val title: String?,
    val entries: List<Entry>,
    val nextPage: String? = null,
    val searchLink: String? = null,
)

data class Server(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
)

data class SafFolder(val treeUri: String, val name: String)

data class Progress(val book: BookRef, val page: Int, val pageCount: Int, val updatedAt: Long)

enum class ReadingMode { RTL, LTR, VERTICAL }

enum class FitMode { SCREEN, WIDTH }
