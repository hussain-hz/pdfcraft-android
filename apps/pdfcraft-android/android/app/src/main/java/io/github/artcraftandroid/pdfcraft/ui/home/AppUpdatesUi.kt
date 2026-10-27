package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.updates.AppDownloadStatus
import io.github.artcraftandroid.pdfcraft.updates.AppUpdateStatus

@Composable
internal fun AppUpdatesSection(vm: AppViewModel) {
    Text(stringResource(R.string.updates_section), color = pdfColors.accent, fontSize = 13.sp,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
    AppUpdateActions(vm)
}

@Composable
internal fun AppUpdatesDialog(vm: AppViewModel) {
    PdfDialog(onDismiss = { vm.appUpdatesOpen = false }, title = stringResource(R.string.updates_section),
        confirm = null, dismiss = stringResource(R.string.common_ok)) {
        Text(stringResource(R.string.updates_installed, vm.appVersion), color = pdfColors.textSecondary, fontSize = 13.sp)
        AppUpdateActions(vm)
    }
}

@Composable
private fun AppUpdateActions(vm: AppViewModel) {
    val c = pdfColors
    val uri = LocalUriHandler.current
    val status = vm.appUpdateStatus
    val download = vm.appUpdateDownload.status
    val checking = status == AppUpdateStatus.Checking
    val busy = download is AppDownloadStatus.Downloading || download == AppDownloadStatus.Verifying
    SheetRow(LucideIcons.RotateCw, stringResource(if (checking) R.string.updates_checking else R.string.updates_check),
        { vm.checkAppUpdates() }, enabled = !checking && !busy) {
        if (checking) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
    }
    val update = vm.availableAppUpdate
    val message = when (status) {
        AppUpdateStatus.Current -> stringResource(R.string.updates_current)
        is AppUpdateStatus.Failed -> stringResource(if (status.rateLimited) R.string.updates_rate_limited else R.string.updates_failed)
        is AppUpdateStatus.Available -> stringResource(if (status.update.downloadUrl == null) R.string.updates_no_apk else R.string.updates_available, status.update.version)
        else -> stringResource(R.string.updates_manual_hint)
    }
    Text(message, color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    when (download) {
        is AppDownloadStatus.Downloading -> {
            val fraction = if (download.total > 0) (download.bytes.toDouble() / download.total).coerceIn(0.0, 1.0).toFloat() else null
            Text(if (fraction == null) stringResource(R.string.updates_downloading) else stringResource(R.string.updates_progress, (fraction * 100).toInt()),
                color = c.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = c.accent)
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(20.dp), color = c.accent)
            SheetRow(null, stringResource(R.string.updates_cancel_download), { vm.appUpdateDownload.cancel() })
        }
        AppDownloadStatus.Verifying -> Text(stringResource(R.string.updates_verifying), color = c.text, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
        is AppDownloadStatus.Ready -> {
            Text(stringResource(R.string.updates_ready, download.version), color = c.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            SheetRow(null, stringResource(R.string.updates_install), { vm.appUpdateDownload.install() }, chevron = true)
        }
        else -> {
            if (download is AppDownloadStatus.Failed) Text(stringResource(if (download.incompatible) R.string.updates_incompatible else R.string.updates_download_failed),
                color = c.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (update != null) {
                if (update.downloadUrl != null) SheetRow(null, stringResource(R.string.updates_download), { vm.appUpdateDownload.start(update) })
                else {
                    val error = stringResource(R.string.updates_open_failed)
                    SheetRow(null, stringResource(R.string.updates_view_release), { runCatching { uri.openUri(update.pageUrl) }.onFailure { vm.tell(error) } }, chevron = true)
                }
            }
        }
    }
}
