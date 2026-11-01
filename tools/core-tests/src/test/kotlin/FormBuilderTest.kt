import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.FieldKind
import com.tandidong.pdf.core.FormBuilder
import com.tandidong.pdf.core.Forms
import com.tandidong.pdf.core.NewField
import com.tandidong.pdf.core.NewFieldType
import com.tandidong.pdf.core.TextExtractor
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import support.TestPdfs
import support.toBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormBuilderTest {
    @Test
    fun createsFillsAndFlattensAllFieldTypes() {
        for (rot in listOf(0, 90)) {
            PDDocument().use { doc ->
                TestPdfs.addTextPage(doc, listOf("Application form"), rotation = rot)
                FormBuilder.addFields(doc, listOf(
                    NewField(0, FRect(100f, 100f, 300f, 124f), NewFieldType.TEXT, "name", tooltip = "Full name"),
                    NewField(0, FRect(100f, 140f, 300f, 200f), NewFieldType.MULTILINE, "notes"),
                    NewField(0, FRect(100f, 220f, 116f, 236f), NewFieldType.CHECKBOX, "agree"),
                    NewField(0, FRect(100f, 250f, 116f, 266f), NewFieldType.RADIO, "size", exportValue = "Small"),
                    NewField(0, FRect(130f, 250f, 146f, 266f), NewFieldType.RADIO, "size", exportValue = "Large"),
                    NewField(0, FRect(100f, 280f, 250f, 300f), NewFieldType.DROPDOWN, "country", options = listOf("Indonesia", "Japan", "Peru")),
                    NewField(0, FRect(100f, 320f, 300f, 360f), NewFieldType.SIGNATURE, "sig"),
                ))
                PDDocument.load(doc.toBytes()).use { d ->
                    val fields = Forms.list(d).associateBy { it.name }
                    assertEquals(setOf("name", "notes", "agree", "size", "country", "sig"), fields.keys, "rot=$rot")
                    assertEquals(FieldKind.TEXT, fields["name"]!!.kind)
                    assertEquals("Full name", fields["name"]!!.label)
                    assertTrue(fields["notes"]!!.multiline)
                    assertEquals(FieldKind.CHECKBOX, fields["agree"]!!.kind)
                    assertEquals(FieldKind.RADIO, fields["size"]!!.kind)
                    assertEquals(2, fields["size"]!!.widgets.size)
                    assertEquals(listOf("Indonesia", "Japan", "Peru"), fields["country"]!!.options)
                    assertEquals(FieldKind.SIGNATURE, fields["sig"]!!.kind)
                    val r = fields["name"]!!.widgets.single().displayRect
                    assertEquals(100f, r.x0, 0.5f, "rot=$rot")
                    assertEquals(100f, r.y0, 0.5f, "rot=$rot")

                    val failed = Forms.fill(d, mapOf("name" to "Ada Lovelace", "agree" to "true", "size" to "Large", "country" to "Japan"))
                    assertTrue(failed.isEmpty(), "failed=$failed")
                    PDDocument.load(d.toBytes()).use { filled ->
                        val v = Forms.list(filled).associateBy { it.name }
                        assertEquals("Ada Lovelace", v["name"]!!.value)
                        assertTrue(v["agree"]!!.isChecked)
                        assertEquals("Large", v["size"]!!.value)
                        assertEquals("Japan", v["country"]!!.value)
                        // The checked checkbox renders its check mark.
                        val img = PDFRenderer(filled).renderImage(0, 2f, ImageType.RGB)
                        var dark = 0
                        for (y in 2 * 222 until 2 * 234) for (x in 2 * 102 until 2 * 114) if ((img.getRGB(x, y) and 0xFF) < 80) dark++
                        assertTrue(dark > 10, "rot=$rot check mark visible, dark=$dark")
                        Forms.flatten(filled)
                        assertFalse(Forms.hasForm(filled))
                        assertTrue(TextExtractor.extract(filled, 0).text.contains("Ada Lovelace"), "rot=$rot")
                    }
                }
            }
        }
    }

    @Test
    fun deletesAndRenamesFields() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("x"))
            FormBuilder.addFields(doc, listOf(
                NewField(0, FRect(100f, 100f, 300f, 124f), NewFieldType.TEXT, FormBuilder.suggestName(doc, NewFieldType.TEXT)),
            ))
            FormBuilder.addFields(doc, listOf(
                NewField(0, FRect(100f, 140f, 300f, 164f), NewFieldType.TEXT, FormBuilder.suggestName(doc, NewFieldType.TEXT)),
            ))
            assertEquals(listOf("Text1", "Text2"), Forms.list(doc).map { it.name })
            assertTrue(FormBuilder.renameField(doc, "Text2", "email"))
            assertTrue(FormBuilder.deleteField(doc, "Text1"))
            PDDocument.load(doc.toBytes()).use { d ->
                assertEquals(listOf("email"), Forms.list(d).map { it.name })
                assertEquals(1, d.getPage(0).annotations.size)
            }
        }
    }
}
