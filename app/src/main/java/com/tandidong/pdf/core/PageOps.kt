package com.tandidong.pdf.core

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

/** One page of an organize plan: an existing page (by index) with extra rotation, or a blank page. */
sealed class PageSpec {
    data class Existing(val sourceIndex: Int, val rotateBy: Int = 0) : PageSpec()
    data class Blank(val width: Float, val height: Float) : PageSpec()
}

object PageSizes {
    val A4 = PDRectangle.A4
    val LETTER = PDRectangle.LETTER
    val LEGAL = PDRectangle.LEGAL
    val A5 = PDRectangle.A5
    val A3 = PDRectangle.A3
}

/** Page organization: reorder, rotate, delete, duplicate, insert, extract, split, merge and crop. */
object PageOps {

    /**
     * Rebuilds the document's pages according to [plan]. Pages that are not referenced are removed
     * (and references to them scrubbed); pages referenced more than once are duplicated.
     */
    fun applyPlan(doc: PDDocument, plan: List<PageSpec>) {
        require(plan.isNotEmpty()) { "A document must contain at least one page" }
        val original = (0 until doc.numberOfPages).map { doc.getPage(it) }
        val used = IdentityHashMap<PDPage, Boolean>()
        val result = ArrayList<PDPage>(plan.size)
        val fallbackSize = original.firstOrNull()?.mediaBox ?: PDRectangle.LETTER
        for (spec in plan) {
            when (spec) {
                is PageSpec.Existing -> {
                    val src = original.getOrNull(spec.sourceIndex) ?: throw IllegalArgumentException("No page ${spec.sourceIndex}")
                    materializeInherited(src)
                    result.add(if (used.containsKey(src)) duplicate(src) else src.also { used[it] = true })
                }
                is PageSpec.Blank -> {
                    val size = if (spec.width > 0 && spec.height > 0) PDRectangle(spec.width, spec.height) else fallbackSize
                    result.add(PDPage(size))
                }
            }
        }
        // Rotate only after all duplicates were taken, so copies don't inherit another entry's rotation.
        for ((spec, page) in plan.zip(result)) {
            if (spec is PageSpec.Existing && spec.rotateBy != 0) {
                page.rotation = PageGeometry.normalizeRotation(page.rotation + spec.rotateBy)
            }
        }
        val removed = original.filter { !used.containsKey(it) }
        val removedDicts = IdentityHashMap<COSDictionary, Boolean>().apply { removed.forEach { put(it.cosObject, true) } }
        if (removed.isNotEmpty()) {
            val widgets = HashSet<COSDictionary>()
            for (p in removed) {
                val annots = try { p.annotations } catch (e: IOException) { emptyList<PDAnnotation>() }
                annots.filter { it.subtype == "Widget" }.forEach { widgets.add(it.cosObject) }
            }
            if (widgets.isNotEmpty()) FormFieldPruner.removeWidgets(doc, widgets)
        }
        rebuildPageTree(doc, result)
        if (removed.isNotEmpty()) {
            scrubReferences(doc, removedDicts)
            removed.forEach { neuter(it) }
        }
    }

    fun deletePages(doc: PDDocument, indices: Collection<Int>) {
        val drop = indices.toSet()
        applyPlan(doc, (0 until doc.numberOfPages).filter { it !in drop }.map { PageSpec.Existing(it) })
    }

    fun rotatePages(doc: PDDocument, indices: Collection<Int>, degrees: Int) {
        for (i in indices.toSet()) {
            if (i !in 0 until doc.numberOfPages) continue
            val p = doc.getPage(i)
            p.rotation = PageGeometry.normalizeRotation(p.rotation + degrees)
        }
    }

    /** [order] lists source indices in their new order; omitted pages are deleted. */
    fun reorder(doc: PDDocument, order: List<Int>) = applyPlan(doc, order.map { PageSpec.Existing(it) })

    fun duplicatePages(doc: PDDocument, indices: Collection<Int>) {
        val dup = indices.toSet()
        val plan = ArrayList<PageSpec>()
        for (i in 0 until doc.numberOfPages) {
            plan.add(PageSpec.Existing(i))
            if (i in dup) plan.add(PageSpec.Existing(i))
        }
        applyPlan(doc, plan)
    }

    fun insertBlank(doc: PDDocument, at: Int, width: Float = 0f, height: Float = 0f, count: Int = 1) {
        val n = doc.numberOfPages
        val pos = at.coerceIn(0, n)
        val plan = ArrayList<PageSpec>()
        for (i in 0 until n) {
            if (i == pos) repeat(count) { plan.add(PageSpec.Blank(width, height)) }
            plan.add(PageSpec.Existing(i))
        }
        if (pos == n) repeat(count) { plan.add(PageSpec.Blank(width, height)) }
        applyPlan(doc, plan)
    }

    fun reverse(doc: PDDocument) = reorder(doc, (doc.numberOfPages - 1 downTo 0).toList())

    /** Writes the given pages (in the given order) of [source] into [output]. */
    fun extract(source: File, password: String?, indices: List<Int>, output: File) {
        PdfIO.transform(source, output, password) { doc -> reorder(doc, indices) }
    }

    /** Splits into groups of [ranges] (each a list of page indices); returns the written files. */
    fun split(source: File, password: String?, ranges: List<List<Int>>, outputFor: (Int) -> File): List<File> =
        ranges.mapIndexed { i, r ->
            val out = outputFor(i)
            extract(source, password, r, out)
            out
        }

    /** Ranges for "split every N pages". */
    /**
     * Page ranges that start at each top-level bookmark (in page order); pages before the first
     * bookmark belong to the first part. Null when there are fewer than two distinct starts.
     */
    fun bookmarkRanges(entries: List<OutlineEntry>, pageCount: Int): List<List<Int>>? {
        val starts = entries.mapNotNull { it.pageIndex }.filter { it in 0 until pageCount }.distinct().sorted()
        if (starts.size < 2) return null
        val firsts = listOf(0) + starts.drop(1)
        return firsts.mapIndexed { k, start ->
            val end = if (k + 1 < firsts.size) firsts[k + 1] - 1 else pageCount - 1
            (start..end).toList()
        }
    }

    fun everyN(pageCount: Int, n: Int): List<List<Int>> =
        (0 until pageCount).chunked(n.coerceAtLeast(1))

    /**
     * Parses page ranges like "1-3, 5, 8-" (1-based, inclusive) into 0-based indices.
     * Returns null when the text is invalid.
     */
    fun parseRanges(text: String, pageCount: Int): List<List<Int>>? {
        val out = ArrayList<List<Int>>()
        for (part in text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("^(\\d*)\\s*-\\s*(\\d*)$").find(part)
            val (a, b) = if (m != null) {
                val s = m.groupValues[1].toIntOrNull() ?: 1
                val e = m.groupValues[2].toIntOrNull() ?: pageCount
                s to e
            } else {
                val v = part.toIntOrNull() ?: return null
                v to v
            }
            if (a < 1 || b < 1 || a > pageCount || b > pageCount) return null
            out.add(if (a <= b) (a - 1..b - 1).toList() else (a - 1 downTo b - 1).toList())
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Appends all pages of [sources] to [dest] (forms, outlines and structure are merged). */
    fun appendAll(dest: PDDocument, sources: List<PDDocument>) {
        val merger = PDFMergerUtility()
        for (src in sources) {
            if (src.isEncrypted) src.isAllSecurityToBeRemoved = true
            merger.appendDocument(dest, src)
        }
    }

    /**
     * Inserts pages [srcIndices] of [srcFile] into [dest] before position [at].
     * The caller must save [dest] before the returned source document is closed.
     */
    fun insertFrom(dest: PDDocument, srcFile: File, srcPassword: String?, srcIndices: List<Int>?, at: Int): PDDocument {
        val src = PdfIO.load(srcFile, srcPassword)
        if (src.isEncrypted) src.isAllSecurityToBeRemoved = true
        if (srcIndices != null) reorder(src, srcIndices)
        val before = dest.numberOfPages
        appendAll(dest, listOf(src))
        val added = dest.numberOfPages - before
        val pos = at.coerceIn(0, before)
        val order = ArrayList<Int>()
        for (i in 0 until before) {
            if (i == pos) for (k in 0 until added) order.add(before + k)
            order.add(i)
        }
        if (pos == before) for (k in 0 until added) order.add(before + k)
        reorder(dest, order)
        return src
    }

    /**
     * Crops pages by the given margins (points, in display orientation: left, top, right, bottom).
     */
    fun crop(doc: PDDocument, indices: Collection<Int>, left: Float, top: Float, right: Float, bottom: Float) {
        for (i in indices.toSet()) {
            if (i !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(i)
            val g = PageGeometry.of(page)
            val w = g.displayWidth
            val h = g.displayHeight
            if (left + right >= w - 1f || top + bottom >= h - 1f) continue
            val user = g.rectToUser(FRect(left, top, w - right, h - bottom))
            val media = FRect.of(page.mediaBox)
            val clipped = user.intersection(media) ?: continue
            page.cropBox = clipped.toPdRectangle()
        }
    }

    /** Sets the media box of pages to a new size, scaling content to fit (keeps aspect). */
    fun resize(doc: PDDocument, indices: Collection<Int>, target: PDRectangle) {
        for (i in indices.toSet()) {
            if (i !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(i)
            val crop = page.cropBox
            val swap = page.rotation % 180 != 0
            val tw = if (swap) target.height else target.width
            val th = if (swap) target.width else target.height
            val s = minOf(tw / crop.width, th / crop.height)
            val dx = (tw - crop.width * s) / 2f - crop.lowerLeftX * s
            val dy = (th - crop.height * s) / 2f - crop.lowerLeftY * s
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(
                doc, page, com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.PREPEND, true,
            ).use { cs -> cs.transform(com.tom_roush.pdfbox.util.Matrix(s, 0f, 0f, s, dx, dy)) }
            val box = PDRectangle(0f, 0f, tw, th)
            page.mediaBox = box
            page.cropBox = box
            page.cosObject.removeItem(COSName.BLEED_BOX)
            page.cosObject.removeItem(COSName.TRIM_BOX)
            page.cosObject.removeItem(COSName.ART_BOX)
            // Annotations live in user space too: move them with the content.
            for (a in try { page.annotations } catch (e: IOException) { emptyList<PDAnnotation>() }) {
                val r = a.rectangle ?: continue
                a.rectangle = PDRectangle(r.lowerLeftX * s + dx, r.lowerLeftY * s + dy, r.width * s, r.height * s)
            }
        }
    }

    // ------------------------------------------------------------------ internals

    /** Copies inheritable attributes onto the page itself so it can be re-parented safely. */
    private fun materializeInherited(page: PDPage) {
        val d = page.cosObject
        if (!d.containsKey(COSName.RESOURCES)) page.resources?.let { d.setItem(COSName.RESOURCES, it.cosObject) }
        if (!d.containsKey(COSName.MEDIA_BOX)) d.setItem(COSName.MEDIA_BOX, page.mediaBox.cosArray)
        if (!d.containsKey(COSName.CROP_BOX)) {
            val inheritedCrop = inherited(d, COSName.CROP_BOX)
            if (inheritedCrop != null) d.setItem(COSName.CROP_BOX, inheritedCrop)
        }
        if (!d.containsKey(COSName.ROTATE)) {
            val rot = page.rotation
            if (rot != 0) d.setInt(COSName.ROTATE, rot)
        }
    }

    private fun inherited(d: COSDictionary, key: COSName): COSBase? {
        var node: COSDictionary? = d.getDictionaryObject(COSName.PARENT, COSName.P) as? COSDictionary
        var depth = 0
        while (node != null && depth++ < 64) {
            node.getDictionaryObject(key)?.let { return it }
            node = node.getDictionaryObject(COSName.PARENT, COSName.P) as? COSDictionary
        }
        return null
    }

    private fun duplicate(src: PDPage): PDPage {
        val dict = COSDictionary(src.cosObject)
        dict.removeItem(COSName.PARENT)
        dict.removeItem(COSName.STRUCT_PARENTS)
        val copy = PDPage(dict)
        val annots = try { src.annotations } catch (e: IOException) { emptyList<PDAnnotation>() }
        if (annots.isNotEmpty()) {
            val arr = COSArray()
            val popupMap = IdentityHashMap<COSDictionary, COSDictionary>()
            for (a in annots) {
                if (a.subtype == "Widget") continue // form fields cannot be shared between pages
                val c = COSDictionary(a.cosObject)
                c.setItem(COSName.P, dict)
                c.removeItem(COSName.getPDFName("StructParent"))
                popupMap[a.cosObject] = c
                arr.add(c)
            }
            // Re-link popups and their parents within the copy.
            for (i in 0 until arr.size()) {
                val c = arr.getObject(i) as COSDictionary
                (c.getDictionaryObject(COSName.getPDFName("Popup")) as? COSDictionary)?.let { p ->
                    popupMap[p]?.let { c.setItem(COSName.getPDFName("Popup"), it) }
                }
                (c.getDictionaryObject(COSName.PARENT) as? COSDictionary)?.let { p ->
                    popupMap[p]?.let { c.setItem(COSName.PARENT, it) }
                }
            }
            dict.setItem(COSName.ANNOTS, arr)
        }
        return copy
    }

    private fun rebuildPageTree(doc: PDDocument, pages: List<PDPage>) {
        val catalog = doc.documentCatalog.cosObject
        val root = COSDictionary()
        root.setItem(COSName.TYPE, COSName.PAGES)
        val kids = COSArray()
        for (p in pages) {
            p.cosObject.setItem(COSName.PARENT, root)
            kids.add(p.cosObject)
        }
        root.setItem(COSName.KIDS, kids)
        root.setItem(COSName.COUNT, COSInteger.get(pages.size.toLong()))
        catalog.setItem(COSName.PAGES, root)
    }

    /** Clears a removed page so dangling references (outlines, structure) carry no content. */
    private fun neuter(page: PDPage) {
        val d = page.cosObject
        for (k in listOf(COSName.CONTENTS, COSName.RESOURCES, COSName.ANNOTS, COSName.getPDFName("Thumb"),
            COSName.getPDFName("PieceInfo"), COSName.METADATA, COSName.getPDFName("B"))) d.removeItem(k)
    }

    private fun destPage(dest: Any?, doc: PDDocument): COSDictionary? = when (dest) {
        is PDPageDestination -> dest.page?.cosObject
        else -> null
    }

    private fun scrubReferences(doc: PDDocument, removed: IdentityHashMap<COSDictionary, Boolean>) {
        val catalog = doc.documentCatalog
        // Outline items
        catalog.documentOutline?.let { outline ->
            fun pointsToRemoved(item: PDOutlineItem): Boolean = try {
                val d = item.destination
                val target = if (d is PDPageDestination) d.page?.cosObject
                else (item.action as? PDActionGoTo)?.destination.let { it as? PDPageDestination }?.page?.cosObject
                target != null && removed.containsKey(target)
            } catch (e: IOException) {
                false
            }
            fun walk(node: PDOutlineNode) {
                for (child in node.children().toList()) {
                    walk(child)
                    if (pointsToRemoved(child)) {
                        if (child.hasChildren()) {
                            child.cosObject.removeItem(COSName.DEST)
                            child.cosObject.removeItem(COSName.A)
                        } else {
                            OutlineSupport.unlink(child.cosObject)
                        }
                    }
                }
            }
            walk(outline)
            OutlineSupport.recount(outline.cosObject)
        }
        // Links on remaining pages
        for (page in doc.pages) {
            val annots = try { page.annotations } catch (e: IOException) { continue }
            val keep = annots.filter { a ->
                if (a !is PDAnnotationLink) return@filter true
                val target = try {
                    destPage(a.destination, doc) ?: destPage((a.action as? PDActionGoTo)?.destination, doc)
                } catch (e: IOException) {
                    null
                }
                target == null || !removed.containsKey(target)
            }
            if (keep.size != annots.size) page.annotations = keep
        }
        // Simple named destinations dictionary
        (catalog.cosObject.getDictionaryObject(COSName.DESTS) as? COSDictionary)?.let { dests ->
            for (k in dests.keySet().toList()) {
                val v = dests.getDictionaryObject(k)
                val arr = (v as? COSArray) ?: ((v as? COSDictionary)?.getDictionaryObject(COSName.D) as? COSArray)
                val first = arr?.getObject(0) as? COSDictionary
                if (first != null && removed.containsKey(first)) dests.removeItem(k)
            }
        }
        // Open action
        val open = catalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION)
        val openArr = (open as? COSArray) ?: ((open as? COSDictionary)?.getDictionaryObject(COSName.D) as? COSArray)
        val openPage = openArr?.getObject(0) as? COSDictionary
        if (openPage != null && removed.containsKey(openPage)) catalog.cosObject.removeItem(COSName.OPEN_ACTION)
    }
}

/** Low-level outline tree editing (PDFBox has no remove API). */
internal object OutlineSupport {
    private val FIRST = COSName.FIRST
    private val LAST = COSName.LAST
    private val NEXT = COSName.NEXT
    private val PREV = COSName.PREV

    fun unlink(item: COSDictionary) {
        val parent = item.getDictionaryObject(COSName.PARENT) as? COSDictionary
        val prev = item.getDictionaryObject(PREV) as? COSDictionary
        val next = item.getDictionaryObject(NEXT) as? COSDictionary
        if (prev != null) {
            if (next != null) prev.setItem(NEXT, next) else prev.removeItem(NEXT)
        } else if (parent != null) {
            if (next != null) parent.setItem(FIRST, next) else parent.removeItem(FIRST)
        }
        if (next != null) {
            if (prev != null) next.setItem(PREV, prev) else next.removeItem(PREV)
        } else if (parent != null) {
            if (prev != null) parent.setItem(LAST, prev) else parent.removeItem(LAST)
        }
        item.removeItem(PREV)
        item.removeItem(NEXT)
        item.removeItem(COSName.PARENT)
    }

    /** Recomputes /Count values for the whole subtree; returns the number of visible descendants. */
    fun recount(node: COSDictionary, depth: Int = 0): Int {
        if (depth > 64) return 0
        var visible = 0
        var total = 0
        var child = node.getDictionaryObject(FIRST) as? COSDictionary
        var guard = 0
        while (child != null && guard++ < 100000) {
            val childVisible = recount(child, depth + 1)
            val childOpen = (child.getInt(COSName.COUNT, 0)) > 0
            total += 1
            visible += 1 + if (childOpen) childVisible else 0
            child = child.getDictionaryObject(NEXT) as? COSDictionary
        }
        val isRoot = depth == 0
        val wasOpen = isRoot || node.getInt(COSName.COUNT, 0) > 0
        if (total == 0) {
            node.removeItem(COSName.COUNT)
        } else {
            node.setInt(COSName.COUNT, if (wasOpen) visible else -total)
        }
        return visible
    }
}
