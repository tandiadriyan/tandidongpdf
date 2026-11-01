import com.tandidong.pdf.core.FontFamily
import com.tandidong.pdf.core.FontSelector
import com.tandidong.pdf.core.Redactor
import com.tandidong.pdf.core.TextEditor
import com.tandidong.pdf.core.TextExtractor
import com.tandidong.pdf.core.TextFormat
import com.tandidong.pdf.core.TextStyles
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import support.AwtImageCodec
import support.AwtRasterizer
import support.noUnicodeFont
import support.toBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TextStyleTest {
    private val fonts = FontSelector(noUnicodeFont)
    private fun editor() = TextEditor(Redactor(AwtImageCodec(), AwtRasterizer(), fonts), fonts)

    private fun page(doc: PDDocument, font: PDFont, size: Float, rgb: Triple<Float, Float, Float>, text: String, x: Float = 72f, y: Float = 700f) {
        val p = PDPage(PDRectangle.LETTER)
        doc.addPage(p)
        PDPageContentStream(doc, p).use { cs ->
            cs.setNonStrokingColor(rgb.first, rgb.second, rgb.third)
            cs.beginText()
            cs.setFont(font, size)
            cs.newLineAtOffset(x, y)
            cs.showText(text)
            cs.endText()
        }
    }

    @Test
    fun matchesStandardFontColorSizeAndBaseline() {
        PDDocument().use { doc ->
            page(doc, PDType1Font.TIMES_BOLD, 14f, Triple(0.8f, 0f, 0f), "Invoice ABC")
            val r = TextExtractor.extract(doc, 0).find("ABC").single().rects.single()
            val style = assertNotNull(TextStyles.probe(doc, 0, r))
            assertEquals("Times-Bold", style.fontName)
            assertEquals(FontFamily.SERIF, style.family)
            assertTrue(style.bold)
            assertFalse(style.italic)
            assertEquals(14f, style.size, 0.01f)
            assertEquals(0xFFCC0000.toInt(), style.argb)
            val origin = assertNotNull(style.origin)
            assertEquals(792f - 700f, origin.y, 0.5f)

            val res = editor().replace(doc, 0, r, "XYZ", TextFormat())
            assertTrue(res.reusedOriginalFont)
            PDDocument.load(doc.toBytes()).use { d ->
                val t = TextExtractor.extract(d, 0)
                assertTrue(t.text.contains("Invoice") && t.text.contains("XYZ") && !t.text.contains("ABC"), t.text)
                val nr = t.find("XYZ").single().rects.single()
                assertEquals(r.x0, nr.x0, 1f)
                assertEquals(r.y1, nr.y1, 1.5f)
                val after = assertNotNull(TextStyles.probe(d, 0, nr))
                assertEquals("Times-Bold", after.fontName)
                assertEquals(0xFFCC0000.toInt(), after.argb)
                assertEquals(14f, after.size, 0.01f)
            }
        }
    }

    @Test
    fun reusesEmbeddedSubsetOnlyWhenGlyphsExist() {
        PDDocument().use { doc ->
            val ttf = javaClass.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")!!
            val font = PDType0Font.load(doc, ttf, true)
            page(doc, font, 12f, Triple(0f, 0f, 0.5f), "Total due: 100 USD")
            // Subset fonts are finalized on save; reload so the page uses the subset.
            PDDocument.load(doc.toBytes()).use { d ->
                val t = TextExtractor.extract(d, 0)
                val r = t.find("100").single().rects.single()
                val style = assertNotNull(TextStyles.probe(d, 0, r))
                assertEquals("LiberationSans", style.fontName)
                assertEquals(FontFamily.SANS, style.family)
                // '0' and '1' are in the subset: the embedded font is reused.
                val first = editor().replace(d, 0, r, "010", TextFormat())
                assertTrue(first.reusedOriginalFont, "reused subset for existing glyphs")
                PDDocument.load(d.toBytes()).use { d2 ->
                    val t2 = TextExtractor.extract(d2, 0)
                    assertTrue(t2.text.contains("010"), t2.text)
                    // 'Q' and 'K' are not in the subset: fall back to the closest standard font.
                    val r2 = t2.find("USD").single().rects.single()
                    val second = editor().replace(d2, 0, r2, "QK", TextFormat())
                    assertFalse(second.reusedOriginalFont)
                    assertEquals("Helvetica", second.fontUsed)
                    PDDocument.load(d2.toBytes()).use { d3 ->
                        val t3 = TextExtractor.extract(d3, 0).text
                        assertTrue(t3.contains("QK") && !t3.contains("USD"), t3)
                        val st = assertNotNull(TextStyles.probe(d3, 0, TextExtractor.extract(d3, 0).find("QK").single().rects.single()))
                        assertEquals(0xFF000080.toInt(), st.argb, "color kept")
                    }
                }
            }
        }
    }

    @Test
    fun formatOverridesStyle() {
        PDDocument().use { doc ->
            page(doc, PDType1Font.HELVETICA, 12f, Triple(0f, 0f, 0f), "Hello world")
            val r = TextExtractor.extract(doc, 0).find("world").single().rects.single()
            val res = editor().replace(doc, 0, r, "there", TextFormat(family = FontFamily.MONO, bold = true, size = 10f))
            assertFalse(res.reusedOriginalFont)
            assertEquals("Courier-Bold", res.fontUsed)
            PDDocument.load(doc.toBytes()).use { d ->
                val t = TextExtractor.extract(d, 0)
                val st = assertNotNull(TextStyles.probe(d, 0, t.find("there").single().rects.single()))
                assertEquals(FontFamily.MONO, st.family)
                assertTrue(st.bold)
                assertEquals(10f, st.size, 0.01f)
            }
        }
    }

    @Test
    fun editsWholeParagraphWithRewrap() {
        PDDocument().use { doc ->
            val p = PDPage(PDRectangle.LETTER)
            doc.addPage(p)
            PDPageContentStream(doc, p).use { cs ->
                fun line(font: PDFont, size: Float, x: Float, y: Float, t: String) {
                    cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(x, y); cs.showText(t); cs.endText()
                }
                line(PDType1Font.HELVETICA_BOLD, 18f, 72f, 720f, "Quarterly report")
                line(PDType1Font.HELVETICA, 11f, 72f, 690f, "Revenue grew in every region during the")
                line(PDType1Font.HELVETICA, 11f, 72f, 676f, "third quarter, led by strong demand for")
                line(PDType1Font.HELVETICA, 11f, 72f, 662f, "our new products.")
                line(PDType1Font.HELVETICA, 11f, 72f, 620f, "Costs were flat.")
            }
            val t = TextExtractor.extract(doc, 0)
            val idx = t.lines.indexOfFirst { t.text.substring(it.start, it.end).contains("third quarter") }
            val para = assertNotNull(TextStyles.paragraphAround(t, idx))
            assertEquals(3, para.lineCount, para.toString())
            assertEquals("Revenue grew in every region during the third quarter, led by strong demand for our new products.", para.text)
            assertEquals(14f, para.leading, 0.5f)

            val newText = "Revenue grew in every region during the third quarter, led by record demand for our new " +
                "products and services, while churn fell to its lowest level in five years."
            editor().replace(doc, 0, para.rect, newText,
                TextFormat(wrapWidth = para.rect.width, leading = para.leading))
            PDDocument.load(doc.toBytes()).use { d ->
                val t2 = TextExtractor.extract(d, 0)
                val flat = t2.text.replace("\n", " ")
                assertTrue(flat.contains("churn fell to its lowest"), t2.text)
                assertTrue(t2.text.contains("Quarterly report") && t2.text.contains("Costs were flat."), t2.text)
                // Every new line stays within the paragraph's width and starts at its left edge.
                val newLines = t2.lines.filter { it.box.y0 > para.rect.y0 - 2f && it.fontSize < 12f && !t2.text.substring(it.start, it.end).contains("Costs") }
                assertTrue(newLines.size >= 4, "wrapped into ${newLines.size} lines")
                for (l in newLines) {
                    assertTrue(l.box.x1 <= para.rect.x1 + 1f, "line within width: ${l.box}")
                    assertEquals(para.rect.x0, l.box.x0, 1.5f)
                }
            }
        }
    }
}
