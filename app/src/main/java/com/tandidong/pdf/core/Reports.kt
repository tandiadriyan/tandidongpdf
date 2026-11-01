package com.tandidong.pdf.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont

/** Simple flowing-text page writer used for reports and text-to-PDF. */
internal class FlowWriter(private val doc: PDDocument, private val fonts: FontSelector, private val size: PDRectangle = PDRectangle.A4) {
    private val margin = 56f
    private var page: PDPage? = null
    private var cs: PDPageContentStream? = null
    private var y = 0f

    private fun newPage() {
        cs?.close()
        val p = PDPage(size)
        doc.addPage(p)
        page = p
        cs = PDPageContentStream(doc, p)
        y = size.height - margin
    }

    private fun ensure(height: Float) {
        if (cs == null || y - height < margin) newPage()
    }

    /** Writes [text] wrapped to the page width; returns the number of lines written. */
    fun paragraph(text: String, fontSize: Float = 11f, bold: Boolean = false, argb: Int = 0xFF000000.toInt(), indent: Float = 0f, spaceAfter: Float = 4f): Int {
        val (font, clean) = fonts.fontFor(doc, text.ifEmpty { " " }, bold)
        val width = size.width - 2 * margin - indent
        val lines = FontSelector.wrap(font, clean, fontSize, width)
        val leading = fontSize * 1.3f
        for (line in lines) {
            ensure(leading)
            val c = cs!!
            y -= leading
            c.beginText()
            c.setFont(font, fontSize)
            c.setNonStrokingColor(Redactor.red(argb), Redactor.green(argb), Redactor.blue(argb))
            c.newLineAtOffset(margin + indent, y + fontSize * 0.25f)
            c.showText(line)
            c.endText()
        }
        y -= spaceAfter
        return lines.size
    }

    fun rule(argb: Int = 0xFFBDBDBD.toInt()) {
        ensure(8f)
        val c = cs!!
        y -= 4f
        c.setStrokingColor(Redactor.red(argb), Redactor.green(argb), Redactor.blue(argb))
        c.setLineWidth(0.6f)
        c.moveTo(margin, y); c.lineTo(size.width - margin, y); c.stroke()
        y -= 4f
    }

    fun finish() {
        cs?.close()
        cs = null
        if (doc.numberOfPages == 0) newPage().also { cs?.close(); cs = null }
    }

    @Suppress("unused")
    private fun unused(f: PDFont) = f
}

object Reports {
    /** A new document listing every comment with its page, type, author, date and replies. */
    fun commentSummary(source: PDDocument, title: String, fonts: FontSelector): PDDocument {
        val comments = AnnotationEditor.list(source)
        val out = PDDocument()
        val w = FlowWriter(out, fonts)
        w.paragraph("Comment summary", fontSize = 18f, bold = true, spaceAfter = 2f)
        w.paragraph("$title · ${comments.count { it.replyTo == null }} comments", fontSize = 10f, argb = 0xFF616161.toInt(), spaceAfter = 10f)
        if (comments.isEmpty()) w.paragraph("This document has no comments.")
        val byParent = comments.filter { it.replyTo != null }.groupBy { it.pageIndex to it.replyTo }
        for ((pageIndex, onPage) in comments.groupBy { it.pageIndex }.toSortedMap()) {
            w.rule()
            w.paragraph("Page ${pageIndex + 1}", fontSize = 13f, bold = true, spaceAfter = 6f)
            fun write(a: AnnotationInfo, depth: Int) {
                if (depth > 8) return
                val who = listOfNotNull(a.author?.takeIf { it.isNotBlank() }, a.modified?.let { formatDate(it) }).joinToString(" · ")
                val head = (if (depth > 0) "Reply" else typeName(a.subtype)) + if (who.isNotEmpty()) " — $who" else ""
                w.paragraph(head, fontSize = 10f, bold = true, argb = a.argb ?: 0xFF424242.toInt(), indent = depth * 18f, spaceAfter = 1f)
                val body = a.contents?.takeIf { it.isNotBlank() }
                if (body != null) w.paragraph(body, fontSize = 10.5f, indent = depth * 18f + 8f, spaceAfter = 6f)
                else w.paragraph("(no text)", fontSize = 9.5f, argb = 0xFF9E9E9E.toInt(), indent = depth * 18f + 8f, spaceAfter = 6f)
                for (r in byParent[a.pageIndex to a.index].orEmpty()) write(r, depth + 1)
            }
            for (a in onPage.filter { it.replyTo == null }) write(a, 0)
        }
        w.finish()
        return out
    }

    private fun typeName(subtype: String): String = when (subtype) {
        "Text" -> "Note"
        "FreeText" -> "Text box"
        "Square" -> "Rectangle"
        "Circle" -> "Ellipse"
        "Ink" -> "Drawing"
        "StrikeOut" -> "Strikethrough"
        "Stamp" -> "Stamp"
        "Line" -> "Line"
        else -> subtype
    }

    /** PDF dates look like D:20240131120000+01'00'; show them as 2024-01-31 12:00. */
    fun formatDate(pdfDate: String): String {
        val m = Regex("D?:?(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?").find(pdfDate) ?: return pdfDate
        val (y, mo, d, h, mi) = m.destructured
        return buildString {
            append(y)
            if (mo.isNotEmpty()) append("-").append(mo)
            if (d.isNotEmpty()) append("-").append(d)
            if (h.isNotEmpty() && mi.isNotEmpty()) append(" ").append(h).append(":").append(mi)
        }
    }

    /** Lays out plain text as a PDF (A4, 11 pt, wrapped, blank lines kept). */
    fun textToPdf(text: String, fonts: FontSelector, title: String? = null): PDDocument {
        val out = PDDocument()
        val w = FlowWriter(out, fonts)
        title?.takeIf { it.isNotBlank() }?.let { w.paragraph(it, fontSize = 16f, bold = true, spaceAfter = 8f) }
        for (para in text.replace("\r\n", "\n").replace('\t', ' ').split('\n')) {
            w.paragraph(para, fontSize = 11f, spaceAfter = 0f)
        }
        w.finish()
        out.documentInformation.title = title
        return out
    }
}
