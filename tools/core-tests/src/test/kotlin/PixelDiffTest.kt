import com.tandidong.pdf.core.PixelDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixelDiffTest {
    private fun white(w: Int, h: Int) = IntArray(w * h) { -1 }

    @Test
    fun findsChangedRegions() {
        val w = 100
        val h = 80
        val a = white(w, h)
        val b = white(w, h)
        // Two separate changes in the revised image, plus a barely different pixel (ignored).
        for (y in 10 until 20) for (x in 10 until 30) b[y * w + x] = 0xFF000000.toInt()
        for (y in 60 until 70) for (x in 70 until 90) b[y * w + x] = 0xFF2040FF.toInt()
        b[5 * w + 5] = 0xFFF0F0F0.toInt()
        val r = PixelDiff.compare(a, w, h, b, w, h)
        assertEquals(400, r.changedPixels)
        assertEquals(2, r.regions.size, r.regions.toString())
        val first = r.regions[0]
        assertTrue(first.x0 <= 10f && first.x1 >= 30f && first.y0 <= 10f && first.y1 >= 20f, first.toString())
        assertEquals(0xFFE53935.toInt(), r.overlay[15 * w + 15])
        assertTrue(r.changedFraction in 0.04f..0.06f)
    }

    @Test
    fun identicalImagesHaveNoChanges() {
        val a = white(40, 40)
        val r = PixelDiff.compare(a, 40, 40, a.copyOf(), 40, 40)
        assertEquals(0, r.changedPixels)
        assertTrue(r.regions.isEmpty())
    }

    @Test
    fun differentHeightsCountMissingAreaAsChange() {
        val a = white(10, 10)
        val b = IntArray(10 * 12) { 0xFF000000.toInt() }
        val r = PixelDiff.compare(a, 10, 10, b, 10, 12)
        assertEquals(12, r.height)
        assertEquals(10 * 12, r.changedPixels, "every pixel differs (black vs white, or missing in the original)")
    }
}
