package support

import com.tandidong.pdf.core.FontProvider
import com.tandidong.pdf.core.ImageCodec
import com.tandidong.pdf.core.PageRasterizer
import com.tandidong.pdf.core.RasterImage
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/** Desktop (AWT) implementations of the platform interfaces, mirroring the Android ones. */
class AwtRaster(var image: BufferedImage) : RasterImage {
    override val width: Int get() = image.width
    override val height: Int get() = image.height
}

class AwtImageCodec : ImageCodec {
    override fun decode(image: PDImage): RasterImage? = try {
        val src = image.image
        val argb = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        argb.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
        AwtRaster(argb)
    } catch (e: Exception) {
        null
    }

    override fun fillRect(img: RasterImage, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int): RasterImage {
        val r = img as AwtRaster
        val g = r.image.createGraphics()
        g.color = Color(argb, true)
        g.fillRect(x0, y0, x1 - x0, y1 - y0)
        g.dispose()
        return r
    }

    override fun scaled(img: RasterImage, width: Int, height: Int): RasterImage {
        val r = img as AwtRaster
        val out = BufferedImage(width, height, r.image.type.takeIf { it != 0 } ?: BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(r.image, 0, 0, width, height, null)
        g.dispose()
        return AwtRaster(out)
    }

    override fun hasAlpha(img: RasterImage): Boolean = (img as AwtRaster).image.colorModel.hasAlpha()

    override fun encodeJpeg(doc: PDDocument, img: RasterImage, quality: Float): PDImageXObject =
        JPEGFactory.createFromImage(doc, (img as AwtRaster).image, quality)

    override fun encodeLossless(doc: PDDocument, img: RasterImage): PDImageXObject =
        LosslessFactory.createFromImage(doc, (img as AwtRaster).image)

    override fun release(img: RasterImage) {}
}

class AwtRasterizer : PageRasterizer {
    override fun rasterize(doc: PDDocument, pageIndex: Int, dpi: Float): RasterImage {
        val renderer = PDFRenderer(doc)
        renderer.setAnnotationsFilter { false }
        return AwtRaster(renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB))
    }
}

val noUnicodeFont = FontProvider { _, _ -> null }

fun PDDocument.toBytes(): ByteArray = ByteArrayOutputStream().also { save(it) }.toByteArray()

/** Concatenation of every decoded stream and every string object in a saved PDF. */
fun allDecodedContent(bytes: ByteArray): String {
    val sb = StringBuilder()
    sb.append(String(bytes, Charsets.ISO_8859_1))
    PDDocument.load(bytes).use { d ->
        for (o in d.document.objects) {
            val b = o.getObject()
            if (b is COSStream) {
                try {
                    b.createInputStream().use { sb.append(String(it.readBytes(), Charsets.ISO_8859_1)) }
                } catch (e: Exception) {
                    // undecodable stream; raw bytes are already included above
                }
            } else {
                sb.append(b.toString())
            }
        }
    }
    return sb.toString()
}
