package io.github.hussainhz.mangatv

import io.github.hussainhz.mangatv.source.NaturalOrder
import io.github.hussainhz.mangatv.source.isImageName
import io.github.hussainhz.mangatv.source.isZipName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun pagesSortByNumberNotText() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "Page 003.png", "page 20.png", "001.jpg")
        assertEquals(
            listOf("001.jpg", "1.jpg", "2.jpg", "10.jpg", "Page 003.png", "page 20.png"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun chaptersSortNaturally() {
        val names = listOf("Chapter 10", "Chapter 9", "Chapter 1", "Chapter 100")
        assertEquals(listOf("Chapter 1", "Chapter 9", "Chapter 10", "Chapter 100"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun fileTypes() {
        assertTrue(isImageName("a/b/001.JPG"))
        assertTrue(isImageName("x.webp"))
        assertFalse(isImageName("a/._001.jpg"))
        assertFalse(isImageName("ComicInfo.xml"))
        assertTrue(isZipName("Vol 1.cbz"))
        assertFalse(isZipName("Vol 1.cbr"))
    }
}
