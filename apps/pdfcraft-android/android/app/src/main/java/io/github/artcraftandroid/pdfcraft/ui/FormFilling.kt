package io.github.artcraftandroid.pdfcraft.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.FieldActions
import io.github.artcraftandroid.pdfcraft.ui.viewer.FieldEdit
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet
import kotlinx.coroutines.launch

/** Filling in a document's form: its fields and buttons, and the words typed into fields before. */
class FormFilling internal constructor(private val vm: AppViewModel) {
    private val appPrefs = vm.getApplication<android.app.Application>().getSharedPreferences("app", Context.MODE_PRIVATE)

    /** The field being filled in. */
    var fieldEdit by mutableStateOf<FieldEdit?>(null)

    /** The top bar's Reset and arrows while a text field is filled. */
    var fieldActions by mutableStateOf<FieldActions?>(null)

    /**
     * A tap at [at]: fill the form field there, if any (a check box or radio button toggles at
     * once). Returns whether there was one.
     */
    fun tapField(doc: OpenDocument, at: PagePoint?): Boolean {
        if (at == null || doc.widgets.isEmpty()) return false
        val w = doc.widgets.firstOrNull { w ->
            w.page.toInt() == at.page && w.rect.size == 4 && at.x in w.rect[0]..w.rect[2] && at.y in w.rect[1]..w.rect[3]
        } ?: return false
        if (w.readOnly) return true
        if (!doc.info.allowsFormFilling) {
            vm.tell(text(R.string.msg_tool_blocked_security))
            return true
        }
        when (w.kind) {
            "checkbox" -> fillField(doc, w.field, !w.on)
            "radio" -> w.export?.let { fillField(doc, w.field, it) }
            "text", "combo", "list" -> fieldEdit = FieldEdit(doc, w)
            // a signed field shows its signature's details; an empty one, a drawn or a digital signature
            "signature" -> if (doc.signatures.any { it.field == w.field && it.signed }) {
                doc.controller.sheet = ViewerSheet.Signatures
            } else {
                vm.signing.signFieldChoice = doc to w
            }
            "button" -> pressButton(doc, w)
            else -> return false
        }
        return true
    }

    /**
     * A form button: does what its release action says, as far as a reader does on a phone:
     * clear the form (or the fields it names), open a web address after asking, or go to a page.
     * Scripts and sending the form online aren't run.
     */
    private fun pressButton(doc: OpenDocument, w: io.github.artcraftandroid.pdfcraft.core.WidgetInfo) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            val r = runCatching { eng.tool("form_actions", mapOf("doc" to doc.id, "field" to w.field)) }.getOrNull() ?: return@launch
            val acts = r.optJSONArray("actions") ?: return@launch
            val all = (0 until acts.length()).mapNotNull { acts.optJSONObject(it) }
            val act = all.firstOrNull { it.optString("trigger") == "mouse_up" } ?: all.firstOrNull() ?: return@launch
            when {
                act.has("reset") -> {
                    val names = act.optJSONArray("reset")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    vm.runTool(doc, "form_reset", if (names.isEmpty()) emptyMap() else mapOf("fields" to names)) { vm.tell(text(R.string.msg_form_cleared)) }
                }
                act.has("url") -> vm.linkPrompt = act.optString("url")
                act.has("page") -> doc.viewer.goToPage((act.optInt("page") - 1).coerceIn(0, doc.pageCount - 1))
                act.has("submit") -> vm.tell(text(R.string.msg_button_submit))
                act.has("javascript") -> vm.tell(text(R.string.msg_button_script))
            }
        }
    }

    fun fillField(doc: OpenDocument, field: String, value: Any) {
        vm.runTool(doc, "form_fill", mapOf("values" to mapOf(field to value)))
    }

    /** Move to the next (or previous) fillable text or choice field in reading order. */
    fun nextField(doc: OpenDocument, from: io.github.artcraftandroid.pdfcraft.core.WidgetInfo, forward: Boolean) {
        val fillable = doc.widgets.filter { !it.readOnly && (it.kind == "text" || it.kind == "combo" || it.kind == "list") }
        val i = fillable.indexOfFirst { it.field == from.field && it.page == from.page && it.rect == from.rect }
        val next = fillable.getOrNull(if (forward) i + 1 else i - 1)
        fieldEdit = next?.let {
            if (it.page.toInt() != doc.viewer.currentPage) doc.viewer.goToPage(it.page.toInt(), (it.rect[1] - 60f).coerceAtLeast(0f))
            FieldEdit(doc, it)
        }
    }

    // ---- Form auto-suggestions -----------------------------------------------------------------

    var formSuggestions by mutableStateOf(appPrefs.getBoolean("formSuggestions", true))
        private set

    fun updateFormSuggestions(on: Boolean) {
        formSuggestions = on
        appPrefs.edit { putBoolean("formSuggestions", on) }
    }

    /** Words typed into form fields before, newest first. */
    fun suggestionsFor(prefix: String): List<String> {
        if (!formSuggestions || prefix.isBlank()) return emptyList()
        val all = appPrefs.getString("suggestions", "").orEmpty().split('\n').filter { it.isNotBlank() }
        return all.filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }.take(3)
    }

    fun rememberSuggestion(value: String) {
        if (!formSuggestions || value.isBlank() || value.length > 200) return
        val all = appPrefs.getString("suggestions", "").orEmpty().split('\n').filter { it.isNotBlank() && it != value }
        appPrefs.edit { putString("suggestions", (listOf(value) + all).take(200).joinToString("\n")) }
    }

    fun clearFormSuggestions() {
        appPrefs.edit { remove("suggestions") }
        vm.tell(text(R.string.msg_suggestions_cleared))
    }
}
