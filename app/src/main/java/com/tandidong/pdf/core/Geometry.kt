package com.tandidong.pdf.core

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A 2D point. Used for both display space and PDF user space; the context defines which. */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Float) = Pt(x * s, y * s)
}

/**
 * Normalized axis-aligned rectangle (x0 <= x1, y0 <= y1).
 *
 * In display space y grows downward, so y0 is the top edge. In PDF user space y grows upward,
 * so y0 is the bottom edge. All geometry helpers are agnostic to that convention.
 */
data class FRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
    val area: Float get() = max(0f, width) * max(0f, height)
    val centerX: Float get() = (x0 + x1) / 2f
    val centerY: Float get() = (y0 + y1) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun intersects(o: FRect): Boolean = x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1

    /** Like [intersects] but treats touching edges / zero-size boxes as intersecting. */
    fun touches(o: FRect): Boolean = x0 <= o.x1 && o.x0 <= x1 && y0 <= o.y1 && o.y0 <= y1

    fun intersection(o: FRect): FRect? {
        val l = max(x0, o.x0); val t = max(y0, o.y0)
        val r = min(x1, o.x1); val b = min(y1, o.y1)
        return if (l < r && t < b) FRect(l, t, r, b) else null
    }

    fun union(o: FRect) = FRect(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))
    fun contains(x: Float, y: Float) = x in x0..x1 && y in y0..y1
    fun contains(o: FRect) = o.x0 >= x0 && o.x1 <= x1 && o.y0 >= y0 && o.y1 <= y1
    fun outset(d: Float) = FRect(x0 - d, y0 - d, x1 + d, y1 + d)
    fun outset(dx: Float, dy: Float) = FRect(x0 - dx, y0 - dy, x1 + dx, y1 + dy)
    fun offset(dx: Float, dy: Float) = FRect(x0 + dx, y0 + dy, x1 + dx, y1 + dy)
    fun scale(s: Float) = FRect(x0 * s, y0 * s, x1 * s, y1 * s)

    /** Subtracts [cut] from this rectangle, returning up to 4 non-overlapping pieces. */
    fun minus(cut: FRect): List<FRect> {
        val i = intersection(cut) ?: return listOf(this)
        val out = ArrayList<FRect>(4)
        if (i.y0 > y0) out.add(FRect(x0, y0, x1, i.y0))
        if (i.y1 < y1) out.add(FRect(x0, i.y1, x1, y1))
        if (i.x0 > x0) out.add(FRect(x0, i.y0, i.x0, i.y1))
        if (i.x1 < x1) out.add(FRect(i.x1, i.y0, x1, i.y1))
        return out
    }

    fun toPdRectangle(): PDRectangle = PDRectangle(x0, y0, width, height)

    companion object {
        fun of(a: Pt, b: Pt) = FRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        fun bounds(points: Iterable<Pt>): FRect? {
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            var any = false
            for (p in points) {
                any = true
                if (p.x < l) l = p.x; if (p.x > r) r = p.x
                if (p.y < t) t = p.y; if (p.y > b) b = p.y
            }
            return if (any) FRect(l, t, r, b) else null
        }
        fun of(r: PDRectangle) = FRect(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
        fun unionAll(rects: Iterable<FRect>): FRect? = rects.reduceOrNull { a, b -> a.union(b) }
    }
}

/** Applies an affine PDF matrix to a point without touching platform geometry classes. */
fun Matrix.mapPoint(x: Float, y: Float): Pt =
    Pt(scaleX * x + shearX * y + translateX, shearY * x + scaleY * y + translateY)

fun Matrix.mapPoint(p: Pt): Pt = mapPoint(p.x, p.y)

/** Bounding box (in the target space) of [r] transformed by this matrix. */
fun Matrix.mapRect(r: FRect): FRect = FRect.bounds(
    listOf(mapPoint(r.x0, r.y0), mapPoint(r.x1, r.y0), mapPoint(r.x0, r.y1), mapPoint(r.x1, r.y1))
)!!

/** Inverse of an affine matrix, or null when it is singular. */
fun Matrix.inverseOrNull(): Matrix? {
    val a = scaleX; val b = shearY; val c = shearX; val d = scaleY
    val e = translateX; val f = translateY
    val det = a * d - b * c
    if (abs(det) < 1e-12f) return null
    val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
    val ie = -(ia * e + ic * f); val iF = -(ib * e + id * f)
    return Matrix(ia, ib, ic, id, ie, iF)
}

/** True if the matrix only scales/translates (no rotation or skew). */
fun Matrix.isAxisAligned(eps: Float = 1e-4f): Boolean = abs(shearX) < eps && abs(shearY) < eps

/** Average linear scale factor of the matrix, used to transform line widths. */
fun Matrix.meanScale(): Float {
    val sx = kotlin.math.sqrt(scaleX * scaleX + shearY * shearY)
    val sy = kotlin.math.sqrt(shearX * shearX + scaleY * scaleY)
    return (sx + sy) / 2f
}

/**
 * Maps between a page's *display space* and PDF *user space*.
 *
 * Display space is what the user sees: the crop box after applying /Rotate, measured in points
 * with the origin at the top-left corner and y growing downward.
 */
class PageGeometry(
    val cropLlx: Float,
    val cropLly: Float,
    val cropWidth: Float,
    val cropHeight: Float,
    rotation: Int,
) {
    /** Clockwise display rotation, normalized to 0, 90, 180 or 270. */
    val rotation: Int = normalizeRotation(rotation)

    val displayWidth: Float get() = if (rotation == 90 || rotation == 270) cropHeight else cropWidth
    val displayHeight: Float get() = if (rotation == 90 || rotation == 270) cropWidth else cropHeight

    fun toUser(dx: Float, dy: Float): Pt {
        val u: Float
        val v: Float
        when (rotation) {
            90 -> { u = dy; v = cropHeight - dx }
            180 -> { u = cropWidth - dx; v = cropHeight - dy }
            270 -> { u = cropWidth - dy; v = dx }
            else -> { u = dx; v = dy }
        }
        return Pt(cropLlx + u, cropLly + cropHeight - v)
    }

    fun toUser(p: Pt) = toUser(p.x, p.y)

    fun toDisplay(x: Float, y: Float): Pt {
        val u = x - cropLlx
        val v = cropLly + cropHeight - y
        return when (rotation) {
            90 -> Pt(cropHeight - v, u)
            180 -> Pt(cropWidth - u, cropHeight - v)
            270 -> Pt(v, cropWidth - u)
            else -> Pt(u, v)
        }
    }

    fun toDisplay(p: Pt) = toDisplay(p.x, p.y)

    fun rectToUser(r: FRect): FRect = FRect.of(toUser(r.x0, r.y0), toUser(r.x1, r.y1))
    fun rectToDisplay(r: FRect): FRect = FRect.of(toDisplay(r.x0, r.y0), toDisplay(r.x1, r.y1))

    /**
     * Matrix mapping a y-up frame aligned with the display (origin at the display's bottom-left
     * corner, x to the right, y up) into user space. Text drawn after `cm` with this matrix
     * appears upright to the reader regardless of /Rotate.
     */
    fun displayUpMatrix(): Matrix {
        val o = toUser(0f, displayHeight)
        val xAxis = toUser(1f, displayHeight) - o
        val yAxis = toUser(0f, displayHeight - 1f) - o
        return Matrix(xAxis.x, xAxis.y, yAxis.x, yAxis.y, o.x, o.y)
    }

    /** Matrix mapping display coordinates (origin top-left, y down) into user space. */
    fun displayDownMatrix(): Matrix {
        val o = toUser(0f, 0f)
        val xAxis = toUser(1f, 0f) - o
        val yAxis = toUser(0f, 1f) - o
        return Matrix(xAxis.x, xAxis.y, yAxis.x, yAxis.y, o.x, o.y)
    }

    /**
     * Rotation matrix for annotation appearance streams so content drawn upright in a
     * display-aligned bounding box appears upright on the rotated page.
     */
    fun appearanceRotationMatrix(): Matrix = when (rotation) {
        90 -> Matrix(0f, 1f, -1f, 0f, 0f, 0f)
        180 -> Matrix(-1f, 0f, 0f, -1f, 0f, 0f)
        270 -> Matrix(0f, -1f, 1f, 0f, 0f, 0f)
        else -> Matrix()
    }

    companion object {
        fun normalizeRotation(r: Int): Int {
            val n = ((r % 360) + 360) % 360
            return ((n + 45) / 90 * 90) % 360
        }

        fun of(page: PDPage): PageGeometry {
            val crop = page.cropBox
            return PageGeometry(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, page.rotation)
        }
    }
}
