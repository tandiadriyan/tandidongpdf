import com.tandidong.pdf.core.TableDetector
import com.tandidong.pdf.core.TextExtractor
import com.tandidong.pdf.core.XlsxWriter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficeExportTest {

    /** An invoice-like table: left-aligned names, right-aligned amounts. */
    private fun tableDoc(): PDDocument {
        val doc = PDDocument()
        val p = PDPage(PDRectangle.LETTER)
        doc.addPage(p)
        val font = PDType1Font.HELVETICA
        PDPageContentStream(doc, p).use { cs ->
            fun text(x: Float, y: Float, t: String, right: Boolean = false) {
                val w = font.getStringWidth(t) / 1000f * 11f
                cs.beginText(); cs.setFont(font, 11f); cs.newLineAtOffset(if (right) x - w else x, y); cs.showText(t); cs.endText()
            }
            val rows = listOf(
                listOf("Item", "Qty", "Price"),
                listOf("Paper A4 box", "3", "12.50"),
                listOf("Toner cartridge", "1", "1,249.00"),
                listOf("Stapler", "12", "(4.75)"),
            )
            var y = 700f
            for (r in rows) {
                text(72f, y, r[0]); text(330f, y, r[1], right = true); text(480f, y, r[2], right = true)
                y -= 18f
            }
        }
        return doc
    }

    @Test
    fun detectsTableAndWritesReadableXlsx() {
        tableDoc().use { doc ->
            val cells = TableDetector.cells(TextExtractor.extract(doc, 0))
            val grid = cells.associate { (it.row to it.col) to it.text }
            assertEquals("Item", grid[0 to 0])
            assertEquals("Toner cartridge", grid[2 to 0])
            assertEquals(3, cells.map { it.col }.distinct().size, "three columns: $cells")
            val out = ByteArrayOutputStream()
            XlsxWriter.write(listOf("Page 1" to cells), out)
            XSSFWorkbook(ByteArrayInputStream(out.toByteArray())).use { wb ->
                val sheet = wb.getSheet("Page 1")
                assertEquals("Item", sheet.getRow(0).getCell(0).stringCellValue)
                val qtyCol = cells.first { it.text == "Qty" }.col
                val priceCol = cells.first { it.text == "Price" }.col
                val toner = sheet.getRow(2)
                assertEquals(CellType.NUMERIC, toner.getCell(qtyCol).cellType)
                assertEquals(1.0, toner.getCell(qtyCol).numericCellValue)
                assertEquals(1249.0, toner.getCell(priceCol).numericCellValue)
                assertEquals(-4.75, sheet.getRow(3).getCell(priceCol).numericCellValue)
            }
        }
    }

    @Test
    fun parsesNumbersConservatively() {
        assertEquals(1234.5, XlsxWriter.parseNumber("1,234.50"))
        assertEquals(0.12, XlsxWriter.parseNumber("12%")!!, 1e-9)
        assertEquals(-45.0, XlsxWriter.parseNumber("(45)"))
        assertEquals(12.0, XlsxWriter.parseNumber("$12"))
        assertNull(XlsxWriter.parseNumber("12/05/2024"))
        assertNull(XlsxWriter.parseNumber("1,23"))
        assertNull(XlsxWriter.parseNumber("Invoice 7"))
    }

    @Test
    fun writesReadablePptx() {
        fun jpeg(w: Int, h: Int): ByteArray {
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics(); g.color = java.awt.Color.ORANGE; g.fillRect(0, 0, w, h); g.dispose()
            val out = ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "jpeg", out)
            return out.toByteArray()
        }
        val slides = listOf(
            com.tandidong.pdf.core.SlideImage(jpeg(85, 110), false, 612f, 792f),
            com.tandidong.pdf.core.SlideImage(jpeg(110, 85), false, 792f, 612f),
        )
        val out = ByteArrayOutputStream()
        com.tandidong.pdf.core.PptxWriter.write(slides, out, "Deck & <notes>")
        org.apache.poi.xslf.usermodel.XMLSlideShow(ByteArrayInputStream(out.toByteArray())).use { ppt ->
            assertEquals(2, ppt.slides.size)
            val size = ppt.pageSize
            assertEquals(720.0, size.getWidth(), 0.5) // 10 inches in points
            assertEquals(720.0 * 792 / 612, size.getHeight(), 1.0)
            for (slide in ppt.slides) {
                val pic = slide.shapes.filterIsInstance<org.apache.poi.xslf.usermodel.XSLFPictureShape>().single()
                assertEquals(org.apache.poi.sl.usermodel.PictureData.PictureType.JPEG, pic.pictureData.type)
                val a = pic.anchor
                assertTrue(a.x >= -0.5 && a.y >= -0.5 && a.x + a.width <= size.getWidth() + 1.0 && a.y + a.height <= size.getHeight() + 1.0, "picture inside slide: $a")
            }
            // The landscape page keeps its proportions inside the portrait slide.
            val a2 = ppt.slides[1].shapes.single().anchor
            assertEquals(792.0 / 612.0, a2.width / a2.height, 0.01)
        }
    }

    @Test
    fun splitsAtTopLevelBookmarks() {
        fun e(title: String, page: Int?) = com.tandidong.pdf.core.OutlineEntry(title, page, emptyList(), emptyList())
        val r = com.tandidong.pdf.core.PageOps.bookmarkRanges(listOf(e("Intro", 1), e("Body", 4), e("Same", 4), e("End", 8), e("Broken", null)), 10)
        assertEquals(listOf((0..3).toList(), (4..7).toList(), (8..9).toList()), r)
        assertNull(com.tandidong.pdf.core.PageOps.bookmarkRanges(listOf(e("Only", 0)), 5))
    }
}
