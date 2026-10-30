package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.artcraftandroid.pdfcraft.updates.AppUpdateDownload
import io.github.artcraftandroid.pdfcraft.updates.AppUpdateFeed
import io.github.artcraftandroid.pdfcraft.updates.AppUpdateStatus
import io.github.artcraftandroid.pdfcraft.updates.UpdateRateLimited
import io.github.artcraftandroid.pdfcraft.updates.cachedAppUpdate
import io.github.artcraftandroid.pdfcraft.updates.selectAppUpdate
import io.github.artcraftandroid.pdfcraft.updates.shouldCheckOnOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's own updates: checking GitHub for a newer release and downloading it (About, Home's banner). */
class AppUpdater(app: Application, private val scope: CoroutineScope, launchIntent: (Intent) -> Unit) {
    private val appPrefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** The app's version, as the About page shows it. */
    val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    var appUpdateStatus by mutableStateOf<AppUpdateStatus>(AppUpdateStatus.Idle)
        private set
    var availableAppUpdate by mutableStateOf(cachedAppUpdate(
        appVersion, appPrefs.getString("updateVersion", null), appPrefs.getString("updatePage", null),
        appPrefs.getString("updateDownload", null), appPrefs.getString("updateHash", null),
        appPrefs.getString("updateNotes", "").orEmpty(), appPrefs.getString("updatePublished", "").orEmpty(), appPrefs.getLong("updateSize", 0),
    ))
        private set
    var appUpdatesOpen by mutableStateOf(false)
    val appUpdateDownload = AppUpdateDownload(app, scope, launchIntent)
    private var lastUpdateCheck: Long? = null

    fun onAppOpened() {
        appUpdateDownload.onAppOpened()
        checkAppUpdates(automatic = true)
    }

    fun checkAppUpdates(automatic: Boolean = false) {
        val checking = AppUpdateStatus.Checking
        if (appUpdateStatus == checking) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (automatic && !shouldCheckOnOpen(lastUpdateCheck, now)) return
        lastUpdateCheck = now
        appUpdateStatus = checking
        scope.launch {
            try {
                val update = withContext(Dispatchers.IO) {
                    selectAppUpdate(appVersion, android.os.Build.SUPPORTED_ABIS.toList(), AppUpdateFeed.fetch())
                }
                availableAppUpdate = update
                appPrefs.edit {
                    putString("updateVersion", update?.version); putString("updatePage", update?.pageUrl)
                    putString("updateDownload", update?.downloadUrl); putString("updateHash", update?.sha256)
                    putString("updateNotes", update?.notes); putString("updatePublished", update?.publishedAt)
                    putLong("updateSize", update?.size ?: 0)
                }
                appUpdateStatus = if (update == null) AppUpdateStatus.Current else AppUpdateStatus.Available(update)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UpdateRateLimited) {
                appUpdateStatus = AppUpdateStatus.Failed(rateLimited = true)
            } catch (_: Exception) {
                appUpdateStatus = AppUpdateStatus.Failed()
            }
        }
    }
}
