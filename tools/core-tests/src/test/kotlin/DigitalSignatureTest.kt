import com.tandidong.pdf.core.DigitalId
import com.tandidong.pdf.core.DigitalSignatures
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.FontSelector
import com.tandidong.pdf.core.Pt
import com.tandidong.pdf.core.SignRequest
import com.tandidong.pdf.core.SignatureAppearanceSpec
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import support.TestPdfs
import support.noUnicodeFont
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DigitalSignatureTest {
    private val fonts = FontSelector(noUnicodeFont)
    private val dir = createTempDir()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun sampleFile(rotation: Int = 0): File {
        val f = File(dir, "in-$rotation.pdf")
        PDDocument().use { d ->
            TestPdfs.addTextPage(d, listOf("Contract", "Signed below"), rotation = rotation)
            d.save(f)
        }
        return f
    }

    @Test
    fun pkcs12RoundTrip() {
        val id = DigitalId.createSelfSigned("Ada Lovelace", "ada@example.com", "Analytical Engines")
        assertEquals("Ada Lovelace", id.commonName)
        assertTrue(id.isSelfSigned)
        val p12 = id.toPkcs12("secret".toCharArray())
        val again = DigitalId.fromPkcs12(p12, "secret".toCharArray())
        assertEquals(id.certificate, again.certificate)
        assertEquals("ada@example.com", again.email)
    }

    @Test
    fun signsVisiblyAndValidates() {
        for (rot in listOf(0, 90)) {
            val id = DigitalId.createSelfSigned("Grace Hopper", null, null)
            val input = sampleFile(rot)
            val out = File(dir, "signed-$rot.pdf")
            val strokes = listOf(listOf(Pt(0f, 0.6f), Pt(0.3f, 0.2f), Pt(0.6f, 0.8f), Pt(1f, 0.3f)))
            DigitalSignatures.sign(
                input, out, id,
                SignRequest("I approve this document", "Arlington", null, SignatureAppearanceSpec(0, FRect(300f, 400f, 520f, 460f), strokes)),
                fonts,
            )
            val statuses = DigitalSignatures.verify(out)
            assertEquals(1, statuses.size, "rot=$rot")
            val s = statuses[0]
            assertTrue(s.integrityValid, "rot=$rot ${s.error}")
            assertTrue(s.coversWholeDocument, "rot=$rot")
            assertTrue(s.isValid, "rot=$rot")
            assertEquals("Grace Hopper", s.signerName)
            assertEquals("I approve this document", s.reason)
            assertEquals(0, s.pageIndex)
            val r = s.displayRect!!
            assertEquals(300f, r.x0, 0.5f, "rot=$rot")
            assertEquals(400f, r.y0, 0.5f, "rot=$rot")
            // The appearance renders inside the box (non-white pixels).
            PDDocument.load(out).use { d ->
                val img = PDFRenderer(d).renderImage(0, 1f, ImageType.RGB)
                var dark = 0
                for (y in 402 until 458) for (x in 302 until 518) if ((img.getRGB(x, y) and 0xFF) < 200) dark++
                assertTrue(dark > 100, "rot=$rot visible signature drawn, dark=$dark")
            }
        }
    }

    @Test
    fun detectsTamperingAndLaterChanges() {
        val id = DigitalId.createSelfSigned("Signer One", null, null)
        val input = sampleFile()
        val signed = File(dir, "signed.pdf")
        DigitalSignatures.sign(input, signed, id, SignRequest(), fonts)
        assertTrue(DigitalSignatures.verify(signed).single().isValid)

        // Flip a byte inside the signed range (in the page content area, not the signature value).
        val bytes = signed.readBytes()
        val idx = String(bytes, Charsets.ISO_8859_1).indexOf("Contract").let { if (it < 0) 400 else it }
        val tampered = File(dir, "tampered.pdf")
        tampered.writeBytes(bytes.copyOf().also { it[idx] = (it[idx] + 1).toByte() })
        val t = DigitalSignatures.verify(tampered)
        assertTrue(t.isEmpty() || !t.single().integrityValid, "tampering must be detected")

        // A second signature is appended incrementally: the first stays intact but no longer covers everything.
        val twice = File(dir, "twice.pdf")
        DigitalSignatures.sign(signed, twice, DigitalId.createSelfSigned("Signer Two", null, null), SignRequest(), fonts)
        val both = DigitalSignatures.verify(twice)
        assertEquals(2, both.size)
        val first = both.first { it.signerName == "Signer One" }
        val second = both.first { it.signerName == "Signer Two" }
        assertTrue(first.integrityValid && !first.coversWholeDocument)
        assertTrue(second.integrityValid && second.coversWholeDocument)

        // A full rewrite (what ordinary edits do) invalidates the signature.
        val rewritten = File(dir, "rewritten.pdf")
        PDDocument.load(signed).use { it.save(rewritten) }
        assertFalse(DigitalSignatures.verify(rewritten).single().integrityValid)
    }

    @Test
    fun signsIntoExistingSignatureField() {
        for (rot in listOf(0, 90)) {
            val input = File(dir, "field-$rot.pdf")
            PDDocument().use { d ->
                TestPdfs.addTextPage(d, listOf("Agreement"), rotation = rot)
                com.tandidong.pdf.core.FormBuilder.addFields(d, listOf(
                    com.tandidong.pdf.core.NewField(0, FRect(320f, 380f, 520f, 440f), com.tandidong.pdf.core.NewFieldType.SIGNATURE, "Buyer"),
                ))
                d.save(input)
            }
            val id = DigitalId.createSelfSigned("Katherine Johnson", null, null)
            val out = File(dir, "field-signed-$rot.pdf")
            DigitalSignatures.sign(
                input, out, id,
                SignRequest("I agree", null, null, SignatureAppearanceSpec(0, FRect(320f, 380f, 520f, 440f)), fieldName = "Buyer"),
                fonts,
            )
            val statuses = DigitalSignatures.verify(out)
            assertEquals(1, statuses.size, "rot=$rot")
            assertEquals("Buyer", statuses[0].fieldName)
            assertTrue(statuses[0].isValid && statuses[0].coversWholeDocument, "rot=$rot ${statuses[0].error}")
            PDDocument.load(out).use { d ->
                // Signed in place: still one field and one widget on the page.
                assertEquals(1, d.documentCatalog.acroForm.fields.size, "rot=$rot")
                assertEquals(1, d.getPage(0).annotations.size, "rot=$rot")
                val r = statuses[0].displayRect!!
                assertEquals(320f, r.x0, 0.5f, "rot=$rot")
                assertEquals(380f, r.y0, 0.5f, "rot=$rot")
                val img = PDFRenderer(d).renderImage(0, 1f, ImageType.RGB)
                var dark = 0
                for (y in 382 until 438) for (x in 322 until 518) if ((img.getRGB(x, y) and 0xFF) < 120) dark++
                assertTrue(dark > 60, "rot=$rot signed appearance drawn, dark=$dark")
            }
        }
    }
}
