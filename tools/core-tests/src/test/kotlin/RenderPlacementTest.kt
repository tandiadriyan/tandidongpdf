import com.tandidong.pdf.core.AnnotationSpec
import com.tandidong.pdf.core.AnnotationWriter
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.FontSelector
import com.tandidong.pdf.core.Pt
import com.tandidong.pdf.core.ShapeKind
import com.tandidong.pdf.core.Stamper
import com.tandidong.pdf.core.TextWatermark
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import support.noUnicodeFont
import support.toBytes
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders documents with the reference PDFBox renderer and checks that annotations and stamps
 * appear where the user placed them in display coordinates, on pages with every rotation.
 */
class RenderPlacementTest {
    private val fonts = FontSelector(noUnicodeFont)

    private fun isRed(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r > 150 && g < 110 && b < 110
    }

    private fun countRed(img: BufferedImage, r: FRect): Int {
        var n = 0
        for (y in r.y0.toInt() until r.y1.toInt()) for (x in r.x0.toInt() until r.x1.toInt()) {
            if (x in 0 until img.width && y in 0 until img.height && isRed(img.getRGB(x, y))) n++
        }
        return n
    }

    @Test
    fun annotationsAppearWhereTheyWerePlaced() {
        for (rot in listOf(0, 90, 180, 270)) {
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.LETTER)
                page.rotation = rot
                doc.addPage(page)
                val red = 0xFFE53935.toInt()
                AnnotationWriter("T", fonts).add(doc, listOf(
                    AnnotationSpec.Shape(0, ShapeKind.RECTANGLE, Pt(100f, 100f), Pt(200f, 160f), red, red, 2f),
                    AnnotationSpec.Stamp(0, FRect(300f, 300f, 450f, 350f), "OK", red),
                ))
                PDDocument.load(doc.toBytes()).use { d ->
                    val img = PDFRenderer(d).renderImage(0, 1f, ImageType.RGB)
                    // Display size swaps for 90/270.
                    val expectW = if (rot % 180 == 0) 612 else 792
                    assertEquals(expectW, img.width, "rot=$rot")
                    // The filled rectangle covers its display rect...
                    val inside = countRed(img, FRect(110f, 110f, 190f, 150f))
                    assertTrue(inside > 80 * 40 * 0.9, "rot=$rot filled shape inside=$inside")
                    // ...and nothing red leaks to the mirrored location.
                    val mirror = countRed(img, FRect(img.width - 190f, img.height - 150f, img.width - 110f, img.height - 110f))
                    assertTrue(mirror < 50, "rot=$rot mirror=$mirror")
                    // Stamp border pixels lie on the stamp's display rect edges.
                    val left = countRed(img, FRect(300f, 310f, 306f, 340f))
                    assertTrue(left > 20, "rot=$rot stamp left border=$left")
                }
            }
        }
    }

    @Test
    fun watermarkIsCenteredOnRotatedPages() {
        for (rot in listOf(0, 90)) {
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.LETTER)
                page.rotation = rot
                doc.addPage(page)
                Stamper(fonts).textWatermark(doc, listOf(0), TextWatermark("XXXX", fontSize = 80f, argb = 0xFFE53935.toInt(), opacity = 1f, rotation = 0f))
                PDDocument.load(doc.toBytes()).use { d ->
                    val img = PDFRenderer(d).renderImage(0, 1f, ImageType.RGB)
                    val cx = img.width / 2f
                    val cy = img.height / 2f
                    val center = countRed(img, FRect(cx - 60f, cy - 25f, cx + 60f, cy + 25f))
                    assertTrue(center > 500, "rot=$rot center red=$center")
                    // Text reads horizontally: wide, not tall.
                    val above = countRed(img, FRect(cx - 20f, cy - 120f, cx + 20f, cy - 60f))
                    assertTrue(above < 20, "rot=$rot text should be horizontal, above=$above")
                }
            }
        }
    }
}
