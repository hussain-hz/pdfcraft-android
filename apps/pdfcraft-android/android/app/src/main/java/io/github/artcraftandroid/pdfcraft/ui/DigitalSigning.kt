package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.core.WidgetInfo
import io.github.artcraftandroid.pdfcraft.core.digitalIdInfo
import io.github.artcraftandroid.pdfcraft.engine.DigitalId
import io.github.artcraftandroid.pdfcraft.engine.DigitalIds
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.TrustedCertificates
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.CertLook
import io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget
import io.github.artcraftandroid.pdfcraft.ui.viewer.IdImport
import io.github.artcraftandroid.pdfcraft.ui.viewer.LinkFlash
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.SignatureEntry
import io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePad
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.parseSignatures
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Digital signatures: signing with a digital ID, and what a document's signatures say. */
class DigitalSigning internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    /** The digital IDs the reader added or made (lazy: the engine's start may need them first). */
    val digitalIds by lazy { DigitalIds(app.filesDir) }

    /** The signers the reader chose to trust, given to the engine when it starts. */
    val trustedCerts by lazy { TrustedCertificates(app.filesDir) }

    /** The document a digital signature's box is being drawn on (Sign ▸ Sign with a digital ID). */
    var certPlacing by mutableStateOf<OpenDocument?>(null)

    /** Sign with a digital ID, open for where the signature goes. */
    var certSign by mutableStateOf<CertTarget?>(null)

    /** Why the last signing failed (a wrong password), shown on the Sign screen. */
    var certSignError by mutableStateOf<String?>(null)

    /** The digital ID chosen on the Sign screen. */
    var chosenId by mutableStateOf<DigitalId?>(null)

    /** A picked digital ID file, waiting for its password. */
    var idImport by mutableStateOf<IdImport?>(null)
        internal set

    /** Create a digital ID is open. */
    var idCreate by mutableStateOf(false)

    /** An empty signature field was tapped: draw a signature there, or sign it with a digital ID. */
    var signFieldChoice by mutableStateOf<Pair<OpenDocument, WidgetInfo>?>(null)

    /** Draw the box for a new digital signature on [doc], in Fill & Sign. */
    fun startCertPlacing(doc: OpenDocument) {
        if (!doc.info.readOnlyReason.isNullOrEmpty()) {
            vm.tell(text(R.string.msg_sign_read_only))
            return
        }
        if (doc.controller.tool != ViewerTool.FillSign) vm.enterTool(doc, ViewerTool.FillSign)
        doc.controller.signPanel = false
        vm.selectedFill = null
        certPlacing = doc
    }

    /** The box was drawn: on to choosing the ID. */
    fun placeCert(doc: OpenDocument, page: Int, rect: FloatArray) {
        certPlacing = null
        openCertSign(CertTarget(doc, null, page, rect))
    }

    fun signFieldWithId(doc: OpenDocument, w: WidgetInfo) {
        openCertSign(CertTarget(doc, w.field, w.page.toInt(), null))
    }

    /** A drawn (Fill & Sign) signature in signature field [w], as a tap on it did before digital IDs. */
    fun drawSignatureInField(doc: OpenDocument, w: WidgetInfo) {
        vm.signaturePad = SignaturePad(false, doc, PagePoint(w.page.toInt(), (w.rect[0] + w.rect[2]) / 2f - 40f, (w.rect[1] + w.rect[3]) / 2f - 10f))
    }

    private fun openCertSign(t: CertTarget) {
        certSignError = null
        val ids = digitalIds.all
        if (chosenId !in ids) chosenId = ids.lastOrNull()
        certSign = t
    }

    fun pickDigitalId() = vm.pickFiles(FilePickKind.DigitalId)

    /** The digital ID file's password: add it (when the password opens it), or give up (null). */
    fun answerIdImport(password: String?) {
        val imp = idImport ?: return
        idImport = null
        if (password == null) {
            imp.file.delete()
            return
        }
        vm.viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { digitalIdInfo(imp.file.absolutePath, password) }
                val problem = info.problem
                if (problem != null) {
                    imp.file.delete()
                    vm.tell(text(R.string.msg_id_cannot_sign, problem))
                    return@launch
                }
                val id = withContext(Dispatchers.IO) { digitalIds.add(imp.file, info) }
                imp.file.delete()
                chosenId = id
                vm.tell(text(R.string.msg_id_added, id.name))
            } catch (e: MobileException.WrongPassword) {
                idImport = IdImport(imp.file, imp.name, wrong = true)
            } catch (e: MobileException) {
                imp.file.delete()
                vm.tell(text(R.string.msg_id_unusable, e.userMessage))
            }
        }
    }

    /** Make a self-signed digital ID (2048-bit RSA, valid 5 years) and choose it. */
    fun createDigitalId(name: String, email: String, organization: String, password: String) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            vm.busy = text(R.string.msg_busy_creating_id)
            val tmp = File(app.cacheDir, "new-id-${System.nanoTime()}.p12")
            try {
                eng.tool("sign_id_create", mapOf(
                    "name" to name.trim(), "email" to email.trim().ifEmpty { null }, "organization" to organization.trim().ifEmpty { null },
                    "password" to password, "path" to tmp.absolutePath,
                ))
                val info = withContext(Dispatchers.IO) { digitalIdInfo(tmp.absolutePath, password) }
                chosenId = withContext(Dispatchers.IO) { digitalIds.add(tmp, info) }
                idCreate = false
                vm.tell(text(R.string.msg_id_created))
            } catch (e: MobileException) {
                vm.tell(vm.toolMessage(e, "sign_id_create"))
            } finally {
                tmp.delete()
                vm.busy = null
            }
        }
    }

    fun removeDigitalId(id: DigitalId) {
        digitalIds.remove(id)
        if (chosenId == id) chosenId = digitalIds.all.lastOrNull()
    }

    /**
     * Sign [t]'s document with [id]: the engine signs it as it is now (with any changes not yet
     * saved) and the signed file replaces the working copy, then goes back where it came from.
     */
    fun signWithId(
        t: CertTarget,
        id: DigitalId,
        password: String,
        look: CertLook,
        reason: String,
        location: String,
    ) {
        val eng = vm.engine ?: return
        val doc = t.doc
        vm.viewModelScope.launch {
            vm.busy = text(R.string.msg_busy_signing)
            try {
                val args = mutableMapOf<String, Any?>(
                    "doc" to doc.id, "id" to id.file.absolutePath, "password" to password, "out" to doc.file.absolutePath,
                    "reason" to reason.trim().ifEmpty { null }, "location" to location.trim().ifEmpty { null },
                )
                if (t.field != null) {
                    args["field"] = t.field
                } else {
                    args["page"] = t.page + 1
                    args["rect"] = t.rect?.map { it.toDouble() }
                }
                when (look) {
                    CertLook.Name -> Unit
                    CertLook.Typed -> args["graphic"] = mapOf("text" to id.name)
                    CertLook.Drawn -> args["graphic"] = mapOf("image" to vm.signatures.signature.absolutePath)
                }
                eng.tool("sign_document", args)
                certSign = null
                certSignError = null
                if (doc.controller.tool == ViewerTool.FillSign) vm.leaveTool(doc)
                vm.refresh(doc)
                // the working copy is signed; the original gets it now, or a copy does
                doc.notWrittenBack = true
                if (vm.save(doc, quiet = true)) {
                    vm.tell(text(R.string.msg_signed_saved))
                } else {
                    vm.tell(text(R.string.msg_signed_not_saved, doc.name), text(R.string.common_save_copy)) { vm.saveCopyAs(doc) }
                }
            } catch (e: MobileException) {
                val m = vm.toolMessage(e, "sign_document")
                certSignError = if (m.contains("password is incorrect", ignoreCase = true)) text(R.string.common_wrong_password) else m
            } finally {
                vm.busy = null
            }
        }
    }

    /** What the document's signatures say (only asked for when it has signatures or signature fields). */
    internal suspend fun loadSignatures(eng: Engine, doc: OpenDocument) {
        if (!doc.info.signed && doc.widgets.none { it.kind == "signature" }) {
            doc.signatures = emptyList()
            return
        }
        doc.signatures = runCatching { parseSignatures(eng.tool("sign_list", mapOf("doc" to doc.id))) }.getOrDefault(emptyList())
    }

    /** On opening a signed document: whether its signatures hold, with the details a tap away. */
    internal fun announceSignatures(doc: OpenDocument) {
        val signed = doc.signatures.filter { it.signed }
        if (signed.isEmpty()) return
        val one = signed.singleOrNull()
        val message = when {
            signed.any { it.status == "invalid" } -> text(if (one != null) R.string.msg_sig_invalid else R.string.msg_sig_some_invalid)
            signed.all { it.status == "valid" } -> if (one != null) {
                one.signer?.let { text(R.string.msg_sig_valid, it) } ?: text(R.string.msg_sig_valid_unknown)
            } else {
                plural(R.plurals.msg_sig_all_valid, signed.size, signed.size)
            }
            one != null -> one.signer?.let { text(R.string.msg_sig_unverified, it) } ?: text(R.string.msg_sig_unverified_unknown)
            else -> text(R.string.msg_sig_some_unverified)
        }
        vm.tell(message, text(R.string.msg_sig_details)) { doc.controller.sheet = ViewerSheet.Signatures }
    }

    /** Show signature [s] on its page, flashing its box. */
    fun showSignature(doc: OpenDocument, s: SignatureEntry) {
        val page = s.page ?: return
        val rect = s.rect ?: return
        doc.viewer.goToPage(page, (rect[1] - 60f).coerceAtLeast(0f))
        vm.viewModelScope.launch {
            delay(250)
            doc.linkFlash = LinkFlash(page, rect)
            delay(600)
            doc.linkFlash = null
        }
    }

    /** Trust the certificate that made signature [s], on this device: signatures by it then validate. */
    fun trustSigner(doc: OpenDocument, s: SignatureEntry) {
        val eng = vm.engine ?: return
        val sha = s.cert?.sha256 ?: return
        vm.viewModelScope.launch {
            try {
                val der = eng.command { signerCertificate(doc.id, s.field) } ?: return@launch
                withContext(Dispatchers.IO) { trustedCerts.add(der, sha) }
                applyTrust(eng)
                loadSignatures(eng, doc)
                vm.tell(s.signer?.let { text(R.string.msg_signer_trusted, it) } ?: text(R.string.msg_signer_trusted_unknown))
            } catch (e: MobileException) {
                vm.tell(e.userMessage)
            }
        }
    }

    fun untrustSigner(doc: OpenDocument, s: SignatureEntry) {
        val eng = vm.engine ?: return
        val sha = s.cert?.sha256 ?: return
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { trustedCerts.remove(sha) }
            runCatching { applyTrust(eng) }
            loadSignatures(eng, doc)
        }
    }

    /** Give the engine the trusted signers' certificates (it revalidates open documents). */
    internal suspend fun applyTrust(eng: Engine) {
        val paths = withContext(Dispatchers.IO) { trustedCerts.files().map { it.absolutePath } }
        eng.tool("sign_trust", mapOf("clear" to true, "paths" to paths))
    }
}
