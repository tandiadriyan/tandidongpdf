package com.tandidong.pdf.core

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.util.Matrix
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class MarkupKind(val subtype: String) {
    HIGHLIGHT("Highlight"), UNDERLINE("Underline"), STRIKEOUT("StrikeOut"), SQUIGGLY("Squiggly")
}

enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, ARROW }

enum class StampPreset(val label: String, val argb: Int) {
    APPROVED("APPROVED", 0xFF2E7D32.toInt()),
    REVIEWED("REVIEWED", 0xFF1565C0.toInt()),
    CONFIDENTIAL("CONFIDENTIAL", 0xFFC62828.toInt()),
    DRAFT("DRAFT", 0xFF6A1B9A.toInt()),
    FINAL("FINAL", 0xFF2E7D32.toInt()),
    REJECTED("REJECTED", 0xFFC62828.toInt()),
    COMPLETED("COMPLETED", 0xFF00695C.toInt()),
    PAID("PAID", 0xFF2E7D32.toInt()),
    FOR_COMMENT("FOR COMMENT", 0xFFEF6C00.toInt()),
    VOID("VOID", 0xFF424242.toInt()),
}

/** New annotations to add. All geometry is in the page's display space (points, top-left origin). */
sealed class AnnotationSpec {
    abstract val pageIndex: Int

    data class Ink(override val pageIndex: Int, val strokes: List<List<Pt>>, val argb: Int, val width: Float, val opacity: Float = 1f) : AnnotationSpec()
    data class Shape(
        override val pageIndex: Int, val kind: ShapeKind, val start: Pt, val end: Pt,
        val strokeArgb: Int, val fillArgb: Int?, val width: Float, val opacity: Float = 1f,
    ) : AnnotationSpec()
    data class Markup(override val pageIndex: Int, val kind: MarkupKind, val rects: List<FRect>, val argb: Int, val text: String? = null) : AnnotationSpec()
    data class Note(override val pageIndex: Int, val at: Pt, val text: String, val argb: Int = 0xFFFFC107.toInt()) : AnnotationSpec()
    data class FreeText(
        override val pageIndex: Int, val rect: FRect, val text: String, val fontSize: Float,
        val textArgb: Int, val borderArgb: Int? = null, val fillArgb: Int? = null,
    ) : AnnotationSpec()
    data class Stamp(override val pageIndex: Int, val rect: FRect, val label: String, val argb: Int, val subLabel: String? = null) : AnnotationSpec()
    data class Image(override val pageIndex: Int, val rect: FRect, val image: (PDDocument) -> PDImageXObject, val title: String = "Image") : AnnotationSpec()
    /** Vector signature/initials; [strokes] are normalized to 0..1 within [rect] (y down). */
    data class Signature(override val pageIndex: Int, val rect: FRect, val strokes: List<List<Pt>>, val argb: Int, val width: Float, val title: String = "Signature") : AnnotationSpec()
}

data class AnnotationInfo(
    val pageIndex: Int,
    val index: Int,
    val subtype: String,
    val displayRect: FRect,
    val contents: String?,
    val author: String?,
    val argb: Int?,
    val modified: String?,
    val id: String?,
    /** Index (same page) of the comment this one replies to. */
    val replyTo: Int? = null,
)

/** Builds annotations with explicit appearance streams so every viewer renders them identically. */
class AnnotationWriter(private val author: String, private val fonts: FontSelector) {

    fun add(doc: PDDocument, specs: List<AnnotationSpec>): Int {
        var n = 0
        for (spec in specs) {
            if (spec.pageIndex !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(spec.pageIndex)
            val g = PageGeometry.of(page)
            val annot = when (spec) {
                is AnnotationSpec.Ink -> ink(doc, g, spec)
                is AnnotationSpec.Shape -> shape(doc, g, spec)
                is AnnotationSpec.Markup -> markup(doc, g, spec)
                is AnnotationSpec.Note -> note(doc, g, spec)
                is AnnotationSpec.FreeText -> freeText(doc, g, spec)
                is AnnotationSpec.Stamp -> stamp(doc, g, spec)
                is AnnotationSpec.Image -> image(doc, g, spec)
                is AnnotationSpec.Signature -> signature(doc, g, spec)
            } ?: continue
            annot.page = page
            annot.annotationName = UUID.randomUUID().toString()
            annot.setModifiedDate(Calendar.getInstance())
            annot.isPrinted = true
            if (annot is PDAnnotationMarkup) {
                annot.titlePopup = author
                annot.creationDate = Calendar.getInstance()
            }
            val list = page.annotations.toMutableList()
            list.add(annot)
            page.annotations = list
            n++
        }
        return n
    }

    private fun base(subtype: String): PDAnnotationMarkup {
        val a = PDAnnotationMarkup()
        a.cosObject.setName(COSName.SUBTYPE, subtype)
        return a
    }

    private fun ink(doc: PDDocument, g: PageGeometry, s: AnnotationSpec.Ink): PDAnnotation? {
        val strokes = s.strokes.filter { it.isNotEmpty() }.map { st -> st.map { g.toUser(it) } }
        if (strokes.isEmpty()) return null
        val a = base("Ink")
        val inkList = COSArray()
        for (st in strokes) inkList.add(floats(st.flatMap { listOf(it.x, it.y) }))
        a.cosObject.setItem(COSName.getPDFName("InkList"), inkList)
        a.color = color(s.argb)
        if (s.opacity < 1f) a.constantOpacity = s.opacity
        a.borderStyle = PDBorderStyleDictionary().apply { width = s.width }
        val bbox = FRect.bounds(strokes.flatten())!!.outset(s.width + 1f)
        a.rectangle = bbox.toPdRectangle()
        a.appearance = Appearances.inkAppearance(doc, bbox, strokes, s.argb, s.width, s.opacity)
        return a
    }

    private fun shape(doc: PDDocument, g: PageGeometry, s: AnnotationSpec.Shape): PDAnnotation? {
        val p1 = g.toUser(s.start)
        val p2 = g.toUser(s.end)
        if (hypot(p2.x - p1.x, p2.y - p1.y) < 1f) return null
        return when (s.kind) {
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> {
                val a = base(if (s.kind == ShapeKind.RECTANGLE) "Square" else "Circle")
                val rect = FRect.of(p1, p2)
                a.rectangle = rect.toPdRectangle()
                a.color = color(s.strokeArgb)
                s.fillArgb?.let { a.interiorColor = color(it) }
                if (s.opacity < 1f) a.constantOpacity = s.opacity
                a.borderStyle = PDBorderStyleDictionary().apply { width = s.width }
                a.appearance = Appearances.squareCircleAppearance(doc, rect, s.kind == ShapeKind.ELLIPSE, s.strokeArgb, s.fillArgb, s.width, s.opacity)
                a
            }
            ShapeKind.LINE, ShapeKind.ARROW -> {
                val a = base("Line")
                a.cosObject.setItem(COSName.L, floats(listOf(p1.x, p1.y, p2.x, p2.y)))
                if (s.kind == ShapeKind.ARROW) {
                    val le = COSArray()
                    le.add(COSName.getPDFName("None"))
                    le.add(COSName.getPDFName("OpenArrow"))
                    a.cosObject.setItem(COSName.LE, le)
                }
                a.color = color(s.strokeArgb)
                if (s.opacity < 1f) a.constantOpacity = s.opacity
                a.borderStyle = PDBorderStyleDictionary().apply { width = s.width }
                val ap = Appearances.lineAppearance(doc, p1, p2, s.kind == ShapeKind.ARROW, s.strokeArgb, s.width, s.opacity)
                a.rectangle = ap.second.toPdRectangle()
                a.appearance = ap.first
                a
            }
        }
    }

    private fun markup(doc: PDDocument, g: PageGeometry, s: AnnotationSpec.Markup): PDAnnotation? {
        if (s.rects.isEmpty()) return null
        val quads = s.rects.map { r ->
            listOf(g.toUser(r.x0, r.y0), g.toUser(r.x1, r.y0), g.toUser(r.x0, r.y1), g.toUser(r.x1, r.y1))
        }
        val a = base(s.kind.subtype)
        a.cosObject.setItem(COSName.getPDFName("QuadPoints"), floats(quads.flatMap { q -> q.flatMap { listOf(it.x, it.y) } }))
        a.color = color(s.argb)
        if (!s.text.isNullOrBlank()) a.contents = s.text
        val (ap, bbox) = Appearances.markupAppearance(doc, s.kind, quads, s.argb)
        a.rectangle = bbox.toPdRectangle()
        a.appearance = ap
        return a
    }

    private fun note(doc: PDDocument, g: PageGeometry, s: AnnotationSpec.Note): PDAnnotation {
        val a = PDAnnotationText()
        val disp = FRect(s.at.x, s.at.y, s.at.x + 22f, s.at.y + 22f)
        a.rectangle = g.rectToUser(disp).toPdRectangle()
        a.contents = s.text
        a.name = PDAnnotationText.NAME_COMMENT
        a.color = color(s.argb)
        a.isNoZoom = true
        a.isNoRotate = true
        a.appearance = Appearances.displayAligned(doc, g, disp) { cs, w, h -> Appearances.drawNoteIcon(cs, w, h, s.argb) }
        return a
    }

    private fun freeText(doc: PDDocument, g: PageGeometry, s: AnnotationSpec.FreeText): PDAnnotation? {
        if (s.text.isBlank()) return null
        val a = base("FreeText")
        val (font, text) = fonts.fontFor(doc, s.text)
        a.rectangle = g.rectToUser(s.rect).toPdRectangle()
        a.contents = text
        val r = Redactor.red(s.textArgb)
        val gg = Redactor.green(s.textArgb)
        val b = Redactor.blue(s.textArgb)
        a.cosObject.setString(COSName.DA, "/Helv ${fmt(s.fontSize)} Tf ${fmt(r)} ${fmt(gg)} ${fmt(b)} rg")
        a.cosObject.setString(COSName.getPDFName("DS"), "font: Helvetica ${fmt(s.fontSize)}pt; color: ${hex(s.textArgb)}")
        if (s.borderArgb == null && s.fillArgb == null) a.intent = PDAnnotationMarkup.IT_FREE_TEXT_TYPE_WRITER
        a.borderStyle = PDBorderStyleDictionary().apply { width = if (s.borderArgb != null) 1f else 0f }
        s.borderArgb?.let { a.color = color(it) }
        a.appearance = Appearances.displayAligned(doc, g, s.rect) { cs, w, h ->
            Appearances.drawTextBox(cs, w, h, font, text, s.fontSize, s.textArgb, s.borderArgb, s.fillArgb)
        }
        return a
    }

    private fun stamp(doc: PDDocument, g: PageGeometry, s: AnnotationSpec.Stamp): PDAnnotation {
        val a = PDAnnotationRubberStamp()
        a.rectangle = g.rectToUser(s.rect).toPdRectangle()
        a.name = s.label.replace(" ", "")
        a.contents = s.label
        a.color = color(s.argb)
        val (font, label) = fonts.fontFor(doc, s.label, bold = true)
        val sub = s.subLabel ?: "${author.ifBlank { "" }} ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}".trim()
        val (subFont, subText) = fonts.fontFor(doc, sub)
        a.appearance = Appearances.displayAligned(doc, g, s.rect) { cs, w, h ->
            Appearances.drawStamp(cs, w, h, font, label, subFont, subText, s.argb)
        }
        return a
    }

    private fun image(doc: PDDocument, g: PageGeometry, s: AnnotationSpec.Image): PDAnnotation {
        val a = PDAnnotationRubberStamp()
        a.rectangle = g.rectToUser(s.rect).toPdRectangle()
        a.name = s.title.replace(" ", "")
        a.contents = s.title
        val img = s.image(doc)
        a.appearance = Appearances.displayAligned(doc, g, s.rect) { cs, w, h -> cs.drawImage(img, 0f, 0f, w, h) }
        return a
    }

    private fun signature(doc: PDDocument, g: PageGeometry, s: AnnotationSpec.Signature): PDAnnotation? {
        if (s.strokes.none { it.isNotEmpty() }) return null
        val a = PDAnnotationRubberStamp()
        a.rectangle = g.rectToUser(s.rect).toPdRectangle()
        a.name = s.title.replace(" ", "")
        a.contents = s.title
        a.appearance = Appearances.displayAligned(doc, g, s.rect) { cs, w, h ->
            cs.setStrokingColor(Redactor.red(s.argb), Redactor.green(s.argb), Redactor.blue(s.argb))
            cs.setLineWidth(s.width)
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            for (stroke in s.strokes) {
                if (stroke.isEmpty()) continue
                Appearances.smoothPath(cs, stroke.map { Pt(it.x * w, (1f - it.y) * h) })
                cs.stroke()
            }
        }
        return a
    }

    companion object {
        fun color(argb: Int) = PDColor(floatArrayOf(Redactor.red(argb), Redactor.green(argb), Redactor.blue(argb)), PDDeviceRGB.INSTANCE)
        fun floats(values: List<Float>): COSArray = COSArray().also { arr -> values.forEach { arr.add(COSFloat(it)) } }
        fun fmt(v: Float): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
        fun hex(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)
    }
}

/** Appearance stream construction shared by creation, editing and flattening. */
internal object Appearances {
    private const val K = 0.5523f

    fun matrixArray(m: Matrix): COSArray = AnnotationWriter.floats(
        listOf(m.scaleX, m.shearY, m.shearX, m.scaleY, m.translateX, m.translateY)
    )

    fun newStream(doc: PDDocument, bbox: FRect, matrix: Matrix?, draw: (PDPageContentStream) -> Unit): PDAppearanceStream {
        val ap = PDAppearanceStream(doc)
        ap.bBox = PDRectangle(bbox.x0, bbox.y0, bbox.width, bbox.height)
        ap.resources = PDResources()
        if (matrix != null) ap.cosObject.setItem(COSName.MATRIX, matrixArray(matrix))
        PDPageContentStream(doc, ap).use { draw(it) }
        return ap
    }

    fun dict(ap: PDAppearanceStream): PDAppearanceDictionary = PDAppearanceDictionary().also { it.setNormalAppearance(ap) }

    /** Appearance drawn upright in display orientation inside the display rectangle [disp]. */
    fun displayAligned(doc: PDDocument, g: PageGeometry, disp: FRect, draw: (PDPageContentStream, Float, Float) -> Unit): PDAppearanceDictionary {
        val w = disp.width
        val h = disp.height
        val ap = newStream(doc, FRect(0f, 0f, w, h), g.appearanceRotationMatrix()) { cs -> draw(cs, w, h) }
        return dict(ap)
    }

    fun alpha(cs: PDPageContentStream, opacity: Float, multiply: Boolean = false) {
        if (opacity >= 1f && !multiply) return
        val gs = PDExtendedGraphicsState()
        gs.strokingAlphaConstant = opacity
        gs.nonStrokingAlphaConstant = opacity
        if (multiply) gs.cosObject.setItem(COSName.BM, COSName.getPDFName("Multiply"))
        cs.setGraphicsStateParameters(gs)
    }

    fun stroke(cs: PDPageContentStream, argb: Int) = cs.setStrokingColor(Redactor.red(argb), Redactor.green(argb), Redactor.blue(argb))
    fun fill(cs: PDPageContentStream, argb: Int) = cs.setNonStrokingColor(Redactor.red(argb), Redactor.green(argb), Redactor.blue(argb))

    fun smoothPath(cs: PDPageContentStream, pts: List<Pt>) {
        if (pts.isEmpty()) return
        cs.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 1) {
            cs.lineTo(pts[0].x + 0.01f, pts[0].y)
            return
        }
        if (pts.size == 2) {
            cs.lineTo(pts[1].x, pts[1].y)
            return
        }
        var cur = pts[0]
        for (i in 1 until pts.size - 1) {
            val ctrl = pts[i]
            val mid = Pt((pts[i].x + pts[i + 1].x) / 2f, (pts[i].y + pts[i + 1].y) / 2f)
            val c1 = Pt(cur.x + 2f / 3f * (ctrl.x - cur.x), cur.y + 2f / 3f * (ctrl.y - cur.y))
            val c2 = Pt(mid.x + 2f / 3f * (ctrl.x - mid.x), mid.y + 2f / 3f * (ctrl.y - mid.y))
            cs.curveTo(c1.x, c1.y, c2.x, c2.y, mid.x, mid.y)
            cur = mid
        }
        val last = pts.last()
        cs.lineTo(last.x, last.y)
    }

    fun ellipse(cs: PDPageContentStream, r: FRect) {
        val cx = r.centerX
        val cy = r.centerY
        val rx = r.width / 2f
        val ry = r.height / 2f
        cs.moveTo(cx + rx, cy)
        cs.curveTo(cx + rx, cy + K * ry, cx + K * rx, cy + ry, cx, cy + ry)
        cs.curveTo(cx - K * rx, cy + ry, cx - rx, cy + K * ry, cx - rx, cy)
        cs.curveTo(cx - rx, cy - K * ry, cx - K * rx, cy - ry, cx, cy - ry)
        cs.curveTo(cx + K * rx, cy - ry, cx + rx, cy - K * ry, cx + rx, cy)
        cs.closePath()
    }

    fun inkAppearance(doc: PDDocument, bbox: FRect, strokes: List<List<Pt>>, argb: Int, width: Float, opacity: Float): PDAppearanceDictionary =
        dict(newStream(doc, bbox, null) { cs ->
            alpha(cs, opacity)
            stroke(cs, argb)
            cs.setLineWidth(width)
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            for (st in strokes) {
                smoothPath(cs, st)
                cs.stroke()
            }
        })

    fun squareCircleAppearance(
        doc: PDDocument, rect: FRect, ellipse: Boolean, strokeArgb: Int, fillArgb: Int?, width: Float, opacity: Float,
    ): PDAppearanceDictionary = dict(newStream(doc, rect, null) { cs ->
        alpha(cs, opacity)
        val inner = FRect(rect.x0 + width / 2f, rect.y0 + width / 2f, rect.x1 - width / 2f, rect.y1 - width / 2f)
        val r = if (inner.isEmpty) rect else inner
        cs.setLineWidth(width)
        stroke(cs, strokeArgb)
        fillArgb?.let { fill(cs, it) }
        if (ellipse) ellipse(cs, r) else cs.addRect(r.x0, r.y0, r.width, r.height)
        if (fillArgb != null) cs.fillAndStroke() else cs.stroke()
    })

    /** Returns the appearance and the annotation rectangle (user space). */
    fun lineAppearance(
        doc: PDDocument, p1: Pt, p2: Pt, arrow: Boolean, argb: Int, width: Float, opacity: Float,
    ): Pair<PDAppearanceDictionary, FRect> {
        val headLen = max(8f, width * 4f)
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val len = max(0.001f, hypot(dx, dy))
        val ux = dx / len
        val uy = dy / len
        val left = Pt(p2.x - ux * headLen - uy * headLen * 0.5f, p2.y - uy * headLen + ux * headLen * 0.5f)
        val right = Pt(p2.x - ux * headLen + uy * headLen * 0.5f, p2.y - uy * headLen - ux * headLen * 0.5f)
        val pts = if (arrow) listOf(p1, p2, left, right) else listOf(p1, p2)
        val bbox = FRect.bounds(pts)!!.outset(width + 2f)
        val ap = newStream(doc, bbox, null) { cs ->
            alpha(cs, opacity)
            stroke(cs, argb)
            cs.setLineWidth(width)
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            cs.moveTo(p1.x, p1.y)
            cs.lineTo(p2.x, p2.y)
            if (arrow) {
                cs.moveTo(left.x, left.y)
                cs.lineTo(p2.x, p2.y)
                cs.lineTo(right.x, right.y)
            }
            cs.stroke()
        }
        return dict(ap) to bbox
    }

    private fun lerp(a: Pt, b: Pt, t: Float) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** Quads are [TL, TR, BL, BR] in user space. Returns appearance and its bounding box. */
    fun markupAppearance(doc: PDDocument, kind: MarkupKind, quads: List<List<Pt>>, argb: Int): Pair<PDAppearanceDictionary, FRect> {
        val bbox = FRect.bounds(quads.flatten())!!.outset(2f)
        val ap = newStream(doc, bbox, null) { cs ->
            when (kind) {
                MarkupKind.HIGHLIGHT -> {
                    alpha(cs, 1f, multiply = true)
                    fill(cs, argb)
                    for (q in quads) {
                        cs.moveTo(q[0].x, q[0].y); cs.lineTo(q[1].x, q[1].y)
                        cs.lineTo(q[3].x, q[3].y); cs.lineTo(q[2].x, q[2].y)
                        cs.closePath()
                    }
                    cs.fill()
                }
                MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                    stroke(cs, argb)
                    for (q in quads) {
                        val h = hypot(q[0].x - q[2].x, q[0].y - q[2].y)
                        cs.setLineWidth(max(0.6f, h / 14f))
                        val t = if (kind == MarkupKind.UNDERLINE) 0.1f else 0.42f
                        val a = lerp(q[2], q[0], t)
                        val b = lerp(q[3], q[1], t)
                        cs.moveTo(a.x, a.y); cs.lineTo(b.x, b.y)
                        cs.stroke()
                    }
                }
                MarkupKind.SQUIGGLY -> {
                    stroke(cs, argb)
                    for (q in quads) {
                        val h = hypot(q[0].x - q[2].x, q[0].y - q[2].y)
                        cs.setLineWidth(max(0.5f, h / 18f))
                        val base0 = lerp(q[2], q[0], 0.06f)
                        val base1 = lerp(q[3], q[1], 0.06f)
                        val len = hypot(base1.x - base0.x, base1.y - base0.y)
                        val amp = max(0.8f, h / 12f)
                        val period = amp * 2.5f
                        val steps = max(2, (len / (period / 2f)).toInt())
                        val nx = -(base1.y - base0.y) / max(len, 0.001f)
                        val ny = (base1.x - base0.x) / max(len, 0.001f)
                        for (i in 0..steps) {
                            val p = lerp(base0, base1, i.toFloat() / steps)
                            val off = if (i % 2 == 0) 0f else amp
                            val x = p.x + nx * off
                            val y = p.y + ny * off
                            if (i == 0) cs.moveTo(x, y) else cs.lineTo(x, y)
                        }
                        cs.stroke()
                    }
                }
            }
        }
        return dict(ap) to bbox
    }

    fun drawNoteIcon(cs: PDPageContentStream, w: Float, h: Float, argb: Int) {
        fill(cs, argb)
        stroke(cs, 0xFF5D4037.toInt())
        cs.setLineWidth(0.8f)
        // Speech bubble with a tail.
        cs.moveTo(1f, h - 1f); cs.lineTo(w - 1f, h - 1f); cs.lineTo(w - 1f, h * 0.3f)
        cs.lineTo(w * 0.45f, h * 0.3f); cs.lineTo(w * 0.25f, 1f); cs.lineTo(w * 0.3f, h * 0.3f)
        cs.lineTo(1f, h * 0.3f); cs.closePath()
        cs.fillAndStroke()
        stroke(cs, 0xFF5D4037.toInt())
        cs.setLineWidth(1f)
        for (i in 0 until 3) {
            val y = h * (0.82f - i * 0.15f)
            cs.moveTo(w * 0.18f, y); cs.lineTo(w * 0.82f, y)
        }
        cs.stroke()
    }

    fun drawTextBox(
        cs: PDPageContentStream, w: Float, h: Float, font: com.tom_roush.pdfbox.pdmodel.font.PDFont, text: String,
        fontSize: Float, textArgb: Int, borderArgb: Int?, fillArgb: Int?,
    ) {
        if (fillArgb != null) {
            fill(cs, fillArgb)
            cs.addRect(0f, 0f, w, h)
            cs.fill()
        }
        if (borderArgb != null) {
            stroke(cs, borderArgb)
            cs.setLineWidth(1f)
            cs.addRect(0.5f, 0.5f, w - 1f, h - 1f)
            cs.stroke()
        }
        val pad = if (borderArgb != null || fillArgb != null) 3f else 1f
        val lines = FontSelector.wrap(font, text, fontSize, max(1f, w - 2 * pad))
        cs.saveGraphicsState()
        cs.addRect(0f, 0f, w, h)
        cs.clip()
        fill(cs, textArgb)
        cs.beginText()
        cs.setFont(font, fontSize)
        val leading = fontSize * 1.18f
        cs.setLeading(leading)
        cs.newLineAtOffset(pad, h - pad - fontSize * 0.86f)
        for ((i, line) in lines.withIndex()) {
            if (i > 0) cs.newLine()
            cs.showText(line)
        }
        cs.endText()
        cs.restoreGraphicsState()
    }

    fun drawStamp(
        cs: PDPageContentStream, w: Float, h: Float,
        font: com.tom_roush.pdfbox.pdmodel.font.PDFont, label: String,
        subFont: com.tom_roush.pdfbox.pdmodel.font.PDFont, sub: String, argb: Int,
    ) {
        val lw = max(1.5f, min(w, h) / 18f)
        alpha(cs, 0.9f)
        stroke(cs, argb)
        cs.setLineWidth(lw)
        val r = min(min(w, h) / 6f, 10f)
        val x0 = lw; val y0 = lw; val x1 = w - lw; val y1 = h - lw
        cs.moveTo(x0 + r, y0); cs.lineTo(x1 - r, y0); cs.curveTo(x1, y0, x1, y0, x1, y0 + r)
        cs.lineTo(x1, y1 - r); cs.curveTo(x1, y1, x1, y1, x1 - r, y1)
        cs.lineTo(x0 + r, y1); cs.curveTo(x0, y1, x0, y1, x0, y1 - r)
        cs.lineTo(x0, y0 + r); cs.curveTo(x0, y0, x0, y0, x0 + r, y0)
        cs.closePath()
        cs.stroke()
        val hasSub = sub.isNotBlank() && h > 28f
        val mainArea = if (hasSub) h * 0.62f else h
        var size = mainArea * 0.55f
        val tw = FontSelector.width(font, label, size)
        if (tw > w * 0.86f) size *= (w * 0.86f) / tw
        fill(cs, argb)
        cs.beginText()
        cs.setFont(font, size)
        val mainBase = if (hasSub) h - mainArea / 2f - size * 0.35f - lw else h / 2f - size * 0.35f
        cs.newLineAtOffset((w - FontSelector.width(font, label, size)) / 2f, mainBase)
        cs.showText(label)
        cs.endText()
        if (hasSub) {
            var s2 = min(h * 0.16f, size * 0.45f)
            val sw = FontSelector.width(subFont, sub, s2)
            if (sw > w * 0.88f) s2 *= (w * 0.88f) / sw
            cs.beginText()
            cs.setFont(subFont, s2)
            cs.newLineAtOffset((w - FontSelector.width(subFont, sub, s2)) / 2f, lw + h * 0.12f)
            cs.showText(sub)
            cs.endText()
        }
    }

    /** Regenerates the normal appearance of annotation types this app knows how to draw. */
    fun regenerate(doc: PDDocument, annot: PDAnnotation): Boolean {
        val argb = argbOf(annot) ?: 0xFF000000.toInt()
        val d = annot.cosObject
        val bsWidth = (d.getDictionaryObject(COSName.BS) as? COSDictionary)?.getFloat(COSName.W, 1f) ?: 1f
        val opacity = d.getFloat(COSName.CA, 1f)
        when (annot.subtype) {
            "Highlight", "Underline", "StrikeOut", "Squiggly" -> {
                val qp = (d.getDictionaryObject(COSName.getPDFName("QuadPoints")) as? COSArray)?.toFloatArray() ?: return false
                val quads = qp.toList().chunked(8).filter { it.size == 8 }.map { q ->
                    listOf(Pt(q[0], q[1]), Pt(q[2], q[3]), Pt(q[4], q[5]), Pt(q[6], q[7]))
                }
                if (quads.isEmpty()) return false
                val kind = MarkupKind.values().first { it.subtype == annot.subtype }
                val (ap, bbox) = markupAppearance(doc, kind, quads, argb)
                annot.rectangle = bbox.toPdRectangle()
                annot.appearance = ap
                return true
            }
            "Square", "Circle" -> {
                val rect = annot.rectangle ?: return false
                val ic = (d.getDictionaryObject(COSName.IC) as? COSArray)?.toFloatArray()
                val fillArgb = if (ic != null && ic.size >= 3) rgbToArgb(ic[0], ic[1], ic[2]) else null
                annot.appearance = squareCircleAppearance(doc, FRect.of(rect), annot.subtype == "Circle", argb, fillArgb, bsWidth, opacity)
                return true
            }
            "Ink" -> {
                val list = d.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray ?: return false
                val strokes = (0 until list.size()).mapNotNull { i ->
                    (list.getObject(i) as? COSArray)?.toFloatArray()?.toList()?.chunked(2)?.filter { it.size == 2 }?.map { Pt(it[0], it[1]) }
                }.filter { it.isNotEmpty() }
                if (strokes.isEmpty()) return false
                val bbox = FRect.bounds(strokes.flatten())!!.outset(bsWidth + 1f)
                annot.rectangle = bbox.toPdRectangle()
                annot.appearance = inkAppearance(doc, bbox, strokes, argb, bsWidth, opacity)
                return true
            }
            "Line" -> {
                val l = (d.getDictionaryObject(COSName.L) as? COSArray)?.toFloatArray() ?: return false
                if (l.size < 4) return false
                val le = d.getDictionaryObject(COSName.LE) as? COSArray
                val arrow = le != null && le.size() >= 2 && (le.getObject(1) as? COSName)?.name?.contains("Arrow") == true
                val (ap, bbox) = lineAppearance(doc, Pt(l[0], l[1]), Pt(l[2], l[3]), arrow, argb, bsWidth, opacity)
                annot.rectangle = bbox.toPdRectangle()
                annot.appearance = ap
                return true
            }
        }
        return false
    }

    fun rgbToArgb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or ((r * 255f).toInt().coerceIn(0, 255) shl 16) or ((g * 255f).toInt().coerceIn(0, 255) shl 8) or (b * 255f).toInt().coerceIn(0, 255)

    fun argbOf(annot: PDAnnotation): Int? {
        val c = annot.cosObject.getDictionaryObject(COSName.C) as? COSArray ?: return null
        val v = c.toFloatArray()
        return when (v.size) {
            1 -> rgbToArgb(v[0], v[0], v[0])
            3 -> rgbToArgb(v[0], v[1], v[2])
            4 -> rgbToArgb((1 - v[0]) * (1 - v[3]), (1 - v[1]) * (1 - v[3]), (1 - v[2]) * (1 - v[3]))
            else -> null
        }
    }
}

/** Lists and edits existing annotations (comments) in a document. */
object AnnotationEditor {
    private val NON_COMMENT = setOf("Popup", "Widget", "Link")

    fun list(doc: PDDocument, pageIndex: Int? = null, includeLinks: Boolean = false): List<AnnotationInfo> {
        val out = ArrayList<AnnotationInfo>()
        val pages = if (pageIndex != null) listOf(pageIndex) else (0 until doc.numberOfPages).toList()
        for (pi in pages) {
            val page = doc.getPage(pi)
            val g = PageGeometry.of(page)
            val annots = try { page.annotations } catch (e: IOException) { continue }
            for ((i, a) in annots.withIndex()) {
                val st = a.subtype ?: continue
                if (st in NON_COMMENT && !(includeLinks && st == "Link")) continue
                if (a.isHidden || a.isNoView) continue
                val r = a.rectangle ?: continue
                val author = (a as? PDAnnotationMarkup)?.titlePopup
                val mod = try { a.modifiedDate } catch (e: Exception) { null }
                val irt = a.cosObject.getDictionaryObject(IRT)
                val parent = if (irt != null) annots.indexOfFirst { it.cosObject === irt }.takeIf { it >= 0 } else null
                out.add(AnnotationInfo(pi, i, st, g.rectToDisplay(FRect.of(r)), a.contents, author, Appearances.argbOf(a), mod, a.annotationName, parent))
            }
        }
        return out
    }

    private fun annotAt(doc: PDDocument, pageIndex: Int, index: Int): Pair<PDPage, PDAnnotation>? {
        if (pageIndex !in 0 until doc.numberOfPages) return null
        val page = doc.getPage(pageIndex)
        val a = page.annotations.getOrNull(index) ?: return null
        return page to a
    }

    fun delete(doc: PDDocument, pageIndex: Int, index: Int): Boolean {
        val (page, a) = annotAt(doc, pageIndex, index) ?: return false
        // Remove the comment, its popup, and the whole reply thread below it.
        val doomed = HashSet<Any>()
        doomed.add(a.cosObject)
        val all = page.annotations
        var grew = true
        while (grew) {
            grew = false
            for (x in all) {
                if (x.cosObject in doomed) continue
                val irt = x.cosObject.getDictionaryObject(IRT)
                if (irt != null && irt in doomed) { doomed.add(x.cosObject); grew = true }
            }
        }
        val popups = all.filter { it.cosObject in doomed }.mapNotNull { it.cosObject.getDictionaryObject(COSName.getPDFName("Popup")) }.toSet()
        page.annotations = all.filter { it.cosObject !in doomed && it.cosObject !in popups }
        return true
    }

    private val IRT = COSName.getPDFName("IRT")

    /** Adds a reply to the comment at [index]; replies draw nothing on the page. */
    fun reply(doc: PDDocument, pageIndex: Int, index: Int, text: String, author: String): Boolean {
        val (page, parent) = annotAt(doc, pageIndex, index) ?: return false
        val reply = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText()
        val r = parent.rectangle ?: return false
        reply.rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(r.lowerLeftX, r.lowerLeftY, r.width, r.height)
        reply.contents = text
        reply.titlePopup = author
        reply.setInReplyTo(parent)
        reply.replyType = "R"
        val now = java.util.GregorianCalendar()
        reply.setCreationDate(now)
        reply.setModifiedDate(now)
        reply.annotationName = java.util.UUID.randomUUID().toString()
        reply.isNoZoom = true
        reply.isNoRotate = true
        // An empty appearance keeps viewers from drawing a second note icon.
        val ap = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(doc)
        ap.bBox = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(0f, 0f, 1f, 1f)
        val dict = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary()
        dict.setNormalAppearance(ap)
        reply.appearance = dict
        reply.page = page
        page.annotations = page.annotations + reply
        return true
    }

    fun deleteAll(doc: PDDocument, pageIndex: Int? = null): Int {
        var n = 0
        val pages = if (pageIndex != null) listOf(pageIndex) else (0 until doc.numberOfPages).toList()
        for (pi in pages) {
            val page = doc.getPage(pi)
            val annots = page.annotations
            val keep = annots.filter { (it.subtype in setOf("Widget", "Link")) }
            n += annots.size - keep.size
            page.annotations = keep
        }
        return n
    }

    /** Moves an annotation by a display-space offset. */
    fun move(doc: PDDocument, pageIndex: Int, index: Int, dx: Float, dy: Float): Boolean {
        val (page, a) = annotAt(doc, pageIndex, index) ?: return false
        val g = PageGeometry.of(page)
        val o = g.toUser(0f, 0f)
        val t = g.toUser(dx, dy)
        val ux = t.x - o.x
        val uy = t.y - o.y
        shift(a, ux, uy)
        (a.cosObject.getDictionaryObject(COSName.getPDFName("Popup")) as? COSDictionary)?.let { p ->
            (p.getDictionaryObject(COSName.RECT) as? COSArray)?.let { shiftArray(it, ux, uy) }
        }
        a.setModifiedDate(Calendar.getInstance())
        return true
    }

    /** Sets a new display rectangle (move + resize); appearance streams scale to fit. */
    fun setRect(doc: PDDocument, pageIndex: Int, index: Int, displayRect: FRect): Boolean {
        val (page, a) = annotAt(doc, pageIndex, index) ?: return false
        val g = PageGeometry.of(page)
        val old = a.rectangle ?: return false
        val newUser = g.rectToUser(displayRect)
        if (a.subtype in setOf("Ink", "Line", "Highlight", "Underline", "StrikeOut", "Squiggly", "Polygon", "PolyLine")) {
            // Coordinates arrays must follow: map old rect onto new rect.
            val sx = if (old.width != 0f) newUser.width / old.width else 1f
            val sy = if (old.height != 0f) newUser.height / old.height else 1f
            transformArrays(a) { x, y -> Pt(newUser.x0 + (x - old.lowerLeftX) * sx, newUser.y0 + (y - old.lowerLeftY) * sy) }
            a.rectangle = newUser.toPdRectangle()
            Appearances.regenerate(doc, a)
        } else {
            a.rectangle = newUser.toPdRectangle()
        }
        a.setModifiedDate(Calendar.getInstance())
        return true
    }

    fun setContents(doc: PDDocument, pageIndex: Int, index: Int, text: String): Boolean {
        val (_, a) = annotAt(doc, pageIndex, index) ?: return false
        a.contents = text
        a.setModifiedDate(Calendar.getInstance())
        return true
    }

    fun setColor(doc: PDDocument, pageIndex: Int, index: Int, argb: Int): Boolean {
        val (_, a) = annotAt(doc, pageIndex, index) ?: return false
        a.color = AnnotationWriter.color(argb)
        Appearances.regenerate(doc, a)
        a.setModifiedDate(Calendar.getInstance())
        return true
    }

    private fun shift(a: PDAnnotation, ux: Float, uy: Float) {
        transformArrays(a) { x, y -> Pt(x + ux, y + uy) }
        val r = a.rectangle ?: return
        a.rectangle = PDRectangle(r.lowerLeftX + ux, r.lowerLeftY + uy, r.width, r.height)
    }

    private fun shiftArray(arr: COSArray, ux: Float, uy: Float) {
        for (i in 0 until arr.size()) {
            val n = arr.getObject(i) as? COSNumber ?: continue
            arr.set(i, COSFloat(n.floatValue() + if (i % 2 == 0) ux else uy))
        }
    }

    private fun transformArrays(a: PDAnnotation, f: (Float, Float) -> Pt) {
        val d = a.cosObject
        fun mapFlat(arr: COSArray) {
            var i = 0
            while (i + 1 < arr.size()) {
                val x = (arr.getObject(i) as? COSNumber)?.floatValue() ?: 0f
                val y = (arr.getObject(i + 1) as? COSNumber)?.floatValue() ?: 0f
                val p = f(x, y)
                arr.set(i, COSFloat(p.x))
                arr.set(i + 1, COSFloat(p.y))
                i += 2
            }
        }
        for (key in listOf("QuadPoints", "L", "Vertices", "CL")) {
            (d.getDictionaryObject(COSName.getPDFName(key)) as? COSArray)?.let { mapFlat(it) }
        }
        (d.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray)?.let { list ->
            for (i in 0 until list.size()) (list.getObject(i) as? COSArray)?.let { mapFlat(it) }
        }
    }

    /** Hit-tests annotations on a page at a display point; returns the topmost index. */
    fun hitTest(infos: List<AnnotationInfo>, pageIndex: Int, x: Float, y: Float, slop: Float = 6f): AnnotationInfo? =
        infos.lastOrNull { it.pageIndex == pageIndex && it.displayRect.outset(slop).contains(x, y) }

    fun isPopup(a: PDAnnotation) = a is PDAnnotationPopup
    @Suppress("unused") private fun abs0(v: Float) = abs(v)
}
