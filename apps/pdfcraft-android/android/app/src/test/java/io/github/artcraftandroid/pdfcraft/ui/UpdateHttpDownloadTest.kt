package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.updates.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UpdateHttpDownloadTest {
    private val start = "https://github.com/hussain-hz/pdfcraft-android/releases/download/v0.0.2/app.apk"
    private class Response(url: URL, private val code: Int, private val location: String? = null) : HttpURLConnection(url) {
        var closed = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() {}
    }

    @Test fun githubAssetRedirectPreservesTlsAndTimeouts() {
        val redirect = Response(URL(start), 302, "https://release-assets.githubusercontent.com/github-production-release-asset/a?token=example")
        val asset = Response(URL("https://release-assets.githubusercontent.com/a"), 200)
        var calls = 0
        assertSame(asset, openUpdateConnection(start) { if (calls++ == 0) redirect else asset })
        assertTrue(redirect.closed)
        assertEquals(15_000, asset.connectTimeout)
        assertEquals(15_000, asset.readTimeout)
        assertFalse(asset.instanceFollowRedirects)
    }

    @Test fun blockedRedirectAndHttpFailureCloseConnections() {
        for (location in listOf("http://github.com/file", "https://evil.test/file", "https://user@github.com/file", "https://github.com.evil.test/file")) {
            val response = Response(URL(start), 302, location)
            try { openUpdateConnection(start) { response }; fail("Accepted $location") } catch (_: IOException) { }
            assertTrue(response.closed)
        }
        val failure = Response(URL(start), 404)
        try { openUpdateConnection(start) { failure }; fail("Accepted HTTP 404") } catch (_: IOException) { }
        assertTrue(failure.closed)
        var calls = 0
        try { openUpdateConnection(start) { calls++; Response(it, 302, start) }; fail("Redirect loop") } catch (_: IOException) { }
        assertEquals(6, calls)
    }

    @Test fun completeStreamReportsRealBytes() = runBlocking {
        val output = ByteArrayOutputStream()
        var lastProgress = 0L
        assertEquals(3L, copyUpdateBody(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 3) { lastProgress = it })
        assertEquals(3L, lastProgress)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }

    @Test fun incompleteEmptyAndOversizedResponsesFail() = runBlocking {
        for ((bytes, expected) in listOf(byteArrayOf(1, 2) to 3L, byteArrayOf() to 0L, byteArrayOf(1, 2) to 1L)) {
            try { copyUpdateBody(ByteArrayInputStream(bytes), ByteArrayOutputStream(), expected) {}; fail("Accepted incomplete stream") }
            catch (_: IOException) { }
        }
    }

    @Test fun cancellationStopsBeforeWritingRemainingBytes() = runBlocking {
        val output = ByteArrayOutputStream()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            copyUpdateBody(ByteArrayInputStream(ByteArray(128 * 1024)), output, 128 * 1024L) { cancel() }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(output.size() < 128 * 1024)
    }

    @Test fun fallbackUsesTimeWithoutProgressNotTotalDownloadTime() {
        assertFalse(systemDownloadStalled(1_000, 30_999))
        assertTrue(systemDownloadStalled(1_000, 31_000))
        assertFalse(systemDownloadStalled(31_000, 31_500))
    }
}
