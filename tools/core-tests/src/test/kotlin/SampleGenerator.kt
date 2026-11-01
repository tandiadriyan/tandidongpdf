import com.tandidong.pdf.core.Outline
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assume
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test

/**
 * Writes tools/smoke/sample.pdf, used by the emulator smoke test.
 * Run with: ./gradlew -p tools/core-tests test --tests SampleGenerator -DgenerateSample=true
 */
class SampleGenerator {
    @Test
    fun generate() {
        Assume.assumeTrue(System.getProperty("generateSample") == "true" || System.getenv("GENERATE_SAMPLE") == "true")
        val out = File(System.getProperty("user.dir"), "../smoke/sample.pdf").canonicalFile
        out.parentFile.mkdirs()
        PDDocument().use { doc ->
            val p1 = PDPage(PDRectangle.A4)
            doc.addPage(p1)
            PDPageContentStream(doc, p1).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 22f)
                cs.newLineAtOffset(60f, 770f)
                cs.showText("Quarterly Report")
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.setLeading(18f)
                for (line in listOf(
                    "",
                    "Prepared for the board of directors. Contact jane.doe@example.com",
                    "or call +1 555 123 4567 for questions about this document.",
                    "Revenue grew steadily across all regions this quarter, driven by",
                    "strong demand for our core products and new partnerships.",
                    "Account number 4111 1111 1111 1111 must remain confidential.",
                    "The remainder of this page contains sample paragraphs used to test",
                    "searching, highlighting, redaction and text selection features.",
                )) { cs.newLine(); cs.showText(line) }
                cs.endText()
                cs.setNonStrokingColor(0.9f, 0.95f, 1f)
                cs.addRect(60f, 420f, 475f, 120f)
                cs.fill()
                cs.beginText()
                cs.setNonStrokingColor(0f, 0f, 0f)
                cs.setFont(PDType1Font.HELVETICA, 11f)
                cs.newLineAtOffset(70f, 520f)
                cs.showText("Full name:")
                cs.endText()
            }
            val acro = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acro
            acro.defaultResources = PDResources().also { it.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            acro.defaultAppearance = "/Helv 0 Tf 0 g"
            val tf = PDTextField(acro)
            tf.partialName = "fullName"
            tf.alternateFieldName = "Full name"
            tf.defaultAppearance = "/Helv 12 Tf 0 0 0 rg"
            acro.fields.add(tf)
            val w = tf.widgets[0]
            w.rectangle = PDRectangle(140f, 512f, 250f, 22f)
            w.page = p1

            val p2 = PDPage(PDRectangle.A4)
            p2.rotation = 90
            doc.addPage(p2)
            PDPageContentStream(doc, p2).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 14f)
                cs.newLineAtOffset(60f, 760f)
                cs.showText("This page is rotated 90 degrees. Secret project codename: ORION.")
                cs.endText()
            }

            val p3 = PDPage(PDRectangle.A4)
            doc.addPage(p3)
            val bi = BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 200) for (x in 0 until 300) bi.setRGB(x, y, ((x * 255 / 300) shl 16) or ((y * 255 / 200) shl 8) or 0x80)
            val img = LosslessFactory.createFromImage(doc, bi)
            PDPageContentStream(doc, p3).use { cs ->
                cs.drawImage(img, 60f, 500f, 300f, 200f)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(60f, 470f)
                cs.showText("Figure 1. Gradient chart. Tap here to go back to page 1.")
                cs.endText()
            }
            val link = PDAnnotationLink()
            link.rectangle = PDRectangle(60f, 465f, 330f, 18f)
            link.action = PDActionGoTo().apply { destination = PDPageFitDestination().also { it.page = p1 } }
            p1.annotations = listOf(w)
            p3.annotations = listOf(link)

            Outline.add(doc, "Quarterly Report", 0)
            Outline.add(doc, "Rotated page", 1)
            Outline.add(doc, "Figure", 2)
            doc.save(out)
        }
        println("Wrote ${out.absolutePath} (${out.length()} bytes)")
    }
}
