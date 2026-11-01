package com.tandidong.pdf.core

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.SetLineWidth
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDInlineImage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A rectangle to redact, in PDF user space. */
data class RedactionArea(val pageIndex: Int, val rect: FRect)

data class RedactionOptions(
    /** Color of the box painted over redacted areas (ARGB). */
    val fillArgb: Int = 0xFF000000.toInt(),
    /** When false no box is drawn (content is still removed). */
    val drawFill: Boolean = true,
    /** Optional label drawn inside each box, e.g. "REDACTED" or an exemption code. */
    val overlayText: String? = null,
    val overlayTextArgb: Int = 0xFFFFFFFF.toInt(),
    /** Remove annotations and form fields that overlap redacted areas. */
    val removeAnnotations: Boolean = true,
    /** Resolution used when a page has to be flattened to an image as a fallback. */
    val rasterDpi: Float = 200f,
)

class RedactionReport {
    var glyphsRemoved = 0
    var imagesRedacted = 0
    var imagesRemoved = 0
    var pathsRemoved = 0
    var annotationsRemoved = 0
    var formFieldsRemoved = 0
    val pagesRasterized = ArrayList<Int>()
    val warnings = ArrayList<String>()

    override fun toString() =
        "glyphs=$glyphsRemoved images=$imagesRedacted/$imagesRemoved paths=$pathsRemoved " +
            "annots=$annotationsRemoved fields=$formFieldsRemoved rasterized=$pagesRasterized"
}

/**
 * True redaction: rewrites page content so that text, image pixels and vector graphics under the
 * redaction areas are permanently removed from the file (not merely covered), removes overlapping
 * annotations/form fields, then paints opaque boxes. Pages containing content that cannot be
 * edited precisely (e.g. unsupported image codecs) are flattened to images as a safe fallback.
 */
class Redactor(
    private val codec: ImageCodec?,
    private val rasterizer: PageRasterizer?,
    private val fonts: FontSelector = FontSelector(null),
) {
    fun redact(
        doc: PDDocument,
        areas: List<RedactionArea>,
        options: RedactionOptions = RedactionOptions(),
        progress: Progress? = null,
    ): RedactionReport {
        val report = RedactionReport()
        val byPage = areas.filter { !it.rect.isEmpty }.groupBy { it.pageIndex }.toSortedMap()
        var done = 0
        for ((pageIndex, list) in byPage) {
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) continue
            redactPage(doc, pageIndex, list.map { it.rect }, options, report)
            progress?.invoke(++done, byPage.size)
        }
        return report
    }

    private fun redactPage(doc: PDDocument, pageIndex: Int, rects: List<FRect>, options: RedactionOptions, report: RedactionReport) {
        val page = doc.getPage(pageIndex)
        if (options.removeAnnotations) removeAnnotations(doc, page, rects, report)
        var fallbackReason: String? = null
        try {
            val engine = RedactionEngine(doc, page, rects, codec, report)
            fallbackReason = engine.run()
            if (fallbackReason == null) engine.commit()
        } catch (e: Exception) {
            CoreLog.w("Precise redaction failed on page ${pageIndex + 1}", e)
            fallbackReason = e.message ?: e.javaClass.simpleName
        }
        if (fallbackReason != null) {
            val r = rasterizer
            val c = codec
            if (r == null || c == null) {
                throw IOException("Page ${pageIndex + 1} cannot be redacted precisely ($fallbackReason)")
            }
            rasterizePage(doc, page, pageIndex, rects, options, r, c)
            report.pagesRasterized.add(pageIndex)
            report.warnings.add("Page ${pageIndex + 1} was flattened to an image ($fallbackReason)")
        }
        drawBoxes(doc, page, rects, options)
        page.cosObject.removeItem(COSName.getPDFName("Thumb"))
        page.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
    }

    private fun rasterizePage(
        doc: PDDocument, page: PDPage, pageIndex: Int, rects: List<FRect>,
        options: RedactionOptions, rasterizer: PageRasterizer, codec: ImageCodec,
    ) {
        val geom = PageGeometry.of(page)
        // Render content only: remaining annotations stay interactive instead of being baked in twice.
        val savedAnnots = page.cosObject.getItem(COSName.ANNOTS)
        page.cosObject.removeItem(COSName.ANNOTS)
        var img = try {
            rasterizer.rasterize(doc, pageIndex, options.rasterDpi)
        } finally {
            if (savedAnnots != null) page.cosObject.setItem(COSName.ANNOTS, savedAnnots)
        }
        val sx = img.width / geom.displayWidth
        val sy = img.height / geom.displayHeight
        for (r in rects) {
            val d = geom.rectToDisplay(r)
            img = codec.fillRect(
                img,
                floor(d.x0 * sx).toInt().coerceIn(0, img.width), floor(d.y0 * sy).toInt().coerceIn(0, img.height),
                ceil(d.x1 * sx).toInt().coerceIn(0, img.width), ceil(d.y1 * sy).toInt().coerceIn(0, img.height),
                options.fillArgb or 0xFF000000.toInt(),
            )
        }
        val xobj = codec.encodeJpeg(doc, img, 0.9f)
        codec.release(img)
        page.resources = PDResources()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(geom.displayUpMatrix())
            cs.drawImage(xobj, 0f, 0f, geom.displayWidth, geom.displayHeight)
            cs.restoreGraphicsState()
        }
    }

    private fun drawBoxes(doc: PDDocument, page: PDPage, rects: List<FRect>, options: RedactionOptions) {
        if (!options.drawFill && options.overlayText.isNullOrBlank()) return
        val geom = PageGeometry.of(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            if (options.drawFill) {
                cs.setNonStrokingColor(red(options.fillArgb), green(options.fillArgb), blue(options.fillArgb))
                for (r in rects) cs.addRect(r.x0, r.y0, r.width, r.height)
                cs.fill()
            }
            val label = options.overlayText
            if (!label.isNullOrBlank()) {
                val (font, text) = fonts.fontFor(doc, label, bold = true)
                cs.setNonStrokingColor(red(options.overlayTextArgb), green(options.overlayTextArgb), blue(options.overlayTextArgb))
                cs.saveGraphicsState()
                cs.transform(geom.displayUpMatrix())
                for (r in rects) {
                    val d = geom.rectToDisplay(r)
                    val w = d.width
                    val h = d.height
                    if (w < 6f || h < 4f) continue
                    var size = min(h * 0.7f, 14f)
                    val tw = FontSelector.width(font, text, size)
                    if (tw > w * 0.92f) size *= (w * 0.92f) / tw
                    if (size < 3f) continue
                    val textW = FontSelector.width(font, text, size)
                    val x = d.x0 + (w - textW) / 2f
                    val baseline = geom.displayHeight - (d.y0 + h / 2f + size * 0.35f)
                    cs.beginText()
                    cs.setFont(font, size)
                    cs.newLineAtOffset(x, baseline)
                    cs.showText(text)
                    cs.endText()
                }
                cs.restoreGraphicsState()
            }
        }
    }

    private fun removeAnnotations(doc: PDDocument, page: PDPage, rects: List<FRect>, report: RedactionReport) {
        val annots: List<PDAnnotation> = try { page.annotations } catch (e: IOException) { return }
        if (annots.isEmpty()) return
        val remove = IdentityHashMap<COSDictionary, Boolean>()
        for (a in annots) {
            if (a is PDAnnotationPopup) continue
            val r = a.rectangle ?: continue
            val fr = FRect.of(r)
            if (rects.any { it.touches(fr) }) remove[a.cosObject] = true
        }
        if (remove.isEmpty()) return
        // Popups belong to the markup annotation that owns them.
        for (a in annots) {
            if (a is PDAnnotationPopup) {
                val parent = a.cosObject.getDictionaryObject(COSName.PARENT)
                if (parent is COSDictionary && remove.containsKey(parent)) remove[a.cosObject] = true
            }
        }
        val keep = annots.filter { !remove.containsKey(it.cosObject) }
        report.annotationsRemoved += annots.size - keep.size
        page.annotations = keep
        val widgets = annots.filter { remove.containsKey(it.cosObject) && it.subtype == "Widget" }.map { it.cosObject }
        if (widgets.isNotEmpty()) report.formFieldsRemoved += FormFieldPruner.removeWidgets(doc, widgets.toSet())
    }

    companion object {
        internal fun red(argb: Int) = ((argb shr 16) and 0xFF) / 255f
        internal fun green(argb: Int) = ((argb shr 8) and 0xFF) / 255f
        internal fun blue(argb: Int) = (argb and 0xFF) / 255f
    }
}

/** Removes widget annotations from the AcroForm field tree (and empty parent fields). */
internal object FormFieldPruner {
    fun removeWidgets(doc: PDDocument, widgets: Set<COSDictionary>): Int {
        val acro = doc.documentCatalog.acroForm ?: return 0
        val fields = acro.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray ?: return 0
        val identity = IdentityHashMap<COSDictionary, Boolean>().apply { widgets.forEach { put(it, true) } }
        val removedFields = IdentityHashMap<COSDictionary, Boolean>()
        prune(fields, identity, removedFields, 0)
        val co = acro.cosObject.getDictionaryObject(COSName.getPDFName("CO")) as? COSArray
        if (co != null) {
            for (i in co.size() - 1 downTo 0) {
                val d = resolve(co.get(i)) as? COSDictionary ?: continue
                if (removedFields.containsKey(d) || identity.containsKey(d)) co.remove(i)
            }
        }
        return removedFields.size
    }

    /** Returns true if [array] became empty. */
    private fun prune(
        array: COSArray, widgets: IdentityHashMap<COSDictionary, Boolean>,
        removed: IdentityHashMap<COSDictionary, Boolean>, depth: Int,
    ): Boolean {
        if (depth > 32) return false
        for (i in array.size() - 1 downTo 0) {
            val d = resolve(array.get(i)) as? COSDictionary ?: continue
            if (widgets.containsKey(d)) {
                array.remove(i)
                if (d.containsKey(COSName.T) || d.containsKey(COSName.FT)) removed[d] = true
                continue
            }
            val kids = d.getDictionaryObject(COSName.KIDS) as? COSArray ?: continue
            if (kids.size() == 0) continue
            if (prune(kids, widgets, removed, depth + 1)) {
                array.remove(i)
                removed[d] = true
            }
        }
        return array.size() == 0
    }

    internal fun resolve(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
}

/**
 * Content-stream rewriting engine. Runs the page through PDFBox's graphics state machine while
 * re-emitting every operator, dropping or editing the ones that draw inside redaction areas.
 */
internal class RedactionEngine(
    private val doc: PDDocument,
    private val page: PDPage,
    private val rects: List<FRect>,
    private val codec: ImageCodec?,
    private val report: RedactionReport,
) : PDFStreamEngine() {

    private class BdcEntry(val dictIndex: Int) { var tainted = false }

    private class PathOp(val op: Operator, val args: List<COSBase>, val points: List<Pt>, val startsSubpath: Boolean)

    private inner class Ctx(val baseResources: COSDictionary?) {
        val tokens = ArrayList<Any>()
        var changed = false
        var ownResources: COSDictionary? = null
        val bdc = ArrayList<BdcEntry>()
        var qDepth = 0
        var inText = false
        var path = ArrayList<PathOp>()
        var pathClip = false

        fun resourcesForWrite(): COSDictionary {
            ownResources?.let { return it }
            val clone = if (baseResources != null) COSDictionary(baseResources) else COSDictionary()
            val xo = clone.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
            clone.setItem(COSName.XOBJECT, if (xo != null) COSDictionary(xo) else COSDictionary())
            val fonts = clone.getDictionaryObject(COSName.FONT) as? COSDictionary
            if (fonts != null) clone.setItem(COSName.FONT, COSDictionary(fonts))
            ownResources = clone
            return clone
        }
    }

    private class CodeRec(val offset: Int, val len: Int)
    private class GlyphRec(val offset: Int, val len: Int, val removed: Boolean, val adjust: Float)
    private class StringRec {
        val pending = ArrayDeque<CodeRec>()
        val glyphs = ArrayList<GlyphRec>()
    }
    private class ShowRecord {
        val strings = ArrayList<StringRec>()
        var current: StringRec? = null
    }

    private val metrics = FontMetricsCache()
    private val pageCtx = Ctx(page.resources?.cosObject)
    private var ctx = pageCtx
    private var nesting = 0
    private var formDepth = 0
    private var recording: ShowRecord? = null
    private var fallback: String? = null
    private var nameCounter = 0

    init {
        registerTextOperators()
        addOperator(SetLineWidth())
    }

    /** Processes the page; returns a reason string when the page must be rasterized instead. */
    fun run(): String? {
        processPage(page)
        return fallback
    }

    fun commit() {
        if (!pageCtx.changed) return
        cleanTaintedBdc(pageCtx)
        val tokens = ArrayList<Any>(pageCtx.tokens.size + 8)
        tokens.add(Operator.getOperator("q"))
        tokens.addAll(pageCtx.tokens)
        if (pageCtx.inText) tokens.add(Operator.getOperator("ET"))
        repeat(pageCtx.qDepth) { tokens.add(Operator.getOperator("Q")) }
        tokens.add(Operator.getOperator("Q"))
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        page.setContents(stream)
        val res = pageCtx.resourcesForWrite()
        pruneResources(res, pageCtx.tokens)
        page.cosObject.setItem(COSName.RESOURCES, res)
    }

    // ---------------------------------------------------------------- operator dispatch

    override fun processOperator(operator: Operator, operands: List<COSBase>) {
        if (nesting > 0) {
            super.processOperator(operator, operands)
            return
        }
        nesting++
        try {
            handle(operator, ArrayList(operands))
        } finally {
            nesting--
        }
    }

    private fun emit(args: List<COSBase>, op: Operator) {
        ctx.tokens.addAll(args)
        ctx.tokens.add(op)
    }

    private fun emitAndProcess(op: Operator, args: List<COSBase>) {
        emit(args, op)
        super.processOperator(op, args)
    }

    private fun handle(op: Operator, args: MutableList<COSBase>) {
        when (op.name) {
            "Tj" -> showRecorded(op, args, isArray = false)
            "TJ" -> showRecorded(op, args, isArray = true)
            "'" -> {
                emitAndProcess(Operator.getOperator("T*"), emptyList())
                showRecorded(Operator.getOperator("Tj"), args, isArray = false)
            }
            "\"" -> {
                if (args.size >= 3) {
                    emitAndProcess(Operator.getOperator("Tw"), listOf(args[0]))
                    emitAndProcess(Operator.getOperator("Tc"), listOf(args[1]))
                    emitAndProcess(Operator.getOperator("T*"), emptyList())
                    showRecorded(Operator.getOperator("Tj"), listOf(args[2]), isArray = false)
                } else {
                    emit(args, op)
                }
            }
            "BT" -> { ctx.inText = true; emitAndProcess(op, args) }
            "ET" -> { ctx.inText = false; emitAndProcess(op, args) }
            "q" -> { ctx.qDepth++; emitAndProcess(op, args) }
            "Q" -> if (ctx.qDepth > 0) { ctx.qDepth--; emitAndProcess(op, args) } else ctx.changed = true
            "Do" -> handleDo(op, args)
            "BI" -> handleInlineImage(op, args)
            "m", "l", "c", "v", "y", "h", "re" -> bufferPath(op, args)
            "W", "W*" -> { ctx.pathClip = true; ctx.path.add(PathOp(op, args, emptyList(), false)) }
            "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n" -> flushPath(op, args)
            "BDC" -> {
                val dictIndex = if (args.size >= 2 && args[1] is COSDictionary) ctx.tokens.size + 1 else -1
                emit(args, op)
                ctx.bdc.add(BdcEntry(dictIndex))
            }
            "BMC" -> { emit(args, op); ctx.bdc.add(BdcEntry(-1)) }
            "EMC" -> {
                if (ctx.bdc.isNotEmpty()) {
                    val e = ctx.bdc.removeAt(ctx.bdc.size - 1)
                    if (e.tainted) stripActualText(ctx, e)
                }
                emit(args, op)
            }
            else -> emitAndProcess(op, args)
        }
    }

    private fun taint() {
        ctx.changed = true
        for (e in ctx.bdc) e.tainted = true
    }

    private fun stripActualText(c: Ctx, e: BdcEntry) {
        if (e.dictIndex < 0 || e.dictIndex >= c.tokens.size) return
        val dict = c.tokens[e.dictIndex] as? COSDictionary ?: return
        val copy = COSDictionary(dict)
        copy.removeItem(COSName.getPDFName("ActualText"))
        copy.removeItem(COSName.getPDFName("Alt"))
        copy.removeItem(COSName.getPDFName("E"))
        c.tokens[e.dictIndex] = copy
    }

    private fun cleanTaintedBdc(c: Ctx) {
        for (e in c.bdc) if (e.tainted) stripActualText(c, e)
    }

    // ---------------------------------------------------------------- text

    private fun showRecorded(op: Operator, args: List<COSBase>, isArray: Boolean) {
        val rec = ShowRecord()
        recording = rec
        try {
            super.processOperator(op, args)
        } finally {
            recording = null
        }
        val anyRemoved = rec.strings.any { s -> s.glyphs.any { it.removed } }
        if (!anyRemoved) {
            emit(args, op)
            return
        }
        taint()
        val out = COSArray()
        if (!isArray) {
            val s = args.firstOrNull() as? COSString
            if (s != null && rec.strings.isNotEmpty()) appendRewritten(out, s.bytes, rec.strings[0])
        } else {
            val arr = args.firstOrNull() as? COSArray
            var si = 0
            if (arr != null) {
                for (i in 0 until arr.size()) {
                    when (val el = FormFieldPruner.resolve(arr.get(i))) {
                        is COSString -> {
                            val sr = rec.strings.getOrNull(si++)
                            if (sr != null) appendRewritten(out, el.bytes, sr) else out.add(el)
                        }
                        is COSNumber -> appendNumber(out, el.floatValue())
                        else -> {}
                    }
                }
            }
        }
        emit(listOf(out), Operator.getOperator("TJ"))
    }

    private fun appendRewritten(out: COSArray, bytes: ByteArray, sr: StringRec) {
        val kept = ByteArrayOutputStream()
        var consumed = 0
        for (g in sr.glyphs) {
            if (!g.removed) {
                kept.write(bytes, g.offset, g.len)
            } else {
                if (kept.size() > 0) {
                    out.add(COSString(kept.toByteArray()))
                    kept.reset()
                }
                appendNumber(out, g.adjust)
            }
            consumed = g.offset + g.len
        }
        if (consumed < bytes.size) kept.write(bytes, consumed, bytes.size - consumed)
        if (kept.size() > 0) out.add(COSString(kept.toByteArray()))
    }

    private fun appendNumber(out: COSArray, v: Float) {
        val lastIndex = out.size() - 1
        val last = if (lastIndex >= 0) out.get(lastIndex) else null
        if (last is COSNumber) {
            out.set(lastIndex, COSFloat(last.floatValue() + v))
        } else {
            out.add(COSFloat(v))
        }
    }

    override fun showText(string: ByteArray) {
        val rec = recording
        if (rec == null) {
            super.showText(string)
            return
        }
        val font: PDFont = graphicsState.textState.font ?: PDType1Font.HELVETICA
        val sr = StringRec()
        val input = ByteArrayInputStream(string)
        var offset = 0
        while (input.available() > 0) {
            val before = input.available()
            font.readCode(input)
            val len = before - input.available()
            if (len <= 0) break
            sr.pending.addLast(CodeRec(offset, len))
            offset += len
        }
        rec.strings.add(sr)
        rec.current = sr
        try {
            super.showText(string)
        } finally {
            rec.current = null
        }
    }

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        val sr = recording?.current ?: return
        val cr = sr.pending.removeFirstOrNull() ?: return
        val corners = GlyphGeometry.corners(textRenderingMatrix, font, code, displacement, metrics.extent(font))
        val box = FRect.bounds(corners.asList())!!
        val removed = shouldRemove(box)
        val ts = graphicsState.textState
        val fs = ts.fontSize
        val tc = ts.characterSpacing
        val tw = if (cr.len == 1 && code == 32) ts.wordSpacing else 0f
        val advance = if (font.isVertical) displacement.y else displacement.x
        val adjust = if (fs != 0f) -((advance * fs + tc + tw) / fs) * 1000f else -advance * 1000f
        sr.glyphs.add(GlyphRec(cr.offset, cr.len, removed, adjust))
        if (removed) report.glyphsRemoved++
    }

    private fun shouldRemove(box: FRect): Boolean {
        val degenerate = box.width < 0.01f || box.height < 0.01f
        for (r in rects) {
            if (degenerate) {
                if (r.contains(box.centerX, box.centerY)) return true
                continue
            }
            val inter = box.intersection(r) ?: continue
            if (inter.area / box.area >= 0.15f) return true
            if (r.contains(box.centerX, box.centerY)) return true
        }
        return false
    }

    // ---------------------------------------------------------------- XObjects

    private fun handleDo(op: Operator, args: MutableList<COSBase>) {
        val name = args.firstOrNull() as? COSName
        val res = resources
        if (name == null || res == null) { emit(args, op); return }
        val xobj: PDXObject? = try { res.getXObject(name) } catch (e: IOException) { null }
        when (xobj) {
            is PDImageXObject -> handleImage(op, args, xobj)
            is PDFormXObject -> handleForm(op, args, xobj)
            else -> emit(args, op)
        }
    }

    private fun unitSquareHits(ctm: Matrix): Pair<FRect, List<FRect>> {
        val placement = FRect.bounds(
            listOf(ctm.mapPoint(0f, 0f), ctm.mapPoint(1f, 0f), ctm.mapPoint(0f, 1f), ctm.mapPoint(1f, 1f))
        )!!
        return placement to rects.filter { it.intersects(placement) }
    }

    /** Maps user-space rectangles into pixel rectangles of an image drawn in the unit square. */
    private fun pixelRects(ctm: Matrix, hits: List<FRect>, w: Int, h: Int): List<IntArray>? {
        val inv = ctm.inverseOrNull() ?: return null
        return hits.mapNotNull { r ->
            val u = inv.mapRect(r)
            val x0 = floor(u.x0 * w).toInt().coerceIn(0, w)
            val x1 = ceil(u.x1 * w).toInt().coerceIn(0, w)
            val y0 = floor((1f - u.y1) * h).toInt().coerceIn(0, h)
            val y1 = ceil((1f - u.y0) * h).toInt().coerceIn(0, h)
            if (x1 > x0 && y1 > y0) intArrayOf(x0, y0, x1, y1) else null
        }
    }

    private fun handleImage(op: Operator, args: List<COSBase>, image: PDImageXObject) {
        val ctm = graphicsState.currentTransformationMatrix
        val (_, hits) = unitSquareHits(ctm)
        if (hits.isEmpty()) { emit(args, op); return }
        val w = image.width
        val h = image.height
        val px = pixelRects(ctm, hits, w, h)
        if (px == null) { fallback = fallback ?: "degenerate image transform"; return }
        if (px.isEmpty()) { emit(args, op); return }
        taint()
        if (px.any { it[0] == 0 && it[1] == 0 && it[2] == w && it[3] == h }) {
            report.imagesRemoved++
            return // fully covered: drop the image entirely
        }
        val redacted = ImageRedaction.redactXObject(doc, image, px, codec)
        if (redacted == null) {
            fallback = fallback ?: "unsupported image encoding"
            return
        }
        report.imagesRedacted++
        val newName = addXObject(redacted)
        emit(listOf(newName), op)
    }

    private fun handleInlineImage(op: Operator, args: List<COSBase>) {
        val ctm = graphicsState.currentTransformationMatrix
        val (_, hits) = unitSquareHits(ctm)
        if (hits.isEmpty()) { emit(args, op); return }
        val params = op.imageParameters ?: COSDictionary()
        val data = op.imageData ?: ByteArray(0)
        val inline = try { PDInlineImage(COSDictionary(params), data, resources) } catch (e: Exception) { null }
        if (inline == null) { fallback = fallback ?: "undecodable inline image"; return }
        val w = inline.width
        val h = inline.height
        val px = pixelRects(ctm, hits, w, h) ?: run { fallback = fallback ?: "degenerate inline image transform"; return }
        if (px.isEmpty()) { emit(args, op); return }
        taint()
        if (px.any { it[0] == 0 && it[1] == 0 && it[2] == w && it[3] == h }) {
            report.imagesRemoved++
            return
        }
        val raw = ImageRedaction.redactInline(inline, params, px)
        if (raw != null) {
            report.imagesRedacted++
            emit(emptyList(), raw)
            return
        }
        val c = codec
        val bitmap = if (c != null) try { c.decode(inline) } catch (e: Exception) { null } else null
        if (c == null || bitmap == null) { fallback = fallback ?: "unsupported inline image encoding"; return }
        var img: RasterImage = bitmap
        for (r in px) img = c.fillRect(img, r[0], r[1], r[2], r[3], 0xFF000000.toInt())
        val xobj = c.encodeJpeg(doc, img, 0.92f)
        c.release(img)
        report.imagesRedacted++
        emit(listOf(addXObject(xobj)), Operator.getOperator("Do"))
    }

    private fun handleForm(op: Operator, args: List<COSBase>, form: PDFormXObject) {
        val bbox = form.bBox
        if (bbox == null) { emit(args, op); return }
        val ctm = graphicsState.currentTransformationMatrix
        val fm = form.matrix
        val corners = listOf(
            Pt(bbox.lowerLeftX, bbox.lowerLeftY), Pt(bbox.upperRightX, bbox.lowerLeftY),
            Pt(bbox.lowerLeftX, bbox.upperRightY), Pt(bbox.upperRightX, bbox.upperRightY),
        ).map { ctm.mapPoint(fm.mapPoint(it)) }
        val placed = FRect.bounds(corners)!!
        if (rects.none { it.intersects(placed) }) { emit(args, op); return }
        if (formDepth > 24) { fallback = fallback ?: "form nesting too deep"; return }

        val parentCtx = ctx
        val parentNesting = nesting
        val parentRecording = recording
        val formCtx = Ctx(form.resources?.cosObject)
        ctx = formCtx
        nesting = 0
        recording = null
        formDepth++
        try {
            showForm(form)
        } finally {
            formDepth--
            ctx = parentCtx
            nesting = parentNesting
            recording = parentRecording
        }
        if (!formCtx.changed) { emit(args, op); return }
        taint()
        cleanTaintedBdc(formCtx)
        val tokens = ArrayList<Any>(formCtx.tokens)
        if (formCtx.inText) tokens.add(Operator.getOperator("ET"))
        repeat(formCtx.qDepth) { tokens.add(Operator.getOperator("Q")) }
        val newStream = PDStream(doc)
        val dict = newStream.cosObject
        for ((key, value) in form.cosObject.entrySet()) {
            if (key == COSName.LENGTH || key == COSName.FILTER || key == COSName.DECODE_PARMS ||
                key.name == "DL" || key == COSName.RESOURCES) continue
            dict.setItem(key, value)
        }
        val res = formCtx.resourcesForWrite()
        pruneResources(res, formCtx.tokens)
        dict.setItem(COSName.RESOURCES, res)
        newStream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        val newForm = PDFormXObject(newStream)
        emit(listOf(addXObject(newForm)), op)
    }

    private fun addXObject(x: PDXObject): COSName {
        val res = ctx.resourcesForWrite()
        val xo = res.getDictionaryObject(COSName.XOBJECT) as COSDictionary
        var name: COSName
        do {
            name = COSName.getPDFName("Rd${++nameCounter}")
        } while (xo.containsKey(name))
        xo.setItem(name, x.cosObject)
        ctx.changed = true
        return name
    }

    /** Removes XObjects and fonts that the rewritten content no longer references. */
    private fun pruneResources(res: COSDictionary, tokens: List<Any>) {
        val usedX = HashSet<COSName>()
        val usedF = HashSet<COSName>()
        var prev: Any? = null
        var prev2: Any? = null
        for (t in tokens) {
            if (t is Operator) {
                when (t.name) {
                    "Do" -> (prev as? COSName)?.let { usedX.add(it) }
                    "Tf" -> (prev2 as? COSName)?.let { usedF.add(it) }
                }
            }
            prev2 = prev
            prev = t
        }
        (res.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)?.let { xo ->
            for (k in xo.keySet().toList()) if (k !in usedX) xo.removeItem(k)
        }
        // Fonts can also be selected through ExtGState /Font entries; only prune when none do.
        val gsUsesFont = (res.getDictionaryObject(COSName.EXT_G_STATE) as? COSDictionary)?.values?.any {
            (FormFieldPruner.resolve(it) as? COSDictionary)?.containsKey(COSName.FONT) == true
        } == true
        if (!gsUsesFont) {
            (res.getDictionaryObject(COSName.FONT) as? COSDictionary)?.let { f ->
                for (k in f.keySet().toList()) if (k !in usedF) f.removeItem(k)
            }
        }
    }

    // ---------------------------------------------------------------- vector paths

    private fun bufferPath(op: Operator, args: List<COSBase>) {
        val ctm = graphicsState.currentTransformationMatrix
        val nums = args.map { (it as? COSNumber)?.floatValue() ?: 0f }
        val pts = ArrayList<Pt>(4)
        when (op.name) {
            "m", "l" -> if (nums.size >= 2) pts.add(ctm.mapPoint(nums[0], nums[1]))
            "c" -> if (nums.size >= 6) for (i in 0 until 3) pts.add(ctm.mapPoint(nums[i * 2], nums[i * 2 + 1]))
            "v", "y" -> if (nums.size >= 4) for (i in 0 until 2) pts.add(ctm.mapPoint(nums[i * 2], nums[i * 2 + 1]))
            "re" -> if (nums.size >= 4) {
                val x = nums[0]; val y = nums[1]; val w = nums[2]; val h = nums[3]
                pts.add(ctm.mapPoint(x, y)); pts.add(ctm.mapPoint(x + w, y))
                pts.add(ctm.mapPoint(x, y + h)); pts.add(ctm.mapPoint(x + w, y + h))
            }
        }
        val starts = op.name == "m" || op.name == "re" || ctx.path.isEmpty()
        ctx.path.add(PathOp(op, args, pts, starts))
    }

    private fun flushPath(paint: Operator, paintArgs: List<COSBase>) {
        val ops = ctx.path
        val clip = ctx.pathClip
        ctx.path = ArrayList()
        ctx.pathClip = false
        if (paint.name == "n" || clip || ops.isEmpty()) {
            for (o in ops) emit(o.args, o.op)
            emit(paintArgs, paint)
            return
        }
        val stroking = paint.name in STROKE_OPS
        val ctm = graphicsState.currentTransformationMatrix
        val halfWidth = if (stroking) max(graphicsState.lineWidth, 1f) * ctm.meanScale() / 2f else 0f

        // Split into subpaths.
        val subpaths = ArrayList<MutableList<PathOp>>()
        for (o in ops) {
            if (o.startsSubpath || subpaths.isEmpty()) subpaths.add(ArrayList())
            subpaths.last().add(o)
        }
        val out = ArrayList<Any>()
        var removedAny = false
        for (sp in subpaths) {
            val pts = sp.flatMap { it.points }
            val bbox = FRect.bounds(pts)?.outset(halfWidth)
            if (bbox == null) { sp.forEach { out.addAll(it.args); out.add(it.op) }; continue }
            val hits = rects.filter { it.touches(bbox) }
            if (hits.isEmpty() || (stroking && !strokeTouches(sp, halfWidth, hits))) {
                sp.forEach { out.addAll(it.args); out.add(it.op) }
                continue
            }

            if (sp.size == 1 && sp[0].op.name == "re" && !stroking && ctm.isAxisAligned()) {
                // Cut the redaction areas out of a filled rectangle exactly.
                var pieces = listOf(bbox)
                for (r in hits) pieces = pieces.flatMap { it.minus(r) }
                val inv = ctm.inverseOrNull()
                if (inv != null) {
                    removedAny = true
                    report.pathsRemoved++
                    for (p in pieces) {
                        val local = inv.mapRect(p)
                        if (local.isEmpty) continue
                        out.add(COSFloat(local.x0)); out.add(COSFloat(local.y0))
                        out.add(COSFloat(local.width)); out.add(COSFloat(local.height))
                        out.add(Operator.getOperator("re"))
                    }
                    continue
                }
            }
            val covered = hits.maxOf { r -> bbox.intersection(r)?.area ?: 0f }
            val frac = if (bbox.area > 0.01f) covered / bbox.area else 1f
            val maxHit = hits.maxOf { max(it.width, it.height) }
            val small = max(bbox.width, bbox.height) <= 1.5f * maxHit
            if (frac >= 0.4f || small) {
                removedAny = true
                report.pathsRemoved++
                continue
            }
            sp.forEach { out.addAll(it.args); out.add(it.op) }
        }
        if (!removedAny) {
            for (o in ops) emit(o.args, o.op)
            emit(paintArgs, paint)
            return
        }
        taint()
        if (out.isEmpty()) return
        ctx.tokens.addAll(out)
        emit(paintArgs, paint)
    }

    /** For stroked paths only the outline is painted: test each segment's (outset) bounds. */
    private fun strokeTouches(sp: List<PathOp>, halfWidth: Float, hits: List<FRect>): Boolean {
        var start: Pt? = null
        var cur: Pt? = null
        fun seg(points: List<Pt>): Boolean {
            val b = FRect.bounds(points)?.outset(halfWidth) ?: return false
            return hits.any { it.touches(b) }
        }
        for (o in sp) {
            val p = o.points
            when (o.op.name) {
                "m" -> { start = p.firstOrNull(); cur = start }
                "l", "c", "v", "y" -> {
                    val from = cur
                    if (from != null && p.isNotEmpty() && seg(listOf(from) + p)) return true
                    cur = p.lastOrNull() ?: cur
                }
                "h" -> {
                    val a = cur
                    val b = start
                    if (a != null && b != null && seg(listOf(a, b))) return true
                    cur = start
                }
                "re" -> if (p.size == 4) {
                    // corners: (x,y) (x+w,y) (x,y+h) (x+w,y+h)
                    if (seg(listOf(p[0], p[1])) || seg(listOf(p[1], p[3])) ||
                        seg(listOf(p[3], p[2])) || seg(listOf(p[2], p[0]))
                    ) return true
                    start = p[0]; cur = p[0]
                }
            }
        }
        return false
    }

    companion object {
        private val STROKE_OPS = setOf("S", "s", "B", "B*", "b", "b*")
    }
}

/** Pixel-level image redaction that destroys the original samples inside the given regions. */
internal object ImageRedaction {
    private val RAW_FILTERS = setOf(
        "FlateDecode", "Fl", "LZWDecode", "LZW", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85",
        "RunLengthDecode", "RL", "CCITTFaxDecode", "CCF",
    )
    private val DCT_FILTERS = setOf("DCTDecode", "DCT")

    fun filterNames(dict: COSDictionary, key: COSName = COSName.FILTER, abbrev: COSName? = null): List<String> {
        val f = dict.getDictionaryObject(key) ?: abbrev?.let { dict.getDictionaryObject(it) }
        return when (f) {
            is COSName -> listOf(f.name)
            is COSArray -> (0 until f.size()).mapNotNull { (FormFieldPruner.resolve(f.get(it)) as? COSName)?.name }
            else -> emptyList()
        }
    }

    /** Returns a new image XObject with the regions blanked, or null if the encoding is unsupported. */
    fun redactXObject(
        doc: PDDocument, image: PDImageXObject, regions: List<IntArray>, codec: ImageCodec?, isMask: Boolean = false,
    ): PDImageXObject? {
        val stream = image.cosObject
        val filters = filterNames(stream)
        if (filters.all { it in RAW_FILTERS }) {
            val bpc = if (image.isStencil) 1 else image.bitsPerComponent
            val ncomp = if (image.isStencil) 1 else (try { image.colorSpace.numberOfComponents } catch (e: Exception) { return null })
            if (bpc <= 0 || ncomp <= 0) return null
            val data = try { stream.createInputStream().use { it.readBytes() } } catch (e: Exception) { return null }
            val w = image.width
            val h = image.height
            val rowBytes = (w * ncomp * bpc + 7) / 8
            if (data.size < rowBytes * h) return null
            val value = if (image.isStencil) stencilClearValue(stream.getDictionaryObject(COSName.DECODE) as? COSArray) else 0
            for (r in regions) blank(data, rowBytes, bpc, ncomp, r, value)
            val out = PDStream(doc)
            val dict = out.cosObject
            for ((key, v) in stream.entrySet()) {
                if (key == COSName.LENGTH || key == COSName.FILTER || key == COSName.DECODE_PARMS ||
                    key.name == "DL" || key == COSName.SMASK || key == COSName.MASK ||
                    key == COSName.METADATA) continue
                dict.setItem(key, v)
            }
            out.createOutputStream(COSName.FLATE_DECODE).use { it.write(data) }
            // Soft masks and stencil masks carry the shape of the content: blank them too.
            val smask = stream.getDictionaryObject(COSName.SMASK) as? COSStream
            if (smask != null) {
                val newMask = redactMask(doc, smask, w, h, regions, codec) ?: return null
                dict.setItem(COSName.SMASK, newMask)
            }
            when (val mask = stream.getDictionaryObject(COSName.MASK)) {
                is COSStream -> {
                    val newMask = redactMask(doc, mask, w, h, regions, codec) ?: return null
                    dict.setItem(COSName.MASK, newMask)
                }
                is COSArray -> dict.setItem(COSName.MASK, mask)
                else -> {}
            }
            return PDImageXObject(out, null)
        }
        if (filters.size == 1 && filters[0] in DCT_FILTERS && codec != null && !isMask) {
            val decoded = try { codec.decode(image) } catch (e: Exception) { null } ?: return null
            var img = decoded
            for (r in regions) img = codec.fillRect(img, r[0], r[1], r[2], r[3], 0xFF000000.toInt())
            val result = codec.encodeJpeg(doc, img, 0.92f)
            codec.release(img)
            return result
        }
        return null
    }

    private fun redactMask(doc: PDDocument, mask: COSStream, imgW: Int, imgH: Int, regions: List<IntArray>, codec: ImageCodec?): COSStream? {
        val maskImage = PDImageXObject(PDStream(mask), null)
        val mw = maskImage.width
        val mh = maskImage.height
        if (mw <= 0 || mh <= 0) return null
        val scaled = regions.map { r ->
            intArrayOf(
                floor(r[0].toFloat() * mw / imgW).toInt().coerceIn(0, mw), floor(r[1].toFloat() * mh / imgH).toInt().coerceIn(0, mh),
                ceil(r[2].toFloat() * mw / imgW).toInt().coerceIn(0, mw), ceil(r[3].toFloat() * mh / imgH).toInt().coerceIn(0, mh),
            )
        }.filter { it[2] > it[0] && it[3] > it[1] }
        if (scaled.isEmpty()) return mask
        return redactXObject(doc, maskImage, scaled, codec, isMask = true)?.cosObject
    }

    /** For image masks, the sample value meaning "leave the page unpainted". */
    private fun stencilClearValue(decode: COSArray?): Int {
        val d0 = (decode?.getObject(0) as? COSNumber)?.intValue() ?: 0
        return if (d0 == 1) 0 else 1
    }

    /** Sets all samples in region [x0,x1) x [y0,y1) to [value] (0 or 1 for 1-bit; 0 otherwise). */
    internal fun blank(data: ByteArray, rowBytes: Int, bpc: Int, ncomp: Int, r: IntArray, value: Int) {
        val bitsPerPixel = bpc * ncomp
        for (y in r[1] until r[3]) {
            val rowStart = y * rowBytes
            if (bitsPerPixel % 8 == 0) {
                val bpp = bitsPerPixel / 8
                val fill = (if (value != 0) 0xFF else 0).toByte()
                val start = rowStart + r[0] * bpp
                val end = min(rowStart + r[2] * bpp, rowStart + rowBytes)
                for (i in start until end) data[i] = fill
            } else {
                val startBit = r[0] * bitsPerPixel
                val endBit = r[2] * bitsPerPixel
                for (bit in startBit until endBit) {
                    val byteIndex = rowStart + (bit ushr 3)
                    val mask = 0x80 ushr (bit and 7)
                    data[byteIndex] = if (value != 0) {
                        (data[byteIndex].toInt() or mask).toByte()
                    } else {
                        (data[byteIndex].toInt() and mask.inv()).toByte()
                    }
                }
            }
        }
    }

    /** Rewrites a raw-sample inline image with regions blanked; null if unsupported. */
    fun redactInline(inline: PDInlineImage, params: COSDictionary, regions: List<IntArray>): Operator? {
        val filters = inline.filters ?: emptyList()
        if (!filters.all { it in RAW_FILTERS }) return null
        val bpc = if (inline.isStencil) 1 else inline.bitsPerComponent
        val ncomp = if (inline.isStencil) 1 else (try { inline.colorSpace.numberOfComponents } catch (e: Exception) { return null })
        val data = inline.data.copyOf()
        val rowBytes = (inline.width * ncomp * bpc + 7) / 8
        if (bpc <= 0 || data.size < rowBytes * inline.height) return null
        val decode = (params.getDictionaryObject(COSName.D) ?: params.getDictionaryObject(COSName.DECODE)) as? COSArray
        val value = if (inline.isStencil) stencilClearValue(decode) else 0
        for (r in regions) blank(data, rowBytes, bpc, ncomp, r, value)
        val newParams = COSDictionary(params)
        newParams.removeItem(COSName.F)
        newParams.removeItem(COSName.FILTER)
        newParams.removeItem(COSName.DP)
        newParams.removeItem(COSName.DECODE_PARMS)
        val op = Operator.getOperator("BI")
        op.imageParameters = newParams
        op.imageData = data
        return op
    }
}
