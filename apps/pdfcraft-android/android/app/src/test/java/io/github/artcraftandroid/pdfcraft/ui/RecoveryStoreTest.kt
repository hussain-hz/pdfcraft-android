package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RecoveryStoreTest {
    @Test fun pendingEditsSurviveProcessRestartAndFailedPublication() {
        val root = Files.createTempDirectory("jahfan-recovery").toFile()
        try {
            val file = File(root, "document.pdf").apply { writeText("edited PDF") }
            RecoveryStore().mark(file)
            // Recreating the store models a fresh process after an interrupted external write.
            assertTrue(RecoveryStore().pending(file))
            assertEquals("edited PDF", file.readText())
            RecoveryStore().complete(file)
            assertFalse(RecoveryStore().pending(file))
        } finally { root.deleteRecursively() }
    }
    @Test fun cleanupKeepsRecentSharesAndRemovesExpiredOnes() {
        val root = Files.createTempDirectory("jahfan-share").toFile()
        try {
            val recent = File(root, "recent").apply { mkdirs() }
            val old = File(root, "old").apply { mkdirs() }
            File(recent, "first.pdf").writeText("first recipient")
            File(old, "old.pdf").writeText("expired")
            val now = System.currentTimeMillis()
            old.setLastModified(now - 8L * 24 * 60 * 60 * 1000)
            cleanOldShares(root, now)
            assertTrue(File(recent, "first.pdf").exists())
            assertFalse(old.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun structuralChangesInvalidateAllButTextDoesNotReloadAnnotations() {
        assertEquals(MetadataRefresh.Content, metadataFor("text_edit"))
        assertEquals(MetadataRefresh.Comments, metadataFor("comment_edit"))
        assertEquals(MetadataRefresh.All, metadataFor("page_delete"))
        assertEquals(MetadataRefresh.All, metadataFor("edit_undo"))
        assertEquals(MetadataRefresh.All, metadataFor("unknown_future_tool"))
    }
    @Test fun failedExternalWriteRetainsRecoveryUntilSuccessfulRetry() {
        val root = Files.createTempDirectory("jahfan-failure").toFile()
        try {
            val source = File(root, "working.pdf").apply { writeText("complete edited PDF") }
            val destination = File(root, "original.pdf").apply { writeText("old PDF") }
            try {
                RecoveryStore().publish(source) {
                    destination.writeText("partial")
                    throw java.io.IOException("simulated full storage")
                }
                fail("publication should fail")
            } catch (_: java.io.IOException) { }
            assertTrue(RecoveryStore().pending(source))
            assertEquals("complete edited PDF", source.readText())
            RecoveryStore().publish(source) { atomicCopy(source, destination) }
            assertEquals(source.readText(), destination.readText())
            assertFalse(RecoveryStore().pending(source))
        } finally { root.deleteRecursively() }
    }
}
