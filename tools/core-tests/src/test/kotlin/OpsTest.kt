import com.tandidong.pdf.core.AnnotationEditor
import com.tandidong.pdf.core.AnnotationSpec
import com.tandidong.pdf.core.AnnotationWriter
import com.tandidong.pdf.core.Compressor
import com.tandidong.pdf.core.CompressOptions
import com.tandidong.pdf.core.DocInfo
import com.tandidong.pdf.core.DocxWriter
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.Flattener
import com.tandidong.pdf.core.FontSelector
import com.tandidong.pdf.core.Forms
import com.tandidong.pdf.core.HeaderFooter
import com.tandidong.pdf.core.MarkupKind
import com.tandidong.pdf.core.OcrLayerWriter
import com.tandidong.pdf.core.OcrWord
import com.tandidong.pdf.core.Outline
import com.tandidong.pdf.core.PageGeometry
import com.tandidong.pdf.core.PageOps
import com.tandidong.pdf.core.PageSpec
import com.tandidong.pdf.core.Permissions
import com.tandidong.pdf.core.Pt
import com.tandidong.pdf.core.Redactor
import com.tandidong.pdf.core.SanitizeOptions
import com.tandidong.pdf.core.Sanitizer
import com.tandidong.pdf.core.Security
import com.tandidong.pdf.core.ShapeKind
import com.tandidong.pdf.core.StampPreset
import com.tandidong.pdf.core.Stamper
import com.tandidong.pdf.core.TextDiff
import com.tandidong.pdf.core.TextEditor
import com.tandidong.pdf.core.TextExtractor
import com.tandidong.pdf.core.TextWatermark
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import support.AwtImageCodec
import support.AwtRasterizer
import support.TestPdfs
import support.noUnicodeFont
import support.toBytes
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpsTest {
    private val fonts = FontSelector(noUnicodeFont)

    private fun numbered(n: Int): PDDocument {
        val doc = PDDocument()
        for (i in 1..n) TestPdfs.addTextPage(doc, listOf("Page $i"))
        return doc
    }

    private fun texts(doc: PDDocument) = (0 until doc.numberOfPages).map { TextExtractor.extract(doc, it).text }

    private fun roundTrip(doc: PDDocument): PDDocument = PDDocument.load(doc.toBytes())

    @Test
    fun organizePlan() {
        numbered(4).use { doc ->
            PageOps.applyPlan(doc, listOf(PageSpec.Existing(2, 90), PageSpec.Blank(200f, 300f), PageSpec.Existing(0), PageSpec.Existing(2)))
            roundTrip(doc).use { d ->
                assertEquals(listOf("Page 3", "", "Page 1", "Page 3"), texts(d))
                assertEquals(90, d.getPage(0).rotation)
                assertEquals(0, d.getPage(3).rotation, "duplicate keeps its own rotation")
                assertEquals(200f, d.getPage(1).mediaBox.width)
            }
        }
    }

    @Test
    fun deleteScrubsBookmarksAndLinks() {
        numbered(3).use { doc ->
            Outline.add(doc, "To page 2", 1)
            Outline.add(doc, "To page 3", 2)
            val link = PDAnnotationLink().apply {
                rectangle = PDRectangle(10f, 10f, 50f, 20f)
                action = PDActionGoTo().apply { destination = PDPageFitDestination().also { it.page = doc.getPage(1) } }
            }
            doc.getPage(0).annotations = listOf(link)
            PageOps.deletePages(doc, listOf(1))
            val bytes = doc.toBytes()
            PDDocument.load(bytes).use { d ->
                assertEquals(listOf("Page 1", "Page 3"), texts(d))
                val outline = Outline.read(d)
                assertEquals(listOf("To page 3"), outline.map { it.title })
                assertEquals(1, outline[0].pageIndex)
                assertTrue(d.getPage(0).annotations.isEmpty(), "link to deleted page removed")
            }
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Page 2"), "deleted page content must not be written")
        }
    }

    @Test
    fun parseRangesAndSplit() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(4), listOf(6, 7)), PageOps.parseRanges("1-3, 5, 7-", 8))
        assertNull(PageOps.parseRanges("0-2", 5))
        assertEquals(listOf(listOf(2, 1, 0)), PageOps.parseRanges("3-1", 5))
        val dir = createTempDir()
        try {
            val src = File(dir, "src.pdf")
            numbered(5).use { it.save(src) }
            val parts = PageOps.split(src, null, PageOps.everyN(5, 2)) { File(dir, "part$it.pdf") }
            assertEquals(3, parts.size)
            PDDocument.load(parts[2]).use { assertEquals(listOf("Page 5"), texts(it)) }
            PDDocument.load(parts[0]).use { assertEquals(listOf("Page 1", "Page 2"), texts(it)) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mergeAndInsert() {
        val dir = createTempDir()
        try {
            val other = File(dir, "other.pdf")
            PDDocument().use { d -> TestPdfs.addTextPage(d, listOf("Other A")); TestPdfs.addTextPage(d, listOf("Other B")); d.save(other) }
            numbered(2).use { doc ->
                val src = PageOps.insertFrom(doc, other, null, listOf(1), 1)
                val bytes = doc.toBytes()
                src.close()
                PDDocument.load(bytes).use { assertEquals(listOf("Page 1", "Other B", "Page 2"), texts(it)) }
            }
            numbered(1).use { doc ->
                PDDocument.load(other).use { o ->
                    PageOps.appendAll(doc, listOf(o))
                    roundTrip(doc).use { assertEquals(listOf("Page 1", "Other A", "Other B"), texts(it)) }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cropUsesDisplayMargins() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("x"), rotation = 90)
            PageOps.crop(doc, listOf(0), left = 10f, top = 20f, right = 30f, bottom = 40f)
            val g = PageGeometry.of(doc.getPage(0))
            assertEquals(792f - 40f, g.displayWidth, 0.01f)
            assertEquals(612f - 60f, g.displayHeight, 0.01f)
        }
    }

    @Test
    fun annotationsRoundTripOnRotatedPage() {
        for (rot in listOf(0, 90)) {
            PDDocument().use { doc ->
                TestPdfs.addTextPage(doc, listOf("Hello annotated world"), rotation = rot)
                val t = TextExtractor.extract(doc, 0)
                val rects = t.find("annotated").single().rects
                val w = AnnotationWriter("Tester", fonts)
                val added = w.add(doc, listOf(
                    AnnotationSpec.Markup(0, MarkupKind.HIGHLIGHT, rects, 0xFFFFEB3B.toInt()),
                    AnnotationSpec.Ink(0, listOf(listOf(Pt(100f, 100f), Pt(120f, 130f), Pt(140f, 110f))), 0xFF1E88E5.toInt(), 2f),
                    AnnotationSpec.Shape(0, ShapeKind.RECTANGLE, Pt(200f, 200f), Pt(260f, 240f), 0xFFE53935.toInt(), null, 2f),
                    AnnotationSpec.Shape(0, ShapeKind.ARROW, Pt(50f, 300f), Pt(150f, 350f), 0xFF000000.toInt(), null, 1.5f),
                    AnnotationSpec.Note(0, Pt(300f, 300f), "A comment"),
                    AnnotationSpec.FreeText(0, FRect(50f, 400f, 250f, 440f), "Typed text", 12f, 0xFF000000.toInt()),
                    AnnotationSpec.Stamp(0, FRect(300f, 400f, 450f, 450f), StampPreset.APPROVED.label, StampPreset.APPROVED.argb),
                    AnnotationSpec.Signature(0, FRect(300f, 480f, 420f, 520f), listOf(listOf(Pt(0f, 0.5f), Pt(0.5f, 0f), Pt(1f, 1f))), 0xFF0D47A1.toInt(), 1.5f),
                ))
                assertEquals(8, added)
                roundTrip(doc).use { d ->
                    val list = AnnotationEditor.list(d)
                    assertEquals(listOf("Highlight", "Ink", "Square", "Line", "Text", "FreeText", "Stamp", "Stamp"), list.map { it.subtype })
                    val hl = list[0]
                    assertEquals(rects[0].x0, hl.displayRect.x0, 2.5f, "rot=$rot")
                    val sq = list[2].displayRect
                    assertEquals(200f, sq.x0, 0.5f, "rot=$rot square $sq")
                    assertEquals(240f, sq.y1, 0.5f, "rot=$rot square $sq")
                    assertEquals("Tester", list[0].author)
                    assertEquals("A comment", list[4].contents)
                    assertTrue(d.getPage(0).annotations.all { it.normalAppearanceStream != null })
                    // Move the square and recolor the highlight.
                    assertTrue(AnnotationEditor.move(d, 0, list[2].index, 10f, 5f))
                    assertTrue(AnnotationEditor.setColor(d, 0, list[0].index, 0xFF00FF00.toInt()))
                    val moved = AnnotationEditor.list(d)[2].displayRect
                    assertEquals(210f, moved.x0, 0.5f)
                    assertEquals(245f, moved.y1, 0.5f)
                    assertTrue(AnnotationEditor.delete(d, 0, list[4].index))
                    assertEquals(7, AnnotationEditor.list(d).size)
                    // Flatten bakes everything into content.
                    val n = Flattener.flatten(d)
                    assertEquals(7, n)
                    assertTrue(d.getPage(0).annotations.isEmpty())
                    assertTrue(TextExtractor.extract(d, 0).text.contains("Typed text"))
                }
            }
        }
    }

    @Test
    fun formsListFillAndFlatten() {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            val acro = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acro
            acro.defaultResources = PDResources().also { it.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            acro.defaultAppearance = "/Helv 0 Tf 0 g"
            val tf = PDTextField(acro)
            tf.partialName = "fullName"
            tf.defaultAppearance = "/Helv 12 Tf 0 0 0 rg"
            acro.fields.add(tf)
            val wdg = tf.widgets[0]
            wdg.rectangle = PDRectangle(72f, 600f, 200f, 20f)
            wdg.page = page
            page.annotations = listOf(wdg)

            val fields = Forms.list(doc)
            assertEquals(1, fields.size)
            assertEquals("fullName", fields[0].name)
            assertEquals(0, fields[0].widgets.single().pageIndex)
            assertEquals(792f - 620f, fields[0].widgets.single().displayRect.y0, 0.01f)
            assertTrue(Forms.fill(doc, mapOf("fullName" to "Ada Lovelace")).isEmpty())
            roundTrip(doc).use { d ->
                assertEquals("Ada Lovelace", Forms.list(d).single().value)
                Forms.flatten(d)
                assertFalse(Forms.hasForm(d))
                assertTrue(TextExtractor.extract(d, 0).text.contains("Ada Lovelace"))
            }
        }
    }

    @Test
    fun passwordProtection() {
        numbered(1).use { doc ->
            Security.protect(doc, "open123", "owner456", Permissions(copy = false))
            val bytes = doc.toBytes()
            assertFailsWith<InvalidPasswordException> { PDDocument.load(bytes).close() }
            PDDocument.load(bytes, "open123").use { d ->
                val info = Security.info(d)
                assertTrue(info.encrypted)
                assertEquals(256, info.keyLength)
                assertFalse(info.isOwner)
                assertFalse(info.permissions.copy)
                assertEquals(listOf("Page 1"), texts(d))
            }
            PDDocument.load(bytes, "owner456").use { d ->
                assertTrue(Security.info(d).isOwner)
                Security.removeSecurity(d)
                PDDocument.load(d.toBytes()).use { plain -> assertFalse(plain.isEncrypted) }
            }
        }
    }

    @Test
    fun watermarkHeaderFooterAndBates() {
        numbered(2).use { doc ->
            val s = Stamper(fonts)
            s.textWatermark(doc, listOf(0, 1), TextWatermark("CONFIDENTIAL"))
            s.headerFooter(doc, listOf(0, 1), HeaderFooter(bottomCenter = "Page {page} of {pages}", bottomRight = "{bates}", batesPrefix = "ACME-", batesDigits = 4))
            roundTrip(doc).use { d ->
                val t1 = TextExtractor.extract(d, 1).text
                assertTrue(t1.contains("CONFIDENTIAL"), t1)
                assertTrue(t1.contains("Page 2 of 2"), t1)
                assertTrue(t1.contains("ACME-0002"), t1)
            }
        }
    }

    @Test
    fun compressShrinksLargeImages() {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            val bi = BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB)
            val rnd = java.util.Random(1)
            for (y in 0 until 1800 step 2) for (x in 0 until 2400 step 2) bi.setRGB(x, y, rnd.nextInt(0xFFFFFF))
            val img = LosslessFactory.createFromImage(doc, bi)
            PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, 612f, 459f) }
            val before = doc.toBytes().size
            val report = Compressor(AwtImageCodec()).compress(doc, CompressOptions.MEDIUM)
            assertEquals(1, report.imagesRecompressed)
            val after = doc.toBytes()
            assertTrue(after.size < before / 2, "before=$before after=${after.size}")
            PDDocument.load(after).use { d ->
                val res = d.getPage(0).resources
                val x = res.getXObject(res.xObjectNames.first()) as org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
                assertEquals(1600, x.width)
            }
        }
    }

    @Test
    fun ocrLayerIsSearchable() {
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.LETTER))
            OcrLayerWriter(fonts).addTextLayer(doc, 0, listOf(OcrWord("Scanned", FRect(100f, 100f, 180f, 120f)), OcrWord("receipt", FRect(186f, 100f, 250f, 120f))))
            roundTrip(doc).use { d ->
                val t = TextExtractor.extract(d, 0)
                assertEquals("Scanned receipt", t.text)
                val r = t.find("receipt").single().rects.single()
                assertEquals(186f, r.x0, 1.5f)
                assertEquals(250f, r.x1, 1.5f)
            }
        }
    }

    @Test
    fun replaceTextEditsContent() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Total due: 100 USD"))
            val t = TextExtractor.extract(doc, 0)
            val r = t.find("100").single().rects.single()
            TextEditor(Redactor(AwtImageCodec(), AwtRasterizer(), fonts), fonts).replace(doc, 0, r, "250", 12f, 0xFF000000.toInt())
            roundTrip(doc).use { d ->
                val after = TextExtractor.extract(d, 0).text
                assertTrue(after.contains("250"), after)
                assertFalse(after.contains("100"), after)
            }
        }
    }

    @Test
    fun docxExportAndDiff() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Title & <Intro>", "Body text line"))
            val out = ByteArrayOutputStream()
            DocxWriter.write(TextExtractor.extractAll(doc), out, "T")
            val entries = HashMap<String, String>()
            ZipInputStream(out.toByteArray().inputStream()).use { z ->
                while (true) { val e = z.nextEntry ?: break; entries[e.name] = String(z.readBytes()) }
            }
            assertTrue("[Content_Types].xml" in entries && "word/document.xml" in entries)
            assertTrue(entries["word/document.xml"]!!.contains("Title &amp; &lt;Intro&gt;"))
        }
        val s = TextDiff.compare("the quick brown fox", "the quick red fox jumps")
        assertEquals(2, s.insertedWords)
        assertEquals(1, s.deletedWords)
        assertEquals(listOf(TextDiff.Op.EQUAL, TextDiff.Op.DELETE, TextDiff.Op.INSERT, TextDiff.Op.EQUAL, TextDiff.Op.INSERT), s.chunks.map { it.op })
    }

    @Test
    fun sanitizeAndProperties() {
        numbered(1).use { doc ->
            doc.documentInformation = PDDocumentInformation().apply { author = "Secret Author"; title = "T" }
            doc.documentCatalog.openAction = PDActionJavaScript("app.alert('x')")
            val r = Sanitizer.sanitize(doc, SanitizeOptions())
            assertTrue(r.itemsRemoved >= 1)
            val bytes = doc.toBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Secret Author"))
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("app.alert"))
            PDDocument.load(bytes).use { d ->
                DocInfo.write(d, "New title", "Me", "", "a, b")
                val p = DocInfo.read(d)
                assertEquals("New title", p.title)
                assertEquals(1, p.pageCount)
                assertTrue(p.fonts.any { it.name.contains("Helvetica") })
            }
        }
    }

    @Test
    fun outlineEditing() {
        numbered(3).use { doc ->
            Outline.add(doc, "Intro", 0)
            Outline.add(doc, "Chapter", 1)
            Outline.add(doc, "Section", 2, parentPath = listOf(1))
            var o = Outline.read(doc)
            assertEquals(listOf("Intro", "Chapter"), o.map { it.title })
            assertEquals("Section", o[1].children.single().title)
            assertEquals(2, o[1].children.single().pageIndex)
            Outline.rename(doc, listOf(0), "Introduction")
            Outline.delete(doc, listOf(1, 0))
            roundTrip(doc).use { d ->
                o = Outline.read(d)
                assertEquals("Introduction", o[0].title)
                assertTrue(o[1].children.isEmpty())
            }
            assertNotNull(doc.documentCatalog.documentOutline)
        }
    }
}
