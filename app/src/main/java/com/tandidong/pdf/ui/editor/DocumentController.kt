package com.tandidong.pdf.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tandidong.pdf.core.AnnotationEditor
import com.tandidong.pdf.core.AnnotationInfo
import com.tandidong.pdf.core.AnnotationWriter
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.FormField
import com.tandidong.pdf.core.Forms
import com.tandidong.pdf.core.PageText
import com.tandidong.pdf.core.Pt
import com.tandidong.pdf.core.RedactionArea
import com.tandidong.pdf.core.RedactionOptions
import com.tandidong.pdf.core.RedactionReport
import com.tandidong.pdf.core.Redactor
import com.tandidong.pdf.core.ShapeKind
import com.tandidong.pdf.core.TextEditor
import com.tandidong.pdf.core.TextLine
import com.tandidong.pdf.core.TextMatch
import com.tandidong.pdf.data.SavedSignature
import com.tandidong.pdf.platform.AndroidImageCodec
import com.tandidong.pdf.platform.PdfBoxRasterizer
import com.tandidong.pdf.render.PageLink
import com.tandidong.pdf.session.DocumentSession
import com.tandidong.pdf.ui.AppViewModel
import com.tandidong.pdf.ui.viewer.PageHit
import com.tandidong.pdf.ui.viewer.ViewerInput
import com.tandidong.pdf.ui.viewer.ViewerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class TextPurpose { COMMENT_BOX, FILL, CONTENT }

sealed interface DocDialog {
    data class Note(val page: Int, val at: Pt) : DocDialog
    data class TextEntry(val page: Int, val at: Pt, val purpose: TextPurpose) : DocDialog
    data class EditText(val page: Int, val line: TextLine, val text: String, val paragraph: com.tandidong.pdf.core.Paragraph? = null) : DocDialog
    data class FieldEdit(val field: FormField) : DocDialog
    data class EditComment(val info: AnnotationInfo) : DocDialog
    data class Signature(val initials: Boolean, val placeAt: Pair<Int, Pt>?) : DocDialog
    data class BookmarkName(val path: List<Int>?, val initial: String, val page: Int) : DocDialog
    data class Info(val title: String, val text: String) : DocDialog
    data object StampPicker : DocDialog
    data object ApplyRedactions : DocDialog
    data object SearchRedact : DocDialog
    data object RedactPages : DocDialog
    data object Sanitize : DocDialog
    data object Protect : DocDialog
    data object Compress : DocDialog
    data object Watermark : DocDialog
    data object HeaderFooter : DocDialog
    data object Properties : DocDialog
    data object GoToPage : DocDialog
    data object Export : DocDialog
    data object Ocr : DocDialog
    data object Flatten : DocDialog
    data object ConfirmClose : DocDialog
    data object ConfirmDiscardTool : DocDialog
    data class CertSign(val page: Int, val rect: FRect, val fieldName: String? = null) : DocDialog
    data class CreateDigitalId(val then: DocDialog?) : DocDialog
    data class ImportDigitalId(val data: ByteArray, val then: DocDialog?) : DocDialog
    data class SignedWarning(val proceed: () -> Unit) : DocDialog
    data class NewFormField(val type: com.tandidong.pdf.core.NewFieldType, val page: Int, val rect: FRect) : DocDialog
    data class NewLink(val page: Int, val rect: FRect) : DocDialog
    data class LinkActions(val page: Int, val at: Pt, val link: PageLink) : DocDialog
    data object Background : DocDialog
    data class FieldProperties(val field: FormField) : DocDialog
}

enum class Panel { BOOKMARKS, COMMENTS, FIELDS, SIGNATURES, ATTACHMENTS }

/** All editor logic for one open document; survives navigation to other screens. */
@Stable
class DocumentController(
    val vm: AppViewModel,
    val session: DocumentSession,
    private val scope: CoroutineScope,
    private val appContext: Context,
) : ViewerInput {
    val viewer = ViewerState()
    val editor = EditorState()
    val readAloud = ReadAloudController(appContext)

    var chrome by mutableStateOf(true)
    var dialog by mutableStateOf<DocDialog?>(null)
    var panel by mutableStateOf<Panel?>(null)
    var searchOpen by mutableStateOf(false)
    var night by mutableStateOf(vm.prefs.nightReading)
    var links by mutableStateOf<Map<Int, List<PageLink>>>(emptyMap())
    var launchHandled = false
    var signatureStatuses by mutableStateOf<List<com.tandidong.pdf.core.SignatureStatus>?>(null)
    var attachments by mutableStateOf<List<com.tandidong.pdf.core.AttachmentInfo>>(emptyList())
    private var signedWarningAccepted = false

    private var loadedVersion = -1
    private var dragStart: PageHit? = null
    private var searchJob: Job? = null
    private val fonts get() = vm.fonts
    private val author get() = vm.prefs.authorName.ifBlank { "Me" }

    fun redactor() = Redactor(AndroidImageCodec, PdfBoxRasterizer, fonts)

    // ------------------------------------------------------------------ version tracking

    /** Called when the session version changes: drop caches that describe the old version. */
    fun onVersion(version: Int) {
        if (version == loadedVersion) return
        loadedVersion = version
        editor.pageTexts.clear()
        editor.selection = null
        editor.selectedAnnot = null
        links = emptyMap()
        linkLoading.clear()
        editor.searchResults = emptyList()
        editor.searchIndex = -1
        if (editor.tool == Tool.ERASER || editor.tool == Tool.SELECT || panel == Panel.COMMENTS) loadAnnotations()
        loadFormFields()
        verifySignatures()
        if (panel == Panel.ATTACHMENTS) loadAttachments()
        editor.pageImages.clear()
        editor.imageDrag = null
        val rs = reselectImage
        reselectImage = null
        editor.selectedImage = null
        if (editor.tool == Tool.EDIT_IMAGE) {
            viewer.visiblePages().forEach { loadImages(it) }
            if (rs != null) scope.launch { editor.selectedImage = imagesFor(rs.first).getOrNull(rs.second) }
        }
    }

    // ------------------------------------------------------------------ images

    private var reselectImage: Pair<Int, Int>? = null
    private var imageDragMode = 0
    private var imageDragStart: FRect? = null

    suspend fun imagesFor(page: Int): List<com.tandidong.pdf.core.PlacedImage> {
        editor.pageImages[page]?.let { return it }
        val list = try {
            session.read { com.tandidong.pdf.core.PageImages.list(it, page) }
        } catch (e: Exception) {
            emptyList()
        }
        editor.pageImages[page] = list
        return list
    }

    fun loadImages(page: Int) {
        if (!editor.pageImages.containsKey(page)) scope.launch { imagesFor(page) }
    }

    private fun handleRadius(): Float = 28f / max(0.01f, viewer.scale)

    fun deleteImage(img: com.tandidong.pdf.core.PlacedImage) {
        scope.launch {
            try {
                val ok = session.edit("Deleting image…") { com.tandidong.pdf.core.PageImages.delete(it, img) }
                vm.message(if (ok) "Image deleted" else "The page changed; select the image again")
            } catch (e: Exception) {
                vm.message("Couldn't delete the image: ${e.message}")
            }
        }
    }

    fun replaceImage(img: com.tandidong.pdf.core.PlacedImage, bitmap: Bitmap) {
        scope.launch {
            try {
                reselectImage = img.pageIndex to img.ordinal
                val ok = session.edit("Replacing image…") { doc ->
                    val x = if (bitmap.hasAlpha()) com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, bitmap)
                    else com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, bitmap, 0.9f)
                    val done = com.tandidong.pdf.core.PageImages.replace(doc, img, x)
                    if (done) {
                        // Keep the new picture's proportions inside the old frame.
                        val r = img.displayRect
                        val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                        val fitted = if (r.width / r.height > aspect) {
                            val w = r.height * aspect
                            FRect(r.centerX - w / 2f, r.y0, r.centerX + w / 2f, r.y1)
                        } else {
                            val h = r.width / aspect
                            FRect(r.x0, r.centerY - h / 2f, r.x1, r.centerY + h / 2f)
                        }
                        val again = com.tandidong.pdf.core.PageImages.list(doc, img.pageIndex).getOrNull(img.ordinal)
                        if (again != null && (abs(fitted.width - r.width) > 0.5f || abs(fitted.height - r.height) > 0.5f)) {
                            com.tandidong.pdf.core.PageImages.transform(doc, again, fitted)
                        }
                    }
                    done
                }
                if (!ok) { reselectImage = null; vm.message("The page changed; select the image again") }
            } catch (e: Exception) {
                reselectImage = null
                vm.message("Couldn't replace the image: ${e.message}")
            }
        }
    }

    /** Encoded bytes and file extension of [img] for saving (JPEG as stored, otherwise PNG). */
    suspend fun imageBytes(img: com.tandidong.pdf.core.PlacedImage): Pair<ByteArray, String>? = session.read { doc ->
        com.tandidong.pdf.core.PageImages.rawJpeg(doc, img)?.let { return@read it to "jpg" }
        val x = com.tandidong.pdf.core.PageImages.xobject(doc, img) ?: return@read null
        val raster = AndroidImageCodec.decode(x) as? com.tandidong.pdf.platform.BitmapRaster ?: return@read null
        val out = java.io.ByteArrayOutputStream()
        raster.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        raster.bitmap.recycle()
        out.toByteArray() to "png"
    }

    // ------------------------------------------------------------------ links, attachments, background

    fun addLink(page: Int, rect: FRect, uri: String?, targetPage: Int?) {
        scope.launch {
            try {
                session.edit("Adding link…") { doc ->
                    if (targetPage != null) com.tandidong.pdf.core.LinkEditor.addPageLink(doc, page, rect, targetPage)
                    else com.tandidong.pdf.core.LinkEditor.addUri(doc, page, rect, uri ?: "")
                }
                vm.message("Link added")
            } catch (e: Exception) {
                vm.message("Couldn't add the link: ${e.message}")
            }
        }
    }

    fun removeLinkAt(page: Int, at: Pt) {
        scope.launch {
            try {
                val n = session.edit("Removing link…") { com.tandidong.pdf.core.LinkEditor.removeAt(it, page, at.x, at.y) }
                vm.message(if (n > 0) "Link removed" else "No editable link here")
            } catch (e: Exception) {
                vm.message("Couldn't remove the link: ${e.message}")
            }
        }
    }

    fun loadAttachments() {
        scope.launch {
            attachments = try { session.read { com.tandidong.pdf.core.Attachments.list(it) } } catch (e: Exception) { emptyList() }
        }
    }

    fun addAttachment(name: String, mime: String?, data: ByteArray, description: String?) {
        scope.launch {
            try {
                session.edit("Attaching file…") { com.tandidong.pdf.core.Attachments.add(it, name, data, mime, description) }
                vm.message("File attached")
                loadAttachments()
            } catch (e: Exception) {
                vm.message("Couldn't attach the file: ${e.message}")
            }
        }
    }

    fun deleteAttachment(a: com.tandidong.pdf.core.AttachmentInfo) {
        scope.launch {
            try {
                val ok = session.edit("Removing attachment…") { com.tandidong.pdf.core.Attachments.delete(it, a.key) }
                vm.message(if (ok) "Attachment removed" else "Couldn't remove the attachment")
                loadAttachments()
            } catch (e: Exception) {
                vm.message("Couldn't remove the attachment: ${e.message}")
            }
        }
    }

    suspend fun attachmentBytes(a: com.tandidong.pdf.core.AttachmentInfo): ByteArray =
        session.read { com.tandidong.pdf.core.Attachments.read(it, a.key) }

    fun applyBackground(pages: List<Int>, argb: Int) {
        scope.launch {
            try {
                session.edit("Adding background…") { com.tandidong.pdf.core.Stamper(fonts).background(it, pages, argb) }
                vm.message("Background added to ${pages.size} page${if (pages.size == 1) "" else "s"}")
            } catch (e: Exception) {
                vm.message("Couldn't add the background: ${e.message}")
            }
        }
    }

    fun verifySignatures() {
        scope.launch {
            if (!session.hasSignatures) {
                signatureStatuses = emptyList()
                return@launch
            }
            val (file, password) = session.signatureCheckFile()
            signatureStatuses = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.tandidong.pdf.core.DigitalSignatures.verify(file, password)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** Runs [action] after warning (once) that changes invalidate existing digital signatures. */
    fun guardSigned(action: () -> Unit) {
        if (session.hasSignatures && !signedWarningAccepted) {
            dialog = DocDialog.SignedWarning {
                signedWarningAccepted = true
                action()
            }
        } else {
            action()
        }
    }

    fun loadAnnotations() {
        scope.launch {
            editor.annotations = try { session.read { AnnotationEditor.list(it) } } catch (e: Exception) { emptyList() }
        }
    }

    fun loadFormFields() {
        scope.launch {
            editor.formFields = try { session.read { Forms.list(it) } } catch (e: Exception) { emptyList() }
        }
    }

    suspend fun pageText(page: Int): PageText? {
        editor.pageTexts[page]?.let { return it }
        return try {
            session.pageText(page).also { editor.pageTexts[page] = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun prefetchText(page: Int) {
        if (editor.pageTexts.containsKey(page)) return
        scope.launch { pageText(page) }
    }

    private val linkLoading = HashSet<Int>()

    fun loadLinks(page: Int) {
        if (links.containsKey(page) || !linkLoading.add(page)) return
        scope.launch {
            try {
                val l = session.links(page)
                links = links + (page to l)
            } finally {
                linkLoading.remove(page)
            }
        }
    }

    // ------------------------------------------------------------------ tools

    fun openCategory(c: ToolCategory) {
        if (session.restricted) {
            vm.message("Editing is restricted by the document's security settings")
            return
        }
        if (session.hasSignatures && !signedWarningAccepted) {
            guardSigned { openCategory(c) }
            return
        }
        editor.category = c
        editor.tool = Tool.NONE
        editor.selection = null
        if (c == ToolCategory.FILL_SIGN || c == ToolCategory.FORMS) loadFormFields()
        if (c == ToolCategory.FORMS) chrome = true
    }

    /** Opens Fill & Sign with the certificate (digital ID) tool selected. */
    fun openSigning() {
        if (session.restricted) {
            vm.message("Signing is restricted by the document's security settings")
            return
        }
        editor.category = ToolCategory.FILL_SIGN
        editor.tool = Tool.CERT_SIGN
        editor.selection = null
        loadFormFields()
        vm.message("Drag a box where the signature should appear")
    }

    fun selectTool(t: Tool) {
        if (session.hasSignatures && !signedWarningAccepted && t != Tool.CERT_SIGN && t != editor.tool) {
            guardSigned { selectTool(t) }
            return
        }
        editor.tool = if (editor.tool == t) Tool.NONE else t
        editor.selection = null
        editor.selectedAnnot = null
        if (editor.tool == Tool.ERASER || editor.tool == Tool.SELECT) loadAnnotations()
        if (editor.tool.needsText) viewer.visiblePages().forEach { prefetchText(it) }
        editor.selectedImage = null
        if (editor.tool == Tool.EDIT_IMAGE) viewer.visiblePages().forEach { loadImages(it) }
        if (editor.tool == Tool.LINK) viewer.visiblePages().forEach { loadLinks(it) }
        when (editor.tool) {
            Tool.STAMP -> if (editor.tool == t) dialog = DocDialog.StampPicker
            Tool.SIGNATURE -> if (editor.signature == null) dialog = DocDialog.Signature(false, null)
            Tool.INITIALS -> if (editor.initials == null) dialog = DocDialog.Signature(true, null)
            Tool.FORM -> { loadFormFields(); panel = Panel.FIELDS }
            else -> {}
        }
    }

    /** Leaves the current tool category, committing pending edits when [commit] is true. */
    fun closeCategory(commit: Boolean) {
        if (commit) commitPending()
        else {
            editor.pending.clear()
            editor.redoStack.clear()
        }
        editor.active = null
        editor.tool = Tool.NONE
        editor.category = null
        editor.selectedAnnot = null
        editor.selectedImage = null
        editor.imageDrag = null
    }

    fun requestCloseCategory() {
        when {
            editor.category == ToolCategory.REDACT && editor.redactions.isNotEmpty() -> dialog = DocDialog.ApplyRedactions
            editor.pending.isNotEmpty() -> commitPending().also { closeCategory(false) }
            else -> closeCategory(false)
        }
    }

    fun commitPending(onDone: () -> Unit = {}) {
        if (editor.pending.isEmpty()) { onDone(); return }
        scope.launch { if (flushPending()) onDone() }
    }

    /** Writes pending items into the document; returns false if that failed. */
    suspend fun flushPending(): Boolean {
        val items = editor.pending.toList()
        if (items.isEmpty()) return true
        editor.pending.clear()
        editor.redoStack.clear()
        return try {
            val writer = AnnotationWriter(author, fonts)
            val textEditor = TextEditor(redactor(), fonts)
            session.edit("Saving changes…") { doc ->
                writer.add(doc, items.mapNotNull { it.toSpec() })
                for (it in items) {
                    if (it is PendingItem.TextBox && it.asContent) {
                        textEditor.addText(doc, it.page, Pt(it.rect.x0, it.rect.y0), it.text, it.size, it.argb)
                    }
                }
            }
            true
        } catch (e: Exception) {
            editor.pending.addAll(items)
            vm.message("Couldn't apply changes: ${e.message}")
            false
        }
    }

    /** Signs with a stored digital ID (incremental update, so earlier signatures stay valid). */
    fun signWithCertificate(
        page: Int, rect: FRect, entry: com.tandidong.pdf.data.DigitalIdEntry, password: String,
        reason: String, location: String, includeInk: Boolean, fieldName: String? = null,
    ) {
        scope.launch {
            if (!flushPending()) return@launch
            val id = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.digitalIds.unlock(entry, password) }
            } catch (e: Exception) {
                vm.message("Wrong password for this digital ID")
                dialog = DocDialog.CertSign(page, rect, fieldName)
                return@launch
            }
            val ink = if (includeInk) vm.signatures.items.firstOrNull { !it.initials } else null
            val spec = com.tandidong.pdf.core.SignatureAppearanceSpec(page, rect, ink?.strokes, ink?.argb ?: 0xFF0D47A1.toInt(), ink?.aspect ?: 0f)
            val out = vm.scratchFile("signed-${System.nanoTime()}.pdf")
            try {
                val input = session.currentFile
                session.task("Signing…") {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.tandidong.pdf.core.DigitalSignatures.sign(
                            input, out, id, com.tandidong.pdf.core.SignRequest(reason, location, null, spec, fieldName), fonts,
                        )
                    }
                }
                session.adopt(out, "Signing…")
                editor.tool = Tool.NONE
                editor.category = null
                signedWarningAccepted = false
                dialog = DocDialog.Info(
                    "Document signed",
                    "Digitally signed by ${id.commonName}.\n\nSave the document to keep the signature. Adding more digital " +
                        "signatures keeps it valid, but any other change made afterwards will invalidate it.",
                )
            } catch (e: Exception) {
                vm.message("Couldn't sign: ${e.message}")
            } finally {
                out.delete()
            }
        }
    }

    fun undo() {
        if (editor.undoPending()) return
        scope.launch { session.undo() }
    }

    fun redo() {
        if (editor.redoPending()) return
        scope.launch { session.redo() }
    }

    val canUndo: Boolean get() = editor.pending.isNotEmpty() || session.canUndo
    val canRedo: Boolean get() = editor.redoStack.isNotEmpty() || session.canRedo

    // ------------------------------------------------------------------ ViewerInput

    override fun wantsDrag(hit: PageHit?): Boolean {
        if (hit == null) return false
        val t = editor.tool
        if (t == Tool.SELECT) {
            val sel = editor.selectedAnnot ?: return false
            return sel.pageIndex == hit.page && sel.displayRect.outset(10f).contains(hit.pt.x, hit.pt.y)
        }
        if (t == Tool.EDIT_IMAGE) {
            val sel = editor.selectedImage ?: return false
            return sel.pageIndex == hit.page && sel.displayRect.outset(handleRadius()).contains(hit.pt.x, hit.pt.y)
        }
        return t.dragTool
    }

    override fun tapsAreTools(): Boolean = editor.tool != Tool.NONE

    override fun onToggleChrome() {
        chrome = !chrome
    }

    override fun onDragStart(hit: PageHit) {
        dragStart = hit
        val t = editor.tool
        when {
            t.needsText -> prefetchText(hit.page)
            t == Tool.PEN -> editor.active = PendingItem.Ink(hit.page, listOf(listOf(hit.pt)), vm.prefs.penColor, vm.prefs.penWidth, 1f)
            t == Tool.SELECT -> editor.moveOffset = Pt(0f, 0f)
            t == Tool.EDIT_IMAGE -> {
                val r = editor.selectedImage?.displayRect
                imageDragStart = r
                imageDragMode = 0
                if (r != null) {
                    val corners = listOf(Pt(r.x0, r.y0), Pt(r.x1, r.y0), Pt(r.x1, r.y1), Pt(r.x0, r.y1))
                    val rad = handleRadius()
                    val idx = corners.indexOfFirst { hypot(it.x - hit.pt.x, it.y - hit.pt.y) <= rad }
                    imageDragMode = idx + 1 // 0 = move, 1..4 = corner
                }
            }
        }
        onDrag(hit)
    }

    override fun onDrag(hit: PageHit) {
        val start = dragStart ?: return
        val page = start.page
        val t = editor.tool
        when {
            t.isMarkup || t == Tool.REDACT_TEXT -> {
                val text = editor.pageTexts[page]
                val rects = textRects(text, start.pt, hit.pt)
                if (t == Tool.REDACT_TEXT) {
                    editor.activeRedact = rects.map { RedactMark(page, it) }
                } else {
                    val kind = t.markupKind ?: return
                    val color = if (t == Tool.HIGHLIGHT) vm.prefs.highlightColor else vm.prefs.shapeColor
                    editor.active = PendingItem.Markup(page, kind, rects, color, selectedText(text, start.pt, hit.pt))
                }
            }
            t == Tool.PEN -> {
                val ink = editor.active as? PendingItem.Ink ?: return
                val stroke = ink.strokes.last()
                val last = stroke.last()
                if (hypot(hit.pt.x - last.x, hit.pt.y - last.y) >= 0.8f / max(0.3f, viewer.zoom)) {
                    editor.active = ink.copy(strokes = ink.strokes.dropLast(1) + listOf(stroke + hit.pt))
                }
            }
            t.shapeKind != null -> {
                val kind = t.shapeKind ?: return
                editor.active = PendingItem.Shape(page, kind, start.pt, hit.pt, vm.prefs.shapeColor, null, vm.prefs.penWidth)
            }
            t == Tool.REDACT_AREA -> editor.activeRedact = listOf(RedactMark(page, FRect.of(start.pt, hit.pt)))
            t == Tool.WHITEOUT -> editor.whiteoutRect = page to FRect.of(start.pt, hit.pt)
            t == Tool.CERT_SIGN -> editor.certRect = page to FRect.of(start.pt, hit.pt)
            t.newFieldType != null -> editor.fieldRect = page to FRect.of(start.pt, hit.pt)
            t == Tool.LINK -> editor.linkRect = page to FRect.of(start.pt, hit.pt)
            t == Tool.EDIT_IMAGE -> {
                val r0 = imageDragStart ?: return
                editor.imageDrag = if (imageDragMode == 0) {
                    r0.offset(hit.pt.x - start.pt.x, hit.pt.y - start.pt.y)
                } else {
                    // Resize from a corner, keeping proportions; the opposite corner stays put.
                    val fixed = when (imageDragMode) {
                        1 -> Pt(r0.x1, r0.y1)
                        2 -> Pt(r0.x0, r0.y1)
                        3 -> Pt(r0.x0, r0.y0)
                        else -> Pt(r0.x1, r0.y0)
                    }
                    val sx = abs(hit.pt.x - fixed.x) / max(1f, r0.width)
                    val sy = abs(hit.pt.y - fixed.y) / max(1f, r0.height)
                    val sc = max(max(sx, sy), 8f / max(1f, min(r0.width, r0.height)))
                    val w = r0.width * sc
                    val h = r0.height * sc
                    val left = imageDragMode == 1 || imageDragMode == 4
                    val top = imageDragMode == 1 || imageDragMode == 2
                    FRect(
                        if (left) fixed.x - w else fixed.x, if (top) fixed.y - h else fixed.y,
                        if (left) fixed.x else fixed.x + w, if (top) fixed.y else fixed.y + h,
                    )
                }
            }
            t == Tool.SELECT -> editor.moveOffset = Pt(hit.pt.x - start.pt.x, hit.pt.y - start.pt.y)
        }
    }

    override fun onDragEnd() {
        val t = editor.tool
        when {
            t == Tool.REDACT_AREA || t == Tool.REDACT_TEXT -> {
                editor.activeRedact.filter { it.rect.width > 2f && it.rect.height > 2f }.forEach { editor.redactions.add(it) }
                editor.activeRedact = emptyList()
            }
            t == Tool.WHITEOUT -> {
                val wr = editor.whiteoutRect
                editor.whiteoutRect = null
                if (wr != null && wr.second.width > 2f && wr.second.height > 2f) whiteout(wr.first, wr.second)
            }
            t == Tool.CERT_SIGN -> {
                val cr = editor.certRect
                editor.certRect = null
                if (cr != null && cr.second.width > 24f && cr.second.height > 12f) {
                    dialog = DocDialog.CertSign(cr.first, cr.second)
                } else {
                    vm.message("Drag a larger box for the signature")
                }
            }
            t == Tool.LINK -> {
                val lr = editor.linkRect
                editor.linkRect = null
                if (lr != null && lr.second.width > 6f && lr.second.height > 4f) dialog = DocDialog.NewLink(lr.first, lr.second)
                else vm.message("Drag a box over the text or area to link")
            }
            t == Tool.EDIT_IMAGE -> {
                val sel = editor.selectedImage
                val target = editor.imageDrag
                if (sel != null && target != null &&
                    (abs(target.x0 - sel.displayRect.x0) > 0.5f || abs(target.y0 - sel.displayRect.y0) > 0.5f ||
                        abs(target.width - sel.displayRect.width) > 0.5f)
                ) {
                    scope.launch {
                        try {
                            reselectImage = sel.pageIndex to sel.ordinal
                            val ok = session.edit(if (imageDragMode == 0) "Moving image…" else "Resizing image…") {
                                com.tandidong.pdf.core.PageImages.transform(it, sel, target)
                            }
                            if (!ok) { reselectImage = null; editor.imageDrag = null; vm.message("The page changed; select the image again") }
                        } catch (e: Exception) {
                            reselectImage = null
                            editor.imageDrag = null
                            vm.message("Couldn't move the image: ${e.message}")
                        }
                    }
                } else {
                    editor.imageDrag = null
                }
            }
            t.newFieldType != null -> {
                val fr = editor.fieldRect
                editor.fieldRect = null
                val type = t.newFieldType
                if (fr != null && type != null) {
                    val (pg, r) = fr
                    if (r.width < 6f || r.height < 6f) {
                        newFieldAt(type, pg, Pt(r.centerX, r.centerY))
                    } else {
                        val rect = if (type == com.tandidong.pdf.core.NewFieldType.CHECKBOX || type == com.tandidong.pdf.core.NewFieldType.RADIO) {
                            val s = min(r.width, r.height)
                            FRect(r.x0, r.y0, r.x0 + s, r.y0 + s)
                        } else r
                        dialog = DocDialog.NewFormField(type, pg, clampToPage(pg, rect))
                    }
                }
            }
            t == Tool.SELECT -> {
                val sel = editor.selectedAnnot
                val off = editor.moveOffset
                editor.moveOffset = Pt(0f, 0f)
                if (sel != null && (abs(off.x) > 0.5f || abs(off.y) > 0.5f)) {
                    scope.launch {
                        try {
                            session.edit("Moving…") { AnnotationEditor.move(it, sel.pageIndex, sel.index, off.x, off.y) }
                            editor.selectedAnnot = sel.copy(displayRect = sel.displayRect.offset(off.x, off.y))
                            loadAnnotations()
                        } catch (e: Exception) {
                            vm.message("Couldn't move: ${e.message}")
                        }
                    }
                }
            }
            else -> {
                val a = editor.active
                editor.active = null
                if (a != null && isMeaningful(a)) editor.addPending(a)
            }
        }
        dragStart = null
    }

    override fun onDragCancel() {
        editor.active = null
        editor.activeRedact = emptyList()
        editor.whiteoutRect = null
        editor.certRect = null
        editor.fieldRect = null
        editor.linkRect = null
        editor.imageDrag = null
        editor.moveOffset = Pt(0f, 0f)
        dragStart = null
    }

    private fun isMeaningful(p: PendingItem): Boolean = when (p) {
        is PendingItem.Markup -> p.rects.isNotEmpty() && p.rects.any { it.width > 1f }
        is PendingItem.Shape -> hypot(p.end.x - p.start.x, p.end.y - p.start.y) > 3f
        is PendingItem.Ink -> p.strokes.isNotEmpty()
        else -> true
    }

    /** Text-aware selection rectangles between two points, or a plain rectangle on image-only pages. */
    private fun textRects(text: PageText?, a: Pt, b: Pt): List<FRect> {
        if (text == null || text.isBlank) return listOf(FRect.of(a, b)).filter { it.width > 1f }
        val i = text.nearestCharIndex(a.x, a.y)
        val j = text.nearestCharIndex(b.x, b.y)
        if (i < 0 || j < 0) return emptyList()
        return text.rectsFor(min(i, j), max(i, j) + 1)
    }

    private fun selectedText(text: PageText?, a: Pt, b: Pt): String? {
        if (text == null || text.isBlank) return null
        val i = text.nearestCharIndex(a.x, a.y)
        val j = text.nearestCharIndex(b.x, b.y)
        if (i < 0 || j < 0) return null
        return text.text.substring(min(i, j), min(text.text.length, max(i, j) + 1)).trim()
    }

    override fun onTap(hit: PageHit?): Boolean {
        val t = editor.tool
        if (t == Tool.NONE) {
            if (hit == null) return false
            if (editor.selection != null) { editor.selection = null; return true }
            // Links
            links[hit.page]?.firstOrNull { it.rect.outset(2f).contains(hit.pt.x, hit.pt.y) }?.let { link ->
                openLink(link)
                return true
            }
            loadLinks(hit.page)
            // Form fields can be filled directly in view mode.
            fieldAt(hit)?.let { f ->
                if (!session.restricted) openField(f, hit.page)
                return true
            }
            return false
        }
        if (hit == null || !hit.inside) return true
        val p = hit.page
        val pt = hit.pt
        when (t) {
            Tool.NOTE -> dialog = DocDialog.Note(p, pt)
            Tool.TEXT_BOX -> dialog = DocDialog.TextEntry(p, pt, TextPurpose.COMMENT_BOX)
            Tool.FILL_TEXT -> {
                val f = fieldAt(hit)
                if (f != null) openField(f, p) else dialog = DocDialog.TextEntry(p, pt, TextPurpose.FILL)
            }
            Tool.ADD_TEXT -> dialog = DocDialog.TextEntry(p, pt, TextPurpose.CONTENT)
            Tool.FILL_DATE -> {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                editor.addPending(textBox(p, pt, date, 12f, vm.prefs.textColor, border = false, asContent = false))
            }
            Tool.FILL_CHECK, Tool.FILL_CROSS -> {
                val size = 14f
                val r = FRect(pt.x - size / 2, pt.y - size / 2, pt.x + size / 2, pt.y + size / 2)
                val strokes = if (t == Tool.FILL_CHECK) Symbols.CHECK else Symbols.CROSS
                editor.addPending(PendingItem.Signature(p, r, strokes, vm.prefs.textColor, 1.8f, if (t == Tool.FILL_CHECK) "Check" else "Cross"))
            }
            Tool.FILL_DOT -> {
                val s = 3.5f
                editor.addPending(PendingItem.Shape(p, ShapeKind.ELLIPSE, Pt(pt.x - s, pt.y - s), Pt(pt.x + s, pt.y + s), vm.prefs.textColor, vm.prefs.textColor, 0.5f))
            }
            Tool.STAMP -> {
                val w = 150f
                val h = 46f
                editor.addPending(PendingItem.Stamp(p, FRect(pt.x - w / 2, pt.y - h / 2, pt.x + w / 2, pt.y + h / 2), editor.stamp))
            }
            Tool.SIGNATURE -> placeSignature(editor.signature, p, pt, false)
            Tool.INITIALS -> placeSignature(editor.initials, p, pt, true)
            Tool.ADD_IMAGE -> {
                val bmp = editor.imageToPlace
                if (bmp == null) vm.message("Choose an image first") else placeImage(bmp, p, pt)
            }
            Tool.EDIT_TEXT -> scope.launch {
                val text = pageText(p)
                val lineIndex = text?.lines?.indexOfFirst { it.box.outset(2f).contains(pt.x, pt.y) } ?: -1
                val line = text?.lines?.getOrNull(lineIndex)
                if (text == null || line == null) {
                    vm.message("No text here. Use Add text to insert new text.")
                } else {
                    val para = com.tandidong.pdf.core.TextStyles.paragraphAround(text, lineIndex)?.takeIf { it.lineCount > 1 }
                    dialog = DocDialog.EditText(p, line, text.text.substring(line.start, min(line.end, text.text.length)), para)
                }
            }
            Tool.ERASER -> erase(hit)
            Tool.SELECT -> {
                editor.selectedAnnot = AnnotationEditor.hitTest(editor.annotations, p, pt.x, pt.y)
            }
            Tool.FORM -> fieldAt(hit)?.let { openField(it, p) }
            Tool.EDIT_IMAGE -> scope.launch {
                val list = imagesFor(p)
                val img = com.tandidong.pdf.core.PageImages.hitTest(list, p, pt.x, pt.y)
                editor.selectedImage = img
                if (img == null) vm.message(if (list.isEmpty()) "No editable images on this page" else "Tap an image to select it")
            }
            Tool.LINK -> {
                val link = links[p]?.firstOrNull { it.rect.outset(2f).contains(pt.x, pt.y) }
                if (link != null) dialog = DocDialog.LinkActions(p, pt, link)
                else vm.message("Drag a box to create a link")
            }
            Tool.FIELD_EDIT -> {
                val f = fieldAt(hit)
                if (f != null) dialog = DocDialog.FieldProperties(f) else vm.message("Tap a form field to rename or delete it")
            }
            Tool.REDACT_AREA, Tool.REDACT_TEXT -> {
                val mark = editor.redactions.lastOrNull { it.page == p && it.rect.contains(pt.x, pt.y) }
                if (mark != null) editor.redactions.remove(mark)
                else if (t == Tool.REDACT_TEXT) markWordForRedaction(p, pt)
            }
            else -> if (t.isMarkup) markupWordAt(p, pt) else t.newFieldType?.let { newFieldAt(it, p, pt) }
        }
        return true
    }

    // ------------------------------------------------------------------ prepare form

    /** Opens the new-field dialog for a default-sized field centred on [pt]. */
    private fun newFieldAt(type: com.tandidong.pdf.core.NewFieldType, page: Int, pt: Pt) {
        val (w, h) = when (type) {
            com.tandidong.pdf.core.NewFieldType.TEXT -> 160f to 22f
            com.tandidong.pdf.core.NewFieldType.MULTILINE -> 220f to 64f
            com.tandidong.pdf.core.NewFieldType.CHECKBOX, com.tandidong.pdf.core.NewFieldType.RADIO -> 14f to 14f
            com.tandidong.pdf.core.NewFieldType.DROPDOWN -> 150f to 22f
            com.tandidong.pdf.core.NewFieldType.LIST -> 150f to 64f
            com.tandidong.pdf.core.NewFieldType.SIGNATURE -> 180f to 48f
        }
        val r = FRect(pt.x - w / 2f, pt.y - h / 2f, pt.x + w / 2f, pt.y + h / 2f)
        dialog = DocDialog.NewFormField(type, page, clampToPage(page, r))
    }

    /** Moves [r] inside the page bounds (display space) without resizing it when possible. */
    private fun clampToPage(page: Int, r: FRect): FRect {
        val geo = session.geometries.getOrNull(page) ?: return r
        val pw = geo.displayWidth
        val ph = geo.displayHeight
        val w = min(r.width, pw)
        val h = min(r.height, ph)
        val x0 = r.x0.coerceIn(0f, max(0f, pw - w))
        val y0 = r.y0.coerceIn(0f, max(0f, ph - h))
        return FRect(x0, y0, x0 + w, y0 + h)
    }

    fun addFormField(field: com.tandidong.pdf.core.NewField) {
        scope.launch {
            try {
                session.edit("Adding field…") { com.tandidong.pdf.core.FormBuilder.addFields(it, listOf(field)) }
                vm.message("${field.type.label} added")
            } catch (e: Exception) {
                vm.message("Couldn't add the field: ${e.message}")
            }
        }
    }

    fun deleteFormField(f: FormField) {
        scope.launch {
            try {
                val ok = session.edit("Deleting field…") { com.tandidong.pdf.core.FormBuilder.deleteField(it, f.name) }
                vm.message(if (ok) "Field deleted" else "Couldn't delete the field")
            } catch (e: Exception) {
                vm.message("Couldn't delete the field: ${e.message}")
            }
        }
    }

    fun renameFormField(f: FormField, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == f.name) return
        scope.launch {
            try {
                val taken = session.read { it.documentCatalog.acroForm?.getField(name) != null }
                if (taken) { vm.message("A field named \"$name\" already exists"); return@launch }
                val ok = session.edit("Renaming field…") { com.tandidong.pdf.core.FormBuilder.renameField(it, f.name, name) }
                if (!ok) vm.message("Couldn't rename the field")
            } catch (e: Exception) {
                vm.message("Couldn't rename the field: ${e.message}")
            }
        }
    }

    private fun textBox(page: Int, at: Pt, text: String, size: Float, argb: Int, border: Boolean, asContent: Boolean): PendingItem.TextBox {
        val lines = text.split('\n')
        // Helvetica metrics are a good layout estimate for the box; the real font is chosen on save.
        val widest = lines.maxOf {
            com.tandidong.pdf.core.FontSelector.width(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, sanitizeAscii(it), size)
        }
        val pad = if (border) 6f else 2f
        val w = max(widest + pad * 2 + 4f, 24f)
        val h = lines.size * size * 1.18f + pad * 2
        return PendingItem.TextBox(page, FRect(at.x, at.y, at.x + w, at.y + h), text, size, argb, border, asContent)
    }

    private fun sanitizeAscii(s: String) = s.map { if (it.code in 32..126) it else 'M' }.joinToString("")

    fun addTextFromDialog(page: Int, at: Pt, text: String, size: Float, argb: Int, purpose: TextPurpose) {
        if (text.isBlank()) return
        val item = when (purpose) {
            TextPurpose.COMMENT_BOX -> textBox(page, at, text, size, argb, border = true, asContent = false)
            TextPurpose.FILL -> textBox(page, at, text, size, argb, border = false, asContent = false)
            TextPurpose.CONTENT -> textBox(page, at, text, size, argb, border = false, asContent = true)
        }
        editor.addPending(item)
    }

    fun addNote(page: Int, at: Pt, text: String) {
        if (text.isBlank()) return
        editor.addPending(PendingItem.Note(page, at, text, 0xFFFFC107.toInt()))
    }

    fun placeSignature(sig: SavedSignature?, page: Int, at: Pt, initials: Boolean) {
        if (sig == null) {
            dialog = DocDialog.Signature(initials, page to at)
            return
        }
        val w = if (initials) 60f else 150f
        val h = w / max(0.5f, sig.aspect)
        val r = FRect(at.x - w / 2, at.y - h / 2, at.x + w / 2, at.y + h / 2)
        editor.addPending(PendingItem.Signature(page, r, sig.strokes, sig.argb, sig.width * w / 300f + 0.6f, if (initials) "Initials" else "Signature"))
    }

    private fun placeImage(bmp: Bitmap, page: Int, at: Pt) {
        val g = session.geometries.getOrNull(page) ?: return
        val w = min(220f, g.displayWidth * 0.6f)
        val h = w * bmp.height / max(1, bmp.width)
        editor.addPending(PendingItem.Image(page, FRect(at.x - w / 2, at.y - h / 2, at.x + w / 2, at.y + h / 2), bmp))
    }

    private fun markupWordAt(page: Int, pt: Pt) {
        scope.launch {
            val text = pageText(page) ?: return@launch
            val i = text.charIndexAt(pt.x, pt.y, 3f)
            if (i < 0) return@launch
            val range = text.wordRangeAt(i)
            if (range.isEmpty()) return@launch
            val kind = editor.tool.markupKind ?: return@launch
            val color = if (editor.tool == Tool.HIGHLIGHT) vm.prefs.highlightColor else vm.prefs.shapeColor
            editor.addPending(PendingItem.Markup(page, kind, text.rectsFor(range.first, range.last + 1), color, text.text.substring(range.first, range.last + 1)))
        }
    }

    private fun markWordForRedaction(page: Int, pt: Pt) {
        scope.launch {
            val text = pageText(page) ?: return@launch
            val i = text.charIndexAt(pt.x, pt.y, 3f)
            if (i < 0) return@launch
            val range = text.wordRangeAt(i)
            text.rectsFor(range.first, range.last + 1).forEach { editor.redactions.add(RedactMark(page, it)) }
        }
    }

    /** Fills [f], or for an empty signature field starts signing with a digital ID. */
    fun openField(f: FormField, page: Int) {
        if (f.kind == com.tandidong.pdf.core.FieldKind.SIGNATURE) {
            if (f.value.isNotEmpty()) {
                verifySignatures()
                panel = Panel.SIGNATURES
            } else {
                val w = f.widgets.firstOrNull { it.pageIndex == page } ?: f.widgets.firstOrNull() ?: return
                dialog = DocDialog.CertSign(w.pageIndex, w.displayRect, f.name)
            }
            return
        }
        dialog = DocDialog.FieldEdit(f)
    }

    private fun fieldAt(hit: PageHit): FormField? = editor.formFields.firstOrNull { f ->
        f.widgets.any { it.pageIndex == hit.page && it.displayRect.outset(2f).contains(hit.pt.x, hit.pt.y) }
    }

    private fun openLink(link: PageLink) {
        val target = link.targetPage
        if (target != null) {
            viewer.scrollToPage(target)
        } else if (!link.uri.isNullOrBlank()) {
            pendingExternalUri = link.uri
        }
    }

    /** Set when the user taps a web link; the screen confirms before opening it. */
    var pendingExternalUri by mutableStateOf<String?>(null)

    private fun erase(hit: PageHit) {
        val pending = editor.pending.lastOrNull { it.page == hit.page && it.bounds().outset(6f).contains(hit.pt.x, hit.pt.y) }
        if (pending != null) {
            editor.pending.remove(pending)
            return
        }
        val a = AnnotationEditor.hitTest(editor.annotations, hit.page, hit.pt.x, hit.pt.y) ?: return
        scope.launch {
            try {
                session.edit("Deleting…") { AnnotationEditor.delete(it, a.pageIndex, a.index) }
                loadAnnotations()
            } catch (e: Exception) {
                vm.message("Couldn't delete: ${e.message}")
            }
        }
    }

    fun deleteAnnotation(a: AnnotationInfo) {
        scope.launch {
            try {
                session.edit("Deleting…") { AnnotationEditor.delete(it, a.pageIndex, a.index) }
                editor.selectedAnnot = null
                loadAnnotations()
            } catch (e: Exception) {
                vm.message("Couldn't delete: ${e.message}")
            }
        }
    }

    fun recolorAnnotation(a: AnnotationInfo, argb: Int) {
        scope.launch {
            try {
                session.edit("Updating…") { AnnotationEditor.setColor(it, a.pageIndex, a.index, argb) }
                loadAnnotations()
            } catch (e: Exception) {
                vm.message("Couldn't update: ${e.message}")
            }
        }
    }

    fun setAnnotationText(a: AnnotationInfo, text: String) {
        scope.launch {
            try {
                session.edit("Updating…") { AnnotationEditor.setContents(it, a.pageIndex, a.index, text) }
                loadAnnotations()
            } catch (e: Exception) {
                vm.message("Couldn't update: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ text selection (long press)

    override fun onLongPress(hit: PageHit?) {
        if (hit == null || editor.tool != Tool.NONE) return
        scope.launch {
            val text = pageText(hit.page) ?: return@launch
            val i = text.charIndexAt(hit.pt.x, hit.pt.y, 4f).takeIf { it >= 0 } ?: text.nearestCharIndex(hit.pt.x, hit.pt.y)
            if (i < 0) return@launch
            val range = text.wordRangeAt(i)
            if (range.isEmpty()) return@launch
            editor.selection = TextSelection(hit.page, range.first, range.last)
        }
    }

    override fun onLongPressDrag(hit: PageHit) {
        val sel = editor.selection ?: return
        if (hit.page != sel.page) return
        val text = editor.pageTexts[sel.page] ?: return
        val j = text.nearestCharIndex(hit.pt.x, hit.pt.y)
        if (j >= 0) editor.selection = sel.copy(focus = j)
    }

    fun selectionText(): String? {
        val sel = editor.selection ?: return null
        val text = editor.pageTexts[sel.page] ?: return null
        return text.text.substring(sel.start.coerceIn(0, text.text.length), sel.end.coerceIn(0, text.text.length))
    }

    fun selectionRects(): List<FRect> {
        val sel = editor.selection ?: return emptyList()
        return editor.pageTexts[sel.page]?.rectsFor(sel.start, sel.end) ?: emptyList()
    }

    fun copySelection() {
        val t = selectionText() ?: return
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PDF text", t))
        vm.message("Copied")
        editor.selection = null
    }

    /** Creates a markup from the current selection and saves it immediately. */
    fun markupSelection(tool: Tool) {
        val sel = editor.selection ?: return
        val kind = tool.markupKind ?: return
        val rects = selectionRects()
        if (rects.isEmpty()) return
        val color = if (tool == Tool.HIGHLIGHT) vm.prefs.highlightColor else vm.prefs.shapeColor
        editor.addPending(PendingItem.Markup(sel.page, kind, rects, color, selectionText()))
        editor.selection = null
        commitPending()
    }

    fun redactSelection() {
        val sel = editor.selection ?: return
        selectionRects().forEach { editor.redactions.add(RedactMark(sel.page, it)) }
        editor.selection = null
        editor.category = ToolCategory.REDACT
        editor.tool = Tool.REDACT_TEXT
    }

    // ------------------------------------------------------------------ search

    fun search(query: String, matchCase: Boolean = false, wholeWord: Boolean = false) {
        editor.searchQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            editor.searchResults = emptyList()
            editor.searchIndex = -1
            return
        }
        searchJob = scope.launch {
            editor.searching = true
            editor.searchResults = emptyList()
            editor.searchIndex = -1
            val results = ArrayList<TextMatch>()
            try {
                val n = session.pageCount
                val first = viewer.currentPage()
                for (k in 0 until n) {
                    val i = (first + k) % n
                    val t = pageText(i) ?: continue
                    val found = t.find(query, matchCase, wholeWord)
                    if (found.isNotEmpty()) {
                        results.addAll(found)
                        editor.searchResults = results.sortedWith(compareBy({ it.pageIndex }, { it.start }))
                        if (editor.searchIndex < 0) {
                            editor.searchIndex = editor.searchResults.indexOf(found.first())
                            revealMatch(found.first())
                        }
                    }
                    editor.searchProgress = (k + 1f) / n
                }
                if (results.isEmpty()) vm.message("No matches for \"$query\"")
            } finally {
                editor.searching = false
            }
        }
    }

    fun nextMatch(delta: Int) {
        val r = editor.searchResults
        if (r.isEmpty()) return
        editor.searchIndex = ((editor.searchIndex + delta) % r.size + r.size) % r.size
        revealMatch(r[editor.searchIndex])
    }

    private fun revealMatch(m: TextMatch) {
        val rect = FRect.unionAll(m.rects) ?: return
        if (viewer.zoom < 1f) viewer.zoomTo(1f, androidx.compose.ui.geometry.Offset.Zero)
        viewer.reveal(m.pageIndex, rect)
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchOpen = false
        editor.searchResults = emptyList()
        editor.searchIndex = -1
        editor.searchQuery = ""
    }

    // ------------------------------------------------------------------ redaction

    fun applyRedactions(options: RedactionOptions) {
        val marks = editor.redactions.toList()
        if (marks.isEmpty()) return
        scope.launch {
            try {
                val geos = session.geometries
                val areas = marks.mapNotNull { m -> geos.getOrNull(m.page)?.let { RedactionArea(m.page, it.rectToUser(m.rect)) } }
                val report: RedactionReport = session.edit("Redacting…") { doc -> redactor().redact(doc, areas, options) }
                editor.redactions.clear()
                editor.category = null
                editor.tool = Tool.NONE
                dialog = DocDialog.Info("Redaction applied", describe(report, marks.size))
            } catch (e: Exception) {
                vm.message("Redaction failed: ${e.message}")
            }
        }
    }

    private fun describe(r: RedactionReport, areas: Int): String = buildString {
        append("$areas area${if (areas == 1) "" else "s"} permanently redacted.\n\n")
        append("• ${r.glyphsRemoved} characters removed\n")
        append("• ${r.imagesRedacted + r.imagesRemoved} images cleaned\n")
        append("• ${r.pathsRemoved} graphics removed\n")
        append("• ${r.annotationsRemoved} comments/fields removed\n")
        if (r.pagesRasterized.isNotEmpty()) {
            append("\nPages ${r.pagesRasterized.joinToString { (it + 1).toString() }} contained content that could not be edited precisely and were converted to images to guarantee removal.")
        }
        append("\nSave the document to keep the redacted version. Consider also using Sanitize to remove hidden information.")
    }

    /** Marks every match of [terms] and the selected [patterns] across the document. */
    fun markMatches(terms: List<String>, patterns: List<Regex>, matchCase: Boolean, wholeWord: Boolean) {
        scope.launch {
            session.task("Searching…") {
                var count = 0
                val n = session.pageCount
                for (i in 0 until n) {
                    val t = pageText(i) ?: continue
                    val matches = terms.filter { it.isNotBlank() }.flatMap { t.find(it, matchCase, wholeWord) } + patterns.flatMap { t.findRegex(it) }
                    for (m in matches) for (r in m.rects) { editor.redactions.add(RedactMark(i, r.outset(0.5f))); count++ }
                    session.progress = (i + 1f) / n
                }
                editor.category = ToolCategory.REDACT
                vm.message(if (count == 0) "No matches found" else "Marked $count area${if (count == 1) "" else "s"} for redaction. Review, then tap Apply.")
            }
        }
    }

    fun markPages(pages: List<Int>) {
        val geos = session.geometries
        for (p in pages) {
            val g = geos.getOrNull(p) ?: continue
            editor.redactions.add(RedactMark(p, FRect(0f, 0f, g.displayWidth, g.displayHeight)))
        }
        editor.category = ToolCategory.REDACT
    }

    private fun whiteout(page: Int, rect: FRect) {
        scope.launch {
            try {
                val g = session.geometries.getOrNull(page) ?: return@launch
                session.edit("Applying whiteout…") { doc ->
                    redactor().redact(doc, listOf(RedactionArea(page, g.rectToUser(rect))),
                        RedactionOptions(fillArgb = 0xFFFFFFFF.toInt(), removeAnnotations = false))
                }
            } catch (e: Exception) {
                vm.message("Whiteout failed: ${e.message}")
            }
        }
    }

    fun replaceText(page: Int, area: FRect, newText: String, format: com.tandidong.pdf.core.TextFormat) {
        scope.launch {
            try {
                val res = session.edit("Editing text…") { doc ->
                    TextEditor(redactor(), fonts).replace(doc, page, area, newText, format)
                }
                if (newText.isNotBlank()) {
                    vm.message(if (res.reusedOriginalFont) "Text replaced in the original font" else "Text replaced using ${res.fontUsed}")
                }
            } catch (e: Exception) {
                vm.message("Couldn't edit text: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ forms

    fun fillField(field: FormField, value: String) {
        scope.launch {
            try {
                val failed = session.edit("Filling form…") { Forms.fill(it, mapOf(field.name to value)) }
                if (failed.isNotEmpty()) vm.message("Couldn't set ${failed.joinToString()}")
            } catch (e: Exception) {
                vm.message("Couldn't fill field: ${e.message}")
            }
        }
    }

    fun launchJob(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    fun viewerScope(): CoroutineScope = scope

    /** Image chosen for an image watermark, consumed by the watermark dialog. */
    var pendingWatermarkImage by mutableStateOf<Bitmap?>(null)
}
