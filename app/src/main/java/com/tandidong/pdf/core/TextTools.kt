package com.tandidong.pdf.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/** A word recognized by OCR, positioned in display space (points). */
data class OcrWord(val text: String, val box: FRect)

/** Adds an invisible, selectable/searchable text layer (as produced by OCR) to a page. */
class OcrLayerWriter(private val fonts: FontSelector) {
    fun addTextLayer(doc: PDDocument, pageIndex: Int, words: List<OcrWord>) {
        if (words.isEmpty() || pageIndex !in 0 until doc.numberOfPages) return
        val page = doc.getPage(pageIndex)
        val g = PageGeometry.of(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(g.displayUpMatrix())
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER)
            for (w in words) {
                val raw = w.text.trim()
                if (raw.isEmpty() || w.box.width <= 0f || w.box.height <= 0f) continue
                val (font, text) = fonts.fontFor(doc, raw)
                if (text.isBlank()) continue
                val size = max(1f, w.box.height * 0.92f)
                val natural = FontSelector.width(font, text, size)
                val scale = if (natural > 0f) (w.box.width / natural * 100f).coerceIn(5f, 500f) else 100f
                cs.setFont(font, size)
                cs.setHorizontalScaling(scale)
                val baseline = g.displayHeight - (w.box.y1 - w.box.height * 0.18f)
                cs.setTextMatrix(com.tom_roush.pdfbox.util.Matrix(1f, 0f, 0f, 1f, w.box.x0, baseline))
                cs.showText(text)
            }
            cs.endText()
            cs.restoreGraphicsState()
        }
    }
}

/** Replaces existing text: removes the original glyphs, then writes new text in place. */
class TextEditor(private val redactor: Redactor, private val fonts: FontSelector) {

    /** What [replace] matched and used. */
    data class ReplaceResult(val original: TextStyle?, val fontUsed: String, val reusedOriginalFont: Boolean)

    fun replace(doc: PDDocument, pageIndex: Int, displayRect: FRect, newText: String, fontSize: Float, argb: Int, bold: Boolean = false): ReplaceResult =
        replace(doc, pageIndex, displayRect, newText, TextFormat(size = fontSize, argb = argb, bold = bold.takeIf { it }))

    /**
     * Permanently removes the text inside [displayRect] and writes [newText] in its place, matching
     * the original font (or the closest standard font), size, color and baseline unless [format]
     * overrides them.
     */
    fun replace(doc: PDDocument, pageIndex: Int, displayRect: FRect, newText: String, format: TextFormat): ReplaceResult {
        val page = doc.getPage(pageIndex)
        val g = PageGeometry.of(page)
        val probed = try { TextStyles.probeWithFont(doc, pageIndex, displayRect) } catch (e: Exception) { null }
        val style = probed?.first
        redactor.redact(
            doc, listOf(RedactionArea(pageIndex, g.rectToUser(displayRect))),
            RedactionOptions(drawFill = false, removeAnnotations = false),
        )
        if (newText.isBlank()) return ReplaceResult(style, "", false)
        val fontSize = format.size ?: style?.size ?: 12f
        val argb = format.argb ?: style?.argb ?: 0xFF000000.toInt()
        val family = format.family ?: style?.family ?: FontFamily.SANS
        val bold = format.bold ?: style?.bold ?: false
        val italic = format.italic ?: style?.italic ?: false
        val sameLook = format.family == null && (format.bold == null || format.bold == style?.bold) &&
            (format.italic == null || format.italic == style?.italic)
        val original = probed?.second
        val lines0 = newText.split('\n')
        val reuse = format.keepOriginalFont && sameLook && original != null &&
            lines0.all { it.isEmpty() || TextStyles.canRender(original, it) }
        val (font, text) = if (reuse && original != null) {
            original to newText
        } else {
            val std = TextStyles.standardFont(family, bold, italic)
            if (FontSelector.canEncode(std, newText.replace("\n", ""))) std to newText else fonts.fontFor(doc, newText, bold)
        }
        val origin = style?.origin
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(g.displayUpMatrix())
            cs.setNonStrokingColor(Redactor.red(argb), Redactor.green(argb), Redactor.blue(argb))
            cs.beginText()
            cs.setFont(font, fontSize)
            val leading = format.leading ?: (fontSize * 1.2f)
            cs.setLeading(leading)
            val wrapWidth = format.wrapWidth
            val lines = if (wrapWidth != null && wrapWidth > fontSize) FontSelector.wrap(font, text, fontSize, wrapWidth) else text.split('\n')
            val startX: Float
            val firstBaseline: Float
            if (origin != null && origin.x in displayRect.x0 - 2f..displayRect.x1) {
                startX = origin.x
                firstBaseline = origin.y
            } else {
                // Baseline of a single line sits ~22% of the line height above the box bottom.
                startX = displayRect.x0
                firstBaseline = if (lines.size == 1 && wrapWidth == null) displayRect.y1 - displayRect.height * 0.22f else displayRect.y0 + fontSize * 0.9f
            }
            cs.newLineAtOffset(startX, g.displayHeight - firstBaseline)
            var x = startX
            for ((i, line) in lines.withIndex()) {
                if (i > 0) {
                    // Wrapped paragraphs continue at the left edge (the first line may be indented).
                    val nx = if (wrapWidth != null) displayRect.x0 else x
                    cs.newLineAtOffset(nx - x, -leading)
                    x = nx
                }
                cs.showText(line)
            }
            cs.endText()
            cs.restoreGraphicsState()
        }
        val usedName = try { font.name } catch (e: Exception) { null } ?: "font"
        return ReplaceResult(style, usedName.substringAfter('+'), reuse)
    }

    /** Adds new text to the page content (not an annotation). [at] is the top-left in display space. */
    fun addText(doc: PDDocument, pageIndex: Int, at: Pt, newText: String, fontSize: Float, argb: Int, bold: Boolean = false) {
        if (newText.isBlank()) return
        val page = doc.getPage(pageIndex)
        val g = PageGeometry.of(page)
        val (font, text) = fonts.fontFor(doc, newText, bold)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(g.displayUpMatrix())
            cs.setNonStrokingColor(Redactor.red(argb), Redactor.green(argb), Redactor.blue(argb))
            cs.beginText()
            cs.setFont(font, fontSize)
            cs.setLeading(fontSize * 1.2f)
            cs.newLineAtOffset(at.x, g.displayHeight - at.y - fontSize * 0.9f)
            for ((i, line) in text.split('\n').withIndex()) {
                if (i > 0) cs.newLine()
                cs.showText(line)
            }
            cs.endText()
            cs.restoreGraphicsState()
        }
    }
}

/** Minimal but valid .docx writer (paragraphs, bold/size runs, page breaks). */
object DocxWriter {
    data class Para(val text: String, val sizePt: Float, val bold: Boolean)

    /** Groups page lines into paragraphs and headings. */
    fun paragraphs(page: PageText): List<Para> {
        if (page.lines.isEmpty()) return emptyList()
        val sizes = page.lines.map { it.fontSize }.sorted()
        val body = sizes[sizes.size / 2].coerceAtLeast(6f)
        val out = ArrayList<Para>()
        var current = StringBuilder()
        var curSize = 0f
        var curBold = false
        var prev: TextLine? = null
        fun flush() {
            if (current.isNotBlank()) out.add(Para(current.toString().trim(), curSize, curBold))
            current = StringBuilder()
        }
        for (line in page.lines) {
            val text = page.text.substring(line.start, min(line.end, page.text.length)).trim()
            if (text.isEmpty()) continue
            val p = prev
            val isHeading = line.fontSize > body * 1.25f
            val gap = if (p != null) line.box.y0 - p.box.y1 else 0f
            val newPara = p == null || gap > line.box.height * 0.8f || isHeading ||
                p.fontSize > body * 1.25f || kotlin.math.abs(line.fontSize - p.fontSize) > 1.5f ||
                line.box.x0 - p.box.x0 > line.box.height * 1.5f
            if (newPara) {
                flush()
                curSize = line.fontSize
                curBold = line.bold || isHeading
            } else if (current.isNotEmpty()) {
                if (current.endsWith("-")) current.setLength(current.length - 1) else current.append(' ')
            }
            current.append(text)
            prev = line
        }
        flush()
        return out
    }

    fun write(pages: List<PageText>, out: OutputStream, title: String = "") {
        val body = StringBuilder()
        for ((i, page) in pages.withIndex()) {
            for (para in paragraphs(page)) {
                val sz = (para.sizePt.coerceIn(6f, 72f) * 2).toInt()
                body.append("<w:p><w:r><w:rPr>")
                if (para.bold) body.append("<w:b/>")
                body.append("<w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr><w:t xml:space=\"preserve\">")
                body.append(xml(para.text))
                body.append("</w:t></w:r></w:p>")
            }
            if (i < pages.size - 1) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""
        val core = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${xml(title)}</dc:title></cp:coreProperties>"""
        ZipOutputStream(out).use { zip ->
            fun put(name: String, s: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(s.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("docProps/core.xml", core)
            put("word/document.xml", document)
        }
    }

    fun xml(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(' ')
                c.code < 0x20 || c == '￾' || c == '￿' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

/** Word-level document comparison (Myers diff). */
object TextDiff {
    enum class Op { EQUAL, INSERT, DELETE }
    data class Chunk(val op: Op, val text: String)

    fun tokenize(s: String): List<String> = Regex("\\S+|\\n").findAll(s).map { it.value }.toList()

    fun diff(a: List<String>, b: List<String>, maxD: Int = 1200): List<Chunk> {
        // Trim common prefix/suffix to keep the core small.
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val ac = a.subList(pre, a.size - suf)
        val bc = b.subList(pre, b.size - suf)
        val ops = ArrayList<Pair<Op, String>>()
        a.subList(0, pre).forEach { ops.add(Op.EQUAL to it) }
        ops.addAll(myers(ac, bc, maxD))
        a.subList(a.size - suf, a.size).forEach { ops.add(Op.EQUAL to it) }
        // Merge consecutive tokens with the same op.
        val out = ArrayList<Chunk>()
        val sb = StringBuilder()
        var cur: Op? = null
        for ((op, tok) in ops) {
            if (op != cur && cur != null) { out.add(Chunk(cur, sb.toString())); sb.setLength(0) }
            cur = op
            if (sb.isNotEmpty() && tok != "\n" && !sb.endsWith("\n")) sb.append(' ')
            sb.append(tok)
        }
        if (cur != null) out.add(Chunk(cur, sb.toString()))
        return out
    }

    private fun myers(a: List<String>, b: List<String>, maxD: Int): List<Pair<Op, String>> {
        val n = a.size
        val m = b.size
        if (n == 0) return b.map { Op.INSERT to it }
        if (m == 0) return a.map { Op.DELETE to it }
        val max = min(n + m, maxD)
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>()
        var found = false
        loop@ for (d in 0..max) {
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = true; break@loop }
                k += 2
            }
        }
        if (!found) {
            // Too different: fall back to delete-all/insert-all.
            return a.map { Op.DELETE to it } + b.map { Op.INSERT to it }
        }
        // Backtrack.
        val result = ArrayList<Pair<Op, String>>()
        var x = n
        var y = m
        for (d in trace.size - 1 downTo 0) {
            val vv = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vv[offset + k - 1] < vv[offset + k + 1])) k + 1 else k - 1
            val prevX = vv[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { result.add(Op.EQUAL to a[x - 1]); x--; y-- }
            if (d > 0) {
                if (x == prevX) result.add(Op.INSERT to b[y - 1]) else result.add(Op.DELETE to a[x - 1])
            }
            x = prevX
            y = prevY
        }
        result.reverse()
        return result
    }

    data class Summary(val insertedWords: Int, val deletedWords: Int, val chunks: List<Chunk>)

    fun compare(textA: String, textB: String): Summary {
        val chunks = diff(tokenize(textA), tokenize(textB))
        val ins = chunks.filter { it.op == Op.INSERT }.sumOf { tokenize(it.text).count { t -> t != "\n" } }
        val del = chunks.filter { it.op == Op.DELETE }.sumOf { tokenize(it.text).count { t -> t != "\n" } }
        return Summary(ins, del, chunks)
    }

    @Suppress("unused") private fun unused() = max(0, 0)
}
