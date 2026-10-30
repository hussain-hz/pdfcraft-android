package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.StyledTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.VoicePrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.hex
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Voice comments: recording one where the page was tapped (asking for the microphone first), and playing one back. */
class VoiceComments internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    /** A voice comment being recorded where the page was tapped. */
    var voicePrompt by mutableStateOf<VoicePrompt?>(null)

    /** Asks the activity for the microphone. */
    val micRequests = Channel<Unit>(Channel.CONFLATED)

    /** The microphone was refused before: explain, and offer the app's settings. */
    var micRationale by mutableStateOf(false)
    private var pendingVoice: VoicePrompt? = null

    /** Record a voice comment at [at], once the microphone is allowed. */
    fun startVoice(doc: OpenDocument, at: PagePoint) {
        vm.viewModelScope.launch {
            val enoughSpace = withContext(Dispatchers.IO) {
                val required = 60L * 1024 * 1024
                val storage = app.getSystemService(android.os.storage.StorageManager::class.java)
                try {
                    val volume = storage.getUuidForPath(app.cacheDir)
                    if (storage.getAllocatableBytes(volume) < required) false else {
                        // Reclaim eligible cache before the recorder starts writing.
                        storage.allocateBytes(volume, required)
                        true
                    }
                } catch (_: java.io.IOException) {
                    app.cacheDir.usableSpace >= required
                } catch (_: SecurityException) {
                    app.cacheDir.usableSpace >= required
                }
            }
            if (doc.closed || doc.closing || vm.document !== doc) return@launch
            if (!enoughSpace) {
                vm.tell(text(R.string.msg_voice_no_storage))
                return@launch
            }
            val prompt = VoicePrompt(doc, at)
            if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                voicePrompt = prompt
            } else {
                pendingVoice = prompt
                micRequests.trySend(Unit)
            }
        }
    }

    /** The answer to the microphone request. */
    fun onMicPermission(granted: Boolean) {
        val p = pendingVoice
        pendingVoice = null
        if (granted && p != null) voicePrompt = p else if (!granted) vm.tell(text(R.string.msg_voice_no_mic))
    }

    fun dismissMicRationale(openSettings: Boolean) {
        micRationale = false
        pendingVoice = null
        if (openSettings) {
            vm.intents.trySend(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** A cache file for a new recording. */
    fun voiceFile(): File = File(app.cacheDir, "voice-${System.nanoTime()}.wav")

    /** Post [file] (a WAV recording) as a voice comment at the prompt's point. */
    fun postVoice(prompt: VoicePrompt, file: File) {
        voicePrompt = null
        val at = prompt.at
        vm.runTool(
            prompt.doc, "comment_add",
            mapOf(
                "page" to at.page + 1, "type" to "sound", "at" to listOf(at.x.toDouble(), at.y.toDouble()),
                "path" to file.absolutePath, "color" to vm.toolPrefs.color(StyledTool.Note).hex(), "author" to vm.author,
            ),
        ) { file.delete() }
    }

    /** Voice comment [c]'s recording as a WAV file to play, or null when it can't be read. */
    suspend fun voiceRecording(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo): File? {
        val eng = vm.engine ?: return null
        val f = File(app.cacheDir, "voice-play.wav")
        return withContext(eng.commands) {
            runCatching { eng.session.saveSound(doc.id, c.page, c.index, f.absolutePath); f }.getOrNull()
        }
    }
}
