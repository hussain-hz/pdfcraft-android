package io.github.artcraftandroid.pdfcraft.updates

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray

/** SemVer order, including numeric prerelease identifiers (beta.11 follows beta.2). */
internal data class AppVersion(val major: Int, val minor: Int, val patch: Int, val pre: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for ((a, b) in listOf(major to other.major, minor to other.minor, patch to other.patch)) {
            if (a != b) return a.compareTo(b)
        }
        if (pre.isEmpty() || other.pre.isEmpty()) return when {
            pre.isEmpty() && other.pre.isEmpty() -> 0
            pre.isEmpty() -> 1
            else -> -1
        }
        for ((a, b) in pre.zip(other.pre)) {
            if (a == b) continue
            val an = a.all(Char::isDigit)
            val bn = b.all(Char::isDigit)
            return when {
                an && bn -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                an -> -1
                bn -> 1
                else -> a.compareTo(b)
            }
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        fun parse(value: String): AppVersion? {
            val normalized = value.removePrefix("preview-").removePrefix("v")
            val m = Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""").matchEntire(normalized) ?: return null
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return AppVersion(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[2].toIntOrNull() ?: return null, m.groupValues[3].toIntOrNull() ?: return null, pre)
        }
    }
}

internal data class UpdateAsset(val name: String, val url: String, val sha256: String? = null, val size: Long = 0)
internal data class AppRelease(val tag: String, val page: String, val preview: Boolean, val draft: Boolean, val assets: List<UpdateAsset>,
    val notes: String = "", val publishedAt: String = "")
data class AvailableAppUpdate(val version: String, val pageUrl: String, val downloadUrl: String?, val sha256: String? = null,
    val notes: String = "", val publishedAt: String = "", val size: Long = 0)

/** Release authors supply translations; select at display time so cached notes follow app language. */
internal fun localizedReleaseNotes(body: String, language: String): String {
    val bounded = body.take(32_000)
    val sections = Regex("<!-- pdfcraft:notes:([a-z]{2}) -->([\\s\\S]*?)<!-- /pdfcraft:notes -->")
        .findAll(bounded).associate { it.groupValues[1] to it.groupValues[2].trim() }
    val selected = if (sections.isNotEmpty()) sections[language] ?: sections["en"].orEmpty()
        else if (Regex("[\\u0600-\\u06ff]").containsMatchIn(bounded) == (language == "ar")) bounded else ""
    // Plain text only: no HTML, remote images or executable links in update details.
    return selected.replace(Regex("<!--[\\s\\S]*?-->"), "")
        .replace(Regex("<[^>]*>"), "")
        .replace(Regex("!?\\[([^]\\n]+)]\\([^\\n)]*\\)"), "$1")
        .replace("**", "").replace("`", "")
        .lineSequence().map { it.trim().replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^[-*]\\s+"), "• ") }
        .joinToString("\n").trim().take(12_000)
}

sealed interface AppUpdateStatus {
    data object Idle : AppUpdateStatus
    data object Checking : AppUpdateStatus
    data object Current : AppUpdateStatus
    data class Available(val update: AvailableAppUpdate) : AppUpdateStatus
    data class Failed(val rateLimited: Boolean = false) : AppUpdateStatus
}

/** Only repository-owned HTTPS release links can leave the checker. */
internal fun trustedReleaseUrl(url: String, download: Boolean): Boolean = runCatching {
    val uri = URI(url)
    val prefix = if (download) "/hussain-hz/pdfcraft-android/releases/download/" else "/hussain-hz/pdfcraft-android/releases/tag/"
    uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) && uri.port == -1 && uri.userInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.path.startsWith(prefix) && uri.path.length > prefix.length &&
        uri.path.split('/').none { it == "." || it == ".." }
}.getOrDefault(false)

internal fun compatibleApk(assets: List<UpdateAsset>, abis: List<String>): UpdateAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && trustedReleaseUrl(it.url, true) }
    fun abi(name: String): String = when {
        "universal" in name -> "universal"
        "arm64" in name || "aarch64" in name -> "arm64-v8a"
        "armeabi-v7a" in name || "armv7" in name -> "armeabi-v7a"
        "x86_64" in name || "x86-64" in name -> "x86_64"
        "x86" in name -> "x86"
        // release.yml names the ARM64 build pdfcraft-android-<version>.apk.
        else -> "arm64-v8a"
    }
    for (supported in abis) {
        apks.firstOrNull { abi(it.name.lowercase()) == supported }?.let { return it }
    }
    return apks.firstOrNull { abi(it.name.lowercase()) == "universal" }
}

internal fun selectAppUpdate(installed: String, abis: List<String>, releases: List<AppRelease>): AvailableAppUpdate? {
    val current = AppVersion.parse(installed) ?: throw IOException("Unknown installed version")
    val candidate = releases.asSequence().filter { !it.draft && !it.preview && !it.tag.startsWith("preview-") && trustedReleaseUrl(it.page, false) }
        .mapNotNull { release -> AppVersion.parse(release.tag)?.let { release to it } }
        .filter { (_, version) -> version > current && version.pre.isEmpty() }
        .filter { (release, _) -> release.assets.any { it.name.endsWith(".apk", true) && trustedReleaseUrl(it.url, true) } }
        .maxByOrNull { it.second } ?: return null
    val release = candidate.first
    val apk = compatibleApk(release.assets, abis)
    return AvailableAppUpdate(release.tag.removePrefix("preview-").removePrefix("v"), release.page, apk?.url, apk?.sha256,
        release.notes.take(32_000), release.publishedAt, apk?.size?.coerceAtLeast(0) ?: 0)
}

internal class UpdateRateLimited : IOException()

/** Anonymous release checks: documents and application data are never sent. Call on IO. */
internal object AppUpdateFeed {
    fun fetch(): List<AppRelease> {
        val connection = URL("https://api.github.com/repos/hussain-hz/pdfcraft-android/releases?per_page=30").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "PdfCraft-Android-Update-Check")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (connection.responseCode == 403 || connection.responseCode == 429) throw UpdateRateLimited()
            if (connection.responseCode != 200) throw IOException("Release request failed")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 1_048_576) throw IOException("Release response too large")
                    bytes.write(buffer, 0, count)
                }
            }
            val array = JSONArray(bytes.toString(Charsets.UTF_8.name()))
            return (0 until array.length()).mapNotNull { index ->
                val release = array.optJSONObject(index) ?: return@mapNotNull null
                val assets = release.optJSONArray("assets") ?: JSONArray()
                AppRelease(release.optString("tag_name"), release.optString("html_url"), release.optBoolean("prerelease"), release.optBoolean("draft"),
                    (0 until assets.length()).mapNotNull { n -> assets.optJSONObject(n)?.let { UpdateAsset(it.optString("name"), it.optString("browser_download_url"), it.optString("digest").removePrefix("sha256:").takeIf { hash -> Regex("[0-9a-fA-F]{64}").matches(hash) }, it.optLong("size").coerceAtLeast(0)) } },
                    release.optString("body").take(32_000), release.optString("published_at"))
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun cachedAppUpdate(installed: String, version: String?, page: String?, download: String?, hash: String?,
    notes: String = "", publishedAt: String = "", size: Long = 0): AvailableAppUpdate? {
    val current = AppVersion.parse(installed) ?: return null
    val newer = version?.let(AppVersion::parse) ?: return null
    if (newer.pre.isNotEmpty() || newer <= current || page == null || !trustedReleaseUrl(page, false)) return null
    if (download != null && !trustedReleaseUrl(download, true)) return null
    return AvailableAppUpdate(version, page, download, hash?.takeIf { Regex("[0-9a-fA-F]{64}").matches(it) },
        notes.take(32_000), publishedAt, size.coerceAtLeast(0))
}

internal fun shouldCheckOnOpen(previous: Long?, now: Long): Boolean = previous == null || now < previous || now - previous >= 60_000

internal fun compatibleUpdateIdentity(installedPackage: String, installedCode: Long, installedSigners: Set<String>,
    archivePackage: String, archiveCode: Long, archiveVersion: String?, expectedVersion: String, archiveSigners: Set<String>): Boolean {
    val expected = AppVersion.parse(expectedVersion) ?: return false
    return expected.pre.isEmpty() && AppVersion.parse(archiveVersion.orEmpty()) == expected && archivePackage == installedPackage &&
        archiveCode > installedCode && installedSigners.isNotEmpty() && installedSigners == archiveSigners
}
