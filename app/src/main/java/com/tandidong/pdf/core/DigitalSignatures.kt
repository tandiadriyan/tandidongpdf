package com.tandidong.pdf.core

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

/** A signing identity: private key plus certificate chain (leaf first). */
class DigitalId(val privateKey: PrivateKey, val chain: List<X509Certificate>) {
    val certificate: X509Certificate get() = chain.first()
    val commonName: String get() = DigitalSignatures.commonName(certificate.subjectX500Principal.name)
    val issuerName: String get() = DigitalSignatures.commonName(certificate.issuerX500Principal.name)
    val isSelfSigned: Boolean get() = certificate.subjectX500Principal == certificate.issuerX500Principal
    val email: String? get() = DigitalSignatures.rdn(certificate.subjectX500Principal.name, BCStyle.EmailAddress)

    companion object {
        /** Loads the first private-key entry from PKCS#12 (.p12 / .pfx) data. */
        fun fromPkcs12(data: ByteArray, password: CharArray): DigitalId {
            val ks = KeyStore.getInstance("PKCS12", DigitalSignatures.provider)
            ks.load(ByteArrayInputStream(data), password)
            for (alias in ks.aliases().toList()) {
                if (!ks.isKeyEntry(alias)) continue
                val key = ks.getKey(alias, password) as? PrivateKey ?: continue
                val chain = ks.getCertificateChain(alias)?.mapNotNull { it as? X509Certificate }.orEmpty()
                if (chain.isNotEmpty()) return DigitalId(key, chain)
            }
            throw IllegalArgumentException("No signing key found in this digital ID")
        }

        /** Creates a self-signed RSA-2048 digital ID valid for [years] years. */
        fun createSelfSigned(name: String, email: String?, organization: String?, years: Int = 5): DigitalId {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()
            val nb = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, name.ifBlank { "Unnamed" })
            if (!organization.isNullOrBlank()) nb.addRDN(BCStyle.O, organization)
            if (!email.isNullOrBlank()) nb.addRDN(BCStyle.EmailAddress, email)
            val subject: X500Name = nb.build()
            val now = Date()
            val until = Calendar.getInstance().apply { add(Calendar.YEAR, years) }.time
            val builder = JcaX509v3CertificateBuilder(
                subject, BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
                Date(now.time - 60_000L), until, subject, kp.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_emailProtection, KeyPurposeId.id_kp_codeSigning)))
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(DigitalSignatures.provider).build(kp.private)
            val cert = JcaX509CertificateConverter().setProvider(DigitalSignatures.provider).getCertificate(builder.build(signer))
            return DigitalId(kp.private, listOf(cert))
        }
    }

    /** Serializes as password-protected PKCS#12. */
    fun toPkcs12(password: CharArray): ByteArray {
        val ks = KeyStore.getInstance("PKCS12", DigitalSignatures.provider)
        ks.load(null, null)
        ks.setKeyEntry("signing", privateKey, password, chain.toTypedArray())
        return ByteArrayOutputStream().also { ks.store(it, password) }.toByteArray()
    }
}

/** Where and how a visible signature is drawn; null on [SignRequest] means invisible. */
data class SignatureAppearanceSpec(
    val pageIndex: Int,
    /** Display-space rectangle (points, top-left origin). */
    val rect: FRect,
    /** Optional handwritten strokes, normalized 0..1 within the left part of the box. */
    val inkStrokes: List<List<Pt>>? = null,
    val inkArgb: Int = 0xFF0D47A1.toInt(),
    /** Width/height ratio of the handwritten strokes (0 = stretch to fill). */
    val inkAspect: Float = 0f,
)

data class SignRequest(
    val reason: String? = null,
    val location: String? = null,
    val contact: String? = null,
    val appearance: SignatureAppearanceSpec? = null,
    /** Existing, unsigned signature field to sign into (its widget keeps its place on the page). */
    val fieldName: String? = null,
)

data class SignatureStatus(
    val fieldName: String,
    val signerName: String,
    val signDate: Calendar?,
    val reason: String?,
    val location: String?,
    val subFilter: String?,
    /** The signed bytes match the signature (the signed revision was not altered). */
    val integrityValid: Boolean,
    /** No changes were appended after this signature. */
    val coversWholeDocument: Boolean,
    val certificateSubject: String,
    val certificateIssuer: String,
    val certificateValidFrom: Date?,
    val certificateValidTo: Date?,
    val certificateValidAtSigning: Boolean,
    val selfSigned: Boolean,
    val pageIndex: Int?,
    val displayRect: FRect?,
    val error: String?,
) {
    val isValid: Boolean get() = error == null && integrityValid && certificateValidAtSigning
}

/** Certificate-based (PAdES-style CMS) signing and signature validation. */
object DigitalSignatures {
    /** A private BouncyCastle provider instance (never registered globally, avoiding Android's stripped "BC"). */
    val provider by lazy { BouncyCastleProvider() }

    fun commonName(dn: String): String = rdn(dn, BCStyle.CN) ?: dn

    fun rdn(dn: String, oid: org.bouncycastle.asn1.ASN1ObjectIdentifier): String? = try {
        X500Name(dn).getRDNs(oid).firstOrNull()?.first?.value?.let { IETFUtils.valueToString(it) }
    } catch (e: Exception) {
        null
    }

    fun hasSignatures(doc: PDDocument): Boolean = try {
        doc.signatureDictionaries.isNotEmpty()
    } catch (e: Exception) {
        false
    }

    private class CmsSigner(private val id: DigitalId) : SignatureInterface {
        override fun sign(content: InputStream): ByteArray {
            val data = content.readBytes()
            val alg = when (id.privateKey.algorithm.uppercase(Locale.US)) {
                "EC", "ECDSA" -> "SHA256withECDSA"
                "DSA" -> "SHA256withDSA"
                else -> "SHA256withRSA"
            }
            val gen = CMSSignedDataGenerator()
            val signer = JcaContentSignerBuilder(alg).setProvider(provider).build(id.privateKey)
            val digests = JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
            gen.addSignerInfoGenerator(
                JcaSignerInfoGeneratorBuilder(digests).build(signer, X509CertificateHolder(id.certificate.encoded))
            )
            gen.addCertificates(JcaCertStore(id.chain))
            return gen.generate(CMSProcessableByteArray(data), false).encoded
        }
    }

    /**
     * Signs [input] into [output] with an incremental update (existing signatures stay valid).
     */
    fun sign(input: File, output: File, id: DigitalId, request: SignRequest, fonts: FontSelector) {
        PdfIO.load(input).use { doc ->
            val signature = PDSignature()
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
            signature.name = id.commonName
            request.reason?.takeIf { it.isNotBlank() }?.let { signature.reason = it }
            request.location?.takeIf { it.isNotBlank() }?.let { signature.location = it }
            request.contact?.takeIf { it.isNotBlank() }?.let { signature.contactInfo = it }
            val now = Calendar.getInstance()
            signature.signDate = now
            val options = SignatureOptions()
            options.preferredSignatureSize = 18_000
            try {
                val spec = request.appearance
                if (spec != null && spec.pageIndex in 0 until doc.numberOfPages) {
                    options.setVisualSignature(visualTemplate(doc, spec, id, request, now, fonts))
                    options.page = spec.pageIndex
                }
                request.fieldName?.let { name ->
                    val field = doc.documentCatalog.acroForm?.getField(name) as? PDSignatureField
                    if (field != null && field.signature == null) {
                        // PDFBox signs into the field whose /V is this signature dictionary.
                        field.setValue(signature)
                        field.cosObject.setNeedToBeUpdated(true)
                        for (w in field.widgets) w.cosObject.setNeedToBeUpdated(true)
                    }
                }
                doc.addSignature(signature, CmsSigner(id), options)
                val tmp = File(output.parentFile, output.name + ".sig")
                try {
                    FileOutputStream(tmp).use { doc.saveIncremental(it) }
                    if (output.exists()) output.delete()
                    if (!tmp.renameTo(output)) { tmp.copyTo(output, overwrite = true); tmp.delete() }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            } finally {
                options.close()
            }
        }
    }

    /** Builds the one-page template PDF that PDFBox merges as the signature widget. */
    private fun visualTemplate(
        src: PDDocument, spec: SignatureAppearanceSpec, id: DigitalId, request: SignRequest, date: Calendar, fonts: FontSelector,
    ): InputStream {
        val srcPage = src.getPage(spec.pageIndex)
        val g = PageGeometry.of(srcPage)
        PDDocument().use { doc ->
            val page = PDPage(srcPage.mediaBox)
            page.cropBox = srcPage.cropBox
            page.rotation = srcPage.rotation
            doc.addPage(page)
            val acro = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acro
            val field = PDSignatureField(acro)
            val widget = field.widgets[0]
            acro.setSignaturesExist(true)
            acro.setAppendOnly(true)
            acro.cosObject.isDirect = true
            acro.fields.add(field)
            widget.rectangle = g.rectToUser(spec.rect).toPdRectangle()

            val w = spec.rect.width
            val h = spec.rect.height
            val stream = PDStream(doc)
            val form = PDFormXObject(stream)
            form.resources = PDResources()
            form.setFormType(1)
            form.bBox = PDRectangle(w, h)
            form.cosObject.setItem(COSName.MATRIX, Appearances.matrixArray(g.appearanceRotationMatrix()))
            val ap = PDAppearanceStream(form.cosObject)
            val dict = PDAppearanceDictionary()
            dict.cosObject.isDirect = true
            dict.setNormalAppearance(ap)
            widget.appearance = dict
            PDPageContentStream(doc, ap).use { cs ->
                drawSignatureBox(doc, cs, w, h, spec, id, request, date, fonts)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return ByteArrayInputStream(out.toByteArray())
        }
    }

    private fun drawSignatureBox(
        doc: PDDocument, cs: PDPageContentStream, w: Float, h: Float, spec: SignatureAppearanceSpec,
        id: DigitalId, request: SignRequest, date: Calendar, fonts: FontSelector,
    ) {
        val strokes = spec.inkStrokes?.filter { it.isNotEmpty() }.orEmpty()
        val textLeft: Float
        if (strokes.isNotEmpty()) {
            // Handwritten signature on the left 45% of the box.
            val iw = w * 0.45f
            // Fit the strokes into the left area, preserving their aspect ratio when known.
            var bw = iw - 4f
            var bh = h - 4f
            if (spec.inkAspect > 0f) {
                if (bw / bh > spec.inkAspect) bw = bh * spec.inkAspect else bh = bw / spec.inkAspect
            }
            val ox = 2f + (iw - 4f - bw) / 2f
            val oy = 2f + (h - 4f - bh) / 2f
            Appearances.stroke(cs, spec.inkArgb)
            cs.setLineWidth(max(0.8f, bh / 30f))
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            for (s in strokes) {
                Appearances.smoothPath(cs, s.map { Pt(ox + it.x * bw, oy + (1f - it.y) * bh) })
                cs.stroke()
            }
            textLeft = iw + 4f
        } else {
            // Large name on the left, details on the right, similar to common viewers.
            val (font, name) = fonts.fontFor(doc, id.commonName)
            var size = h * 0.42f
            val nw = FontSelector.width(font, name, size)
            val maxW = w * 0.48f
            if (nw > maxW) size *= maxW / nw
            Appearances.fill(cs, 0xFF000000.toInt())
            cs.beginText()
            cs.setFont(font, size)
            cs.newLineAtOffset(3f, h / 2f - size * 0.35f)
            cs.showText(name)
            cs.endText()
            textLeft = w * 0.52f
        }
        val df = SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z", Locale.US)
        val lines = listOfNotNull(
            "Digitally signed by",
            id.commonName,
            "Date: ${df.format(date.time)}",
            request.reason?.takeIf { it.isNotBlank() }?.let { "Reason: $it" },
            request.location?.takeIf { it.isNotBlank() }?.let { "Location: $it" },
        )
        val avail = w - textLeft - 2f
        var size = min(h / (lines.size * 1.25f), 10f)
        val (font, _) = fonts.fontFor(doc, lines.joinToString(" "))
        val widest = lines.maxOf { FontSelector.width(font, FontSelector.sanitize(font, it), size) }
        if (widest > avail && widest > 0f) size *= avail / widest
        if (size < 2.5f) return
        Appearances.fill(cs, 0xFF000000.toInt())
        cs.beginText()
        cs.setFont(font, size)
        cs.setLeading(size * 1.2f)
        cs.newLineAtOffset(textLeft, h - (h - lines.size * size * 1.2f) / 2f - size)
        for ((i, l) in lines.withIndex()) {
            if (i > 0) cs.newLine()
            cs.showText(FontSelector.sanitize(font, l))
        }
        cs.endText()
    }

    private fun max(a: Float, b: Float) = kotlin.math.max(a, b)
    private fun min(a: Float, b: Float) = kotlin.math.min(a, b)

    /** Validates every signature in [file]. */
    fun verify(file: File, password: String? = null): List<SignatureStatus> {
        val bytes = file.readBytes()
        val out = ArrayList<SignatureStatus>()
        PDDocument.load(bytes, password ?: "").use { doc ->
            val fieldOf = IdentityHashMap<COSDictionary, PDSignatureField>()
            for (f in doc.signatureFields) {
                f.signature?.let { fieldOf[it.cosObject] = f }
            }
            val pageOf = IdentityHashMap<COSDictionary, Int>()
            for (i in 0 until doc.numberOfPages) {
                val annots = try { doc.getPage(i).annotations } catch (e: Exception) { emptyList() }
                for (a in annots) pageOf[a.cosObject] = i
            }
            for (sig in doc.signatureDictionaries) {
                val field = fieldOf[sig.cosObject]
                val widget = field?.widgets?.firstOrNull()
                val page = widget?.let { pageOf[it.cosObject] }
                val rect = if (widget != null && page != null) widget.rectangle?.let { r ->
                    PageGeometry.of(doc.getPage(page)).rectToDisplay(FRect.of(r)).takeIf { it.width > 1f && it.height > 1f }
                } else null
                out.add(verifyOne(bytes, sig, field?.fullyQualifiedName ?: "Signature", page, rect))
            }
        }
        return out
    }

    private fun verifyOne(bytes: ByteArray, sig: PDSignature, name: String, page: Int?, rect: FRect?): SignatureStatus {
        val br = sig.byteRange
        val covers = br != null && br.size == 4 && run {
            // Allow trailing whitespace after the final %%EOF of the signed revision.
            val end = br[2] + br[3]
            end <= bytes.size && (end until bytes.size).all { bytes[it].toInt().toChar().isWhitespace() }
        }
        fun failure(msg: String) = SignatureStatus(
            name, sig.name ?: "Unknown", sig.signDate, sig.reason, sig.location, sig.subFilter,
            false, covers, "", "", null, null, false, false, page, rect, msg,
        )
        return try {
            val contents = sig.getContents(bytes)
            val signed = sig.getSignedContent(bytes)
            val sub = sig.subFilter ?: ""
            val cms = when (sub) {
                "adbe.pkcs7.detached", "ETSI.CAdES.detached" -> CMSSignedData(CMSProcessableByteArray(signed), contents)
                "adbe.pkcs7.sha1" -> CMSSignedData(contents)
                else -> return failure("Unsupported signature type: $sub")
            }
            val signer: SignerInformation = cms.signerInfos.signers.firstOrNull() ?: return failure("No signer information")
            @Suppress("UNCHECKED_CAST")
            val selector = signer.sid as org.bouncycastle.util.Selector<X509CertificateHolder>
            val holder = cms.certificates.getMatches(selector).firstOrNull()
                ?: return failure("Signer certificate missing")
            val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
            var ok = signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(cert))
            if (ok && sub == "adbe.pkcs7.sha1") {
                // The signed data is the SHA-1 digest of the byte ranges.
                val digest = MessageDigest.getInstance("SHA-1").digest(signed)
                val content = cms.signedContent?.content as? ByteArray
                ok = content != null && content.contentEquals(digest)
            }
            val signingTime = (signer.signedAttributes?.get(CMSAttributes.signingTime) as? Attribute)?.let {
                try { Time.getInstance(it.attrValues.getObjectAt(0)).date } catch (e: Exception) { null }
            }
            val signDate = sig.signDate ?: signingTime?.let { d -> Calendar.getInstance().apply { time = d } }
            val at = signDate?.time ?: Date()
            val certOk = !at.before(cert.notBefore) && !at.after(cert.notAfter)
            SignatureStatus(
                name, sig.name ?: commonName(cert.subjectX500Principal.name), signDate, sig.reason, sig.location, sub,
                ok, covers, commonName(cert.subjectX500Principal.name), commonName(cert.issuerX500Principal.name),
                cert.notBefore, cert.notAfter, certOk, cert.subjectX500Principal == cert.issuerX500Principal,
                page, rect, null,
            )
        } catch (e: Exception) {
            failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
