package com.tandidong.pdf.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.PageGeometry
import com.tandidong.pdf.core.PdfIO
import com.tandidong.pdf.core.Pt
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.util.AlreadyClosedBehavior
import io.legere.pdfiumandroid.util.Config
import java.io.Closeable
import java.io.File

/** A link area on a page in display coordinates. */
data class PageLink(val rect: FRect, val targetPage: Int?, val uri: String?)

/** Renders PDF pages into bitmaps. Implementations are not thread-safe; use one render thread. */
interface PageRenderer : Closeable {
    val pageCount: Int

    /**
     * Renders the page scaled to [fullWidth] x [fullHeight] pixels into [bitmap], positioned so that
     * the page pixel (offsetX, offsetY) lands at the bitmap's top-left corner.
     */
    fun render(page: Int, bitmap: Bitmap, offsetX: Int, offsetY: Int, fullWidth: Int, fullHeight: Int, annotations: Boolean = true)

    fun links(page: Int, geometry: PageGeometry): List<PageLink> = emptyList()
}

object PdfiumSupport {
    /** Loads the native libraries once; false when the device cannot run them. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("pdfium")
            System.loadLibrary("pdfiumandroid")
            true
        } catch (t: Throwable) {
            Log.w("Tandidong", "PDFium unavailable, using fallback renderer", t)
            false
        }
    }

    private var core: PdfiumCore? = null

    @Synchronized
    fun core(context: Context): PdfiumCore =
        core ?: PdfiumCore(context.applicationContext, Config(alreadyClosedBehavior = AlreadyClosedBehavior.IGNORE)).also { core = it }
}

class PdfiumPageRenderer(context: Context, file: File, password: String?) : PageRenderer {
    private val doc: PdfDocument

    init {
        val core = PdfiumSupport.core(context)
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        doc = try {
            core.newDocument(fd, password)
        } catch (e: Exception) {
            fd.close()
            throw e
        }
    }

    override val pageCount: Int = doc.getPageCount()

    override fun render(page: Int, bitmap: Bitmap, offsetX: Int, offsetY: Int, fullWidth: Int, fullHeight: Int, annotations: Boolean) {
        doc.openPage(page).use { p ->
            p.renderPageBitmap(
                bitmap, -offsetX, -offsetY, fullWidth, fullHeight,
                renderAnnot = annotations, textMask = false,
                canvasColor = 0xFFFFFFFF.toInt(), pageBackgroundColor = 0xFFFFFFFF.toInt(),
            )
        }
    }

    override fun links(page: Int, geometry: PageGeometry): List<PageLink> = try {
        doc.openPage(page).use { p ->
            p.getPageLinks().map { l ->
                val b = l.bounds
                PageLink(geometry.rectToDisplay(FRect.of(Pt(b.left, b.top), Pt(b.right, b.bottom))), l.destPageIdx, l.uri)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    override fun close() {
        try { doc.close() } catch (e: Exception) { Log.w("Tandidong", "close failed", e) }
    }
}

/** Pure-Java fallback renderer (slower, used only when PDFium cannot load). */
class PdfBoxPageRenderer(file: File, password: String?) : PageRenderer {
    private val doc: PDDocument = PdfIO.load(file, password)
    private val renderer = PDFRenderer(doc)

    override val pageCount: Int = doc.numberOfPages

    override fun render(page: Int, bitmap: Bitmap, offsetX: Int, offsetY: Int, fullWidth: Int, fullHeight: Int, annotations: Boolean) {
        val g = PageGeometry.of(doc.getPage(page))
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
        renderer.setAnnotationsFilter { annotations }
        renderer.renderPageToGraphics(page, Paint(Paint.ANTI_ALIAS_FLAG), canvas, fullWidth / g.displayWidth, fullHeight / g.displayHeight)
    }

    override fun close() = doc.close()
}

object RendererFactory {
    fun open(context: Context, file: File, password: String? = null): PageRenderer {
        if (PdfiumSupport.available) {
            try {
                return PdfiumPageRenderer(context, file, password)
            } catch (e: Exception) {
                Log.w("Tandidong", "PDFium could not open file, falling back", e)
            }
        }
        return PdfBoxPageRenderer(file, password)
    }

    /** Renders a whole page to a new bitmap of the given width (used for thumbnails/exports). */
    fun renderPage(renderer: PageRenderer, page: Int, geometry: PageGeometry, width: Int, annotations: Boolean = true): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = (w * geometry.displayHeight / geometry.displayWidth).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        renderer.render(page, bmp, 0, 0, w, h, annotations)
        return bmp
    }
}
