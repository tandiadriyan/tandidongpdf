package com.tandidong.pdf.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A cell of a table recovered from page text. */
data class TableCell(val row: Int, val col: Int, val text: String)

/**
 * Recovers rows and columns from positioned text: words separated by wide gaps become cells,
 * and cell left edges that line up across rows become columns.
 */
object TableDetector {

    private class Seg(val x0: Float, val x1: Float, val text: String)

    /** Splits each line into cells at gaps wider than ~1.2 × the line's font size. */
    private fun segments(page: PageText, line: TextLine): List<Seg> {
        val out = ArrayList<Seg>()
        val sb = StringBuilder()
        var segStart = Float.NaN
        var lastRight = Float.NaN
        val gapLimit = max(6f, line.fontSize * 1.2f)
        val end = min(line.end, page.text.length)
        for (i in line.start until end) {
            val ch = page.text[i]
            val box = page.boxAt(i)
            if (box == null || ch.isWhitespace()) {
                if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(' ')
                continue
            }
            if (!lastRight.isNaN() && box.x0 - lastRight > gapLimit && sb.isNotBlank()) {
                out.add(Seg(segStart, lastRight, sb.toString().trim()))
                sb.setLength(0)
                segStart = Float.NaN
            }
            if (segStart.isNaN()) segStart = box.x0
            sb.append(ch)
            lastRight = box.x1
        }
        if (sb.isNotBlank()) out.add(Seg(segStart, lastRight, sb.toString().trim()))
        return out
    }

    fun cells(page: PageText): List<TableCell> {
        val rows = page.lines.map { segments(page, it) }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return emptyList()
        // Column bands: horizontal extents of cells in multi-cell rows, merged where they overlap
        // (so left-, right- and center-aligned columns all form one band each). Single-cell rows
        // such as titles are left out so they cannot glue columns together.
        val basis = rows.filter { it.size >= 2 }.flatten().ifEmpty { rows.flatten() }
        val bands = ArrayList<FloatArray>()
        for (seg in basis.sortedBy { it.x0 }) {
            val last = bands.lastOrNull()
            if (last != null && seg.x0 <= last[1] + 2f) last[1] = max(last[1], seg.x1) else bands.add(floatArrayOf(seg.x0, seg.x1))
        }
        fun bandFor(seg: Seg): Int {
            var best = -1
            var bestOverlap = 0f
            for ((i, b) in bands.withIndex()) {
                val o = min(b[1], seg.x1) - max(b[0], seg.x0)
                if (o > bestOverlap) { bestOverlap = o; best = i }
            }
            if (best >= 0) return best
            val c = (seg.x0 + seg.x1) / 2f
            return bands.indices.minByOrNull { abs((bands[it][0] + bands[it][1]) / 2f - c) } ?: 0
        }
        val out = ArrayList<TableCell>()
        for ((r, segs) in rows.withIndex()) {
            var lastCol = -1
            for (seg in segs) {
                var col = bandFor(seg)
                if (col <= lastCol) col = lastCol + 1
                out.add(TableCell(r, col, seg.text))
                lastCol = col
            }
        }
        return out
    }
}

/** Minimal Office Open XML writers (no external dependencies). */
object XlsxWriter {

    private fun colName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    /** Numbers like "1,234.50", "$12", "(45)", "12%" become numeric cells. */
    fun parseNumber(text: String): Double? {
        var t = text.trim()
        if (t.isEmpty() || t.length > 24) return null
        var negative = false
        if (t.startsWith("(") && t.endsWith(")")) { negative = true; t = t.substring(1, t.length - 1) }
        t = t.removePrefix("$").removePrefix("€").removePrefix("£").removePrefix("¥").removePrefix("Rp").trim()
        var percent = false
        if (t.endsWith("%")) { percent = true; t = t.dropLast(1) }
        if (t.startsWith("-")) { negative = !negative; t = t.substring(1) }
        if (!Regex("^\\d{1,3}(,\\d{3})*(\\.\\d+)?$|^\\d+(\\.\\d+)?$").matches(t)) return null
        val v = t.replace(",", "").toDoubleOrNull() ?: return null
        val signed = if (negative) -v else v
        return if (percent) signed / 100.0 else signed
    }

    /** Writes one worksheet per entry of [sheets] (name to cells). */
    fun write(sheets: List<Pair<String, List<TableCell>>>, out: OutputStream) {
        val names = sheets.mapIndexed { i, s -> sanitizeSheetName(s.first, i) }
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                for (i in sheets.indices) {
                    append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
                }
                append("</Types>")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
                "</Relationships>")
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                for ((i, n) in names.withIndex()) append("""<sheet name="${xml(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                append("</sheets></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                for (i in sheets.indices) {
                    append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
                }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                append("</Relationships>")
            })
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                """<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>""" +
                """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
                """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
                """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
                """<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>""" +
                "</styleSheet>")
            for ((i, sheet) in sheets.withIndex()) {
                put("xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet.second))
            }
        }
    }

    private fun sheetXml(cells: List<TableCell>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        for ((r, rowCells) in cells.groupBy { it.row }.toSortedMap()) {
            append("""<row r="${r + 1}">""")
            for (c in rowCells.sortedBy { it.col }) {
                val ref = colName(c.col) + (r + 1)
                val num = parseNumber(c.text)
                if (num != null && !num.isNaN() && !num.isInfinite()) {
                    append("""<c r="$ref"><v>${formatNumber(num)}</v></c>""")
                } else {
                    append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${xml(c.text)}</t></is></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun formatNumber(v: Double): String =
        if (v == Math.floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun sanitizeSheetName(name: String, index: Int): String {
        val cleaned = name.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().take(31)
        return cleaned.ifBlank { "Sheet${index + 1}" }
    }

    internal fun xml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                ch.code < 0x20 || ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

/** One slide: an encoded page image and the page's size in points (for its aspect ratio). */
class SlideImage(val bytes: ByteArray, val isPng: Boolean, val pageWidthPt: Float, val pageHeightPt: Float)

/** Minimal PowerPoint writer: one picture slide per page. */
object PptxWriter {
    private const val NS = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
        """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
    private const val HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val EMPTY_TREE = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
        """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""

    fun write(slides: List<SlideImage>, out: OutputStream, title: String = "") {
        require(slides.isNotEmpty()) { "Nothing to export" }
        // Slide size follows the first page: 10 inches wide, height by aspect (PowerPoint limits 1–56 in).
        val first = slides.first()
        val cx = 9_144_000L
        val cy = (cx * (first.pageHeightPt / first.pageWidthPt.coerceAtLeast(1f))).toLong().coerceIn(914_400L, 51_206_400L)
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("[Content_Types].xml", buildString {
                append(HEAD)
                append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
                append("""<Default Extension="png" ContentType="image/png"/>""")
                append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
                append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
                append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
                append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
                append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
                for (i in slides.indices) {
                    append("""<Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
                }
                append("</Types>")
            })
            put("_rels/.rels", HEAD + """<Relationships xmlns="$REL_NS">""" +
                """<Relationship Id="rId1" Type="$R/officeDocument" Target="ppt/presentation.xml"/>""" +
                """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
                "</Relationships>")
            put("docProps/core.xml", HEAD + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
                """xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${XlsxWriter.xml(title)}</dc:title><dc:creator>Tandidong PDF</dc:creator></cp:coreProperties>""")
            put("ppt/presentation.xml", buildString {
                append(HEAD)
                append("""<p:presentation $NS saveSubsetFonts="1">""")
                append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""")
                append("<p:sldIdLst>")
                for (i in slides.indices) append("""<p:sldId id="${256 + i}" r:id="rId${i + 2}"/>""")
                append("</p:sldIdLst>")
                append("""<p:sldSz cx="$cx" cy="$cy"/><p:notesSz cx="6858000" cy="9144000"/>""")
                append("</p:presentation>")
            })
            put("ppt/_rels/presentation.xml.rels", buildString {
                append(HEAD)
                append("""<Relationships xmlns="$REL_NS">""")
                append("""<Relationship Id="rId1" Type="$R/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
                for (i in slides.indices) append("""<Relationship Id="rId${i + 2}" Type="$R/slide" Target="slides/slide${i + 1}.xml"/>""")
                append("""<Relationship Id="rId${slides.size + 2}" Type="$R/theme" Target="theme/theme1.xml"/>""")
                append("</Relationships>")
            })
            put("ppt/slideMasters/slideMaster1.xml", HEAD + """<p:sldMaster $NS><p:cSld><p:spTree>$EMPTY_TREE</p:spTree></p:cSld>""" +
                """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" """ +
                """accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
                """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>""")
            put("ppt/slideMasters/_rels/slideMaster1.xml.rels", HEAD + """<Relationships xmlns="$REL_NS">""" +
                """<Relationship Id="rId1" Type="$R/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""" +
                """<Relationship Id="rId2" Type="$R/theme" Target="../theme/theme1.xml"/></Relationships>""")
            put("ppt/slideLayouts/slideLayout1.xml", HEAD + """<p:sldLayout $NS type="blank" preserve="1">""" +
                """<p:cSld name="Blank"><p:spTree>$EMPTY_TREE</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>""")
            put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", HEAD + """<Relationships xmlns="$REL_NS">""" +
                """<Relationship Id="rId1" Type="$R/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>""")
            put("ppt/theme/theme1.xml", THEME)
            for ((i, s) in slides.withIndex()) {
                val ext = if (s.isPng) "png" else "jpeg"
                zip.putNextEntry(ZipEntry("ppt/media/image${i + 1}.$ext")); zip.write(s.bytes); zip.closeEntry()
                // Fit the page inside the slide, centered, keeping its proportions.
                val aspect = s.pageWidthPt / s.pageHeightPt.coerceAtLeast(1f)
                var w = cx
                var h = (cx / aspect).toLong()
                if (h > cy) { h = cy; w = (cy * aspect).toLong() }
                val x = (cx - w) / 2
                val y = (cy - h) / 2
                put("ppt/slides/slide${i + 1}.xml", HEAD + """<p:sld $NS><p:cSld><p:spTree>$EMPTY_TREE""" +
                    """<p:pic><p:nvPicPr><p:cNvPr id="2" name="Page ${i + 1}"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>""" +
                    """<p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>""" +
                    """<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>""" +
                    """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>""")
                put("ppt/slides/_rels/slide${i + 1}.xml.rels", HEAD + """<Relationships xmlns="$REL_NS">""" +
                    """<Relationship Id="rId1" Type="$R/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""" +
                    """<Relationship Id="rId2" Type="$R/image" Target="../media/image${i + 1}.$ext"/></Relationships>""")
            }
        }
    }

    private val THEME = HEAD + """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme"><a:themeElements>""" +
        """<a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""" +
        """<a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1>""" +
        """<a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4>""" +
        """<a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink>""" +
        """<a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>""" +
        """<a:fontScheme name="Office"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>""" +
        """<a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>""" +
        """<a:fmtScheme name="Office"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
        """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>""" +
        """<a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>""" +
        """<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>""" +
        """<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
        """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""
}
