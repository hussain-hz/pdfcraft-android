package io.github.hussainhz.mangatv.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.hussainhz.mangatv.R
import io.github.hussainhz.mangatv.data.Entry
import io.github.hussainhz.mangatv.data.Location
import io.github.hussainhz.mangatv.data.Store
import io.github.hussainhz.mangatv.source.BookLoader
import io.github.hussainhz.mangatv.source.Opds
import kotlinx.coroutines.launch

/** Lives on the [Screen.Browse] object so the listing and focus survive opening the reader. */
class BrowseState {
    var entries by mutableStateOf<List<Entry>>(emptyList())
    var loaded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var nextPage by mutableStateOf<String?>(null)
    var searchLink by mutableStateOf<String?>(null)
    var focusedIndex by mutableIntStateOf(0)
}

@Composable
fun BrowseScreen(nav: Navigator, screen: Screen.Browse) {
    val context = LocalContext.current
    val state = screen.state
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = state.focusedIndex)
    val focusTarget = remember { FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }

    suspend fun load(more: Boolean) {
        if (state.loading) return
        state.loading = true
        state.error = null
        try {
            val location = screen.location
            if (more) {
                val next = state.nextPage ?: return
                val page = Opds.load((location as Location.Opds).serverId, next)
                state.entries = state.entries + page.entries
                state.nextPage = page.nextPage
            } else {
                val listing = BookLoader.list(context, location, screen.title)
                state.entries = listing.entries
                state.nextPage = listing.nextPage
                state.searchLink = listing.searchLink
                state.loaded = true
            }
        } catch (e: Exception) {
            if (!more) state.error = e.message ?: e.javaClass.simpleName
        } finally {
            state.loading = false
        }
    }

    LaunchedEffect(Unit) { if (!state.loaded) load(false) }

    // Infinite scroll for paged OPDS feeds.
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.entries.isNotEmpty() && last >= state.entries.size - 8
        }
    }
    LaunchedEffect(nearEnd, state.nextPage) { if (nearEnd && state.nextPage != null) load(true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                screen.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.searchLink != null) {
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { androidx.compose.material3.Text(stringResource(R.string.search_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            val q = query.trim()
                            val link = state.searchLink
                            val loc = screen.location
                            if (q.isNotEmpty() && link != null && loc is Location.Opds) {
                                scope.launch {
                                    runCatching { Opds.searchUrl(link, q) }
                                        .onSuccess { url ->
                                            searching = false
                                            nav.push(Screen.Browse(Location.Opds(loc.serverId, url), "${context.getString(R.string.search)}: $q"))
                                        }
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }
                            }
                        }),
                        modifier = Modifier.width(420.dp).focusRequester(searchFocus),
                    )
                    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                } else {
                    Button(onClick = { searching = true }) { Text("⌕  " + stringResource(R.string.search)) }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                state.error != null -> CenteredMessage(
                    stringResource(R.string.error_prefix, state.error!!),
                    stringResource(R.string.retry),
                ) { scope.launch { load(false) } }
                !state.loaded -> CenteredMessage(stringResource(R.string.loading))
                state.entries.isEmpty() -> CenteredMessage(stringResource(R.string.nothing_here))
                else -> {
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(state.entries) { index, entry ->
                            val book = entry.book
                            val saved = book?.let { Store.progress[it.key] }
                            PosterCard(
                                title = entry.title,
                                cover = entry.cover,
                                subtitle = when {
                                    saved != null -> stringResource(R.string.progress_of, saved.page + 1, saved.pageCount)
                                    book != null && book.pageCount > 0 -> stringResource(R.string.pages_count, book.pageCount)
                                    else -> entry.subtitle
                                },
                                progress = saved?.let { (it.page + 1f) / it.pageCount.coerceAtLeast(1) },
                                width = 160.dp,
                                onClick = {
                                    state.focusedIndex = index
                                    if (entry.folder != null) {
                                        nav.push(Screen.Browse(entry.folder, entry.title))
                                    } else if (book != null) {
                                        val siblings = state.entries.mapNotNull { it.book }
                                        nav.push(Screen.Reader(book, siblings, saved?.page ?: 0))
                                    }
                                },
                                modifier = Modifier
                                    .onFocusChanged { if (it.hasFocus) state.focusedIndex = index }
                                    .then(if (index == state.focusedIndex) Modifier.focusRequester(focusTarget) else Modifier),
                            )
                        }
                    }
                    // Put focus back on the tile the user came from.
                    LaunchedEffect(Unit) {
                        if (grid.layoutInfo.visibleItemsInfo.none { it.index == state.focusedIndex }) {
                            grid.scrollToItem(state.focusedIndex)
                        }
                        runCatching { focusTarget.requestFocus() }
                    }
                }
            }
        }
    }
}
