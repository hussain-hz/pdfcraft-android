package io.github.artcraftandroid.pdfcraft.updates

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val MAX_UPDATE_BYTES = 256L * 1024 * 1024

/** GitHub's release asset redirects only; never downgrade TLS or follow arbitrary hosts. */
internal fun trustedUpdateRedirect(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443) &&
        uri.host?.lowercase() in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
}.getOrDefault(false)

internal fun openUpdateConnection(url: String, open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): HttpURLConnection {
    require(trustedReleaseUrl(url, true))
    var target = URL(url)
    repeat(6) {
        val connection = open(target)
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "PdfCraft-Android-Update-Download")
            connection.setRequestProperty("Accept-Encoding", "identity")
            when (connection.responseCode) {
                200 -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    val next = URL(target, location)
                    if (!trustedUpdateRedirect(next.toString())) throw IOException("Untrusted redirect")
                    target = next
                }
                else -> throw IOException("Update HTTP ${connection.responseCode}")
            }
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }
        connection.disconnect()
    }
    throw IOException("Too many redirects")
}

/** Check cancellation and size while streaming; truncated responses never reach APK verification. */
internal suspend fun copyUpdateBody(input: InputStream, output: OutputStream, expected: Long,
    progress: suspend (Long) -> Unit): Long {
    var bytes = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        bytes += count
        if (bytes > MAX_UPDATE_BYTES || (expected > 0 && bytes > expected)) throw IOException("Update size exceeded")
        output.write(buffer, 0, count)
        progress(bytes)
    }
    if (bytes == 0L || (expected > 0 && bytes != expected)) throw IOException("Incomplete update")
    return bytes
}

internal fun systemDownloadStalled(lastProgress: Long, now: Long): Boolean = now - lastProgress >= 30_000
