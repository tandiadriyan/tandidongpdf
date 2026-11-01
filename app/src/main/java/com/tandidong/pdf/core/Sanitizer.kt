package com.tandidong.pdf.core

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.util.Matrix
import java.io.IOException

data class SanitizeOptions(
    val metadata: Boolean = true,
    val javascript: Boolean = true,
    val attachments: Boolean = true,
    val comments: Boolean = false,
    val links: Boolean = false,
    val formFields: Boolean = false,
    val bookmarks: Boolean = false,
    val thumbnails: Boolean = true,
    val privateAppData: Boolean = true,
)

data class SanitizeReport(var itemsRemoved: Int = 0)

/** "Remove hidden information": metadata, scripts, attachments, comments and more. */
object Sanitizer {
    private val JS = COSName.getPDFName("JavaScript")
    private val AA = COSName.getPDFName("AA")

    fun sanitize(doc: PDDocument, o: SanitizeOptions): SanitizeReport {
        val report = SanitizeReport()
        val catalog = doc.documentCatalog.cosObject
        if (o.metadata) {
            doc.documentInformation = PDDocumentInformation()
            if (catalog.containsKey(COSName.METADATA)) { catalog.removeItem(COSName.METADATA); report.itemsRemoved++ }
        }
        val names = catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary
        if (o.javascript) {
            if (names?.containsKey(JS) == true) { names.removeItem(JS); report.itemsRemoved++ }
            if (catalog.containsKey(AA)) { catalog.removeItem(AA); report.itemsRemoved++ }
            val open = catalog.getDictionaryObject(COSName.OPEN_ACTION) as? COSDictionary
            if (open != null && open.getNameAsString(COSName.S) != "GoTo") { catalog.removeItem(COSName.OPEN_ACTION); report.itemsRemoved++ }
            doc.documentCatalog.acroForm?.let { acro ->
                for (f in acro.fieldTree) {
                    if (f.cosObject.containsKey(AA)) { f.cosObject.removeItem(AA); report.itemsRemoved++ }
                }
            }
        }
        if (o.attachments && names?.containsKey(COSName.EMBEDDED_FILES) == true) {
            names.removeItem(COSName.EMBEDDED_FILES); report.itemsRemoved++
        }
        if (o.bookmarks && catalog.containsKey(COSName.OUTLINES)) { catalog.removeItem(COSName.OUTLINES); report.itemsRemoved++ }
        if (o.privateAppData) catalog.removeItem(COSName.getPDFName("PieceInfo"))
        if (o.formFields) Forms.flatten(doc)

        for (page in doc.pages) {
            val pd = page.cosObject
            if (o.thumbnails && pd.containsKey(COSName.getPDFName("Thumb"))) { pd.removeItem(COSName.getPDFName("Thumb")); report.itemsRemoved++ }
            if (o.privateAppData) pd.removeItem(COSName.getPDFName("PieceInfo"))
            if (o.metadata) pd.removeItem(COSName.METADATA)
            if (o.javascript && pd.containsKey(AA)) { pd.removeItem(AA); report.itemsRemoved++ }
            val annots = try { page.annotations } catch (e: IOException) { continue }
            val keep = ArrayList<PDAnnotation>()
            for (a in annots) {
                val st = a.subtype
                val drop = when {
                    o.attachments && st == "FileAttachment" -> true
                    o.comments && st !in setOf("Widget", "Link") -> true
                    o.links && st == "Link" -> true
                    else -> false
                }
                if (drop) { report.itemsRemoved++; continue }
                if (o.javascript) {
                    val d = a.cosObject
                    if (d.containsKey(AA)) { d.removeItem(AA); report.itemsRemoved++ }
                    val action = d.getDictionaryObject(COSName.A) as? COSDictionary
                    if (action != null && action.getNameAsString(COSName.S) in setOf("JavaScript", "Launch", "ImportData", "SubmitForm", "ResetForm")) {
                        d.removeItem(COSName.A); report.itemsRemoved++
                    }
                }
                keep.add(a)
            }
            if (keep.size != annots.size) page.annotations = keep
        }
        return report
    }
}

/** Bakes annotation appearances (and form fields) into page content. */
object Flattener {
    fun flatten(doc: PDDocument, includeForms: Boolean = true, pages: Collection<Int>? = null): Int {
        if (includeForms) {
            try { Forms.flatten(doc) } catch (e: Exception) { CoreLog.w("Form flatten failed", e) }
        }
        var count = 0
        val targets = pages ?: (0 until doc.numberOfPages).toList()
        for (pi in targets) {
            if (pi !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(pi)
            val annots = try { page.annotations } catch (e: IOException) { continue }
            if (annots.isEmpty()) continue
            val keep = ArrayList<PDAnnotation>()
            val toDraw = ArrayList<PDAnnotation>()
            for (a in annots) {
                when {
                    a.subtype == "Link" || a.subtype == "Popup" -> if (a.subtype == "Link") keep.add(a)
                    a.isHidden || a.isNoView -> {}
                    else -> {
                        if (a.normalAppearanceStream == null) Appearances.regenerate(doc, a)
                        if (a.normalAppearanceStream != null) toDraw.add(a) else keep.add(a)
                    }
                }
            }
            if (toDraw.isEmpty()) continue
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                for (a in toDraw) {
                    val ap = a.normalAppearanceStream ?: continue
                    val rect = a.rectangle ?: continue
                    val bbox = ap.bBox ?: continue
                    val m = ap.matrix
                    val tb = FRect.bounds(listOf(
                        m.mapPoint(bbox.lowerLeftX, bbox.lowerLeftY), m.mapPoint(bbox.upperRightX, bbox.lowerLeftY),
                        m.mapPoint(bbox.lowerLeftX, bbox.upperRightY), m.mapPoint(bbox.upperRightX, bbox.upperRightY),
                    )) ?: continue
                    if (tb.width <= 0f || tb.height <= 0f) continue
                    val sx = rect.width / tb.width
                    val sy = rect.height / tb.height
                    val a2 = Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - tb.x0 * sx, rect.lowerLeftY - tb.y0 * sy)
                    val ca = a.cosObject.getFloat(COSName.CA, 1f)
                    cs.saveGraphicsState()
                    if (ca < 1f) {
                        val gs = com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState()
                        gs.nonStrokingAlphaConstant = ca
                        gs.strokingAlphaConstant = ca
                        cs.setGraphicsStateParameters(gs)
                    }
                    cs.transform(a2)
                    cs.drawForm(PDFormXObject(ap.cosObject))
                    cs.restoreGraphicsState()
                    count++
                }
            }
            page.annotations = keep
        }
        return count
    }
}

/** Shrinks files by re-encoding large images and compressing uncompressed streams. */
data class CompressOptions(
    val maxImageDimension: Int = 1600,
    val jpegQuality: Float = 0.7f,
    val removeMetadata: Boolean = false,
) {
    companion object {
        val LOW = CompressOptions(2400, 0.82f)
        val MEDIUM = CompressOptions(1600, 0.7f)
        val HIGH = CompressOptions(1100, 0.55f, removeMetadata = true)
    }
}

data class CompressReport(var imagesRecompressed: Int = 0, var streamsCompressed: Int = 0, var bytesSavedInImages: Long = 0)

class Compressor(private val codec: ImageCodec) {
    fun compress(doc: PDDocument, o: CompressOptions, progress: Progress? = null): CompressReport {
        val report = CompressReport()
        val images = java.util.IdentityHashMap<com.tom_roush.pdfbox.cos.COSStream, com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject>()
        val contentStreams = java.util.IdentityHashMap<com.tom_roush.pdfbox.cos.COSStream, Boolean>()
        val seenRes = java.util.IdentityHashMap<COSDictionary, Boolean>()
        fun scan(res: com.tom_roush.pdfbox.pdmodel.PDResources?, depth: Int) {
            if (res == null || depth > 10 || seenRes.put(res.cosObject, true) != null) return
            for (n in res.xObjectNames) {
                val x = try { res.getXObject(n) } catch (e: Exception) { null } ?: continue
                when (x) {
                    is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject -> images[x.cosObject] = x
                    is PDFormXObject -> { contentStreams[x.cosObject] = true; scan(x.resources, depth + 1) }
                }
            }
        }
        for (page in doc.pages) {
            scan(page.resources, 0)
            val c = page.cosObject.getDictionaryObject(COSName.CONTENTS)
            when (c) {
                is com.tom_roush.pdfbox.cos.COSStream -> contentStreams[c] = true
                is COSArray -> for (i in 0 until c.size()) (c.getObject(i) as? com.tom_roush.pdfbox.cos.COSStream)?.let { contentStreams[it] = true }
            }
            page.cosObject.removeItem(COSName.getPDFName("Thumb"))
            page.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
        }
        var done = 0
        val total = images.size
        for ((stream, img) in images) {
            try {
                if (recompress(doc, stream, img, o)) report.imagesRecompressed++
            } catch (e: Exception) {
                CoreLog.w("Image recompression failed", e)
            }
            progress?.invoke(++done, total)
        }
        for (s in contentStreams.keys) {
            try {
                if (s.filters == null) {
                    val data = s.createInputStream().use { it.readBytes() }
                    s.createOutputStream(COSName.FLATE_DECODE).use { it.write(data) }
                    report.streamsCompressed++
                }
            } catch (e: Exception) {
                CoreLog.w("Stream compression failed", e)
            }
        }
        if (o.removeMetadata) {
            doc.documentCatalog.cosObject.removeItem(COSName.METADATA)
            doc.documentCatalog.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
        }
        return report
    }

    private fun recompress(
        doc: PDDocument, stream: com.tom_roush.pdfbox.cos.COSStream,
        img: com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject, o: CompressOptions,
    ): Boolean {
        if (img.isStencil || img.bitsPerComponent == 1) return false
        if (stream.containsKey(COSName.MASK)) return false
        val w = img.width
        val h = img.height
        if (w * h < 96 * 96) return false
        val filters = ImageRedaction.filterNames(stream)
        if (filters.any { it == "JBIG2Decode" || it == "CCITTFaxDecode" }) return false
        val oldSize = stream.length
        val isJpeg = filters.lastOrNull() in setOf("DCTDecode", "DCT")
        val maxDim = maxOf(w, h)
        val bytesPerPixel = oldSize.toDouble() / (w.toLong() * h)
        if (isJpeg && maxDim <= o.maxImageDimension && bytesPerPixel < 0.25) return false
        val decoded = codec.decode(img) ?: return false
        var raster = decoded
        if (maxDim > o.maxImageDimension) {
            val s = o.maxImageDimension.toFloat() / maxDim
            val scaled = codec.scaled(raster, maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
            codec.release(raster)
            raster = scaled
        }
        val replacement = codec.encodeJpeg(doc, raster, o.jpegQuality)
        codec.release(raster)
        val newStream = replacement.cosObject
        var newSize = newStream.length
        (newStream.getDictionaryObject(COSName.SMASK) as? com.tom_roush.pdfbox.cos.COSStream)?.let { newSize += it.length }
        val oldMask = (stream.getDictionaryObject(COSName.SMASK) as? com.tom_roush.pdfbox.cos.COSStream)?.length ?: 0L
        if (newSize >= oldSize + oldMask) return false
        // Replace in place so every page that shares the image picks up the smaller version.
        stream.createRawOutputStream().use { out -> newStream.createRawInputStream().use { it.copyTo(out) } }
        for (k in stream.keySet().toList()) if (k != COSName.LENGTH) stream.removeItem(k)
        for ((k, v) in newStream.entrySet()) if (k != COSName.LENGTH) stream.setItem(k, v)
        return true
    }
}
