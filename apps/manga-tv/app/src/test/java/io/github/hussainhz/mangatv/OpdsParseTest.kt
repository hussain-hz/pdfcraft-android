package io.github.hussainhz.mangatv

import io.github.hussainhz.mangatv.data.BookKind
import io.github.hussainhz.mangatv.data.Location
import io.github.hussainhz.mangatv.source.Opds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpdsParseTest {
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:pse="http://vaemendis.net/opds-pse/ns">
          <id>series</id>
          <title>My Series</title>
          <link rel="next" href="/opds/v1.2/series/1?page=1" type="application/atom+xml;profile=opds-catalog"/>
          <link rel="search" href="/opds/v1.2/search" type="application/opensearchdescription+xml"/>
          <entry>
            <title>Chapter 1</title>
            <author><name>Someone</name></author>
            <link rel="http://opds-spec.org/image/thumbnail" href="/opds/v1.2/books/a/thumbnail" type="image/jpeg"/>
            <link rel="http://opds-spec.org/acquisition" href="/opds/v1.2/books/a/file/ch1.cbz" type="application/vnd.comicbook+zip"/>
            <link rel="http://vaemendis.net/opds-pse/stream" href="/opds/v1.2/books/a/pages/{pageNumber}?zero_based=true" type="image/jpeg" pse:count="24"/>
          </entry>
          <entry>
            <title>Chapter 2 (zip only)</title>
            <link rel="http://opds-spec.org/acquisition" href="files/ch2.cbz" type="application/x-cbz"/>
          </entry>
          <entry>
            <title>Sub collection</title>
            <link rel="subsection" href="/opds/v1.2/collections/x" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          </entry>
          <entry>
            <title>An EPUB</title>
            <link rel="http://opds-spec.org/acquisition" href="/b.epub" type="application/epub+zip"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parsesKomgaStyleFeed() {
        val listing = Opds.parse("srv", "http://192.168.1.5:25600/opds/v1.2/series/1", feed)
        assertEquals("My Series", listing.title)
        assertEquals(3, listing.entries.size) // EPUB-only entry is skipped

        val pse = listing.entries[0]
        assertEquals("Chapter 1", pse.title)
        assertEquals("Someone", pse.subtitle)
        assertEquals("http://192.168.1.5:25600/opds/v1.2/books/a/thumbnail", pse.cover)
        val book = pse.book!!
        assertEquals(BookKind.OPDS_PSE, book.kind)
        assertEquals(24, book.pageCount)
        assertEquals(
            "http://192.168.1.5:25600/opds/v1.2/books/a/pages/0?zero_based=true",
            Opds.pageUrl(book.address, 0),
        )

        val zip = listing.entries[1].book!!
        assertEquals(BookKind.OPDS_FILE, zip.kind)
        assertEquals("http://192.168.1.5:25600/opds/v1.2/series/files/ch2.cbz", zip.address)

        val folder = listing.entries[2]
        assertNull(folder.book)
        assertEquals(Location.Opds("srv", "http://192.168.1.5:25600/opds/v1.2/collections/x"), folder.folder)

        assertEquals("http://192.168.1.5:25600/opds/v1.2/series/1?page=1", listing.nextPage)
        assertNotNull(listing.searchLink)
        assertEquals("osd:http://192.168.1.5:25600/opds/v1.2/search", listing.searchLink)
    }
}
