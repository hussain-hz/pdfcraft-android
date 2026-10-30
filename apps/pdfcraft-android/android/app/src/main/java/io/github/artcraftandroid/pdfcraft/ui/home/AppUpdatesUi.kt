package io.github.artcraftandroid.pdfcraft.ui.home

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.*
import io.github.artcraftandroid.pdfcraft.updates.AppDownloadStatus
import io.github.artcraftandroid.pdfcraft.updates.AppUpdateStatus
import io.github.artcraftandroid.pdfcraft.updates.localizedReleaseNotes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val UpdateDownloadIcon = lucide("app-update-download", "M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5")

@Composable
internal fun AppUpdatesSection(vm: AppViewModel) {
    Text(stringResource(R.string.updates_section), color = pdfColors.accent, fontSize = 13.sp,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
    val checking = vm.updates.appUpdateStatus == AppUpdateStatus.Checking
    SheetRow(LucideIcons.RotateCw, stringResource(if (checking) R.string.updates_checking else R.string.updates_check),
        { vm.updates.checkAppUpdates() }, enabled = !checking)
    Text(updateMessage(vm), color = pdfColors.textSecondary, fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    if (vm.updates.availableAppUpdate != null || vm.updates.appUpdateDownload.status != AppDownloadStatus.Idle)
        SheetRow(UpdateDownloadIcon, stringResource(R.string.updates_details), { vm.updates.appUpdatesOpen = true }, chevron = true)
}

@Composable
private fun updateMessage(vm: AppViewModel): String = when (val status = vm.updates.appUpdateStatus) {
    AppUpdateStatus.Current -> stringResource(R.string.updates_current)
    AppUpdateStatus.Checking -> stringResource(R.string.updates_checking)
    is AppUpdateStatus.Failed -> stringResource(if (status.rateLimited) R.string.updates_rate_limited else R.string.updates_failed)
    is AppUpdateStatus.Available -> stringResource(if (status.update.downloadUrl == null) R.string.updates_no_apk else R.string.updates_available, "\u2066${status.update.version}\u2069")
    else -> stringResource(R.string.updates_manual_hint)
}

@Composable
internal fun AppUpdatesDialog(vm: AppViewModel) {
    val c = pdfColors
    val update = vm.updates.availableAppUpdate
    val locale = LocalConfiguration.current.locales[0]
    val notes = localizedReleaseNotes(update?.notes.orEmpty(), locale.language)
    Dialog(onDismissRequest = { vm.updates.appUpdatesOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(24.dp)).background(c.surface)
            .border(1.dp, c.divider, RoundedCornerShape(24.dp)).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                    Icon(UpdateDownloadIcon, null, tint = c.accent, modifier = Modifier.size(25.dp))
                }
                Text(stringResource(R.string.updates_section), color = c.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                BarIcon(LucideIcons.X, stringResource(R.string.viewer_close), { vm.updates.appUpdatesOpen = false })
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 18.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceRaised).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UpdateDetail(stringResource(R.string.updates_installed_label), vm.updates.appVersion, ltr = true)
                    if (update != null) {
                        UpdateDetail(stringResource(R.string.updates_new_version), update.version, ltr = true)
                        val published = runCatching {
                            Instant.parse(update.publishedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                        }.getOrNull()
                        if (published != null) UpdateDetail(stringResource(R.string.updates_published), published)
                        if (update.size > 0) UpdateDetail(stringResource(R.string.updates_size), Formatter.formatShortFileSize(LocalContext.current, update.size))
                    }
                }
                Text(updateMessage(vm), color = c.textSecondary, fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
                if (update != null) {
                    Text(stringResource(R.string.updates_whats_new), color = c.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(notes.ifBlank { stringResource(R.string.updates_notes_unavailable) }, color = c.text, fontSize = 15.sp, lineHeight = 25.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 18.dp))
                }
            }
            HorizontalDivider(color = c.divider)
            Spacer(Modifier.height(14.dp))
            UpdateDownloadActions(vm)
        }
    }
}

@Composable
private fun UpdateDetail(label: String, value: String, ltr: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pdfColors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        CompositionLocalProvider(LocalLayoutDirection provides if (ltr) LayoutDirection.Ltr else LocalLayoutDirection.current) {
            Text(value, color = pdfColors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun UpdateButton(label: String, icon: ImageVector?, onClick: () -> Unit) {
    val c = pdfColors
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(25.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.button, contentColor = Color.White)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UpdateDownloadActions(vm: AppViewModel) {
    val c = pdfColors
    val update = vm.updates.availableAppUpdate
    val uri = LocalUriHandler.current
    when (val download = vm.updates.appUpdateDownload.status) {
        is AppDownloadStatus.Downloading -> {
            val fraction = if (download.total > 0) (download.bytes.toDouble() / download.total).coerceIn(0.0, 1.0).toFloat() else null
            Text(if (fraction == null) stringResource(R.string.updates_downloading) else stringResource(R.string.updates_progress, (fraction * 100).toInt()), color = c.text, fontSize = 14.sp)
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp), color = c.accent)
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), color = c.accent)
            TextButton(onClick = { vm.updates.appUpdateDownload.cancel() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = c.accent)) {
                Text(stringResource(R.string.updates_cancel_download))
            }
        }
        AppDownloadStatus.Verifying -> Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp)
            Text(stringResource(R.string.updates_verifying), color = c.text, fontSize = 14.sp)
        }
        is AppDownloadStatus.Ready -> {
            Text(stringResource(R.string.updates_ready, "\u2066${download.version}\u2069"), color = c.text, fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp))
            UpdateButton(stringResource(R.string.updates_install), UpdateDownloadIcon, { vm.updates.appUpdateDownload.install() })
        }
        else -> {
            if (download is AppDownloadStatus.Failed) Text(stringResource(if (download.incompatible) R.string.updates_incompatible else R.string.updates_download_failed),
                color = c.red, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            if (update?.downloadUrl != null) UpdateButton(stringResource(R.string.updates_download), UpdateDownloadIcon, { vm.updates.appUpdateDownload.start(update) })
            else if (update != null) {
                val error = stringResource(R.string.updates_open_failed)
                UpdateButton(stringResource(R.string.updates_view_release), null,
                    { runCatching { uri.openUri(update.pageUrl) }.onFailure { vm.tell(error) } })
            }
        }
    }
    val busy = vm.updates.appUpdateDownload.status is AppDownloadStatus.Downloading || vm.updates.appUpdateDownload.status == AppDownloadStatus.Verifying
    val checking = vm.updates.appUpdateStatus == AppUpdateStatus.Checking
    TextButton(onClick = { vm.updates.checkAppUpdates() }, enabled = !checking && !busy, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(contentColor = c.accent)) {
        if (checking) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
        else Icon(LucideIcons.RotateCw, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (checking) R.string.updates_checking else R.string.updates_check))
    }
}
