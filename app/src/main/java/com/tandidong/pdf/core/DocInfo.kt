package com.tandidong.pdf.core

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.util.Calendar
import java.util.IdentityHashMap

data class FontInfo(val name: String, val type: String, val embedded: Boolean)

data class DocProperties(
    val title: String,
    val author: String,
    val subject: String,
    val keywords: String,
    val creator: String,
    val producer: String,
    val created: Calendar?,
    val modified: Calendar?,
    val pdfVersion: Float,
    val pageCount: Int,
    val firstPageWidth: Float,
    val firstPageHeight: Float,
    val encrypted: Boolean,
    val tagged: Boolean,
    val hasForm: Boolean,
    val hasOutline: Boolean,
    val attachmentCount: Int,
    val fonts: List<FontInfo>,
)

object DocInfo {
    fun read(doc: PDDocument, includeFonts: Boolean = true): DocProperties {
        val info = doc.documentInformation ?: PDDocumentInformation()
        val catalog = doc.documentCatalog
        val first = if (doc.numberOfPages > 0) PageGeometry.of(doc.getPage(0)) else null
        val markInfo = catalog.cosObject.getDictionaryObject(COSName.getPDFName("MarkInfo")) as? COSDictionary
        val names = catalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary
        val ef = names?.getDictionaryObject(COSName.EMBEDDED_FILES) as? COSDictionary
        val attachments = countNameTree(ef)
        return DocProperties(
            title = info.title ?: "",
            author = info.author ?: "",
            subject = info.subject ?: "",
            keywords = info.keywords ?: "",
            creator = info.creator ?: "",
            producer = info.producer ?: "",
            created = safe { info.creationDate },
            modified = safe { info.modificationDate },
            pdfVersion = doc.version,
            pageCount = doc.numberOfPages,
            firstPageWidth = first?.displayWidth ?: 0f,
            firstPageHeight = first?.displayHeight ?: 0f,
            encrypted = doc.isEncrypted,
            tagged = markInfo?.getBoolean(COSName.getPDFName("Marked"), false) == true,
            hasForm = Forms.hasForm(doc),
            hasOutline = catalog.documentOutline?.firstChild != null,
            attachmentCount = attachments,
            fonts = if (includeFonts) fonts(doc) else emptyList(),
        )
    }

    fun write(doc: PDDocument, title: String, author: String, subject: String, keywords: String) {
        val info = doc.documentInformation ?: PDDocumentInformation().also { doc.documentInformation = it }
        info.title = title.ifBlank { null }
        info.author = author.ifBlank { null }
        info.subject = subject.ifBlank { null }
        info.keywords = keywords.ifBlank { null }
        info.modificationDate = Calendar.getInstance()
        // Stale XMP would contradict the edited values in some viewers.
        doc.documentCatalog.cosObject.removeItem(COSName.METADATA)
    }

    fun touch(doc: PDDocument, producer: String) {
        val info = doc.documentInformation ?: PDDocumentInformation().also { doc.documentInformation = it }
        info.modificationDate = Calendar.getInstance()
        info.producer = producer
    }

    private fun countNameTree(node: COSDictionary?, depth: Int = 0): Int {
        if (node == null || depth > 32) return 0
        var n = 0
        (node.getDictionaryObject(COSName.NAMES) as? com.tom_roush.pdfbox.cos.COSArray)?.let { n += it.size() / 2 }
        (node.getDictionaryObject(COSName.KIDS) as? com.tom_roush.pdfbox.cos.COSArray)?.let { kids ->
            for (i in 0 until kids.size()) n += countNameTree(kids.getObject(i) as? COSDictionary, depth + 1)
        }
        return n
    }

    fun fonts(doc: PDDocument, maxPages: Int = 200): List<FontInfo> {
        val seen = IdentityHashMap<COSDictionary, Boolean>()
        val out = LinkedHashMap<String, FontInfo>()
        fun scan(res: PDResources?, depth: Int) {
            if (res == null || depth > 8) return
            for (name in res.fontNames) {
                val dict = (res.cosObject.getDictionaryObject(COSName.FONT) as? COSDictionary)?.getDictionaryObject(name) as? COSDictionary ?: continue
                if (seen.put(dict, true) != null) continue
                val font = try { res.getFont(name) } catch (e: Exception) { null } ?: continue
                val fname = (try { font.name } catch (e: Exception) { null }) ?: name.name
                val type = dict.getNameAsString(COSName.SUBTYPE) ?: "Unknown"
                val embedded = font is PDType3Font || try { font.isEmbedded } catch (e: Exception) { false }
                out[fname + type] = FontInfo(fname.substringAfter('+'), type, embedded)
            }
            for (x in res.xObjectNames) {
                val xo = try { res.getXObject(x) } catch (e: Exception) { null }
                if (xo is PDFormXObject) scan(xo.resources, depth + 1)
            }
        }
        for (i in 0 until minOf(doc.numberOfPages, maxPages)) scan(doc.getPage(i).resources, 0)
        return out.values.sortedBy { it.name }
    }

    private inline fun <T> safe(block: () -> T?): T? = try { block() } catch (e: Exception) { null }
}
