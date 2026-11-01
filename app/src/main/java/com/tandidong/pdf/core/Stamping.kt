package com.tandidong.pdf.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class Anchor { TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

data class TextWatermark(
    val text: String,
    val fontSize: Float = 60f,
    val argb: Int = 0xFFB0B0B0.toInt(),
    val opacity: Float = 0.35f,
    val rotation: Float = 45f,
    val anchor: Anchor = Anchor.CENTER,
    val behindContent: Boolean = false,
    val tiled: Boolean = false,
    val bold: Boolean = true,
)

data class ImageWatermark(
    val widthFraction: Float = 0.5f,
    val opacity: Float = 0.3f,
    val rotation: Float = 0f,
    val anchor: Anchor = Anchor.CENTER,
    val behindContent: Boolean = false,
)

/**
 * Header/footer templates. Supported tokens: {page}, {pages}, {date}, {file}, {bates}.
 */
data class HeaderFooter(
    val topLeft: String = "", val topCenter: String = "", val topRight: String = "",
    val bottomLeft: String = "", val bottomCenter: String = "", val bottomRight: String = "",
    val fontSize: Float = 10f,
    val argb: Int = 0xFF000000.toInt(),
    val margin: Float = 28f,
    val startNumber: Int = 1,
    val fileName: String = "",
    val batesPrefix: String = "",
    val batesSuffix: String = "",
    val batesStart: Int = 1,
    val batesDigits: Int = 6,
    val datePattern: String = "yyyy-MM-dd",
)

/** Watermarks, headers/footers, page numbers and Bates numbering. */
class Stamper(private val fonts: FontSelector) {

    fun textWatermark(doc: PDDocument, pages: List<Int>, wm: TextWatermark) {
        if (wm.text.isBlank()) return
        val (font, text) = fonts.fontFor(doc, wm.text, wm.bold)
        val textWidth = FontSelector.width(font, text, wm.fontSize)
        for (i in pages) {
            if (i !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(i)
            val g = PageGeometry.of(page)
            val mode = if (wm.behindContent) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
            PDPageContentStream(doc, page, mode, true, true).use { cs ->
                cs.saveGraphicsState()
                cs.transform(g.displayUpMatrix())
                setAlpha(cs, wm.opacity)
                cs.setNonStrokingColor(Redactor.red(wm.argb), Redactor.green(wm.argb), Redactor.blue(wm.argb))
                val centers = if (wm.tiled) {
                    val stepX = textWidth * 0.9f + wm.fontSize * 2f
                    val stepY = wm.fontSize * 4f
                    val list = ArrayList<Pt>()
                    var y = stepY / 2f
                    var row = 0
                    while (y < g.displayHeight + stepY) {
                        var x = if (row % 2 == 0) 0f else stepX / 2f
                        while (x < g.displayWidth + stepX) { list.add(Pt(x, y)); x += stepX }
                        y += stepY; row++
                    }
                    list
                } else listOf(anchorPoint(wm.anchor, g.displayWidth, g.displayHeight, textWidth, wm.fontSize, 36f))
                val rad = Math.toRadians(wm.rotation.toDouble())
                for (c in centers) {
                    cs.beginText()
                    cs.setFont(font, wm.fontSize)
                    val m = Matrix(cos(rad).toFloat(), sin(rad).toFloat(), -sin(rad).toFloat(), cos(rad).toFloat(), c.x, c.y)
                    m.concatenate(Matrix.getTranslateInstance(-textWidth / 2f, -wm.fontSize * 0.35f))
                    cs.setTextMatrix(m)
                    cs.showText(text)
                    cs.endText()
                }
                cs.restoreGraphicsState()
            }
        }
    }

    fun imageWatermark(doc: PDDocument, pages: List<Int>, image: PDImageXObject, wm: ImageWatermark) {
        for (i in pages) {
            if (i !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(i)
            val g = PageGeometry.of(page)
            val w = g.displayWidth * wm.widthFraction.coerceIn(0.05f, 1f)
            val h = w * image.height / image.width.coerceAtLeast(1)
            val c = anchorPoint(wm.anchor, g.displayWidth, g.displayHeight, w, h, 36f)
            val mode = if (wm.behindContent) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
            PDPageContentStream(doc, page, mode, true, true).use { cs ->
                cs.saveGraphicsState()
                cs.transform(g.displayUpMatrix())
                setAlpha(cs, wm.opacity)
                val rad = Math.toRadians(wm.rotation.toDouble())
                val m = Matrix(cos(rad).toFloat(), sin(rad).toFloat(), -sin(rad).toFloat(), cos(rad).toFloat(), c.x, c.y)
                m.concatenate(Matrix(w, 0f, 0f, h, -w / 2f, -h / 2f))
                cs.drawImage(image, m)
                cs.restoreGraphicsState()
            }
        }
    }

    fun headerFooter(doc: PDDocument, pages: List<Int>, hf: HeaderFooter) {
        val total = doc.numberOfPages
        val date = SimpleDateFormat(hf.datePattern, Locale.getDefault()).format(Date())
        for ((ordinal, i) in pages.withIndex()) {
            if (i !in 0 until total) continue
            val page = doc.getPage(i)
            val g = PageGeometry.of(page)
            val number = hf.startNumber + i
            val bates = hf.batesPrefix + (hf.batesStart + ordinal).toString().padStart(hf.batesDigits, '0') + hf.batesSuffix
            fun fill(t: String) = t.replace("{page}", number.toString())
                .replace("{pages}", (total + hf.startNumber - 1).toString())
                .replace("{date}", date).replace("{file}", hf.fileName).replace("{bates}", bates)
            val slots = listOf(
                Anchor.TOP_LEFT to hf.topLeft, Anchor.TOP_CENTER to hf.topCenter, Anchor.TOP_RIGHT to hf.topRight,
                Anchor.BOTTOM_LEFT to hf.bottomLeft, Anchor.BOTTOM_CENTER to hf.bottomCenter, Anchor.BOTTOM_RIGHT to hf.bottomRight,
            ).filter { it.second.isNotBlank() }
            if (slots.isEmpty()) continue
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.saveGraphicsState()
                cs.transform(g.displayUpMatrix())
                cs.setNonStrokingColor(Redactor.red(hf.argb), Redactor.green(hf.argb), Redactor.blue(hf.argb))
                for ((anchor, template) in slots) {
                    val (font, text) = fonts.fontFor(doc, fill(template))
                    val tw = FontSelector.width(font, text, hf.fontSize)
                    val x = when (anchor) {
                        Anchor.TOP_LEFT, Anchor.BOTTOM_LEFT -> hf.margin
                        Anchor.TOP_RIGHT, Anchor.BOTTOM_RIGHT -> g.displayWidth - hf.margin - tw
                        else -> (g.displayWidth - tw) / 2f
                    }
                    val y = if (anchor.name.startsWith("TOP")) g.displayHeight - hf.margin - hf.fontSize * 0.8f else hf.margin
                    cs.beginText()
                    cs.setFont(font, hf.fontSize)
                    cs.newLineAtOffset(x, y)
                    cs.showText(text)
                    cs.endText()
                }
                cs.restoreGraphicsState()
            }
        }
    }

    private fun setAlpha(cs: PDPageContentStream, opacity: Float) {
        val gs = PDExtendedGraphicsState()
        gs.nonStrokingAlphaConstant = opacity.coerceIn(0.02f, 1f)
        gs.strokingAlphaConstant = opacity.coerceIn(0.02f, 1f)
        cs.setGraphicsStateParameters(gs)
    }

    /** Center point (display-up coordinates) for an item of size w x h at [anchor]. */
    private fun anchorPoint(anchor: Anchor, pw: Float, ph: Float, w: Float, h: Float, margin: Float): Pt {
        val left = margin + w / 2f
        val right = pw - margin - w / 2f
        val top = ph - margin - h / 2f
        val bottom = margin + h / 2f
        return when (anchor) {
            Anchor.TOP_LEFT -> Pt(left, top)
            Anchor.TOP_CENTER -> Pt(pw / 2f, top)
            Anchor.TOP_RIGHT -> Pt(right, top)
            Anchor.CENTER -> Pt(pw / 2f, ph / 2f)
            Anchor.BOTTOM_LEFT -> Pt(left, bottom)
            Anchor.BOTTOM_CENTER -> Pt(pw / 2f, bottom)
            Anchor.BOTTOM_RIGHT -> Pt(right, bottom)
        }
    }
}
