package com.tandidong.pdf.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Result of comparing two renderings of a page. [overlay] is ARGB, same size as the inputs. */
class PixelDiffResult(val width: Int, val height: Int, val overlay: IntArray, val changedPixels: Int, val regions: List<FRect>) {
    val changedFraction: Float get() = if (width * height == 0) 0f else changedPixels.toFloat() / (width * height)
}

/** Visual comparison of two page renderings (ARGB pixel arrays). */
object PixelDiff {
    /**
     * Pixels whose largest channel difference exceeds [threshold] are changed. The overlay shows
     * the revised page faded, with changes in red; [regions] are pixel boxes around changed areas.
     */
    fun compare(a: IntArray, aw: Int, ah: Int, b: IntArray, bw: Int, bh: Int, threshold: Int = 60, cell: Int = 12): PixelDiffResult {
        val w = max(aw, bw)
        val h = max(ah, bh)
        val overlay = IntArray(w * h)
        var changed = 0
        val cw = (w + cell - 1) / cell
        val ch = (h + cell - 1) / cell
        val hot = BooleanArray(cw * ch)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pa = if (x < aw && y < ah) a[y * aw + x] else -1 // outside = white
                val pb = if (x < bw && y < bh) b[y * bw + x] else -1
                val d = max(abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)),
                    max(abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)), abs((pa and 0xFF) - (pb and 0xFF))))
                if (d > threshold) {
                    changed++
                    overlay[y * w + x] = 0xFFE53935.toInt()
                    hot[(y / cell) * cw + (x / cell)] = true
                } else {
                    // Faded grayscale of the revised page.
                    val gray = (((pb shr 16) and 0xFF) * 3 + ((pb shr 8) and 0xFF) * 6 + (pb and 0xFF)) / 10
                    val g = 255 - (255 - gray) / 4
                    overlay[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
        }
        return PixelDiffResult(w, h, overlay, changed, regions(hot, cw, ch, cell, w, h))
    }

    /** Merges touching changed cells into bounding boxes (flood fill over the cell grid). */
    private fun regions(hot: BooleanArray, cw: Int, ch: Int, cell: Int, w: Int, h: Int): List<FRect> {
        val seen = BooleanArray(hot.size)
        val out = ArrayList<FRect>()
        val stack = ArrayDeque<Int>()
        for (start in hot.indices) {
            if (!hot[start] || seen[start]) continue
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
            stack.addLast(start); seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val cx = i % cw
                val cy = i / cw
                x0 = min(x0, cx); y0 = min(y0, cy); x1 = max(x1, cx); y1 = max(y1, cy)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue
                    val j = ny * cw + nx
                    if (hot[j] && !seen[j]) { seen[j] = true; stack.addLast(j) }
                }
            }
            out.add(FRect((x0 * cell).toFloat(), (y0 * cell).toFloat(), min(w, (x1 + 1) * cell).toFloat(), min(h, (y1 + 1) * cell).toFloat()))
        }
        return out.sortedWith(compareBy({ it.y0 }, { it.x0 }))
    }
}
