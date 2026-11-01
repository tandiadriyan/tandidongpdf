package com.tandidong.pdf.ui.editor

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tandidong.pdf.core.AnnotationInfo
import com.tandidong.pdf.core.AnnotationSpec
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.FormField
import com.tandidong.pdf.core.MarkupKind
import com.tandidong.pdf.core.PageText
import com.tandidong.pdf.core.Pt
import com.tandidong.pdf.core.ShapeKind
import com.tandidong.pdf.core.StampPreset
import com.tandidong.pdf.core.TextMatch
import com.tandidong.pdf.data.SavedSignature
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.util.concurrent.atomic.AtomicLong

enum class ToolCategory(val label: String) {
    COMMENT("Comment"), FILL_SIGN("Fill & Sign"), EDIT("Edit"), REDACT("Redact")
}

enum class Tool(val label: String, val category: ToolCategory?, val dragTool: Boolean = false) {
    NONE("View", null),
    HIGHLIGHT("Highlight", ToolCategory.COMMENT, true),
    UNDERLINE("Underline", ToolCategory.COMMENT, true),
    STRIKEOUT("Strikethrough", ToolCategory.COMMENT, true),
    SQUIGGLY("Squiggly", ToolCategory.COMMENT, true),
    PEN("Draw", ToolCategory.COMMENT, true),
    RECTANGLE("Rectangle", ToolCategory.COMMENT, true),
    ELLIPSE("Ellipse", ToolCategory.COMMENT, true),
    LINE("Line", ToolCategory.COMMENT, true),
    ARROW("Arrow", ToolCategory.COMMENT, true),
    NOTE("Note", ToolCategory.COMMENT),
    TEXT_BOX("Text box", ToolCategory.COMMENT),
    STAMP("Stamp", ToolCategory.COMMENT),
    ERASER("Eraser", ToolCategory.COMMENT),
    SELECT("Select", ToolCategory.COMMENT),
    FILL_TEXT("Text", ToolCategory.FILL_SIGN),
    FILL_DATE("Date", ToolCategory.FILL_SIGN),
    FILL_CHECK("Check", ToolCategory.FILL_SIGN),
    FILL_CROSS("Cross", ToolCategory.FILL_SIGN),
    FILL_DOT("Dot", ToolCategory.FILL_SIGN),
    SIGNATURE("Sign", ToolCategory.FILL_SIGN),
    INITIALS("Initials", ToolCategory.FILL_SIGN),
    FORM("Form fields", ToolCategory.FILL_SIGN),
    ADD_TEXT("Add text", ToolCategory.EDIT),
    EDIT_TEXT("Edit text", ToolCategory.EDIT),
    ADD_IMAGE("Add image", ToolCategory.EDIT),
    WHITEOUT("Whiteout", ToolCategory.EDIT, true),
    REDACT_AREA("Mark area", ToolCategory.REDACT, true),
    REDACT_TEXT("Mark text", ToolCategory.REDACT, true),
    ;

    val isMarkup: Boolean get() = this == HIGHLIGHT || this == UNDERLINE || this == STRIKEOUT || this == SQUIGGLY
    val markupKind: MarkupKind?
        get() = when (this) {
            HIGHLIGHT -> MarkupKind.HIGHLIGHT
            UNDERLINE -> MarkupKind.UNDERLINE
            STRIKEOUT -> MarkupKind.STRIKEOUT
            SQUIGGLY -> MarkupKind.SQUIGGLY
            else -> null
        }
    val shapeKind: ShapeKind?
        get() = when (this) {
            RECTANGLE -> ShapeKind.RECTANGLE
            ELLIPSE -> ShapeKind.ELLIPSE
            LINE -> ShapeKind.LINE
            ARROW -> ShapeKind.ARROW
            else -> null
        }
    val needsText: Boolean get() = isMarkup || this == REDACT_TEXT || this == EDIT_TEXT

    companion object {
        fun forCategory(c: ToolCategory) = values().filter { it.category == c }
    }
}

private val ids = AtomicLong(1)
fun nextId() = ids.getAndIncrement()

/** Uncommitted edits drawn as an overlay until the user taps Done. Geometry is display points. */
sealed class PendingItem {
    abstract val page: Int
    val id: Long = nextId()
    abstract fun bounds(): FRect

    data class Ink(override val page: Int, val strokes: List<List<Pt>>, val argb: Int, val width: Float, val opacity: Float) : PendingItem() {
        override fun bounds() = FRect.bounds(strokes.flatten())?.outset(width) ?: FRect(0f, 0f, 0f, 0f)
    }
    data class Shape(override val page: Int, val kind: ShapeKind, val start: Pt, val end: Pt, val argb: Int, val fill: Int?, val width: Float) : PendingItem() {
        override fun bounds() = FRect.of(start, end).outset(width + 6f)
    }
    data class Markup(override val page: Int, val kind: MarkupKind, val rects: List<FRect>, val argb: Int, val text: String?) : PendingItem() {
        override fun bounds() = FRect.unionAll(rects) ?: FRect(0f, 0f, 0f, 0f)
    }
    data class Note(override val page: Int, val at: Pt, val text: String, val argb: Int) : PendingItem() {
        override fun bounds() = FRect(at.x, at.y, at.x + 22f, at.y + 22f)
    }
    data class TextBox(override val page: Int, val rect: FRect, val text: String, val size: Float, val argb: Int, val border: Boolean, val asContent: Boolean = false) : PendingItem() {
        override fun bounds() = rect
    }
    data class Stamp(override val page: Int, val rect: FRect, val preset: StampPreset) : PendingItem() {
        override fun bounds() = rect
    }
    data class Signature(override val page: Int, val rect: FRect, val strokes: List<List<Pt>>, val argb: Int, val width: Float, val title: String) : PendingItem() {
        override fun bounds() = rect
    }
    data class Image(override val page: Int, val rect: FRect, val bitmap: Bitmap) : PendingItem() {
        override fun bounds() = rect
    }

    /** Converts to a core annotation spec (null for items written as page content). */
    fun toSpec(): AnnotationSpec? = when (this) {
        is Ink -> AnnotationSpec.Ink(page, strokes, argb, width, opacity)
        is Shape -> AnnotationSpec.Shape(page, kind, start, end, argb, fill, width)
        is Markup -> AnnotationSpec.Markup(page, kind, rects, argb, text)
        is Note -> AnnotationSpec.Note(page, at, text, argb)
        is TextBox -> if (asContent) null else AnnotationSpec.FreeText(page, rect, text, size, argb, if (border) argb else null, null)
        is Stamp -> AnnotationSpec.Stamp(page, rect, preset.label, preset.argb)
        is Signature -> AnnotationSpec.Signature(page, rect, strokes, argb, width, title)
        is Image -> AnnotationSpec.Image(page, rect, { doc ->
            if (bitmap.hasAlpha()) LosslessFactory.createFromImage(doc, bitmap) else JPEGFactory.createFromImage(doc, bitmap, 0.9f)
        })
    }
}

/** A rectangle marked for redaction (display points). */
data class RedactMark(val page: Int, val rect: FRect, val id: Long = nextId())

data class TextSelection(val page: Int, val anchor: Int, val focus: Int) {
    val start: Int get() = minOf(anchor, focus)
    val end: Int get() = maxOf(anchor, focus) + 1
}

object Symbols {
    val CHECK = listOf(listOf(Pt(0.08f, 0.55f), Pt(0.38f, 0.88f), Pt(0.92f, 0.12f)))
    val CROSS = listOf(listOf(Pt(0.12f, 0.12f), Pt(0.88f, 0.88f)), listOf(Pt(0.88f, 0.12f), Pt(0.12f, 0.88f)))
}

@Stable
class EditorState {
    var tool by mutableStateOf(Tool.NONE)
    var category by mutableStateOf<ToolCategory?>(null)
    val pending = mutableStateListOf<PendingItem>()
    val redoStack = ArrayList<PendingItem>()
    var active by mutableStateOf<PendingItem?>(null)
    val redactions = mutableStateListOf<RedactMark>()
    var activeRedact by mutableStateOf<List<RedactMark>>(emptyList())
    var stamp by mutableStateOf(StampPreset.APPROVED)
    var signature by mutableStateOf<SavedSignature?>(null)
    var initials by mutableStateOf<SavedSignature?>(null)
    var imageToPlace by mutableStateOf<Bitmap?>(null)
    var selection by mutableStateOf<TextSelection?>(null)
    var annotations by mutableStateOf<List<AnnotationInfo>>(emptyList())
    var selectedAnnot by mutableStateOf<AnnotationInfo?>(null)
    var moveOffset by mutableStateOf(Pt(0f, 0f))
    var formFields by mutableStateOf<List<FormField>>(emptyList())
    var whiteoutRect by mutableStateOf<Pair<Int, FRect>?>(null)
    val pageTexts = mutableStateMapOf<Int, PageText>()

    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<TextMatch>>(emptyList())
    var searchIndex by mutableIntStateOf(-1)
    var searching by mutableStateOf(false)
    var searchProgress by mutableStateOf(0f)

    fun undoPending(): Boolean {
        if (pending.isEmpty()) return false
        redoStack.add(pending.removeAt(pending.size - 1))
        return true
    }

    fun redoPending(): Boolean {
        if (redoStack.isEmpty()) return false
        pending.add(redoStack.removeAt(redoStack.size - 1))
        return true
    }

    fun addPending(item: PendingItem) {
        pending.add(item)
        redoStack.clear()
    }
}
