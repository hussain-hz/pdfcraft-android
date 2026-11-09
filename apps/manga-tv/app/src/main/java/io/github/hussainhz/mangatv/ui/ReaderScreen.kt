package io.github.hussainhz.mangatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.hussainhz.mangatv.R
import io.github.hussainhz.mangatv.data.BookRef
import io.github.hussainhz.mangatv.data.FitMode
import io.github.hussainhz.mangatv.data.ReadingMode
import io.github.hussainhz.mangatv.data.Store
import io.github.hussainhz.mangatv.source.BookLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Remote control mapping
 *  - ◀ ▶          turn pages (direction follows the reading mode: manga = right-to-left)
 *  - ▲ ▼          scroll a tall page / webtoon strip, then move to the previous / next page
 *  - OK, Menu      open the reader menu (mode, fit, jump, chapters)
 *  - CH+/CH−, ⏮⏭  previous / next page;  ⏪ ⏩  jump 10 pages
 *  - Back          close the menu, or leave the reader
 */
@Composable
fun ReaderScreen(nav: Navigator, screen: Screen.Reader) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }

    var book by remember { mutableStateOf(screen.book) }
    /** Page to open the current chapter at; -1 = last page. */
    var startPage by remember { mutableIntStateOf(screen.startPage) }
    var pages by remember { mutableStateOf<List<Any>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hud by remember { mutableIntStateOf(0) } // bumps to show the page counter briefly
    /** Set after one "past the end" press; a second press moves to the neighbouring chapter. */
    var armedChapterJump by remember { mutableIntStateOf(0) }

    val mode = Store.readingMode
    val fit = Store.fitMode
    val siblings = screen.siblings
    val chapterIndex = siblings.indexOfFirst { it.key == book.key }
    val nextChapter = siblings.getOrNull(chapterIndex + 1).takeIf { chapterIndex >= 0 }
    val prevChapter = siblings.getOrNull(chapterIndex - 1).takeIf { chapterIndex >= 0 }

    LaunchedEffect(book) {
        pages = null
        error = null
        prepared = 0
        try {
            val loaded = BookLoader.pages(context, book) { prepared = it }
            if (loaded.isEmpty()) error = context.getString(R.string.unsupported_book)
            page = if (startPage < 0) loaded.lastIndex.coerceAtLeast(0) else startPage.coerceIn(0, (loaded.size - 1).coerceAtLeast(0))
            pages = loaded
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    // Save progress and warm up the next pages.
    LaunchedEffect(pages, page) {
        val list = pages ?: return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        Store.saveProgress(book.copy(pageCount = list.size, cover = book.cover ?: list.first().toString()), page, list.size)
        val loader = SingletonImageLoader.get(context)
        for (i in page + 1..minOf(page + 3, list.lastIndex)) {
            loader.enqueue(ImageRequest.Builder(context).data(list[i]).build())
        }
    }

    LaunchedEffect(hint) { if (hint != null) { delay(3500); hint = null } }

    val pagedScroll = rememberScrollState()
    val strip = rememberLazyListState()
    val density = LocalDensity.current
    var viewportPx by remember { mutableIntStateOf(1080) }

    fun openChapter(target: BookRef, atEnd: Boolean) {
        armedChapterJump = 0
        startPage = if (atEnd) -1 else 0
        book = target
    }

    fun goTo(target: Int) {
        val list = pages ?: return
        val last = list.lastIndex
        when {
            target > last -> {
                if (nextChapter == null) { hint = context.getString(R.string.end_of_book); return }
                if (armedChapterJump == 1) openChapter(nextChapter, atEnd = false)
                else {
                    armedChapterJump = 1
                    hint = context.getString(if (mode == ReadingMode.RTL) R.string.end_of_chapter_rtl else R.string.end_of_chapter)
                }
            }
            target < 0 -> {
                if (prevChapter == null) return
                if (armedChapterJump == -1) openChapter(prevChapter, atEnd = true)
                else { armedChapterJump = -1; hint = context.getString(R.string.start_of_chapter) }
            }
            else -> {
                armedChapterJump = 0
                page = target
                hud++
                if (mode == ReadingMode.VERTICAL) scope.launch { strip.scrollToItem(target) }
            }
        }
    }

    BackHandler(enabled = menu) { menu = false }
    LaunchedEffect(menu) {
        runCatching { if (menu) menuFocus.requestFocus() else rootFocus.requestFocus() }
    }

    LaunchedEffect(page, book) { if (mode != ReadingMode.VERTICAL) pagedScroll.scrollTo(0) }

    // Webtoon mode: the current page follows the scroll position.
    LaunchedEffect(mode, pages) {
        if (mode == ReadingMode.VERTICAL && pages != null) {
            strip.scrollToItem(page)
            snapshotFlow { strip.firstVisibleItemIndex }.collect { if (it != page) { page = it; hud++ } }
        }
    }

    fun forward() = goTo(page + 1)
    fun backward() = goTo(page - 1)

    fun scrollOrTurn(down: Boolean) {
        val step = viewportPx * 0.8f
        scope.launch {
            when {
                mode == ReadingMode.VERTICAL -> {
                    val atEnd = !strip.canScrollForward
                    val atStart = !strip.canScrollBackward
                    if (down && atEnd) goTo(pages!!.size) else if (!down && atStart) goTo(-1)
                    else strip.animateScrollBy(if (down) step else -step)
                }
                fit == FitMode.WIDTH && (if (down) pagedScroll.canScrollForward else pagedScroll.canScrollBackward) ->
                    pagedScroll.animateScrollBy(if (down) step else -step)
                down -> forward()
                else -> backward()
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (menu || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (pages.isNullOrEmpty()) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> { if (mode == ReadingMode.RTL) backward() else forward(); true }
                    Key.DirectionLeft -> { if (mode == ReadingMode.RTL) forward() else backward(); true }
                    Key.DirectionDown -> { scrollOrTurn(down = true); true }
                    Key.DirectionUp -> { scrollOrTurn(down = false); true }
                    Key.ChannelDown, Key.PageDown, Key.MediaNext -> { forward(); true }
                    Key.ChannelUp, Key.PageUp, Key.MediaPrevious -> { backward(); true }
                    Key.MediaFastForward -> { goTo(minOf(page + 10, pages!!.lastIndex)); true }
                    Key.MediaRewind -> { goTo(maxOf(page - 10, 0)); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu, Key.Info -> { menu = true; true }
                    else -> false
                }
            },
    ) {
        val screenHeight = maxHeight
        viewportPx = with(density) { screenHeight.roundToPx() }
        val list = pages
        when {
            error != null -> CenteredMessage(stringResource(R.string.error_prefix, error!!))
            list == null -> CenteredMessage(
                if (prepared > 0) stringResource(R.string.downloading, prepared) else stringResource(R.string.loading)
            )
            mode == ReadingMode.VERTICAL -> LazyColumn(
                state = strip,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                userScrollEnabled = false,
            ) {
                itemsIndexed(list) { _, model ->
                    var loaded by remember { mutableStateOf(false) }
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        onSuccess = { loaded = true },
                        modifier = Modifier.fillMaxWidth(0.62f).then(if (loaded) Modifier else Modifier.heightIn(min = screenHeight)),
                    )
                }
            }
            fit == FitMode.WIDTH -> Box(Modifier.fillMaxSize().verticalScroll(pagedScroll)) {
                AsyncImage(
                    model = list[page],
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(min = screenHeight),
                )
            }
            else -> AsyncImage(
                model = list[page],
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Page counter, shown for a moment after each page turn.
        var hudVisible by remember { mutableStateOf(true) }
        LaunchedEffect(hud, book) { hudVisible = true; delay(2500); hudVisible = false }
        AnimatedVisibility(
            visible = (hudVisible || menu) && !list.isNullOrEmpty(),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Pill("${page + 1} / ${list?.size ?: 0}")
        }

        hint?.let {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 28.dp)) { Pill(it) }
        }

        if (menu) {
            ReaderMenu(
                title = book.title,
                page = page,
                count = list?.size ?: 0,
                mode = mode,
                fit = fit,
                hasPrev = prevChapter != null,
                hasNext = nextChapter != null,
                firstFocus = menuFocus,
                onMode = { Store.updateReadingMode(it) },
                onFit = { Store.updateFitMode(it) },
                onJump = { delta -> list?.let { goTo((page + delta).coerceIn(0, it.lastIndex)) } },
                onFirst = { goTo(0) },
                onPrevChapter = { prevChapter?.let { openChapter(it, atEnd = false); menu = false } },
                onNextChapter = { nextChapter?.let { openChapter(it, atEnd = false); menu = false } },
                onClose = { menu = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ReaderMenu(
    title: String,
    page: Int,
    count: Int,
    mode: ReadingMode,
    fit: FitMode,
    hasPrev: Boolean,
    hasNext: Boolean,
    firstFocus: FocusRequester,
    onMode: (ReadingMode) -> Unit,
    onFit: (FitMode) -> Unit,
    onJump: (Int) -> Unit,
    onFirst: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().background(Color(0xEE15151B)).padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.progress_of, page + 1, count), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice(stringResource(R.string.direction_rtl), mode == ReadingMode.RTL, Modifier.focusRequester(firstFocus)) { onMode(ReadingMode.RTL) }
            Choice(stringResource(R.string.direction_ltr), mode == ReadingMode.LTR) { onMode(ReadingMode.LTR) }
            Choice(stringResource(R.string.direction_vertical), mode == ReadingMode.VERTICAL) { onMode(ReadingMode.VERTICAL) }
            if (mode != ReadingMode.VERTICAL) {
                Choice(stringResource(R.string.fit_screen), fit == FitMode.SCREEN) { onFit(FitMode.SCREEN) }
                Choice(stringResource(R.string.fit_width), fit == FitMode.WIDTH) { onFit(FitMode.WIDTH) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onFirst) { Text(stringResource(R.string.first_page)) }
            OutlinedButton(onClick = { onJump(-10) }) { Text(stringResource(R.string.back_10)) }
            OutlinedButton(onClick = { onJump(10) }) { Text(stringResource(R.string.forward_10)) }
            if (hasPrev) OutlinedButton(onClick = onPrevChapter) { Text(stringResource(R.string.prev_chapter)) }
            if (hasNext) OutlinedButton(onClick = onNextChapter) { Text(stringResource(R.string.next_chapter)) }
            Button(onClick = onClose) { Text(stringResource(R.string.close_menu)) }
        }
        Text(stringResource(R.string.reader_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.colors(containerColor = Accent, contentColor = Color.Black)) {
            Text("✓ $label")
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}
