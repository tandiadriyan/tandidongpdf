package support

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font

object TestPdfs {
    /** Adds a Letter page with the given lines drawn in Helvetica starting at (72, 700). */
    fun addTextPage(doc: PDDocument, lines: List<String>, rotation: Int = 0, fontSize: Float = 12f): PDPage {
        val page = PDPage(PDRectangle.LETTER)
        page.rotation = rotation
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, fontSize)
            cs.newLineAtOffset(72f, 700f)
            for ((i, line) in lines.withIndex()) {
                if (i > 0) cs.newLineAtOffset(0f, -fontSize * 1.4f)
                cs.showText(line)
            }
            cs.endText()
        }
        return page
    }

    /** Adds a page whose content stream is the given raw operators, with Helvetica as /F1. */
    fun addRawPage(doc: PDDocument, content: String): PDPage {
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val res = page.resources ?: org.apache.pdfbox.pdmodel.PDResources().also { page.resources = it }
        res.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
        val stream = PDStream(doc)
        stream.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
        page.setContents(stream)
        return page
    }
}
