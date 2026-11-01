package com.tandidong.pdf.core

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.IOException

/** An image drawn directly by a page's content stream. Rectangles are display space. */
data class PlacedImage(
    val pageIndex: Int,
    /** Position among the page's top-level image draws (content order). */
    val ordinal: Int,
    val displayRect: FRect,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val inline: Boolean,
    /** XObject resource name, null for inline images. */
    val name: String?,
    /** True for baseline JPEG data that can be saved as-is. */
    val isJpeg: Boolean,
) {
    val sizeLabel: String get() = "$pixelWidth × $pixelHeight px"
}

/**
 * "Edit PDF" image operations: list, delete, move/resize and replace images that a page draws
 * from its own content stream (images nested in form XObjects are not editable here).
 */
object PageImages {

    fun list(doc: PDDocument, pageIndex: Int): List<PlacedImage> {
        val page = doc.getPage(pageIndex)
        return Scanner(page, pageIndex).also { it.scan() }.images.map { it.info }
    }

    /** Topmost image under a display-space point. */
    fun hitTest(images: List<PlacedImage>, pageIndex: Int, x: Float, y: Float): PlacedImage? =
        images.lastOrNull { it.pageIndex == pageIndex && it.displayRect.contains(x, y) }

    fun delete(doc: PDDocument, img: PlacedImage): Boolean = edit(doc, img) { s, rec ->
        s.tokens.subList(rec.firstToken, rec.opToken + 1).clear()
    }

    /** Moves and/or scales [img] so that it occupies [newRect] (display space). */
    fun transform(doc: PDDocument, img: PlacedImage, newRect: FRect): Boolean {
        if (newRect.width < 1f || newRect.height < 1f) return false
        val page = doc.getPage(img.pageIndex)
        val g = PageGeometry.of(page)
        return edit(doc, img) { s, rec ->
            val old = rec.userRect
            val target = g.rectToUser(newRect)
            if (old.width <= 0f || old.height <= 0f) throw IOException("Image has no area")
            val sx = target.width / old.width
            val sy = target.height / old.height
            val t = Matrix(sx, 0f, 0f, sy, target.x0 - old.x0 * sx, target.y0 - old.y0 * sy)
            val inv = rec.ctm.inverseOrNull() ?: throw IOException("Image transform is not invertible")
            // cm pre-multiplies the CTM, so M = CTM · T · CTM⁻¹ gives CTM' = CTM · T.
            val m = rec.ctm.multiply(t).multiply(inv)
            val ops = ArrayList<Any>()
            ops.add(Operator.getOperator("q"))
            for (v in listOf(m.scaleX, m.shearY, m.shearX, m.scaleY, m.translateX, m.translateY)) ops.add(COSFloat(v))
            ops.add(Operator.getOperator("cm"))
            s.tokens.add(rec.opToken + 1, Operator.getOperator("Q"))
            s.tokens.addAll(rec.firstToken, ops)
        }
    }

    /** Swaps the picture drawn by [img] for [replacement], keeping its placement. */
    fun replace(doc: PDDocument, img: PlacedImage, replacement: PDImageXObject): Boolean {
        val page = doc.getPage(img.pageIndex)
        return edit(doc, img) { s, rec ->
            val res = page.resources ?: com.tom_roush.pdfbox.pdmodel.PDResources().also { page.resources = it }
            val name = res.add(replacement, "Im")
            if (rec.inline) {
                s.tokens[rec.opToken] = Operator.getOperator("Do")
                s.tokens.add(rec.opToken, name)
            } else {
                s.tokens[rec.firstToken] = name
            }
        }
    }

    /** The XObject behind [img], or null for inline images. */
    fun xobject(doc: PDDocument, img: PlacedImage): PDImageXObject? {
        val name = img.name ?: return null
        return doc.getPage(img.pageIndex).resources?.getXObject(COSName.getPDFName(name)) as? PDImageXObject
    }

    /** Baseline JPEG bytes of [img] when the image is stored as a plain JPEG. */
    fun rawJpeg(doc: PDDocument, img: PlacedImage): ByteArray? {
        if (!img.isJpeg) return null
        val x = xobject(doc, img) ?: return null
        return x.stream.createInputStream(listOf(COSName.DCT_DECODE.name)).use { it.readBytes() }
    }

    private fun edit(doc: PDDocument, img: PlacedImage, change: (Scanner, Scanner.Rec) -> Unit): Boolean {
        val page = doc.getPage(img.pageIndex)
        val s = Scanner(page, img.pageIndex)
        s.scan()
        val rec = s.images.getOrNull(img.ordinal) ?: return false
        // Guard against a stale selection: the image must still be where the caller saw it.
        if (!rec.info.displayRect.outset(1f).contains(img.displayRect.centerX, img.displayRect.centerY)) return false
        change(s, rec)
        s.write(doc)
        return true
    }

    private class Scanner(private val page: PDPage, private val pageIndex: Int) : PDFStreamEngine() {
        class Rec(val info: PlacedImage, val firstToken: Int, val opToken: Int, val ctm: Matrix, val userRect: FRect, val inline: Boolean)

        val tokens = ArrayList<Any>()
        val images = ArrayList<Rec>()
        private var nesting = 0
        private var qDepth = 0
        private var inText = false
        private val geometry = PageGeometry.of(page)

        init {
            addOperator(Concatenate())
            addOperator(Save())
            addOperator(Restore())
            addOperator(DrawObject())
        }

        fun scan() {
            if (page.hasContents()) processPage(page)
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (nesting > 0) {
                super.processOperator(operator, operands)
                return
            }
            nesting++
            try {
                val args = ArrayList(operands)
                val first = tokens.size
                tokens.addAll(args)
                tokens.add(operator)
                when (operator.name) {
                    "q" -> qDepth++
                    "Q" -> if (qDepth > 0) qDepth--
                    "BT" -> inText = true
                    "ET" -> inText = false
                    "Do" -> recordXObject(args, first)
                    "BI" -> recordInline(operator, first)
                }
                super.processOperator(operator, args)
            } finally {
                nesting--
            }
        }

        private fun recordXObject(args: List<COSBase>, first: Int) {
            val name = args.firstOrNull() as? COSName ?: return
            val res = resources ?: return
            val x = try {
                res.getXObject(name)
            } catch (e: IOException) {
                null
            } as? PDImageXObject ?: return
            val filters = x.cosObject.getDictionaryObject(COSName.FILTER)
            val jpeg = filters == COSName.DCT_DECODE ||
                (filters is com.tom_roush.pdfbox.cos.COSArray && filters.size() == 1 && filters.getObject(0) == COSName.DCT_DECODE)
            add(x.width, x.height, false, name.name, jpeg, first)
        }

        private fun recordInline(op: Operator, first: Int) {
            val params = op.imageParameters ?: return
            val w = params.getInt(COSName.W, params.getInt(COSName.WIDTH, 0))
            val h = params.getInt(COSName.H, params.getInt(COSName.HEIGHT, 0))
            add(w, h, true, null, false, first)
        }

        private fun add(w: Int, h: Int, inline: Boolean, name: String?, jpeg: Boolean, first: Int) {
            val ctm = graphicsState.currentTransformationMatrix.clone()
            val user = ctm.mapRect(FRect(0f, 0f, 1f, 1f))
            val disp = geometry.rectToDisplay(user)
            val info = PlacedImage(pageIndex, images.size, disp, w, h, inline, name, jpeg)
            images.add(Rec(info, first, tokens.size - 1, ctm, user, inline))
        }

        /** Writes the (edited) tokens back as the page's single content stream. */
        fun write(doc: PDDocument) {
            val out = ArrayList<Any>(tokens.size + 4)
            out.add(Operator.getOperator("q"))
            out.addAll(tokens)
            if (inText) out.add(Operator.getOperator("ET"))
            repeat(qDepth) { out.add(Operator.getOperator("Q")) }
            out.add(Operator.getOperator("Q"))
            val stream = PDStream(doc)
            stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
            page.setContents(stream)
        }
    }
}
