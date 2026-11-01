import com.tandidong.pdf.core.Attachments
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.FontSelector
import com.tandidong.pdf.core.LinkEditor
import com.tandidong.pdf.core.PageImages
import com.tandidong.pdf.core.Stamper
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import support.TestPdfs
import support.noUnicodeFont
import support.toBytes
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditExtrasTest {

    private fun solid(w: Int, h: Int, c: Color): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = c
        g.fillRect(0, 0, w, h)
        g.dispose()
        return img
    }

    /** One page with two images: red (JPEG) at (100,100)-(300,250) and blue (lossless) at (350,500)-(450,600), display space. */
    private fun imageDoc(rotation: Int = 0): PDDocument {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        page.rotation = rotation
        doc.addPage(page)
        val red = JPEGFactory.createFromImage(doc, solid(40, 30, Color.RED))
        val blue = LosslessFactory.createFromImage(doc, solid(20, 20, Color.BLUE))
        val g = com.tandidong.pdf.core.PageGeometry.of(page)
        PDPageContentStream(doc, page).use { cs ->
            for ((img, r) in listOf(red to FRect(100f, 100f, 300f, 250f), blue to FRect(350f, 500f, 450f, 600f))) {
                val u = g.rectToUser(r)
                cs.saveGraphicsState()
                // Draw with a rotation-aware matrix so the picture looks upright on rotated pages.
                cs.transform(g.displayUpMatrix())
                val up = FRect(r.x0, g.displayHeight - r.y1, r.x1, g.displayHeight - r.y0)
                cs.drawImage(img, up.x0, up.y0, up.width, up.height)
                cs.restoreGraphicsState()
                check(u.width > 0)
            }
        }
        return doc
    }

    private fun pixel(doc: PDDocument, x: Int, y: Int): Int {
        val img = PDFRenderer(doc).renderImage(0, 1f, ImageType.RGB)
        return img.getRGB(x, y) and 0xFFFFFF
    }

    @Test
    fun listsAndEditsImages() {
        for (rot in listOf(0, 90)) {
            imageDoc(rot).use { doc ->
                val imgs = PageImages.list(doc, 0)
                assertEquals(2, imgs.size, "rot=$rot")
                val red = imgs[0]
                assertEquals(100f, red.displayRect.x0, 0.5f, "rot=$rot")
                assertEquals(100f, red.displayRect.y0, 0.5f, "rot=$rot")
                assertEquals(300f, red.displayRect.x1, 0.5f, "rot=$rot")
                assertEquals(40, red.pixelWidth)
                assertTrue(red.isJpeg)
                assertNotNull(PageImages.rawJpeg(doc, red))
                assertEquals(imgs[1], PageImages.hitTest(imgs, 0, 400f, 550f))

                // Move + resize the red image.
                assertTrue(PageImages.transform(doc, red, FRect(50f, 400f, 150f, 450f)))
                PDDocument.load(doc.toBytes()).use { d ->
                    val after = PageImages.list(d, 0)
                    val r = after[0].displayRect
                    assertEquals(50f, r.x0, 0.5f, "rot=$rot")
                    assertEquals(400f, r.y0, 0.5f, "rot=$rot")
                    assertEquals(150f, r.x1, 0.5f, "rot=$rot")
                    assertEquals(450f, r.y1, 0.5f, "rot=$rot")
                    // Rendered where we put it, and gone from the old place.
                    val (px, py) = displayToPixel(d, 100f, 425f)
                    assertEquals(0xFF0000, nearest(pixel(d, px, py)), "rot=$rot moved")
                    val (ox, oy) = displayToPixel(d, 200f, 175f)
                    assertEquals(0xFFFFFF, pixel(d, ox, oy), "rot=$rot old spot")

                    // Delete the blue image.
                    assertTrue(PageImages.delete(d, after[1]))
                    assertEquals(1, PageImages.list(d, 0).size)

                    // Replace the red one with a green image, same placement.
                    val green = LosslessFactory.createFromImage(d, solid(10, 10, Color.GREEN))
                    assertTrue(PageImages.replace(d, PageImages.list(d, 0)[0], green))
                    PDDocument.load(d.toBytes()).use { d2 ->
                        val (gx, gy) = displayToPixel(d2, 100f, 425f)
                        assertEquals(0x00FF00, nearest(pixel(d2, gx, gy)), "rot=$rot replaced")
                        val left = PageImages.list(d2, 0)
                        assertEquals(1, left.size)
                        assertEquals(50f, left[0].displayRect.x0, 0.5f)
                        assertNull(PageImages.rawJpeg(d2, left[0]))
                    }
                }
            }
        }
    }

    private fun nearest(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        fun q(v: Int) = if (v > 128) 0xFF else 0
        return (q(r) shl 16) or (q(g) shl 8) or q(b)
    }

    /** Display point -> rendered pixel at 72 dpi (renderer output is display oriented). */
    private fun displayToPixel(doc: PDDocument, x: Float, y: Float): Pair<Int, Int> = x.toInt() to y.toInt()

    @Test
    fun attachmentsRoundTrip() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Invoice"))
            val data = "id,amount\n1,42\n".toByteArray()
            val k1 = Attachments.add(doc, "data.csv", data, "text/csv", "Line items")
            val k2 = Attachments.add(doc, "data.csv", byteArrayOf(1, 2, 3), null)
            assertEquals("data.csv", k1)
            assertEquals("data (2).csv", k2)
            PDDocument.load(doc.toBytes()).use { d ->
                val list = Attachments.list(d)
                assertEquals(listOf("data (2).csv", "data.csv"), list.map { it.key })
                val csv = list.first { it.key == "data.csv" }
                assertEquals("Line items", csv.description)
                assertEquals(data.size.toLong(), csv.size)
                assertEquals("text/csv", csv.mimeType)
                assertContentEquals(data, Attachments.read(d, "data.csv"))
                assertTrue(Attachments.delete(d, "data (2).csv"))
                assertTrue(Attachments.delete(d, "data.csv"))
                PDDocument.load(d.toBytes()).use { d2 -> assertTrue(Attachments.list(d2).isEmpty()) }
            }
        }
    }

    @Test
    fun addsAndRemovesLinks() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Contents"))
            TestPdfs.addTextPage(doc, listOf("Chapter 2"), rotation = 90)
            LinkEditor.addUri(doc, 0, FRect(72f, 100f, 200f, 120f), "example.com/docs")
            LinkEditor.addPageLink(doc, 1, FRect(72f, 100f, 200f, 120f), 0)
            PDDocument.load(doc.toBytes()).use { d ->
                val l0 = d.getPage(0).annotations.single() as PDAnnotationLink
                assertEquals("https://example.com/docs", (l0.action as PDActionURI).uri)
                val l1 = d.getPage(1).annotations.single() as PDAnnotationLink
                assertTrue(l1.action is PDActionGoTo)
                assertEquals(1, LinkEditor.removeAt(d, 1, 100f, 110f))
                assertEquals(0, LinkEditor.removeAt(d, 0, 10f, 10f))
                assertEquals(0, d.getPage(1).annotations.size)
            }
        }
        assertEquals("mailto:a@b.co", LinkEditor.normalizeUri("a@b.co"))
        assertEquals("tel:123", LinkEditor.normalizeUri("tel:123"))
    }

    @Test
    fun backgroundGoesBehindContent() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Hello background"), fontSize = 30f)
            Stamper(FontSelector(noUnicodeFont)).background(doc, listOf(0), 0xFFFFF59D.toInt())
            PDDocument.load(doc.toBytes()).use { d ->
                val img = PDFRenderer(d).renderImage(0, 1f, ImageType.RGB)
                // Corner is tinted, and text still renders dark on top.
                val c = img.getRGB(5, 5)
                assertTrue(kotlin.math.abs(((c shr 16) and 0xFF) - 0xFF) <= 2 && kotlin.math.abs(((c shr 8) and 0xFF) - 0xF5) <= 2 &&
                    kotlin.math.abs((c and 0xFF) - 0x9D) <= 2, "background color ${Integer.toHexString(c)}")
                var dark = 0
                for (y in 0 until img.height) for (x in 0 until img.width) if ((img.getRGB(x, y) and 0xFF) < 60) dark++
                assertTrue(dark > 50, "text visible over background, dark=$dark")
            }
        }
    }
}
