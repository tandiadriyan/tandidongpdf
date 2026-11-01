package com.tandidong.pdf.core

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Calendar
import java.util.GregorianCalendar

/** A file embedded in the document, either document-level or attached to a page comment. */
data class AttachmentInfo(
    /** Name-tree key for document attachments; "page:annotIndex" for comment attachments. */
    val key: String,
    val fileName: String,
    val description: String?,
    val size: Long,
    val mimeType: String?,
    val modified: Calendar?,
    /** Page of the attachment comment, or null for document-level attachments. */
    val pageIndex: Int?,
)

/** Document attachments (the EmbeddedFiles name tree) and file-attachment comments. */
object Attachments {

    fun list(doc: PDDocument): List<AttachmentInfo> {
        val out = ArrayList<AttachmentInfo>()
        for ((key, spec) in documentFiles(doc)) {
            out.add(info(key, spec, null))
        }
        for (i in 0 until doc.numberOfPages) {
            val annots = try { doc.getPage(i).annotations } catch (e: IOException) { continue }
            annots.forEachIndexed { k, a ->
                if (a is PDAnnotationFileAttachment) {
                    val spec = a.file as? PDComplexFileSpecification ?: return@forEachIndexed
                    out.add(info("$i:$k", spec, i))
                }
            }
        }
        return out
    }

    private fun info(key: String, spec: PDComplexFileSpecification, page: Int?): AttachmentInfo {
        val ef = embedded(spec)
        val name = spec.fileUnicode ?: spec.filename ?: spec.file ?: key
        val size = ef?.let { if (it.size > 0) it.size.toLong() else it.cosObject.length } ?: 0L
        return AttachmentInfo(key, name, spec.fileDescription, size, ef?.subtype, ef?.modDate, page)
    }

    private fun embedded(spec: PDComplexFileSpecification): PDEmbeddedFile? =
        spec.embeddedFileUnicode ?: spec.embeddedFile ?: spec.embeddedFileDos ?: spec.embeddedFileMac ?: spec.embeddedFileUnix

    /** Flattened name -> file specification map of the document's EmbeddedFiles tree. */
    private fun documentFiles(doc: PDDocument): Map<String, PDComplexFileSpecification> {
        val tree = doc.documentCatalog.names?.embeddedFiles ?: return emptyMap()
        val out = LinkedHashMap<String, PDComplexFileSpecification>()
        collect(tree, out, 0)
        return out
    }

    private fun collect(node: PDNameTreeNode<PDComplexFileSpecification>, out: MutableMap<String, PDComplexFileSpecification>, depth: Int) {
        if (depth > 32) return
        try {
            node.names?.let { out.putAll(it) }
        } catch (e: IOException) {
            CoreLog.w("Unreadable attachment names", e)
        }
        node.kids?.forEach { collect(it, out, depth + 1) }
    }

    fun read(doc: PDDocument, key: String): ByteArray {
        val spec = specFor(doc, key) ?: throw IOException("Attachment not found")
        val ef = embedded(spec) ?: throw IOException("The attachment has no embedded data")
        return ef.createInputStream().use { it.readBytes() }
    }

    private fun specFor(doc: PDDocument, key: String): PDComplexFileSpecification? {
        documentFiles(doc)[key]?.let { return it }
        val parts = key.split(':')
        if (parts.size != 2) return null
        val page = parts[0].toIntOrNull() ?: return null
        val idx = parts[1].toIntOrNull() ?: return null
        if (page !in 0 until doc.numberOfPages) return null
        val a = doc.getPage(page).annotations.getOrNull(idx) as? PDAnnotationFileAttachment ?: return null
        return a.file as? PDComplexFileSpecification
    }

    /** Embeds [data] as a document-level attachment; returns the (unique) key used. */
    fun add(doc: PDDocument, fileName: String, data: ByteArray, mimeType: String?, description: String? = null): String {
        val files = LinkedHashMap(documentFiles(doc))
        var key = fileName.ifBlank { "attachment" }
        if (files.containsKey(key)) {
            val dot = key.lastIndexOf('.')
            val base = if (dot > 0) key.substring(0, dot) else key
            val ext = if (dot > 0) key.substring(dot) else ""
            var i = 2
            while (files.containsKey("$base ($i)$ext")) i++
            key = "$base ($i)$ext"
        }
        val ef = PDEmbeddedFile(doc, ByteArrayInputStream(data))
        ef.size = data.size
        ef.subtype = mimeType ?: "application/octet-stream"
        val now = GregorianCalendar()
        ef.creationDate = now
        ef.modDate = now
        val spec = PDComplexFileSpecification()
        spec.file = key
        spec.fileUnicode = key
        spec.embeddedFile = ef
        spec.embeddedFileUnicode = ef
        description?.takeIf { it.isNotBlank() }?.let { spec.fileDescription = it }
        files[key] = spec
        writeTree(doc, files)
        return key
    }

    fun delete(doc: PDDocument, key: String): Boolean {
        val files = LinkedHashMap(documentFiles(doc))
        if (files.remove(key) != null) {
            writeTree(doc, files)
            return true
        }
        val parts = key.split(':')
        if (parts.size != 2) return false
        val page = parts[0].toIntOrNull() ?: return false
        val idx = parts[1].toIntOrNull() ?: return false
        if (page !in 0 until doc.numberOfPages) return false
        val p = doc.getPage(page)
        val annots = p.annotations.toMutableList()
        if (idx !in annots.indices || annots[idx] !is PDAnnotationFileAttachment) return false
        val removed = annots.removeAt(idx)
        // Drop a popup that belonged to the removed comment.
        annots.removeAll { it.cosObject.getDictionaryObject(COSName.PARENT) === removed.cosObject }
        p.annotations = annots
        return true
    }

    private fun writeTree(doc: PDDocument, files: Map<String, PDComplexFileSpecification>) {
        val catalog = doc.documentCatalog
        val names = catalog.names ?: PDDocumentNameDictionary(catalog).also { catalog.names = it }
        if (files.isEmpty()) {
            names.cosObject.removeItem(COSName.EMBEDDED_FILES)
            if (names.cosObject.size() == 0) catalog.cosObject.removeItem(COSName.NAMES)
            return
        }
        val root = PDEmbeddedFilesNameTreeNode()
        // A single leaf keeps the tree simple; names must be sorted.
        root.names = files.toSortedMap()
        names.embeddedFiles = root
    }
}

/** Adds and removes link annotations. Rectangles are display space. */
object LinkEditor {

    fun addUri(doc: PDDocument, pageIndex: Int, rect: FRect, uri: String) {
        val link = base(doc, pageIndex, rect)
        val action = PDActionURI()
        action.setURI(normalizeUri(uri))
        link.action = action
        attach(doc, pageIndex, link)
    }

    fun addPageLink(doc: PDDocument, pageIndex: Int, rect: FRect, targetPage: Int) {
        if (targetPage !in 0 until doc.numberOfPages) throw IOException("No page ${targetPage + 1}")
        val link = base(doc, pageIndex, rect)
        val dest: PDPageDestination = PDPageFitWidthDestination()
        dest.page = doc.getPage(targetPage)
        val action = PDActionGoTo()
        action.destination = dest
        link.action = action
        attach(doc, pageIndex, link)
    }

    fun normalizeUri(uri: String): String {
        val t = uri.trim()
        return when {
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t) -> t
            t.contains('@') && !t.contains('/') -> "mailto:$t"
            else -> "https://$t"
        }
    }

    private fun base(doc: PDDocument, pageIndex: Int, rect: FRect): PDAnnotationLink {
        val page = doc.getPage(pageIndex)
        val g = PageGeometry.of(page)
        val link = PDAnnotationLink()
        link.rectangle = g.rectToUser(rect).toPdRectangle()
        link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
        link.cosObject.setItem(COSName.BORDER, COSArray().apply {
            add(com.tom_roush.pdfbox.cos.COSInteger.ZERO); add(com.tom_roush.pdfbox.cos.COSInteger.ZERO); add(com.tom_roush.pdfbox.cos.COSInteger.ZERO)
        })
        link.isPrinted = true
        return link
    }

    private fun attach(doc: PDDocument, pageIndex: Int, link: PDAnnotationLink) {
        val page = doc.getPage(pageIndex)
        link.page = page
        val annots = page.annotations.toMutableList()
        annots.add(link)
        page.annotations = annots
    }

    /** Removes link annotations whose area contains the display point; returns how many. */
    fun removeAt(doc: PDDocument, pageIndex: Int, x: Float, y: Float): Int {
        val page = doc.getPage(pageIndex)
        val g = PageGeometry.of(page)
        val annots = page.annotations.toMutableList()
        val before = annots.size
        annots.removeAll { a ->
            a is PDAnnotationLink && a.rectangle?.let { r ->
                g.rectToDisplay(FRect(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)).outset(2f).contains(x, y)
            } == true
        }
        if (annots.size != before) page.annotations = annots
        return before - annots.size
    }

    @Suppress("unused")
    private fun unusedDict(d: COSDictionary) = d
}
