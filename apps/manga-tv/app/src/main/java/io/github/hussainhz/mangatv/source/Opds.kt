package io.github.hussainhz.mangatv.source

import io.github.hussainhz.mangatv.data.BookKind
import io.github.hussainhz.mangatv.data.BookRef
import io.github.hussainhz.mangatv.data.Entry
import io.github.hussainhz.mangatv.data.Http
import io.github.hussainhz.mangatv.data.Listing
import io.github.hussainhz.mangatv.data.Location
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder

/**
 * OPDS 1.x catalogs with the OPDS Page Streaming Extension (OPDS-PSE), which Komga, Kavita
 * and others implement. PSE lets us fetch one page image at a time instead of whole archives.
 */
object Opds {
    private const val PSE_NS = "http://vaemendis.net/opds-pse/ns"
    private const val PSE_REL = "http://vaemendis.net/opds-pse/stream"
    private const val ACQ_REL = "http://opds-spec.org/acquisition"

    /** Placeholders that survive URL resolution (braces would be percent-encoded). */
    const val PAGE_TOKEN = "__MTV_PAGE__"
    const val WIDTH_TOKEN = "__MTV_WIDTH__"

    private val ZIP_TYPES = setOf(
        "application/x-cbz", "application/vnd.comicbook+zip", "application/zip",
        "application/x-zip-compressed",
    )

    data class Link(val rel: String, val href: String, val type: String, val count: Int)

    private class RawEntry(var title: String = "", var author: String? = null, val links: MutableList<Link> = mutableListOf())

    suspend fun load(serverId: String, url: String): Listing = parse(serverId, url, Http.getString(url))

    fun parse(serverId: String, baseUrl: String, xml: String): Listing {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))

        var feedTitle: String? = null
        val feedLinks = mutableListOf<Link>()
        val entries = mutableListOf<RawEntry>()
        var current: RawEntry? = null
        var depth = 0
        var textTarget: String? = null
        val text = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "entry" -> current = RawEntry()
                        "link" -> {
                            val link = Link(
                                rel = parser.getAttributeValue(null, "rel").orEmpty(),
                                href = parser.getAttributeValue(null, "href").orEmpty(),
                                type = parser.getAttributeValue(null, "type").orEmpty(),
                                count = parser.getAttributeValue(PSE_NS, "count")?.toIntOrNull()
                                    ?: parser.getAttributeValue(null, "count")?.toIntOrNull() ?: 0,
                            )
                            if (link.href.isNotEmpty()) (current?.links ?: feedLinks).add(link)
                        }
                        "title" -> if (current != null || depth == 2) { textTarget = "title"; text.clear() }
                        "name" -> if (current != null) { textTarget = "name"; text.clear() }
                    }
                }
                XmlPullParser.TEXT -> if (textTarget != null) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "entry" -> { current?.let(entries::add); current = null }
                        "title" -> if (textTarget == "title") {
                            val value = text.toString().trim()
                            if (current != null) current.title = value else feedTitle = value
                            textTarget = null
                        }
                        "name" -> if (textTarget == "name") {
                            current?.author = text.toString().trim().ifEmpty { null }
                            textTarget = null
                        }
                    }
                    depth--
                }
            }
        }

        val result = entries.mapNotNull { toEntry(serverId, baseUrl, it) }
        return Listing(
            title = feedTitle,
            entries = result,
            nextPage = feedLinks.firstOrNull { it.rel == "next" }?.let { resolve(baseUrl, it.href) },
            searchLink = feedLinks.firstOrNull { it.rel == "search" }?.let {
                val resolved = resolve(baseUrl, it.href.replace("{searchTerms}", "__MTV_Q__"))
                if (it.type.contains("opensearchdescription")) "osd:$resolved" else resolved
            },
        )
    }

    private fun toEntry(serverId: String, baseUrl: String, raw: RawEntry): Entry? {
        val cover = (raw.links.firstOrNull { it.rel == "http://opds-spec.org/image/thumbnail" }
            ?: raw.links.firstOrNull { it.rel == "http://opds-spec.org/image" }
            ?: raw.links.firstOrNull { it.rel.contains("thumbnail") })
            ?.let { resolve(baseUrl, it.href) }

        val pse = raw.links.firstOrNull { it.rel == PSE_REL }
        if (pse != null && pse.count > 0) {
            val template = resolve(
                baseUrl,
                pse.href.replace("{pageNumber}", PAGE_TOKEN).replace("{maxWidth}", WIDTH_TOKEN),
            )
            return Entry(
                title = raw.title,
                subtitle = raw.author,
                cover = cover,
                book = BookRef(BookKind.OPDS_PSE, raw.title, template, serverId, cover, pse.count),
            )
        }

        val zip = raw.links.firstOrNull { it.rel.startsWith(ACQ_REL) && it.type.substringBefore(';').trim() in ZIP_TYPES }
        if (zip != null) {
            val href = resolve(baseUrl, zip.href)
            return Entry(
                title = raw.title,
                subtitle = raw.author,
                cover = cover,
                book = BookRef(BookKind.OPDS_FILE, raw.title, href, serverId, cover),
            )
        }

        val nav = raw.links.firstOrNull { it.type.contains("atom+xml") && !it.rel.startsWith(ACQ_REL) }
            ?: raw.links.firstOrNull { it.type.contains("atom+xml") }
        if (nav != null) {
            return Entry(
                title = raw.title,
                subtitle = raw.author,
                cover = cover,
                folder = Location.Opds(serverId, resolve(baseUrl, nav.href)),
            )
        }
        return null // e.g. EPUB-only or PDF-only books; not supported by this reader.
    }

    /** Turns a search link (see [Listing.searchLink]) into a feed URL for [query]. */
    suspend fun searchUrl(searchLink: String, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        if (!searchLink.startsWith("osd:")) return searchLink.replace("__MTV_Q__", encoded)
        val descriptionUrl = searchLink.removePrefix("osd:")
        val xml = Http.getString(descriptionUrl)
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var template: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Url") {
                val type = parser.getAttributeValue(null, "type").orEmpty()
                val t = parser.getAttributeValue(null, "template") ?: continue
                if (template == null || type.contains("atom")) template = t
            }
        }
        val t = template ?: error("no search template")
        // Drop optional OpenSearch parameters such as {startPage?} before resolving.
        val cleaned = t.replace("{searchTerms}", "__MTV_Q__").replace(Regex("\\{[^}]*\\?\\}"), "")
        return resolve(descriptionUrl, cleaned).replace("__MTV_Q__", encoded)
    }

    fun resolve(base: String, href: String): String =
        base.toHttpUrlOrNull()?.resolve(href)?.toString() ?: href

    fun pageUrl(template: String, index: Int, maxWidth: Int = 1920): String =
        template.replace(PAGE_TOKEN, index.toString()).replace(WIDTH_TOKEN, maxWidth.toString())
}
