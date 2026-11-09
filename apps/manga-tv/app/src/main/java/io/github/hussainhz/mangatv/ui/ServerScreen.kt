package io.github.hussainhz.mangatv.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.hussainhz.mangatv.R
import io.github.hussainhz.mangatv.data.Server
import io.github.hussainhz.mangatv.data.Store
import io.github.hussainhz.mangatv.source.Opds
import kotlinx.coroutines.launch
import androidx.compose.material3.Text as M3Text

@Composable
fun ServerScreen(nav: Navigator, existing: Server?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url ?: "http://") }
    var user by remember { mutableStateOf(existing?.username.orEmpty()) }
    var pass by remember { mutableStateOf(existing?.password.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(if (existing == null) R.string.add_server else R.string.edit_source),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        val field = Modifier.width(620.dp)
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            label = { M3Text(stringResource(R.string.server_name)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = field.focusRequester(first),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim() }, singleLine = true,
            label = { M3Text(stringResource(R.string.server_url)) },
            supportingText = { M3Text(stringResource(R.string.server_url_help)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = field,
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it }, singleLine = true,
            label = { M3Text(stringResource(R.string.username)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = field,
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, singleLine = true,
            label = { M3Text(stringResource(R.string.password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = field,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(
                enabled = !busy && url.startsWith("http") && url.length > 8,
                onClick = {
                    val server = Server(
                        id = existing?.id.orEmpty(),
                        name = name.ifBlank { url.substringAfter("://").substringBefore('/') },
                        url = url, username = user.trim(), password = pass,
                    )
                    busy = true
                    Store.saveServer(server) // saved first so the auth interceptor can use it
                    val saved = Store.servers.last { it.url == server.url && it.name == server.name }
                    scope.launch {
                        // Check the catalog before going back, so typos show up right away.
                        val result = runCatching { Opds.load(saved.id, saved.url) }
                        busy = false
                        result.onSuccess { nav.pop() }.onFailure {
                            Toast.makeText(context, context.getString(R.string.error_prefix, it.message ?: "?"), Toast.LENGTH_LONG).show()
                        }
                    }
                },
            ) { Text(if (busy) stringResource(R.string.loading) else stringResource(R.string.save)) }
            OutlinedButton(onClick = { nav.pop() }) { Text(stringResource(R.string.cancel)) }
            if (existing != null) {
                OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
            }
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete) + ": " + existing.name,
            confirmLabel = stringResource(R.string.delete),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { Store.deleteServer(existing.id); confirmDelete = false; nav.pop() },
            onDismiss = { confirmDelete = false },
        )
    }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
