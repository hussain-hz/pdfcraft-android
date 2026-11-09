package io.github.hussainhz.mangatv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.hussainhz.mangatv.data.BookRef
import io.github.hussainhz.mangatv.data.Location
import io.github.hussainhz.mangatv.data.Server

/** Screens are plain objects kept on a back stack, so a screen's state survives going back to it. */
sealed class Screen {
    object Home : Screen()
    class Browse(val location: Location, val title: String) : Screen() {
        val state = BrowseState()
    }
    class Reader(val book: BookRef, val siblings: List<BookRef>, val startPage: Int) : Screen()
    class EditServer(val server: Server?) : Screen()
}

class Navigator(private val stack: MutableList<Screen>) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    fun push(screen: Screen) { stack.add(screen) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(screen: Screen) { stack[stack.lastIndex] = screen }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MangaTheme {
                val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
                val nav = remember { Navigator(stack) }
                BackHandler(enabled = stack.size > 1) { nav.pop() }
                Box(Modifier.fillMaxSize().background(Background)) {
                    val screen = stack.last()
                    key(screen) {
                        when (screen) {
                            Screen.Home -> HomeScreen(nav)
                            is Screen.Browse -> BrowseScreen(nav, screen)
                            is Screen.Reader -> ReaderScreen(nav, screen)
                            is Screen.EditServer -> ServerScreen(nav, screen.server)
                        }
                    }
                }
            }
        }
    }
}
