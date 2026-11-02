package io.github.artcraftandroid.pdfcraft.engine

import android.graphics.fonts.SystemFonts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android 10+ public font API, plus OEM font partitions. No storage permission is needed. */
suspend fun phoneFontDirectories(): List<String> = withContext(Dispatchers.IO) {
    val platform = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts", "/vendor/fonts", "/data/fonts/files")
    val registered = runCatching { SystemFonts.getAvailableFonts().mapNotNull { it.file?.parentFile?.absolutePath } }.getOrDefault(emptyList())
    (platform + registered).mapNotNull { path ->
        runCatching { File(path).takeIf { it.isDirectory && it.canRead() }?.canonicalPath }.getOrNull()
    }.distinct().take(16)
}
