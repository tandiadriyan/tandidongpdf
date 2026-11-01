package com.tandidong.pdf.core

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorN
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor
import com.tom_roush.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class FontFamily(val label: String) { SANS("Sans serif"), SERIF("Serif"), MONO("Monospace") }

/** The look of existing text, used to match it when the text is edited. */
data class TextStyle(
    /** PostScript name without the subset prefix, e.g. "Calibri-Bold". */
    val fontName: String,
    val family: FontFamily,
    val bold: Boolean,
    val italic: Boolean,
    val size: Float,
    val argb: Int,
    /** Baseline origin of the first glyph in display space; null if the text is not upright. */
    val origin: Pt?,
) {
    val description: String
        get() = fontName + " · " + "%.1f".format(size) + " pt"
}

/** How replacement text should look; null fields mean "as the original". */
data class TextFormat(
    val size: Float? = null,
    val argb: Int? = null,
    val family: FontFamily? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    /** Reuse the document's own font when it contains every needed glyph. */
    val keepOriginalFont: Boolean = true,
    /** Re-wrap the text to this width (display points), as when editing a paragraph. */
    val wrapWidth: Float? = null,
    /** Baseline-to-baseline distance; defaults to 1.2 × size. */
    val leading: Float? = null,
)

/** Consecutive lines that read as one paragraph. [rect] is display space. */
data class Paragraph(val firstLine: Int, val lastLine: Int, val rect: FRect, val text: String, val leading: Float, val fontSize: Float) {
    val lineCount: Int get() = lastLine - firstLine + 1
}

object TextStyles {

    /** Describes the text whose glyph centers fall inside [displayRect]. */
    fun probe(doc: PDDocument, pageIndex: Int, displayRect: FRect): TextStyle? = probeWithFont(doc, pageIndex, displayRect)?.first

    internal fun probeWithFont(doc: PDDocument, pageIndex: Int, displayRect: FRect): Pair<TextStyle, PDFont>? {
        val page = doc.getPage(pageIndex)
        val probe = StyleProbe(page, displayRect)
        try {
            probe.processPage(page)
        } catch (e: Exception) {
            CoreLog.w("Style probe failed", e)
        }
        val hits = probe.hits
        if (hits.isEmpty()) return null
        // The most common font/size/color wins (ignores a stray symbol in another font).
        val main = hits.groupBy { Triple(it.font, Math.round(it.size * 2f), it.rgb) }.maxByOrNull { it.value.size }!!.value
        val first = main.first()
        val font = first.font
        val name = cleanName(font)
        val desc = try { font.fontDescriptor } catch (e: Exception) { null }
        val lower = name.lowercase()
        val mono = desc?.isFixedPitch == true || listOf("courier", "mono", "consol", "menlo", "code").any { lower.contains(it) }
        val serif = !mono && (desc?.isSerif == true || listOf("times", "serif", "georgia", "garamond", "cambria", "book", "minion",
            "palatino", "roman", "baskerville", "caslon", "didot", "bodoni", "century", "schoolbook", "mincho", "song").any { lower.contains(it) }) &&
            !lower.contains("sans")
        val bold = desc?.isForceBold == true || (desc?.fontWeight ?: 0f) >= 600f ||
            listOf("bold", "black", "heavy", "semibold", "demi").any { lower.contains(it) }
        val italic = desc?.isItalic == true || abs(desc?.italicAngle ?: 0f) > 0.5f || lower.contains("italic") || lower.contains("oblique")
        val style = TextStyle(
            fontName = name,
            family = if (mono) FontFamily.MONO else if (serif) FontFamily.SERIF else FontFamily.SANS,
            bold = bold,
            italic = italic,
            size = main.map { it.size }.sorted()[main.size / 2],
            argb = 0xFF000000.toInt() or (first.rgb and 0xFFFFFF),
            origin = if (first.upright) first.origin else null,
        )
        return style to font
    }

    private fun cleanName(font: PDFont): String {
        val raw = try { font.name } catch (e: Exception) { null } ?: return "Unknown font"
        return if (raw.length > 7 && raw[6] == '+' && raw.substring(0, 6).all { it in 'A'..'Z' }) raw.substring(7) else raw
    }

    /**
     * Groups the lines around [lineIndex] that share a size, left edge and regular spacing,
     * i.e. the paragraph a reader would see.
     */
    fun paragraphAround(text: PageText, lineIndex: Int): Paragraph? {
        val lines = text.lines
        val base = lines.getOrNull(lineIndex) ?: return null
        fun gapOk(upper: TextLine, lower: TextLine, size: Float): Boolean {
            val gap = lower.box.y0 - upper.box.y1
            return gap > -size * 0.3f && gap < size * 1.0f
        }
        fun sameBlock(a: TextLine, b: TextLine): Boolean {
            val size = max(a.fontSize, b.fontSize)
            if (abs(a.fontSize - b.fontSize) > max(0.6f, size * 0.08f)) return false
            // Same left edge (allowing a first-line indent) and overlapping horizontally.
            val leftOk = abs(a.box.x0 - b.box.x0) <= max(4f, size * 2.2f)
            val overlap = min(a.box.x1, b.box.x1) - max(a.box.x0, b.box.x0)
            return leftOk && overlap > 0f
        }
        var first = lineIndex
        while (first > 0) {
            val up = lines[first - 1]
            val cur = lines[first]
            if (!sameBlock(up, cur) || !gapOk(up, cur, cur.fontSize)) break
            first--
        }
        var last = lineIndex
        while (last < lines.size - 1) {
            val cur = lines[last]
            val down = lines[last + 1]
            if (!sameBlock(cur, down) || !gapOk(cur, down, cur.fontSize)) break
            last++
        }
        val group = lines.subList(first, last + 1)
        val rect = FRect.unionAll(group.map { it.box }) ?: return null
        val joined = StringBuilder()
        for (l in group) {
            val t = text.text.substring(l.start, min(l.end, text.text.length)).trim()
            if (t.isEmpty()) continue
            if (joined.isNotEmpty()) {
                // Re-join words hyphenated across lines.
                if (joined.endsWith("-") && joined.length > 1 && joined[joined.length - 2].isLetter() && t.first().isLowerCase()) {
                    joined.setLength(joined.length - 1)
                } else joined.append(' ')
            }
            joined.append(t)
        }
        val leading = if (group.size > 1) {
            val gaps = group.zipWithNext { a, b -> b.box.y1 - a.box.y1 }.sorted()
            gaps[gaps.size / 2]
        } else base.fontSize * 1.2f
        return Paragraph(first, last, rect, joined.toString(), leading, group.map { it.fontSize }.sorted()[group.size / 2])
    }

    /** The standard 14 font closest to the given family and weight/style. */
    fun standardFont(family: FontFamily, bold: Boolean, italic: Boolean): PDType1Font = when (family) {
        FontFamily.SANS -> when {
            bold && italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
            bold -> PDType1Font.HELVETICA_BOLD
            italic -> PDType1Font.HELVETICA_OBLIQUE
            else -> PDType1Font.HELVETICA
        }
        FontFamily.SERIF -> when {
            bold && italic -> PDType1Font.TIMES_BOLD_ITALIC
            bold -> PDType1Font.TIMES_BOLD
            italic -> PDType1Font.TIMES_ITALIC
            else -> PDType1Font.TIMES_ROMAN
        }
        FontFamily.MONO -> when {
            bold && italic -> PDType1Font.COURIER_BOLD_OBLIQUE
            bold -> PDType1Font.COURIER_BOLD
            italic -> PDType1Font.COURIER_OBLIQUE
            else -> PDType1Font.COURIER
        }
    }

    /** True when [font] can draw every character of [text] with real glyphs (not .notdef). */
    fun canRender(font: PDFont, text: String): Boolean {
        if (font is PDType3Font) return false
        return try {
            val bytes = font.encode(text)
            val input = ByteArrayInputStream(bytes)
            when {
                font is PDVectorFont -> {
                    while (input.available() > 0) if (!font.hasGlyph(font.readCode(input))) return false
                    true
                }
                font is PDSimpleFont -> {
                    // Non-embedded fonts are drawn by the viewer's substitute, just like the original text.
                    if (!font.isEmbedded) return true
                    val enc = font.encoding ?: return false
                    while (input.available() > 0) {
                        val name = enc.getName(font.readCode(input))
                        if (name == ".notdef" || !font.hasGlyph(name)) return false
                    }
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}

/** Collects font, size, color and position of glyphs inside a display-space rectangle. */
internal class StyleProbe(page: PDPage, private val area: FRect) : PDFStreamEngine() {
    class Hit(val font: PDFont, val size: Float, val rgb: Int, val origin: Pt, val upright: Boolean)

    val hits = ArrayList<Hit>()
    private val geometry = PageGeometry.of(page)
    private val metrics = FontMetricsCache()

    init {
        registerTextOperators()
        addOperator(DrawObject())
        addOperator(SetNonStrokingColor())
        addOperator(SetNonStrokingColorN())
        addOperator(SetNonStrokingColorSpace())
        addOperator(SetNonStrokingDeviceCMYKColor())
        addOperator(SetNonStrokingDeviceGrayColor())
        addOperator(SetNonStrokingDeviceRGBColor())
    }

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        val corners = GlyphGeometry.corners(textRenderingMatrix, font, code, displacement, metrics.extent(font))
        val box = FRect.bounds(corners.asList()) ?: return
        val disp = geometry.rectToDisplay(box)
        if (!area.contains(disp.centerX, disp.centerY)) return
        val size = hypot(textRenderingMatrix.shearX, textRenderingMatrix.scaleY)
        val rgb = try { graphicsState.nonStrokingColor.toRGB() } catch (e: Exception) { 0 }
        val origin = geometry.toDisplay(textRenderingMatrix.translateX, textRenderingMatrix.translateY)
        // Upright in display space: the baseline direction maps to +x on screen.
        val a = geometry.toDisplay(textRenderingMatrix.mapPoint(0f, 0f))
        val b = geometry.toDisplay(textRenderingMatrix.mapPoint(1f, 0f))
        val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
        hits.add(Hit(font, size, rgb, origin, abs(angle) < 2.0))
    }
}
