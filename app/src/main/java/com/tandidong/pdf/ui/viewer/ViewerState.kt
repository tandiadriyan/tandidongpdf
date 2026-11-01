package com.tandidong.pdf.ui.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.PageGeometry
import com.tandidong.pdf.core.Pt
import kotlin.math.max
import kotlin.math.min

/** A touch position resolved to a page, in the page's display coordinates (points). */
data class PageHit(val page: Int, val pt: Pt, val inside: Boolean)

/**
 * Continuous vertical layout of pages with zoom and 2D scrolling. Page geometry is in points;
 * screen values are pixels. Zoom 1 fits the widest page to the viewport width.
 */
@Stable
class ViewerState {
    var zoom by mutableFloatStateOf(1f)
        private set
    var scrollX by mutableFloatStateOf(0f)
        private set
    var scrollY by mutableFloatStateOf(0f)
        private set
    var viewport by mutableStateOf(IntSize.Zero)
        private set
    var layoutVersion by mutableStateOf(0)
        private set

    private var geometries: List<PageGeometry> = emptyList()
    private var tops = FloatArray(0)
    private var widths = FloatArray(0)
    private var heights = FloatArray(0)
    private var contentHeight0 = 0f
    private var marginPx = 24f
    private var gapPx = 16f
    var baseScale = 1f
        private set

    val minZoom = 0.35f
    val maxZoom = 10f

    val pageCount: Int get() = geometries.size
    val scale: Float get() = baseScale * zoom
    val contentWidth: Float get() = viewport.width * zoom
    val contentHeight: Float get() = contentHeight0 * zoom

    fun setup(geos: List<PageGeometry>, vp: IntSize, density: Float) {
        if (vp.width <= 0 || vp.height <= 0) return
        val sameGeos = geos.size == geometries.size && geos.indices.all {
            geos[it].displayWidth == geometries[it].displayWidth && geos[it].displayHeight == geometries[it].displayHeight
        }
        if (sameGeos && vp == viewport) {
            geometries = geos
            return
        }
        // Keep the reading position (page + fraction) across relayouts.
        val anchorPage = if (geometries.isNotEmpty()) currentPage() else 0
        val anchorFraction = if (geometries.isNotEmpty() && anchorPage < tops.size) {
            ((scrollY / zoom - tops[anchorPage]) / max(1f, heights[anchorPage]))
        } else 0f
        geometries = geos
        viewport = vp
        marginPx = 12f * density
        gapPx = 10f * density
        val maxW = geos.maxOfOrNull { it.displayWidth } ?: 1f
        baseScale = (vp.width - 2 * marginPx) / max(1f, maxW)
        tops = FloatArray(geos.size)
        widths = FloatArray(geos.size)
        heights = FloatArray(geos.size)
        var y = marginPx
        for ((i, g) in geos.withIndex()) {
            widths[i] = g.displayWidth * baseScale
            heights[i] = g.displayHeight * baseScale
            tops[i] = y
            y += heights[i] + gapPx
        }
        contentHeight0 = if (geos.isEmpty()) 0f else y - gapPx + marginPx
        if (geos.isNotEmpty()) {
            val p = anchorPage.coerceIn(0, geos.size - 1)
            scrollY = (tops[p] + anchorFraction * heights[p]) * zoom
        }
        clamp()
        layoutVersion++
    }

    fun pageWidthPx(i: Int): Float = widths.getOrElse(i) { 0f } * zoom
    fun pageHeightPx(i: Int): Float = heights.getOrElse(i) { 0f } * zoom

    private fun offsetX(): Float = if (contentWidth <= viewport.width) (viewport.width - contentWidth) / 2f else -scrollX

    /** Page rectangle in screen pixels. */
    fun pageRect(i: Int): Rect {
        val w = pageWidthPx(i)
        val h = pageHeightPx(i)
        val left = offsetX() + (contentWidth - w) / 2f
        val top = tops.getOrElse(i) { 0f } * zoom - scrollY + extraTop()
        return Rect(left, top, left + w, top + h)
    }

    /** Vertically centers short documents. */
    private fun extraTop(): Float = if (contentHeight < viewport.height) (viewport.height - contentHeight) / 2f else 0f

    fun visiblePages(): IntRange {
        if (geometries.isEmpty()) return IntRange.EMPTY
        var first = -1
        var last = -1
        val vh = viewport.height.toFloat()
        for (i in geometries.indices) {
            val r = pageRect(i)
            if (r.bottom >= 0f && r.top <= vh) {
                if (first < 0) first = i
                last = i
            } else if (first >= 0) break
        }
        return if (first < 0) IntRange.EMPTY else first..last
    }

    fun currentPage(): Int {
        if (geometries.isEmpty()) return 0
        val center = viewport.height / 3f
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in visiblePages()) {
            val r = pageRect(i)
            val d = if (center in r.top..r.bottom) 0f else min(kotlin.math.abs(r.top - center), kotlin.math.abs(r.bottom - center))
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun clamp() {
        scrollX = scrollX.coerceIn(0f, max(0f, contentWidth - viewport.width))
        scrollY = scrollY.coerceIn(0f, max(0f, contentHeight - viewport.height))
    }

    /** Scrolls by a pixel delta; returns the consumed delta. */
    fun scrollBy(dx: Float, dy: Float): Offset {
        val ox = scrollX
        val oy = scrollY
        scrollX += dx
        scrollY += dy
        clamp()
        return Offset(scrollX - ox, scrollY - oy)
    }

    fun zoomBy(factor: Float, focus: Offset) = zoomTo(zoom * factor, focus)

    fun zoomTo(target: Float, focus: Offset) {
        val old = zoom
        val new = target.coerceIn(minZoom, maxZoom)
        if (new == old || geometries.isEmpty()) return
        val contentX = if (contentWidth >= viewport.width) scrollX + focus.x else focus.x - (viewport.width - contentWidth) / 2f
        val contentY = scrollY + focus.y - extraTop()
        val ratio = new / old
        zoom = new
        scrollX = if (contentWidth >= viewport.width) contentX * ratio - focus.x else 0f
        scrollY = contentY * ratio - focus.y + extraTop()
        clamp()
    }

    fun scrollToPage(i: Int) {
        if (i !in geometries.indices) return
        scrollY = tops[i] * zoom - marginPx * 0.5f
        clamp()
    }

    /** Ensures [r] (page display points) is visible, centering it if necessary. */
    fun reveal(page: Int, r: FRect) {
        if (page !in geometries.indices) return
        val pr = pageRect(page)
        val top = pr.top + r.y0 * scale
        val bottom = pr.top + r.y1 * scale
        if (top < viewport.height * 0.15f || bottom > viewport.height * 0.85f) {
            scrollY += (top + bottom) / 2f - viewport.height / 2f
        }
        val left = pr.left + r.x0 * scale
        val right = pr.left + r.x1 * scale
        if (left < 0f || right > viewport.width) scrollX += (left + right) / 2f - viewport.width / 2f
        clamp()
    }

    /** Resolves a screen point; for drags, [lockPage] keeps coordinates relative to the start page. */
    fun hit(screen: Offset, lockPage: Int? = null): PageHit? {
        if (geometries.isEmpty()) return null
        val s = scale
        if (lockPage != null && lockPage in geometries.indices) {
            val r = pageRect(lockPage)
            return PageHit(lockPage, Pt((screen.x - r.left) / s, (screen.y - r.top) / s), r.contains(screen))
        }
        var best: PageHit? = null
        var bestD = Float.MAX_VALUE
        for (i in visiblePages()) {
            val r = pageRect(i)
            val p = PageHit(i, Pt((screen.x - r.left) / s, (screen.y - r.top) / s), r.contains(screen))
            if (p.inside) return p
            val d = when {
                screen.y < r.top -> r.top - screen.y
                screen.y > r.bottom -> screen.y - r.bottom
                else -> 0f
            }
            if (d < bestD) { bestD = d; best = p }
        }
        return best
    }

    fun toScreen(page: Int, p: Pt): Offset {
        val r = pageRect(page)
        return Offset(r.left + p.x * scale, r.top + p.y * scale)
    }
}
