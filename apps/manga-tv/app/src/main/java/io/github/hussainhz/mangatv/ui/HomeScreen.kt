package io.github.hussainhz.mangatv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.hussainhz.mangatv.R
import io.github.hussainhz.mangatv.data.Location
import io.github.hussainhz.mangatv.data.SafFolder
import io.github.hussainhz.mangatv.data.Store
import io.github.hussainhz.mangatv.source.Local

@Composable
fun HomeScreen(nav: Navigator) {
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    val openStorage = { nav.push(Screen.Browse(Location.LocalDir(null), context.getString(R.string.device_storage))) }

    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openStorage()
        else Toast.makeText(context, R.string.storage_permission_needed, Toast.LENGTH_LONG).show()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/')
                .ifEmpty { context.getString(R.string.folder) }
            Store.addFolder(SafFolder(uri.toString(), name))
        }
    }

    fun onDeviceStorage() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) openStorage()
                else {
                    val intents = listOf(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    )
                    val launched = intents.any { runCatching { context.startActivity(it); true }.getOrDefault(false) }
                    Toast.makeText(context, R.string.storage_permission_needed, Toast.LENGTH_LONG).show()
                    if (!launched) openStorage() // Some TVs have no such screen; show whatever is readable.
                }
            }
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED -> openStorage()
            else -> legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val recent = Store.recent()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = Accent, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.long_press_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (recent.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.continue_reading)) }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
                ) {
                    itemsIndexed(recent, key = { _, p -> p.book.key }) { index, p ->
                        PosterCard(
                            title = p.book.title,
                            cover = p.book.cover,
                            subtitle = stringResource(R.string.progress_of, p.page + 1, p.pageCount),
                            progress = if (p.pageCount > 0) (p.page + 1f) / p.pageCount else null,
                            onClick = { nav.push(Screen.Reader(p.book, emptyList(), p.page)) },
                            onLongClick = {
                                confirm = context.getString(R.string.remove) + ": " + p.book.title to { Store.forgetProgress(p.book.key) }
                            },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.sources)) }
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
            ) {
                item {
                    SourceCard(
                        glyph = "▤",
                        title = stringResource(R.string.device_storage),
                        subtitle = stringResource(R.string.device_storage_desc),
                        onClick = ::onDeviceStorage,
                        modifier = if (recent.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                items(Store.folders, key = { "f" + it.treeUri }) { folder ->
                    SourceCard(
                        glyph = "▣",
                        title = folder.name,
                        subtitle = stringResource(R.string.folder),
                        onClick = {
                            nav.push(Screen.Browse(Location.SafDir(folder.treeUri, Local.rootDocumentId(folder.treeUri)), folder.name))
                        },
                        onLongClick = {
                            confirm = context.getString(R.string.remove) + ": " + folder.name to { Store.removeFolder(folder.treeUri) }
                        },
                    )
                }
                items(Store.servers, key = { "s" + it.id }) { server ->
                    SourceCard(
                        glyph = "☁",
                        title = server.name,
                        subtitle = server.url,
                        onClick = { nav.push(Screen.Browse(Location.Opds(server.id, server.url), server.name)) },
                        onLongClick = { nav.push(Screen.EditServer(server)) },
                    )
                }
                item {
                    SourceCard(
                        glyph = "+",
                        title = stringResource(R.string.add_server),
                        subtitle = stringResource(R.string.add_server_desc),
                        onClick = { nav.push(Screen.EditServer(null)) },
                    )
                }
                item {
                    SourceCard(
                        glyph = "+",
                        title = stringResource(R.string.pick_folder),
                        subtitle = stringResource(R.string.pick_folder_desc),
                        onClick = {
                            try {
                                folderPicker.launch(null)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, R.string.no_picker, Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
            }
        }
    }

    confirm?.let { (title, action) ->
        ConfirmDialog(
            title = title,
            confirmLabel = stringResource(R.string.remove),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { action(); confirm = null },
            onDismiss = { confirm = null },
        )
    }

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(top = 4.dp))
}
