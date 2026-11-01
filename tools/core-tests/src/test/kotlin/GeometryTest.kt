import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.PageGeometry
import com.tandidong.pdf.core.Pt
import com.tandidong.pdf.core.inverseOrNull
import com.tandidong.pdf.core.mapPoint
import org.apache.pdfbox.util.Matrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometryTest {
    private fun assertPt(expected: Pt, actual: Pt, eps: Float = 1e-3f) {
        assertEquals(expected.x, actual.x, eps, "x of $actual")
        assertEquals(expected.y, actual.y, eps, "y of $actual")
    }

    @Test
    fun roundTripsAllRotations() {
        for (rot in listOf(0, 90, 180, 270, -90, 450)) {
            val g = PageGeometry(10f, 20f, 600f, 800f, rot)
            for (p in listOf(Pt(0f, 0f), Pt(13f, 77f), Pt(g.displayWidth, g.displayHeight), Pt(250f, 5f))) {
                assertPt(p, g.toDisplay(g.toUser(p)))
            }
        }
    }

    @Test
    fun cornersMapAsExpected() {
        // Unrotated: display top-left is user (llx, ury).
        val g0 = PageGeometry(10f, 20f, 600f, 800f, 0)
        assertPt(Pt(10f, 820f), g0.toUser(0f, 0f))
        assertPt(Pt(610f, 20f), g0.toUser(600f, 800f))
        // Rotate 90 clockwise: display is 800 wide, 600 tall; user bottom-left appears top-left.
        val g90 = PageGeometry(10f, 20f, 600f, 800f, 90)
        assertEquals(800f, g90.displayWidth)
        assertEquals(600f, g90.displayHeight)
        assertPt(Pt(10f, 20f), g90.toUser(0f, 0f))
        assertPt(Pt(610f, 820f), g90.toUser(800f, 600f))
        // Rotate 270: user top-right appears top-left.
        val g270 = PageGeometry(0f, 0f, 600f, 800f, 270)
        assertPt(Pt(600f, 800f), g270.toUser(0f, 0f))
    }

    @Test
    fun displayUpMatrixMapsOriginAndAxes() {
        for (rot in listOf(0, 90, 180, 270)) {
            val g = PageGeometry(5f, 7f, 300f, 500f, rot)
            val m = g.displayUpMatrix()
            // local (lx, ly) y-up == display (lx, H - ly)
            for (p in listOf(Pt(0f, 0f), Pt(10f, 20f), Pt(g.displayWidth, g.displayHeight))) {
                val expected = g.toUser(p.x, g.displayHeight - p.y)
                assertPt(expected, m.mapPoint(p))
            }
            val d = g.displayDownMatrix()
            for (p in listOf(Pt(0f, 0f), Pt(10f, 20f))) assertPt(g.toUser(p), d.mapPoint(p))
        }
    }

    @Test
    fun rectMinusProducesDisjointPieces() {
        val r = FRect(0f, 0f, 100f, 100f)
        val pieces = r.minus(FRect(20f, 30f, 60f, 70f))
        assertEquals(4, pieces.size)
        val area = pieces.sumOf { it.area.toDouble() }
        assertEquals(100.0 * 100 - 40.0 * 40, area, 1e-3)
        for (i in pieces.indices) for (j in i + 1 until pieces.size) assertTrue(!pieces[i].intersects(pieces[j]))
    }

    @Test
    fun matrixInverse() {
        val m = Matrix(2f, 0.5f, -0.3f, 1.5f, 10f, -4f)
        val inv = m.inverseOrNull()!!
        val p = Pt(3f, 7f)
        assertPt(p, inv.mapPoint(m.mapPoint(p)))
    }
}
