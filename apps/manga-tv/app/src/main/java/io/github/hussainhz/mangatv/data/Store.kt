package io.github.hussainhz.mangatv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small persistent state, kept in SharedPreferences as JSON and exposed as Compose state. */
object Store {
    private lateinit var prefs: SharedPreferences

    var servers by mutableStateOf(emptyList<Server>()); private set
    var folders by mutableStateOf(emptyList<SafFolder>()); private set
    var progress by mutableStateOf(emptyMap<String, Progress>()); private set
    var readingMode by mutableStateOf(ReadingMode.RTL); private set
    var fitMode by mutableStateOf(FitMode.SCREEN); private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("manga_tv", Context.MODE_PRIVATE)
        servers = JSONArray(prefs.getString("servers", "[]")).objects().map {
            Server(
                id = it.getString("id"),
                name = it.getString("name"),
                url = it.getString("url"),
                username = it.optString("username"),
                password = it.optString("password"),
            )
        }
        folders = JSONArray(prefs.getString("folders", "[]")).objects().map {
            SafFolder(it.getString("treeUri"), it.getString("name"))
        }
        progress = JSONArray(prefs.getString("progress", "[]")).objects().mapNotNull {
            runCatching {
                val book = BookRef.fromJson(it.getJSONObject("book"))
                book.key to Progress(book, it.getInt("page"), it.getInt("pageCount"), it.getLong("updatedAt"))
            }.getOrNull()
        }.toMap()
        readingMode = runCatching { ReadingMode.valueOf(prefs.getString("mode", "RTL")!!) }.getOrDefault(ReadingMode.RTL)
        fitMode = runCatching { FitMode.valueOf(prefs.getString("fit", "SCREEN")!!) }.getOrDefault(FitMode.SCREEN)
    }

    fun server(id: String?): Server? = servers.firstOrNull { it.id == id }

    fun saveServer(server: Server) {
        val withId = if (server.id.isEmpty()) server.copy(id = UUID.randomUUID().toString()) else server
        servers = servers.filterNot { it.id == withId.id } + withId
        persistServers()
    }

    fun deleteServer(id: String) {
        servers = servers.filterNot { it.id == id }
        persistServers()
    }

    fun addFolder(folder: SafFolder) {
        folders = folders.filterNot { it.treeUri == folder.treeUri } + folder
        persistFolders()
    }

    fun removeFolder(treeUri: String) {
        folders = folders.filterNot { it.treeUri == treeUri }
        persistFolders()
    }

    fun recent(limit: Int = 20): List<Progress> =
        progress.values.sortedByDescending { it.updatedAt }.take(limit)

    fun saveProgress(book: BookRef, page: Int, pageCount: Int) {
        val updated = progress.toMutableMap()
        updated[book.key] = Progress(book, page, pageCount, System.currentTimeMillis())
        // Keep the list bounded.
        progress = updated.values.sortedByDescending { it.updatedAt }.take(200).associateBy { it.book.key }
        saveProgressList()
    }

    fun forgetProgress(key: String) {
        progress = progress - key
        saveProgressList()
    }

    private fun saveProgressList() {
        val arr = JSONArray()
        progress.values.forEach {
            arr.put(
                JSONObject().put("book", it.book.toJson()).put("page", it.page)
                    .put("pageCount", it.pageCount).put("updatedAt", it.updatedAt)
            )
        }
        prefs.edit().putString("progress", arr.toString()).apply()
    }

    fun updateReadingMode(mode: ReadingMode) {
        readingMode = mode
        prefs.edit().putString("mode", mode.name).apply()
    }

    fun updateFitMode(mode: FitMode) {
        fitMode = mode
        prefs.edit().putString("fit", mode.name).apply()
    }

    private fun persistServers() {
        val arr = JSONArray()
        servers.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)
                    .put("username", it.username).put("password", it.password)
            )
        }
        prefs.edit().putString("servers", arr.toString()).apply()
    }

    private fun persistFolders() {
        val arr = JSONArray()
        folders.forEach { arr.put(JSONObject().put("treeUri", it.treeUri).put("name", it.name)) }
        prefs.edit().putString("folders", arr.toString()).apply()
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
