package com.tandidong.pdf.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.tandidong.pdf.core.CoreLog
import com.tandidong.pdf.core.FontProvider
import com.tandidong.pdf.core.FontSelector
import com.tandidong.pdf.core.ImageCodec
import com.tandidong.pdf.core.PageRasterizer
import com.tandidong.pdf.core.RasterImage
import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.util.WeakHashMap

/** Android Bitmap wrapper for the core engine. */
class BitmapRaster(var bitmap: Bitmap) : RasterImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

object AndroidImageCodec : ImageCodec {
    override fun decode(image: PDImage): RasterImage? = try {
        val src = image.image ?: null
        if (src == null) {
            null
        } else {
            val b = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true)
            val opaque = image is PDImageXObject && image.softMask == null && image.mask == null
            if (opaque) b.setHasAlpha(false)
            BitmapRaster(b)
        }
    } catch (t: Throwable) {
        CoreLog.w("Image decode failed", t)
        null
    }

    override fun fillRect(img: RasterImage, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int): RasterImage {
        val r = img as BitmapRaster
        if (!r.bitmap.isMutable) r.bitmap = r.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint().apply { color = argb; style = Paint.Style.FILL; isAntiAlias = false }
        Canvas(r.bitmap).drawRect(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint)
        return r
    }

    override fun scaled(img: RasterImage, width: Int, height: Int): RasterImage {
        val r = img as BitmapRaster
        val out = Bitmap.createScaledBitmap(r.bitmap, width, height, true)
        if (!r.bitmap.hasAlpha()) out.setHasAlpha(false)
        return BitmapRaster(out)
    }

    override fun hasAlpha(img: RasterImage): Boolean = (img as BitmapRaster).bitmap.hasAlpha()

    override fun encodeJpeg(doc: PDDocument, img: RasterImage, quality: Float): PDImageXObject =
        JPEGFactory.createFromImage(doc, (img as BitmapRaster).bitmap, quality)

    override fun encodeLossless(doc: PDDocument, img: RasterImage): PDImageXObject =
        LosslessFactory.createFromImage(doc, (img as BitmapRaster).bitmap)

    override fun release(img: RasterImage) {
        val b = (img as BitmapRaster).bitmap
        if (!b.isRecycled) b.recycle()
    }
}

/**
 * Unicode fonts: the Liberation Sans font bundled with PdfBox-Android (Latin, Greek, Cyrillic),
 * then TrueType system fonts for other scripts when available.
 */
object AndroidFontProvider : FontProvider {
    private const val LIBERATION = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    private val cache = WeakHashMap<PDDocument, MutableMap<String, PDFont?>>()

    private val systemCandidates = listOf(
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSansFallback.ttf",
        "/system/fonts/DroidSansFallbackFull.ttf",
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/NotoSerifCJK-Regular.ttc",
        "/system/fonts/NotoSansArabic-Regular.ttf",
        "/system/fonts/NotoSansDevanagari-Regular.ttf",
        "/system/fonts/NotoSansThai-Regular.ttf",
        "/system/fonts/NotoSansHebrew-Regular.ttf",
    )

    @Synchronized
    override fun loadUnicodeFont(doc: PDDocument, text: String): PDFont? {
        val fonts = cache.getOrPut(doc) { LinkedHashMap() }
        val candidates = listOf(LIBERATION) + systemCandidates.filter { File(it).exists() }
        var best: PDFont? = null
        var bestScore = -1
        for (key in candidates) {
            val font = fonts.getOrPut(key) { load(doc, key) } ?: continue
            if (FontSelector.canEncode(font, text)) return font
            val score = FontSelector.sanitize(font, text).count { it != '?' }
            if (score > bestScore) { best = font; bestScore = score }
        }
        return best
    }

    private fun load(doc: PDDocument, key: String): PDFont? = try {
        if (key == LIBERATION) {
            PDFBoxResourceLoader.getStream(LIBERATION).use { PDType0Font.load(doc, it, true) }
        } else if (key.endsWith(".ttc")) {
            var first: TrueTypeFont? = null
            TrueTypeCollection(File(key)).processAllFonts { ttf -> if (first == null) first = ttf }
            first?.let { PDType0Font.load(doc, it, true) }
        } else {
            PDType0Font.load(doc, File(key))
        }
    } catch (t: Throwable) {
        CoreLog.w("Font $key unavailable", t)
        null
    }
}

/** Fallback page rasterizer used when a page must be flattened during redaction. */
object PdfBoxRasterizer : PageRasterizer {
    override fun rasterize(doc: PDDocument, pageIndex: Int, dpi: Float): RasterImage {
        val renderer = PDFRenderer(doc)
        val bmp = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB)
        val out = if (bmp.isMutable && bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        out.setHasAlpha(false)
        return BitmapRaster(out)
    }
}
