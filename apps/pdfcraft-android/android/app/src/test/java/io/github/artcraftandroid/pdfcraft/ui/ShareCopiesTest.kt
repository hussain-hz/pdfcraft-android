package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.copyShareFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ShareCopiesTest {
    @Test fun successfulBatchReservesAndCopiesWithoutChangingSources() {
        val root = Files.createTempDirectory("share-success").toFile()
        try {
            val source = File(root, "source.pdf").apply { writeText("complete document") }
            val reservations = mutableListOf<Long>()
            val copies = copyShareFiles(File(root, "batch"), listOf(source to "shared.pdf")) { output, bytes ->
                reservations += bytes
                output.channel.position(bytes + 20)
                output.write(0)
                output.channel.position(0)
            }
            assertEquals(listOf(source.length()), reservations)
            assertEquals("complete document", copies.single().readText())
            assertEquals(source.length(), copies.single().length())
            assertEquals("complete document", source.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun allocationFailureRemovesPartialBatchAndKeepsEarlierSharesAndSources() {
        val root = Files.createTempDirectory("share-full").toFile()
        try {
            val previous = File(root, "previous").apply { mkdir() }
            File(previous, "old.pdf").writeText("still being received")
            val sources = listOf("first", "second").map { name -> File(root, name).apply { writeText(name) } }
            val batch = File(root, "new-batch")
            var n = 0
            try {
                copyShareFiles(batch, sources.map { it to it.name }) { _, _ ->
                    if (++n == 2) throw IOException("simulated allocation failure")
                }
                fail("allocation must fail")
            } catch (_: IOException) { }
            assertFalse(batch.exists())
            assertEquals("still being received", File(previous, "old.pdf").readText())
            assertEquals(listOf("first", "second"), sources.map { it.readText() })
        } finally { root.deleteRecursively() }
    }

    @Test fun unreadableSourceFailsBeforeAllocationAndLeavesNoPartialCopy() {
        val root = Files.createTempDirectory("share-source").toFile()
        try {
            val source = File(root, "source.pdf").apply { writeText("original") }
            val batch = File(root, "batch")
            var allocated = false
            try {
                copyShareFiles(batch, listOf(source to "first.pdf", File(root, "missing") to "second.pdf")) { _, _ -> allocated = true }
                fail("missing source must fail")
            } catch (_: IOException) { }
            assertFalse(allocated)
            assertFalse(batch.exists())
            assertEquals("original", source.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun existingShareDirectoryIsNeverOverwrittenOrDeleted() {
        val root = Files.createTempDirectory("share-existing").toFile()
        try {
            val batch = File(root, "batch").apply { mkdir() }
            val old = File(batch, "old.pdf").apply { writeText("old recipient") }
            try {
                copyShareFiles(batch, emptyList()) { _, _ -> }
                fail("existing batch must be refused")
            } catch (_: IOException) { }
            assertEquals("old recipient", old.readText())
        } finally { root.deleteRecursively() }
    }
}
