package com.tandidong.pdf.core

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.contentstream.operator.text.BeginText
import com.tom_roush.pdfbox.contentstream.operator.text.EndText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveTextSetLeading
import com.tom_roush.pdfbox.contentstream.operator.text.NextLine
import com.tom_roush.pdfbox.contentstream.operator.text.SetCharSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.SetFontAndSize
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextHorizontalScaling
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextLeading
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRenderingMode
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRise
import com.tom_roush.pdfbox.contentstream.operator.text.SetWordSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.ShowText
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextAdjusted
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLine
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLineAndSpace
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.font.encoding.GlyphList
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Per-font vertical extent cache: [descent, ascent] in text-space (em) units. */
internal class FontMetricsCache {
    private val cache = IdentityHashMap<PDFont, FloatArray>()

    fun extent(font: PDFont): FloatArray = cache.getOrPut(font) { computeExtent(font) }

    companion object {
        fun computeExtent(font: PDFont): FloatArray {
            try {
                if (font is PDType3Font) {
                    val bbox = font.fontBBox
                    val m = font.fontMatrix
                    if (bbox != null && bbox.height > 0f) {
                        val a = m.mapPoint(bbox.lowerLeftX, bbox.lowerLeftY)
                        val b = m.mapPoint(bbox.upperRightX, bbox.upperRightY)
                        val lo = min(a.y, b.y)
                        val hi = max(a.y, b.y)
                        if (hi > lo && hi - lo < 5f) return floatArrayOf(min(lo, -0.1f), max(hi, 0.5f))
                    }
                    return floatArrayOf(-0.2f, 0.8f)
                }
                var asc = 0f
                var desc = 0f
                val fd = font.fontDescriptor
                if (fd != null) {
                    asc = fd.ascent
                    desc = fd.descent
                    if (asc <= 0f) asc = fd.capHeight
                }
                if (asc <= 0f || asc > 1500f || desc > 0f || desc < -1000f) {
                    val bb = try { font.boundingBox } catch (e: Exception) { null }
                    if (bb != null) {
                        if (asc <= 0f || asc > 1500f) asc = bb.upperRightY
                        if (desc > 0f || desc < -1000f) desc = bb.lowerLeftY
                    }
                }
                if (asc <= 0f || asc > 1500f) asc = 800f
                if (desc > 0f || desc < -1000f) desc = -200f
                asc = asc.coerceIn(500f, 1100f)
                desc = desc.coerceIn(-400f, -100f)
                return floatArrayOf(desc / 1000f, asc / 1000f)
            } catch (e: Exception) {
                return floatArrayOf(-0.2f, 0.8f)
            }
        }
    }
}

/** Shared glyph box computation so text search rectangles and redaction agree exactly. */
internal object GlyphGeometry {
    /** Glyph quad corners in user space: bottom-left, bottom-right, top-right, top-left. */
    fun corners(trm: Matrix, font: PDFont, code: Int, displacement: Vector, extent: FloatArray): Array<Pt> {
        val desc = extent[0]
        val asc = extent[1]
        return if (!font.isVertical) {
            val w = max(displacement.x, 0f)
            arrayOf(trm.mapPoint(0f, desc), trm.mapPoint(w, desc), trm.mapPoint(w, asc), trm.mapPoint(0f, asc))
        } else {
            val hw = try { font.getWidth(code) / 2000f } catch (e: Exception) { 0.5f }.let { if (it <= 0f) 0.5f else it }
            val h = min(displacement.y, -0.5f)
            arrayOf(trm.mapPoint(-hw, h), trm.mapPoint(hw, h), trm.mapPoint(hw, 0f), trm.mapPoint(-hw, 0f))
        }
    }
}

/** A glyph collected from a page, in user space. */
internal class Glyph(
    val unicode: String,
    val corners: Array<Pt>,
    val box: FRect,
    val fontSize: Float,
    val bold: Boolean,
)

/** Registers the text- and state-related operators that glyph-tracking engines need. */
internal fun PDFStreamEngine.registerTextOperators() {
    addOperator(BeginText())
    addOperator(EndText())
    addOperator(Concatenate())
    addOperator(Save())
    addOperator(Restore())
    addOperator(SetGraphicsStateParameters())
    addOperator(NextLine())
    addOperator(SetCharSpacing())
    addOperator(MoveText())
    addOperator(MoveTextSetLeading())
    addOperator(SetFontAndSize())
    addOperator(ShowText())
    addOperator(ShowTextAdjusted())
    addOperator(SetTextLeading())
    addOperator(SetMatrix())
    addOperator(SetTextRenderingMode())
    addOperator(SetTextRise())
    addOperator(SetWordSpacing())
    addOperator(SetTextHorizontalScaling())
    addOperator(ShowTextLine())
    addOperator(ShowTextLineAndSpace())
}

internal fun safeUnicode(font: PDFont, code: Int): String? = try {
    font.toUnicode(code) ?: font.toUnicode(code, GlyphList.getAdobeGlyphList())
} catch (e: Exception) {
    null
}

internal class GlyphCollector : PDFStreamEngine() {
    val glyphs = ArrayList<Glyph>()
    private val metrics = FontMetricsCache()

    init {
        registerTextOperators()
        addOperator(DrawObject())
    }

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        val unicode = safeUnicode(font, code) ?: return
        val corners = GlyphGeometry.corners(textRenderingMatrix, font, code, displacement, metrics.extent(font))
        val box = FRect.bounds(corners.asList()) ?: return
        val size = hypot(textRenderingMatrix.shearX, textRenderingMatrix.scaleY)
        val name = try { font.name } catch (e: Exception) { null }
        val bold = name?.contains("Bold", ignoreCase = true) == true ||
            name?.contains("Black", ignoreCase = true) == true ||
            name?.contains("Heavy", ignoreCase = true) == true
        glyphs.add(Glyph(unicode, corners, box, size, bold))
    }
}

data class TextMatch(val pageIndex: Int, val start: Int, val end: Int, val text: String, val rects: List<FRect>)

/** One visual line of text in reading order. */
data class TextLine(val start: Int, val end: Int, val box: FRect, val fontSize: Float, val bold: Boolean)

/**
 * The text of a page with a display-space box for every character.
 * Characters inserted for layout (spaces between words, newlines) have no box.
 */
class PageText internal constructor(
    val pageIndex: Int,
    val text: String,
    private val boxes: Array<FRect?>,
    private val lineOf: IntArray,
    val lines: List<TextLine>,
) {
    val isBlank: Boolean get() = text.isBlank()

    fun boxAt(index: Int): FRect? = boxes.getOrNull(index)

    /** Merged highlight rectangles (one per line) for text[start, end). */
    fun rectsFor(start: Int, end: Int): List<FRect> {
        val out = ArrayList<FRect>()
        var currentLine = -1
        var acc: FRect? = null
        for (i in max(0, start) until min(end, text.length)) {
            val b = boxes[i] ?: continue
            val line = lineOf[i]
            if (line != currentLine) {
                acc?.let { out.add(it) }
                acc = b
                currentLine = line
            } else {
                acc = acc?.union(b) ?: b
            }
        }
        acc?.let { out.add(it) }
        return out
    }

    fun find(query: String, matchCase: Boolean = false, wholeWord: Boolean = false): List<TextMatch> {
        val q = normalize(query.trim(), matchCase).first
        if (q.isEmpty()) return emptyList()
        val (norm, map) = normalize(text, matchCase)
        val out = ArrayList<TextMatch>()
        var from = 0
        while (true) {
            val idx = norm.indexOf(q, from)
            if (idx < 0) break
            from = idx + 1
            val start = map[idx]
            val end = map[idx + q.length - 1] + 1
            if (wholeWord && !isWordBoundary(start, end)) continue
            out.add(TextMatch(pageIndex, start, end, text.substring(start, end), rectsFor(start, end)))
        }
        return out
    }

    fun findRegex(regex: Regex): List<TextMatch> = regex.findAll(text).mapNotNull { m ->
        if (m.value.isBlank()) return@mapNotNull null
        val start = m.range.first
        val end = m.range.last + 1
        TextMatch(pageIndex, start, end, m.value, rectsFor(start, end))
    }.toList()

    private fun isWordBoundary(start: Int, end: Int): Boolean {
        val before = if (start > 0) text[start - 1] else ' '
        val after = if (end < text.length) text[end] else ' '
        return !before.isLetterOrDigit() && !after.isLetterOrDigit()
    }

    /** Index of the character nearest to the display point, or -1 if the page has no text. */
    fun nearestCharIndex(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in boxes.indices) {
            val b = boxes[i] ?: continue
            val dx = if (x < b.x0) b.x0 - x else if (x > b.x1) x - b.x1 else 0f
            val dy = if (y < b.y0) b.y0 - y else if (y > b.y1) y - b.y1 else 0f
            // Weight vertical distance more so the nearest line wins.
            val d = dx + dy * 3f
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** Character directly under the point (with [slop] points tolerance), or -1. */
    fun charIndexAt(x: Float, y: Float, slop: Float = 2f): Int {
        for (i in boxes.indices) {
            val b = boxes[i] ?: continue
            if (x >= b.x0 - slop && x <= b.x1 + slop && y >= b.y0 - slop && y <= b.y1 + slop) return i
        }
        return -1
    }

    fun wordRangeAt(index: Int): IntRange {
        if (index < 0 || index >= text.length) return IntRange.EMPTY
        if (!text[index].isLetterOrDigit()) return index..index
        var s = index
        var e = index
        while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '\'' || text[s - 1] == '-')) s--
        while (e < text.length - 1 && (text[e + 1].isLetterOrDigit() || text[e + 1] == '\'' || text[e + 1] == '-')) e++
        return s..e
    }

    companion object {
        /** Lowercases (optionally) and collapses whitespace, returning a map to original indices. */
        internal fun normalize(s: String, matchCase: Boolean): Pair<String, IntArray> {
            val sb = StringBuilder(s.length)
            val map = IntArray(s.length + 1)
            var lastSpace = false
            for (i in s.indices) {
                val c = s[i]
                if (c.isWhitespace()) {
                    if (lastSpace) continue
                    map[sb.length] = i
                    sb.append(' ')
                    lastSpace = true
                } else {
                    val ch = if (matchCase) c else c.lowercaseChar()
                    map[sb.length] = i
                    sb.append(ch)
                    lastSpace = false
                }
            }
            return sb.toString() to map
        }

        val EMPTY_BOXES = emptyArray<FRect?>()
    }
}

/** Extracts positioned text from pages. */
object TextExtractor {
    fun extract(doc: PDDocument, pageIndex: Int): PageText {
        val page = doc.getPage(pageIndex)
        val collector = GlyphCollector()
        try {
            collector.processPage(page)
        } catch (e: Exception) {
            CoreLog.w("Text extraction failed on page ${pageIndex + 1}", e)
        }
        return layout(pageIndex, PageGeometry.of(page), collector.glyphs)
    }

    fun extractAll(doc: PDDocument, progress: Progress? = null): List<PageText> {
        val n = doc.numberOfPages
        return (0 until n).map { i -> extract(doc, i).also { progress?.invoke(i + 1, n) } }
    }

    fun plainText(doc: PDDocument, pages: Iterable<Int> = 0 until doc.numberOfPages): String =
        pages.joinToString("\n\n") { extract(doc, it).text }

    private class G(
        val s: String,
        val box: FRect,
        /** Baseline direction angle (radians) in display space. */
        val angle: Float,
        /** Glyph bounds in its own reading frame (u along the baseline, v downward). */
        val rb: FRect,
        val size: Float,
        val bold: Boolean,
    )

    /** Projects a display point onto a reading frame with baseline direction [angle]. */
    private fun toReading(angle: Float, p: Pt): Pt {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        // u along the baseline; v along the "down" normal (rotate baseline by +90° in y-down space).
        return Pt(p.x * c + p.y * s, -p.x * s + p.y * c)
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = abs(a - b) % (2f * Math.PI.toFloat())
        if (d > Math.PI.toFloat()) d = 2f * Math.PI.toFloat() - d
        return d
    }

    internal fun layout(pageIndex: Int, geom: PageGeometry, glyphs: List<Glyph>): PageText {
        val gs = ArrayList<G>(glyphs.size)
        val seen = HashSet<Long>()
        for (g in glyphs) {
            if (g.unicode.isEmpty() || g.unicode.all { it.code < 32 && !it.isWhitespace() }) continue
            val dc = g.corners.map { geom.toDisplay(it) }
            val box = FRect.bounds(dc) ?: continue
            val up = dc[3] - dc[0]
            val upLen = hypot(up.x, up.y)
            // Baseline direction is the "up" vector rotated 90° clockwise (display space, y down).
            val angle = if (upLen > 1e-4f) kotlin.math.atan2(up.x, -up.y) else 0f
            // Skip exact duplicates (text drawn twice for a faux-bold effect).
            val key = (g.unicode.hashCode().toLong() shl 40) xor
                ((box.x0 * 2).toLong() and 0xFFFFF shl 20) xor ((box.y0 * 2).toLong() and 0xFFFFF)
            if (!g.unicode.isBlank() && !seen.add(key)) continue
            val rb = FRect.bounds(dc.map { toReading(angle, it) })!!
            val size = if (g.fontSize > 0f) g.fontSize else box.height
            gs.add(G(g.unicode, box, angle, rb, size, g.bold))
        }

        val sb = StringBuilder()
        val boxes = ArrayList<FRect?>()
        val lineIdx = ArrayList<Int>()
        val lines = ArrayList<TextLine>()

        var lineNo = -1
        var lineStart = 0
        var lineBox: FRect? = null
        var lineRb: FRect? = null
        var lineAngle = 0f
        var lineSizeSum = 0f
        var lineGlyphs = 0
        var lineBold = 0
        var last: G? = null
        var prevRb: FRect? = null

        fun closeLine() {
            val lb = lineBox ?: return
            lines.add(TextLine(lineStart, sb.length, lb, lineSizeSum / max(1, lineGlyphs), lineBold * 2 > lineGlyphs))
            sb.append('\n'); boxes.add(null); lineIdx.add(lineNo)
            lineBox = null; lineRb = null; lineSizeSum = 0f; lineGlyphs = 0; lineBold = 0; last = null; prevRb = null
        }

        for (g in gs) {
            val prev = last
            val cur = lineRb
            var newLine = cur == null || angleDiff(g.angle, lineAngle) > 0.12f
            // Express this glyph in the current line's reading frame.
            val grb = if (newLine) g.rb else FRect.bounds(listOf(
                toReading(lineAngle, Pt(g.box.x0, g.box.y0)), toReading(lineAngle, Pt(g.box.x1, g.box.y0)),
                toReading(lineAngle, Pt(g.box.x0, g.box.y1)), toReading(lineAngle, Pt(g.box.x1, g.box.y1)),
            ))!!.let { if (angleDiff(g.angle, lineAngle) < 1e-3f) g.rb else it }
            if (!newLine && cur != null && prev != null) {
                val h = min(cur.height, grb.height).coerceAtLeast(0.1f)
                val overlap = min(cur.y1, grb.y1) - max(cur.y0, grb.y0)
                if (overlap < 0.45f * h) newLine = true
                else if (grb.x1 < prevRb!!.x0 - h) newLine = true
            }
            val rbInLine = if (newLine) g.rb else grb
            if (newLine) {
                closeLine()
                lineNo++
                lineStart = sb.length
                lineAngle = g.angle
            } else if (prev != null) {
                val gap = rbInLine.x0 - prevRb!!.x1
                val h = min(prevRb!!.height, rbInLine.height)
                if (gap > 0.22f * h && !prev.s.endsWith(" ") && !g.s.startsWith(" ")) {
                    sb.append(' '); boxes.add(null); lineIdx.add(lineNo)
                }
            }
            for (ch in g.s) {
                sb.append(if (ch == ' ') ' ' else ch)
                boxes.add(g.box)
                lineIdx.add(lineNo)
            }
            lineBox = lineBox?.union(g.box) ?: g.box
            lineRb = lineRb?.union(rbInLine) ?: rbInLine
            lineSizeSum += g.size
            lineGlyphs++
            if (g.bold) lineBold++
            last = g
            prevRb = rbInLine
        }
        closeLine()
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') {
            sb.setLength(sb.length - 1); boxes.removeAt(boxes.size - 1); lineIdx.removeAt(lineIdx.size - 1)
        }
        return PageText(pageIndex, sb.toString(), boxes.toTypedArray(), lineIdx.toIntArray(), lines)
    }
}
