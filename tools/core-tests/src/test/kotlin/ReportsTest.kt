import com.tandidong.pdf.core.AnnotationEditor
import com.tandidong.pdf.core.AnnotationSpec
import com.tandidong.pdf.core.AnnotationWriter
import com.tandidong.pdf.core.FontSelector
import com.tandidong.pdf.core.Pt
import com.tandidong.pdf.core.Reports
import com.tandidong.pdf.core.TextExtractor
import org.apache.pdfbox.pdmodel.PDDocument
import support.TestPdfs
import support.noUnicodeFont
import support.toBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportsTest {
    private val fonts = FontSelector(noUnicodeFont)

    @Test
    fun summarizesCommentsWithReplies() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Draft one"))
            TestPdfs.addTextPage(doc, listOf("Draft two"))
            AnnotationWriter("Ana", fonts).add(doc, listOf(
                AnnotationSpec.Note(0, Pt(100f, 100f), "Please check the totals"),
                AnnotationSpec.Note(1, Pt(100f, 100f), "Typo in heading"),
            ))
            val first = AnnotationEditor.list(doc, 0).single()
            AnnotationEditor.reply(doc, 0, first.index, "Fixed in v2", "Ben")
            Reports.commentSummary(doc, "contract.pdf", fonts).use { report ->
                PDDocument.load(report.toBytes()).use { r ->
                    val text = TextExtractor.extractAll(r).joinToString("\n") { it.text }
                    for (s in listOf("Comment summary", "contract.pdf · 2 comments", "Page 1", "Please check the totals", "Reply — Ben", "Fixed in v2", "Page 2", "Typo in heading")) {
                        assertTrue(text.contains(s), "missing '$s' in:\n$text")
                    }
                    assertTrue(text.indexOf("Please check") < text.indexOf("Fixed in v2"), "reply follows its comment")
                }
            }
        }
    }

    @Test
    fun textToPdfWrapsAndPaginates() {
        val long = (1..400).joinToString(" ") { "word$it" }
        Reports.textToPdf("Title line\n\n$long\nLast line", fonts, "Notes").use { doc ->
            assertTrue(doc.numberOfPages >= 1)
            PDDocument.load(doc.toBytes()).use { d ->
                val text = TextExtractor.extractAll(d).joinToString("\n") { it.text }
                assertTrue(text.contains("word1 ") && text.contains("word400") && text.contains("Last line"), text.take(300))
                assertEquals("Notes", d.documentInformation.title)
            }
        }
        assertEquals("2024-01-31 12:05", Reports.formatDate("D:20240131120500+01'00'"))
    }
}
