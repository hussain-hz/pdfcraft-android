package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.*
import org.junit.Assert.*
import org.junit.Test

class InlineRowsTest {
    private fun run(a: Int, b: Int, bold: Boolean) = InlineRun(a,b,"NotoSerif",12f,bold,false,listOf(0f,0.5f,0f))
    @Test fun editingNameKeepsTheFollowingStyle() {
        val before = "Name 123"
        val styles = listOf(run(0,5,true),run(5,8,false))
        val after = remapInlineRuns(before,"Name NEW 123",styles)
        assertTrue(after.first().bold)
        assertFalse(after.last().bold)
        assertEquals(9,after.last().start)
        assertEquals(12,after.last().end)
    }
    @Test fun replacementAndDeletionKeepSuffixColorAndWeight() {
        val styles=listOf(run(0,4,true),run(4,8,false))
        val replaced=remapInlineRuns("اسم 1234","عنوان 1234",styles)
        assertEquals(6,replaced.last().start)
        assertFalse(replaced.last().bold)
        assertEquals(listOf(0f,0.5f,0f),replaced.last().rgb)
        assertEquals(emptyList<InlineRun>(),remapInlineRuns("اسم 1234","",styles))
    }
    @Test fun directionComesFromTextAndNotArabicAppLocale() {
        assertFalse(inlineRtl("The E-Training Program"))
        assertTrue(inlineRtl("يهنئ برنامج التدريب"))
        assertFalse(inlineRtl("123 English العربية"))
        assertTrue(inlineRtl("123 العربية English"))
    }
}
