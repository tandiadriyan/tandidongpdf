import com.tandidong.pdf.core.PageGeometry
import com.tandidong.pdf.core.TextExtractor
import org.apache.pdfbox.pdmodel.PDDocument
import support.TestPdfs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTest {
    @Test
    fun extractsLinesAndFindsWords() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Hello Secret World", "Second line here"))
            val t = TextExtractor.extract(doc, 0)
            assertEquals("Hello Secret World\nSecond line here", t.text)
            assertEquals(2, t.lines.size)
            val m = t.find("secret")
            assertEquals(1, m.size)
            assertEquals("Secret", m[0].text)
            val r = m[0].rects.single()
            // Display space: x starts after "Hello " at 72pt margin, y near the top (792-700=92).
            assertTrue(r.x0 > 72f && r.x1 < 72f + 120f, "rect $r")
            assertTrue(r.y0 < 92f && r.y1 > 88f, "rect $r")
            assertEquals(1, t.find("line here").size)
            assertEquals(1, t.find("World Second").size, "matches across line break")
            assertEquals(0, t.find("Secr", wholeWord = true).size)
        }
    }

    @Test
    fun rotatedPageTextReadsHorizontally() {
        PDDocument().use { doc ->
            val page = TestPdfs.addTextPage(doc, listOf("Rotated words"), rotation = 90)
            val t = TextExtractor.extract(doc, 0)
            assertEquals("Rotated words", t.text)
            val g = PageGeometry.of(page)
            val r = t.find("words").single().rects.single()
            // On a 90° page, user-space baseline y=700 maps to display x = 700.
            assertTrue(r.x0 > 690f && r.x1 < 712f, "rect $r in ${g.displayWidth}x${g.displayHeight}")
        }
    }

    @Test
    fun regexFindsEmails() {
        PDDocument().use { doc ->
            TestPdfs.addTextPage(doc, listOf("Contact: jane.doe@example.com or 555-123-4567"))
            val t = TextExtractor.extract(doc, 0)
            val emails = t.findRegex(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"))
            assertEquals(listOf("jane.doe@example.com"), emails.map { it.text })
        }
    }
}
