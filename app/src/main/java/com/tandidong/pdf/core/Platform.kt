package com.tandidong.pdf.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

/**
 * Platform-specific services the core engine needs. The core only talks to PDFBox and these
 * interfaces, so it compiles both on Android (PdfBox-Android) and on the desktop JVM (tests).
 */

/** Opaque decoded raster image (an Android Bitmap or an AWT BufferedImage). */
interface RasterImage {
    val width: Int
    val height: Int
}

interface ImageCodec {
    /** Decodes a PDF image to RGB(A) pixels, or returns null when the format is unsupported. */
    fun decode(image: PDImage): RasterImage?

    /** Fills the pixel rectangle [x0,x1) x [y0,y1) with an opaque color. May mutate [img]. */
    fun fillRect(img: RasterImage, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int): RasterImage

    /** Returns a resampled copy of [img]. */
    fun scaled(img: RasterImage, width: Int, height: Int): RasterImage

    fun hasAlpha(img: RasterImage): Boolean

    /** Encodes as a JPEG image XObject (alpha, if present, becomes an SMask). */
    fun encodeJpeg(doc: PDDocument, img: RasterImage, quality: Float): PDImageXObject

    /** Encodes losslessly (Flate). */
    fun encodeLossless(doc: PDDocument, img: RasterImage): PDImageXObject

    fun release(img: RasterImage)
}

/** Renders a page of an in-memory document (including annotation appearances). */
fun interface PageRasterizer {
    fun rasterize(doc: PDDocument, pageIndex: Int, dpi: Float): RasterImage
}

/**
 * Supplies an embeddable Unicode font for text that the standard 14 fonts cannot encode.
 * Implementations should cache fonts per document and prefer one that can encode [text].
 */
fun interface FontProvider {
    fun loadUnicodeFont(doc: PDDocument, text: String): PDFont?
}

/** Minimal logging hook so core code can report problems without android.util.Log. */
object CoreLog {
    @Volatile
    var sink: (level: Char, message: String, error: Throwable?) -> Unit = { _, _, _ -> }

    fun w(message: String, error: Throwable? = null) = sink('W', message, error)
    fun e(message: String, error: Throwable? = null) = sink('E', message, error)
    fun d(message: String) = sink('D', message, null)
}

/** Thrown when a requested operation is not permitted by the document's security settings. */
class PdfPermissionException(message: String) : Exception(message)

/** Generic progress callback: (completedUnits, totalUnits). */
typealias Progress = (Int, Int) -> Unit
