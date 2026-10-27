package io.github.artcraftandroid.pdfcraft.engine

import io.github.artcraftandroid.pdfcraft.text as appText

import androidx.core.graphics.createBitmap

import android.graphics.Bitmap
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.core.MobileSession
import io.github.artcraftandroid.pdfcraft.core.RenderedImage
import io.github.artcraftandroid.pdfcraft.core.initLogging
import io.github.artcraftandroid.pdfcraft.text
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The PdfCraft engine (Rust: `apps/pdfcraft-android`) as the app uses it: one [MobileSession]
 * and the threads its blocking calls run on. Never call the session from the main thread: tools
 * are quick, renders and saves are not.
 */
class Engine private constructor(val session: MobileSession) {
    /** Tools run one at a time, in the order the UI issued them. */
    val commands: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Page renders: the view's pages and tiles. */
    val renders: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /** Thumbnails (page grids, recent files). */
    val thumbnails: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Run [block] on the session, after the commands issued before it. */
    suspend fun <T> command(block: MobileSession.() -> T): T = withContext(commands) { session.block() }

    /**
     * Run the engine tool [name] (the tools of `pdfcraft-cli run` and MCP; pages are 1-based)
     * with [args], after the commands issued before it. Throws [MobileException] with a message
     * for the user when the tool refuses or fails.
     */
    suspend fun tool(name: String, args: Map<String, Any?> = emptyMap()): JSONObject = command {
        val out = execute(name, toJson(args).toString())
        if (out.trimStart().startsWith("{")) JSONObject(out) else JSONObject().put("result", out)
    }

    fun trimMemory() = session.releaseMemory()

    /**
     * Unpack the text-recognition models shipped in the app's assets (once) and point the engine
     * at them. Returns false when this build has no models. Blocking: call off the main thread.
     */
    fun prepareOcrModels(context: android.content.Context): Boolean {
        val names = runCatching { context.assets.list("models")?.toList() }.getOrNull().orEmpty()
        if (names.none { it.endsWith(".rten") }) return false
        val dir = java.io.File(context.filesDir, "models").apply { mkdirs() }
        for (name in names) {
            val target = java.io.File(dir, name)
            if (target.isFile && target.length() > 0) continue
            val tmp = java.io.File(dir, "$name.part")
            context.assets.open("models/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(target)
        }
        android.system.Os.setenv("PDFCRAFT_MODELS", dir.absolutePath, true)
        return true
    }

    companion object {
        fun open(): Engine {
            initLogging()
            return Engine(MobileSession())
        }

        /** Maps, lists, arrays and plain values as JSON (nulls are left out of objects). */
        fun toJson(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply { for ((k, v) in value) if (v != null) put(k.toString(), toJson(v)) }
            is Iterable<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            is IntArray -> JSONArray().apply { value.forEach { put(it) } }
            is DoubleArray -> JSONArray().apply { value.forEach { put(it) } }
            is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            is Float -> value.toDouble()
            is UInt -> value.toLong()
            is ULong -> value.toLong()
            else -> value
        }
    }
}

/** The pixels as a bitmap: the engine's premultiplied RGBA bytes are the `ARGB_8888` memory layout. */
fun RenderedImage.toBitmap(): Bitmap {
    val bitmap = createBitmap(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))
    if (rgba.size >= bitmap.byteCount) bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    return bitmap
}

/** The user-facing message of an engine failure. */
val Throwable.userMessage: String
    get() = when (this) {
        is MobileException.NeedsPassword -> text(R.string.files_error_needs_password)
        is MobileException.WrongPassword -> text(R.string.common_wrong_password)
        else -> localizedEngineError(message)
    }

/** Localize actionable failures while keeping unknown diagnostics available. */
internal fun localizedEngineError(message: String?): String {
    val raw = message?.takeIf { it.isNotBlank() } ?: return appText(R.string.files_error_generic)
    if (io.github.artcraftandroid.pdfcraft.AppLanguage.current != "ar") return raw
    return when {
        raw.contains("complete source", true) || raw.contains("mixed contextual", true) -> "يحتاج هذا النص إلى خط كامل متوافق أو تنسيق موحد. لم تُغيّر الفقرة."
        raw.contains("glyph", true) || raw.contains("can't show", true) -> "الخط الحالي لا يدعم بعض الأحرف. جرّب خطًا متوافقًا."
        raw.contains("read-only", true) || raw.contains("Permission denied", true) -> "لا يمكن الكتابة إلى هذا الملف. احفظ نسخة في موقع آخر."
        raw.contains("No space", true) || raw.contains("ENOSPC", true) -> "مساحة التخزين غير كافية. حرّر مساحة ثم أعد الحفظ؛ احتُفظ بنسخة الاسترداد إن اكتمل حفظها المحلي."
        else -> raw
    }
}
