package io.github.artcraftandroid.pdfcraft.updates

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.pdfcraft.R
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppDownloadStatus {
    data object Idle : AppDownloadStatus
    data class Downloading(val bytes: Long, val total: Long) : AppDownloadStatus
    data object Verifying : AppDownloadStatus
    data class Ready(val version: String) : AppDownloadStatus
    data class Failed(val incompatible: Boolean = false) : AppDownloadStatus
}

internal fun verifiedDownloadHash(file: File, expected: String?): Boolean {
    if (expected == null) return true // Android signing verification below remains mandatory.
    if (!Regex("[0-9a-fA-F]{64}").matches(expected)) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }.equals(expected, true)
}

/** The system downloads in the background; polling, hashing and APK inspection never block the UI. */
class AppUpdateDownload(private val context: Context, private val scope: CoroutineScope, private val launchIntent: (Intent) -> Unit) {
    private val prefs = context.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private var watcher: Job? = null
    var status by mutableStateOf<AppDownloadStatus>(AppDownloadStatus.Idle)
        private set

    private fun file(): File? {
        val name = prefs.getString("file", null) ?: return null
        if (!Regex("PdfCraft-update-[0-9.]+-[0-9]+\\.apk").matches(name)) return null
        val parent = context.getExternalFilesDir(null)?.let { File(it, "updates") } ?: return null
        return File(parent, name).takeIf { it.canonicalFile.parentFile == parent.canonicalFile }
    }

    fun start(update: AvailableAppUpdate) {
        if (status is AppDownloadStatus.Downloading || status == AppDownloadStatus.Verifying) return
        val url = update.downloadUrl ?: return
        if (!trustedReleaseUrl(url, true) || AppVersion.parse(update.version)?.pre?.isEmpty() != true) return
        cancel()
        try {
            val name = "PdfCraft-update-${update.version}-${System.currentTimeMillis()}.apk"
            val request = DownloadManager.Request(url.toUri())
                .setTitle(context.getString(R.string.updates_download_title))
                .setDescription(update.version)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, "updates/$name")
            val id = manager.enqueue(request)
            prefs.edit { putLong("id", id); putString("file", name); putString("version", update.version); putString("hash", update.sha256) }
            status = AppDownloadStatus.Downloading(0, -1)
            watch(id)
        } catch (_: Exception) {
            status = AppDownloadStatus.Failed()
        }
    }

    fun cancel() {
        watcher?.cancel()
        val id = prefs.getLong("id", -1)
        if (id >= 0) runCatching { manager.remove(id) }
        prefs.edit { clear() }
        status = AppDownloadStatus.Idle
    }

    fun onAppOpened() {
        if (prefs.getBoolean("installPending", false) && !context.packageManager.canRequestPackageInstalls()) prefs.edit { remove("installPending") }
        if (status is AppDownloadStatus.Ready) {
            if (prefs.getBoolean("installPending", false)) install()
            return
        }
        if (watcher?.isActive == true) return
        val id = prefs.getLong("id", -1)
        if (id >= 0) watch(id)
    }

    private fun watch(id: Long) {
        watcher = scope.launch {
            try {
                val version = prefs.getString("version", null) ?: throw IllegalStateException()
                val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                if (current != null && (AppVersion.parse(current)?.let { it >= (AppVersion.parse(version) ?: it) } == true)) {
                    cancel()
                    return@launch
                }
                while (isActive) {
                    val result = withContext(Dispatchers.IO) {
                        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                            if (!cursor.moveToFirst()) throw IllegalStateException()
                            Triple(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                        }
                    }
                    when (result.first) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            status = AppDownloadStatus.Verifying
                            val apk = file() ?: throw IllegalStateException()
                            val valid = withContext(Dispatchers.IO) { validate(apk, version) }
                            if (!valid) {
                                cancel()
                                status = AppDownloadStatus.Failed(incompatible = true)
                            } else {
                                status = AppDownloadStatus.Ready(version)
                                if (prefs.getBoolean("installPending", false)) install()
                            }
                            return@launch
                        }
                        DownloadManager.STATUS_FAILED -> throw IllegalStateException()
                        else -> status = AppDownloadStatus.Downloading(result.second, result.third)
                    }
                    delay(750)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cancel()
                status = AppDownloadStatus.Failed()
            }
        }
    }

    private fun validate(apk: File, version: String): Boolean {
        if (!apk.isFile || !verifiedDownloadHash(apk, prefs.getString("hash", null))) return false
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val currentSigners = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val nextSigners = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        return compatibleUpdateIdentity(context.packageName, installed.longVersionCode, currentSigners,
            archive.packageName, archive.longVersionCode, archive.versionName, version, nextSigners)
    }

    fun install() {
        if (status !is AppDownloadStatus.Ready) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            prefs.edit { putBoolean("installPending", true) }
            launchIntent(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            return
        }
        prefs.edit { remove("installPending") }
        scope.launch {
            try {
                val apk = file() ?: throw IllegalStateException()
                val version = prefs.getString("version", null) ?: throw IllegalStateException()
                if (!withContext(Dispatchers.IO) { validate(apk, version) }) {
                    cancel()
                    status = AppDownloadStatus.Failed(incompatible = true)
                    return@launch
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                launchIntent(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = AppDownloadStatus.Failed()
            }
        }
    }
}
