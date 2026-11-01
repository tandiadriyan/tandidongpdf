package com.tandidong.pdf.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.MarkupKind
import com.tandidong.pdf.core.Pt
import com.tandidong.pdf.core.ShapeKind
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private fun FRect.tl() = Offset(x0, y0)
private fun FRect.sz() = Size(width, height)

private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

/** Draws editor overlays for [page]; the canvas is already in page display points. */
fun DrawScope.drawOverlay(c: DocumentController, page: Int) {
    val e = c.editor
    val px = 1f / max(0.01f, c.viewer.scale)

    if (e.formFields.isNotEmpty() && (e.category == null || e.category == ToolCategory.FILL_SIGN)) {
        for (f in e.formFields) {
            if (f.readOnly) continue
            for (w in f.widgets) {
                if (w.pageIndex != page) continue
                drawRect(Color(0x1F2962FF), w.displayRect.tl(), w.displayRect.sz())
                drawRect(Color(0x882962FF), w.displayRect.tl(), w.displayRect.sz(), style = Stroke(width = 1.2f * px))
            }
        }
    }

    e.searchResults.forEachIndexed { i, m ->
        if (m.pageIndex == page) {
            for (r in m.rects) drawRect(if (i == e.searchIndex) Color(0x99FF6D00) else Color(0x66FFD600), r.tl(), r.sz())
        }
    }

    e.selection?.let { sel ->
        if (sel.page == page) {
            for (r in c.selectionRects()) drawRect(Color(0x552962FF), r.tl(), r.sz())
        }
    }

    for (p in e.pending) if (p.page == page) drawPending(p, px)
    e.active?.let { if (it.page == page) drawPending(it, px) }

    for (m in e.redactions) if (m.page == page) drawRedactMark(m.rect, px)
    for (m in e.activeRedact) if (m.page == page) drawRedactMark(m.rect, px)

    e.whiteoutRect?.let { (pg, r) ->
        if (pg == page) {
            drawRect(Color.White, r.tl(), r.sz())
            drawRect(Color.Gray, r.tl(), r.sz(), style = Stroke(1.2f * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * px, 4f * px))))
        }
    }

    e.selectedAnnot?.let { a ->
        if (a.pageIndex == page) {
            val r = a.displayRect.offset(e.moveOffset.x, e.moveOffset.y).outset(3f)
            drawRect(Color(0xFF2962FF), r.tl(), r.sz(), style = Stroke(2f * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * px, 5f * px))))
            for (corner in listOf(r.tl(), Offset(r.x1, r.y0), Offset(r.x0, r.y1), Offset(r.x1, r.y1))) {
                drawCircle(Color(0xFF2962FF), radius = 4f * px, center = corner)
            }
        }
    }
}

private fun DrawScope.drawRedactMark(r: FRect, px: Float) {
    drawRect(Color(0x40D50000), r.tl(), r.sz())
    drawRect(Color(0xFFD50000), r.tl(), r.sz(), style = Stroke(1.5f * px))
}

private fun smoothPath(points: List<Pt>, map: (Pt) -> Offset = { Offset(it.x, it.y) }): Path {
    val path = Path()
    if (points.isEmpty()) return path
    val first = map(points[0])
    path.moveTo(first.x, first.y)
    if (points.size == 1) {
        path.lineTo(first.x + 0.01f, first.y)
        return path
    }
    if (points.size == 2) {
        val b = map(points[1])
        path.lineTo(b.x, b.y)
        return path
    }
    for (i in 1 until points.size - 1) {
        val ctrl = map(points[i])
        val next = map(points[i + 1])
        path.quadraticBezierTo(ctrl.x, ctrl.y, (ctrl.x + next.x) / 2f, (ctrl.y + next.y) / 2f)
    }
    val last = map(points.last())
    path.lineTo(last.x, last.y)
    return path
}

private fun DrawScope.drawPending(p: PendingItem, px: Float) {
    when (p) {
        is PendingItem.Ink -> {
            val color = Color(p.argb).copy(alpha = p.opacity)
            for (s in p.strokes) {
                drawPath(smoothPath(s), color, style = Stroke(width = p.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        is PendingItem.Shape -> {
            val color = Color(p.argb)
            val stroke = Stroke(width = p.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val r = FRect.of(p.start, p.end)
            when (p.kind) {
                ShapeKind.RECTANGLE -> {
                    p.fill?.let { drawRect(Color(it), r.tl(), r.sz()) }
                    drawRect(color, r.tl(), r.sz(), style = stroke)
                }
                ShapeKind.ELLIPSE -> {
                    p.fill?.let { drawOval(Color(it), r.tl(), r.sz()) }
                    drawOval(color, r.tl(), r.sz(), style = stroke)
                }
                ShapeKind.LINE, ShapeKind.ARROW -> {
                    drawLine(color, Offset(p.start.x, p.start.y), Offset(p.end.x, p.end.y), strokeWidth = p.width, cap = StrokeCap.Round)
                    if (p.kind == ShapeKind.ARROW) {
                        val dx = p.end.x - p.start.x
                        val dy = p.end.y - p.start.y
                        val len = max(0.001f, hypot(dx, dy))
                        val ux = dx / len
                        val uy = dy / len
                        val head = max(8f, p.width * 4f)
                        val left = Offset(p.end.x - ux * head - uy * head * 0.5f, p.end.y - uy * head + ux * head * 0.5f)
                        val right = Offset(p.end.x - ux * head + uy * head * 0.5f, p.end.y - uy * head - ux * head * 0.5f)
                        drawLine(color, Offset(p.end.x, p.end.y), left, strokeWidth = p.width, cap = StrokeCap.Round)
                        drawLine(color, Offset(p.end.x, p.end.y), right, strokeWidth = p.width, cap = StrokeCap.Round)
                    }
                }
            }
        }
        is PendingItem.Markup -> {
            val color = Color(p.argb)
            for (r in p.rects) {
                when (p.kind) {
                    MarkupKind.HIGHLIGHT -> drawRect(color.copy(alpha = 0.5f), r.tl(), r.sz(), blendMode = BlendMode.Multiply)
                    MarkupKind.UNDERLINE -> {
                        val y = r.y1 - r.height * 0.1f
                        drawLine(color, Offset(r.x0, y), Offset(r.x1, y), strokeWidth = max(0.6f, r.height / 14f))
                    }
                    MarkupKind.STRIKEOUT -> {
                        val y = r.y1 - r.height * 0.42f
                        drawLine(color, Offset(r.x0, y), Offset(r.x1, y), strokeWidth = max(0.6f, r.height / 14f))
                    }
                    MarkupKind.SQUIGGLY -> {
                        val amp = max(0.8f, r.height / 12f)
                        val step = amp * 1.25f
                        val path = Path()
                        var x = r.x0
                        var up = false
                        val base = r.y1 - r.height * 0.06f
                        path.moveTo(x, base)
                        while (x < r.x1) {
                            x = min(r.x1, x + step)
                            up = !up
                            path.lineTo(x, if (up) base - amp else base)
                        }
                        drawPath(path, color, style = Stroke(max(0.5f, r.height / 18f)))
                    }
                }
            }
        }
        is PendingItem.Note -> {
            val r = p.bounds()
            drawRoundRect(Color(p.argb), r.tl(), r.sz(), cornerRadius = CornerRadius(3f, 3f))
            drawRoundRect(Color(0xFF5D4037), r.tl(), r.sz(), cornerRadius = CornerRadius(3f, 3f), style = Stroke(0.8f))
            for (i in 0 until 3) {
                val y = r.y0 + r.height * (0.3f + i * 0.18f)
                drawLine(Color(0xFF5D4037), Offset(r.x0 + 4f, y), Offset(r.x1 - 4f, y), strokeWidth = 1f)
            }
        }
        is PendingItem.TextBox -> {
            val r = p.rect
            if (p.border) drawRect(Color(p.argb), r.tl(), r.sz(), style = Stroke(1f))
            else drawRect(Color(0x552962FF), r.tl(), r.sz(), style = Stroke(1f * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * px, 3f * px))))
            textPaint.color = p.argb
            textPaint.textSize = p.size
            val pad = if (p.border) 6f else 2f
            p.text.split('\n').forEachIndexed { i, line ->
                drawContext.canvas.nativeCanvas.drawText(line, r.x0 + pad, r.y0 + pad + p.size * 0.86f + i * p.size * 1.18f, textPaint)
            }
        }
        is PendingItem.Stamp -> {
            val r = p.rect
            val color = Color(p.preset.argb)
            val lw = max(1.5f, min(r.width, r.height) / 18f)
            drawRoundRect(color, Offset(r.x0 + lw, r.y0 + lw), Size(r.width - 2 * lw, r.height - 2 * lw), cornerRadius = CornerRadius(8f, 8f), style = Stroke(lw))
            boldPaint.color = p.preset.argb
            var size = r.height * 0.4f
            boldPaint.textSize = size
            val w = boldPaint.measureText(p.preset.label)
            if (w > r.width * 0.86f) {
                size *= r.width * 0.86f / w
                boldPaint.textSize = size
            }
            val tw = boldPaint.measureText(p.preset.label)
            drawContext.canvas.nativeCanvas.drawText(p.preset.label, r.x0 + (r.width - tw) / 2f, r.y0 + r.height * 0.55f + size * 0.2f, boldPaint)
        }
        is PendingItem.Signature -> {
            val r = p.rect
            val color = Color(p.argb)
            for (s in p.strokes) {
                drawPath(
                    smoothPath(s) { Offset(r.x0 + it.x * r.width, r.y0 + it.y * r.height) },
                    color, style = Stroke(width = p.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            drawRect(Color(0x332962FF), r.tl(), r.sz(), style = Stroke(1f * px))
        }
        is PendingItem.Image -> {
            val r = p.rect
            val img = p.bitmap.asImageBitmap()
            withTransform({
                translate(r.x0, r.y0)
                scale(r.width / img.width, r.height / img.height, Offset.Zero)
            }) {
                drawImage(img)
            }
        }
    }
}
