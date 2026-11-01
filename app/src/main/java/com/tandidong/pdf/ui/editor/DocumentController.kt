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
    data class EditText(val page: Int, val line: TextLine, val text: String) : DocDialog
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
}

enum class Panel { BOOKMARKS, COMMENTS, FIELDS }

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
        editor.searchResults = emptyList()
        editor.searchIndex = -1
        if (editor.tool == Tool.ERASER || editor.tool == Tool.SELECT || panel == Panel.COMMENTS) loadAnnotations()
        loadFormFields()
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

    fun loadLinks(page: Int) {
        if (links.containsKey(page)) return
        scope.launch {
            val l = session.links(page)
            links = links + (page to l)
        }
    }

    // ------------------------------------------------------------------ tools

    fun openCategory(c: ToolCategory) {
        if (session.restricted) {
            vm.message("Editing is restricted by the document's security settings")
            return
        }
        editor.category = c
        editor.tool = Tool.NONE
        editor.selection = null
        if (c == ToolCategory.FILL_SIGN) loadFormFields()
    }

    fun selectTool(t: Tool) {
        editor.tool = if (editor.tool == t) Tool.NONE else t
        editor.selection = null
        editor.selectedAnnot = null
        if (editor.tool == Tool.ERASER || editor.tool == Tool.SELECT) loadAnnotations()
        if (editor.tool.needsText) viewer.visiblePages().forEach { prefetchText(it) }
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
    }

    fun requestCloseCategory() {
        when {
            editor.category == ToolCategory.REDACT && editor.redactions.isNotEmpty() -> dialog = DocDialog.ApplyRedactions
            editor.pending.isNotEmpty() -> commitPending().also { closeCategory(false) }
            else -> closeCategory(false)
        }
    }

    fun commitPending(onDone: () -> Unit = {}) {
        val items = editor.pending.toList()
        if (items.isEmpty()) { onDone(); return }
        editor.pending.clear()
        editor.redoStack.clear()
        scope.launch {
            try {
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
                onDone()
            } catch (e: Exception) {
                editor.pending.addAll(items)
                vm.message("Couldn't apply changes: ${e.message}")
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
                if (!session.restricted) dialog = DocDialog.FieldEdit(f)
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
                dialog = if (f != null) DocDialog.FieldEdit(f) else DocDialog.TextEntry(p, pt, TextPurpose.FILL)
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
                val line = text?.lines?.firstOrNull { it.box.outset(2f).contains(pt.x, pt.y) }
                if (text == null || line == null) {
                    vm.message("No text here. Use Add text to insert new text.")
                } else {
                    dialog = DocDialog.EditText(p, line, text.text.substring(line.start, min(line.end, text.text.length)))
                }
            }
            Tool.ERASER -> erase(hit)
            Tool.SELECT -> {
                editor.selectedAnnot = AnnotationEditor.hitTest(editor.annotations, p, pt.x, pt.y)
            }
            Tool.FORM -> fieldAt(hit)?.let { dialog = DocDialog.FieldEdit(it) }
            Tool.REDACT_AREA, Tool.REDACT_TEXT -> {
                val mark = editor.redactions.lastOrNull { it.page == p && it.rect.contains(pt.x, pt.y) }
                if (mark != null) editor.redactions.remove(mark)
                else if (t == Tool.REDACT_TEXT) markWordForRedaction(p, pt)
            }
            else -> if (t.isMarkup) markupWordAt(p, pt)
        }
        return true
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

    fun replaceText(page: Int, line: TextLine, newText: String, size: Float) {
        scope.launch {
            try {
                session.edit("Editing text…") { doc ->
                    TextEditor(redactor(), fonts).replace(doc, page, line.box, newText, size, vm.prefs.textColor, line.bold)
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
