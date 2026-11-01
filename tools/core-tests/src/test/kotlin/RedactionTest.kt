import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.PageGeometry
import com.tandidong.pdf.core.RedactionArea
import com.tandidong.pdf.core.RedactionOptions
import com.tandidong.pdf.core.Redactor
import com.tandidong.pdf.core.TextExtractor
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import support.AwtImageCodec
import support.AwtRasterizer
import support.TestPdfs
import support.allDecodedContent
import support.toBytes
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    private val redactor = Redactor(AwtImageCodec(), AwtRasterizer())

    /** Finds [word] on [pageIndex] and returns user-space redaction areas covering it. */
    private fun areasFor(doc: PDDocument, pageIndex: Int, word: String): List<RedactionArea> {
        val t = TextExtractor.extract(doc, pageIndex)
        val g = PageGeometry.of(doc.getPage(pageIndex))
        val matches = t.find(word)
        assertTrue(matches.isNotEmpty(), "'$word' not found in: ${t.text}")
        return matches.flatMap { m -> m.rects.map { RedactionArea(pageIndex, g.rectToUser(it)) } }
    }

    private fun reload(doc: PDDocument): Pair<PDDocument, ByteArray> {
        val bytes = doc.toBytes()
        return PDDocument.load(bytes) to bytes
    }

    @Test
    fun removesWordButKeepsNeighborsInPlace() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Hello Secret World", "Another Secret line"))
            val before = TextExtractor.extract(doc, 0)
            val worldBefore = before.find("World").single().rects.single()
            val report = redactor.redact(doc, areasFor(doc, 0, "Secret"))
            assertEquals(12, report.glyphsRemoved, report.toString())
            assertTrue(report.pagesRasterized.isEmpty())
            val (out, bytes) = reload(doc)
            out.use {
                val after = TextExtractor.extract(it, 0)
                assertEquals("Hello World\nAnother line", after.text.replace(Regex(" +"), " "))
                val worldAfter = after.find("World").single().rects.single()
                assertEquals(worldBefore.x0, worldAfter.x0, 0.05f)
                assertEquals(worldBefore.y0, worldAfter.y0, 0.05f)
            }
            assertFalse(allDecodedContent(bytes).contains("Secret"), "secret text must be gone from the file")
        }
    }

    @Test
    fun handlesTjArraysWithSpacingOperators() {
        PDDocument().use { doc ->
            TestPdfs.addRawPage(
                doc,
                "BT /F1 12 Tf 16 TL 1.5 Tc 4 Tw 72 700 Td [(Top) -250 (Secret) 120 (Data here)] TJ " +
                    "0 -20 Td (Keep Secret) ' 2 0.5 (More Secret stuff) \" ET",
            )
            val before = TextExtractor.extract(doc, 0)
            val dataBefore = before.find("Data").single().rects.single()
            val stuffBefore = before.find("stuff").single().rects.single()
            redactor.redact(doc, areasFor(doc, 0, "Secret"))
            val (out, bytes) = reload(doc)
            out.use {
                val after = TextExtractor.extract(it, 0)
                assertFalse(after.text.contains("Secret"), after.text)
                assertTrue(after.text.contains("Top") && after.text.contains("Keep") && after.text.contains("More"))
                assertEquals(dataBefore.x0, after.find("Data").single().rects.single().x0, 0.05f)
                assertEquals(stuffBefore.x0, after.find("stuff").single().rects.single().x0, 0.05f)
            }
            assertFalse(allDecodedContent(bytes).contains("Secret"))
        }
    }

    @Test
    fun redactsOnRotatedPages() {
        for (rot in listOf(90, 180, 270)) {
            PDDocument().use { doc ->
                TestPdfs.addTextPage(doc, listOf("Alpha Secret Omega"), rotation = rot)
                redactor.redact(doc, areasFor(doc, 0, "Secret"))
                val (out, bytes) = reload(doc)
                out.use {
                    val text = TextExtractor.extract(it, 0).text
                    assertTrue(text.contains("Alpha") && text.contains("Omega"), "rot=$rot text=$text")
                    assertFalse(text.contains("Secret"), "rot=$rot text=$text")
                }
                assertFalse(allDecodedContent(bytes).contains("Secret"))
            }
        }
    }

    @Test
    fun blanksImagePixelsUnderTheBox() {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            val bi = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 100) for (x in 0 until 100) bi.setRGB(x, y, 0xCC2222)
            val img = LosslessFactory.createFromImage(doc, bi)
            PDPageContentStream(doc, page).use { it.drawImage(img, 100f, 100f, 100f, 100f) }
            // Redact the bottom-left quarter of the image (user space).
            val report = redactor.redact(doc, listOf(RedactionArea(0, FRect(100f, 100f, 150f, 150f))))
            assertEquals(1, report.imagesRedacted, report.toString())
            val (out, _) = reload(doc)
            out.use {
                val res = it.getPage(0).resources
                val names = res.xObjectNames.toList()
                assertEquals(1, names.size, "old image must not remain referenced")
                val newImg = res.getXObject(names[0]) as PDImageXObject
                val pix = newImg.image
                // Image row 0 is the top; bottom-left quarter is x<50, y>=50.
                assertEquals(0x000000, pix.getRGB(10, 90) and 0xFFFFFF)
                assertEquals(0xCC2222, pix.getRGB(90, 10) and 0xFFFFFF)
                assertEquals(0xCC2222, pix.getRGB(90, 90) and 0xFFFFFF)
                assertEquals(0xCC2222, pix.getRGB(10, 10) and 0xFFFFFF)
            }
        }
    }

    @Test
    fun formXObjectsAreRewrittenPerPage() {
        PDDocument().use { doc ->
            val form = PDFormXObject(doc)
            form.bBox = PDRectangle(0f, 0f, 300f, 50f)
            form.resources = PDResources().also { it.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
            PDPageContentStream(doc, form, form.stream.createOutputStream()).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(5f, 20f)
                cs.showText("Shared Secret Form"); cs.endText()
            }
            repeat(2) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.saveGraphicsState(); cs.transform(org.apache.pdfbox.util.Matrix.getTranslateInstance(72f, 600f))
                    cs.drawForm(form); cs.restoreGraphicsState()
                }
            }
            redactor.redact(doc, areasFor(doc, 0, "Secret"))
            val (out, _) = reload(doc)
            out.use {
                assertFalse(TextExtractor.extract(it, 0).text.contains("Secret"))
                assertTrue(TextExtractor.extract(it, 1).text.contains("Secret"), "other pages keep the original form")
                assertTrue(TextExtractor.extract(it, 0).text.contains("Shared"))
            }
        }
    }

    @Test
    fun removesSmallPathsAndCutsBackgroundRectangles() {
        PDDocument().use { doc ->
            TestPdfs.addRawPage(
                doc,
                "0.9 g 0 0 612 792 re f " + // page background
                    "0 0 1 rg 300 300 m 320 300 l 320 320 l 300 320 l h f " + // small shape inside area
                    "0 0 0 RG 1 w 50 50 m 550 50 l S " + // long line elsewhere
                    "0 0 0 RG 1 w 280 280 100 100 re S", // stroked box around the area, edges outside it
            )
            val report = redactor.redact(doc, listOf(RedactionArea(0, FRect(290f, 290f, 330f, 330f))))
            assertEquals(2, report.pathsRemoved, report.toString()) // background cut + small shape removed
            val (out, _) = reload(doc)
            out.use {
                val content = String(it.getPage(0).contents.readBytes(), Charsets.ISO_8859_1)
                assertFalse(content.contains("300 320 l"), content)
                assertTrue(content.contains("550 50 l"), "unrelated line kept")
                assertTrue(content.contains("280 280 100 100 re"), "stroked box kept: $content")
                assertEquals(6, Regex("\\bre\\b").findAll(content).count(), content) // 4 background pieces + kept box + redaction box
            }
        }
    }

    @Test
    fun removesOverlappingAnnotationsOnly() {
        PDDocument().use { doc ->
            val page = TestPdfs.addTextPage(doc, listOf("Note target"))
            val inside = PDAnnotationText().apply { rectangle = PDRectangle(80f, 690f, 20f, 20f); contents = "secret note" }
            val outside = PDAnnotationText().apply { rectangle = PDRectangle(400f, 100f, 20f, 20f); contents = "keep" }
            page.annotations = listOf(inside, outside)
            val report = redactor.redact(doc, listOf(RedactionArea(0, FRect(70f, 680f, 200f, 720f))))
            assertEquals(1, report.annotationsRemoved)
            val (out, bytes) = reload(doc)
            out.use { assertEquals(listOf("keep"), it.getPage(0).annotations.map { a -> a.contents }) }
            assertFalse(allDecodedContent(bytes).contains("secret note"))
        }
    }

    @Test
    fun blanksInlineImagesAndStripsActualText() {
        PDDocument().use { doc ->
            // 4x4 RGB inline image of 0xFF bytes, drawn at (100,100) size 40x40.
            val hex = "FF".repeat(48)
            TestPdfs.addRawPage(
                doc,
                "q 40 0 0 40 100 100 cm BI /W 4 /H 4 /CS /RGB /BPC 8 /F /AHx ID $hex> EI Q " +
                    "/Span <</ActualText (Secret Name)>> BDC BT /F1 12 Tf 300 300 Td (Hidden) Tj ET EMC",
            )
            val areas = listOf(
                RedactionArea(0, FRect(100f, 100f, 120f, 120f)),
                RedactionArea(0, FRect(295f, 295f, 360f, 315f)),
            )
            val report = redactor.redact(doc, areas)
            assertEquals(1, report.imagesRedacted, report.toString())
            val (_, bytes) = reload(doc)
            val all = allDecodedContent(bytes)
            assertFalse(all.contains("Secret Name"), "ActualText must be stripped")
            assertFalse(all.contains("Hidden"))
            assertTrue(all.contains("BI"), "inline image kept outside the area")
        }
    }

    @Test
    fun overlayTextAndCustomColor() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Account 123456789"))
            redactor.redact(
                doc, areasFor(doc, 0, "123456789"),
                RedactionOptions(fillArgb = 0xFF202020.toInt(), overlayText = "REDACTED"),
            )
            val (out, _) = reload(doc)
            out.use { assertTrue(TextExtractor.extract(it, 0).text.contains("REDACTED")) }
        }
    }

    @Test
    fun jpegImagesUseTheCodecPath() {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            val bi = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 100) for (x in 0 until 200) bi.setRGB(x, y, 0xFFFFFF)
            val img = org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, bi, 0.95f)
            PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, 200f, 100f) }
            val report = redactor.redact(doc, listOf(RedactionArea(0, FRect(0f, 0f, 100f, 100f))))
            assertEquals(1, report.imagesRedacted, report.toString())
            assertTrue(report.pagesRasterized.isEmpty())
            val (out, _) = reload(doc)
            out.use {
                val res = it.getPage(0).resources
                val x = res.getXObject(res.xObjectNames.single()) as PDImageXObject
                assertEquals("jpg", x.suffix)
                val pix = x.image
                assertTrue((pix.getRGB(20, 50) and 0xFFFFFF) < 0x202020, "left half blacked out")
                assertTrue((pix.getRGB(180, 50) and 0xFFFFFF) > 0xE0E0E0, "right half untouched")
            }
        }
    }

    @Test
    fun unsupportedImagesFallBackToRasterizingThePage() {
        PDDocument().use { doc ->
            val page = TestPdfs.addTextPage(doc, listOf("Visible text"))
            val bi = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
            val img = LosslessFactory.createFromImage(doc, bi)
            // Pretend the image uses a codec we cannot edit.
            img.cosObject.setItem(COSName.FILTER, COSName.getPDFName("JBIG2Decode"))
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { it.drawImage(img, 300f, 300f, 50f, 50f) }
            val report = redactor.redact(doc, listOf(RedactionArea(0, FRect(300f, 300f, 320f, 320f))))
            assertEquals(listOf(0), report.pagesRasterized)
            val (out, bytes) = reload(doc)
            out.use {
                val res = it.getPage(0).resources
                assertEquals(1, res.xObjectNames.count(), "page is now a single image")
                assertFalse(TextExtractor.extract(it, 0).text.contains("Visible"))
            }
            assertFalse(allDecodedContent(bytes).contains("Visible text"))
        }
    }
}
