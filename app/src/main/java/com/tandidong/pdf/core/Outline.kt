package com.tandidong.pdf.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.util.IdentityHashMap

data class OutlineEntry(val title: String, val pageIndex: Int?, val path: List<Int>, val children: List<OutlineEntry>)

/** Reading and editing bookmarks (the document outline). */
object Outline {
    fun read(doc: PDDocument): List<OutlineEntry> {
        val outline = doc.documentCatalog.documentOutline ?: return emptyList()
        val pageIndex = IdentityHashMap<Any, Int>()
        for (i in 0 until doc.numberOfPages) pageIndex[doc.getPage(i).cosObject] = i
        return children(doc, outline, emptyList(), pageIndex, 0)
    }

    private fun children(doc: PDDocument, node: PDOutlineNode, path: List<Int>, pages: IdentityHashMap<Any, Int>, depth: Int): List<OutlineEntry> {
        if (depth > 32) return emptyList()
        val out = ArrayList<OutlineEntry>()
        var i = 0
        for (item in node.children()) {
            if (i > 10000) break
            val p = path + i
            val target = try { item.findDestinationPage(doc) } catch (e: Exception) { null }
            out.add(OutlineEntry(item.title ?: "", target?.let { pages[it.cosObject] }, p, children(doc, item, p, pages, depth + 1)))
            i++
        }
        return out
    }

    private fun nodeAt(doc: PDDocument, path: List<Int>): PDOutlineNode? {
        var node: PDOutlineNode = doc.documentCatalog.documentOutline ?: return null
        for (idx in path) {
            node = node.children().elementAtOrNull(idx) ?: return null
        }
        return node
    }

    /** Adds a bookmark to [pageIndex] under [parentPath] (null = top level). */
    fun add(doc: PDDocument, title: String, pageIndex: Int, parentPath: List<Int>? = null) {
        val outline = doc.documentCatalog.documentOutline ?: PDDocumentOutline().also { doc.documentCatalog.documentOutline = it }
        val parent = if (parentPath.isNullOrEmpty()) outline else (nodeAt(doc, parentPath) ?: outline)
        val item = PDOutlineItem()
        item.title = title
        val dest = PDPageFitWidthDestination()
        dest.page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
        dest.top = PageGeometry.of(dest.page).let { g -> g.toUser(0f, 0f).y.toInt() }
        item.destination = dest
        parent.addLast(item)
        if (parent is PDOutlineItem) parent.openNode()
        OutlineSupport.recount(outline.cosObject)
    }

    fun rename(doc: PDDocument, path: List<Int>, title: String) {
        (nodeAt(doc, path) as? PDOutlineItem)?.title = title
    }

    fun delete(doc: PDDocument, path: List<Int>) {
        val outline = doc.documentCatalog.documentOutline ?: return
        val item = nodeAt(doc, path) as? PDOutlineItem ?: return
        OutlineSupport.unlink(item.cosObject)
        OutlineSupport.recount(outline.cosObject)
        if (outline.firstChild == null) doc.documentCatalog.documentOutline = null
    }

    fun removeAll(doc: PDDocument) {
        doc.documentCatalog.documentOutline = null
    }
}
