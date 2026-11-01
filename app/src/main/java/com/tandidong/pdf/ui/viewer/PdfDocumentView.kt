package com.tandidong.pdf.ui.viewer

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tandidong.pdf.session.DocumentSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Callbacks from the document view to the active tool. */
interface ViewerInput {
    /** True when a single-finger drag starting at [hit] should be handled by the tool instead of panning. */
    fun wantsDrag(hit: PageHit?): Boolean = false
    fun onDragStart(hit: PageHit) {}
    fun onDrag(hit: PageHit) {}
    fun onDragEnd() {}
    fun onDragCancel() {}
    /** Returns true if the tap was handled (otherwise the view toggles its chrome). */
    fun onTap(hit: PageHit?): Boolean = false
    /** True when taps are handled immediately (no double-tap zoom delay). */
    fun tapsAreTools(): Boolean = false
    fun onLongPress(hit: PageHit?) {}
    fun onLongPressDrag(hit: PageHit) {}
    fun onLongPressEnd() {}
    fun onToggleChrome() {}
}

private class PageBitmap(val version: Int, val width: Int, val image: ImageBitmap)
private class TileBitmap(val version: Int, val fullWidth: Int, val left: Int, val top: Int, val image: ImageBitmap)

private val INVERT = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

@Composable
fun PdfDocumentView(
    session: DocumentSession,
    state: ViewerState,
    nightMode: Boolean,
    input: ViewerInput,
    modifier: Modifier = Modifier,
    overlay: DrawScope.(page: Int) -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val version = session.version
    val geometries = session.geometries
    val pageBitmaps = remember(session) { mutableStateMapOf<Int, PageBitmap>() }
    val tiles = remember(session) { mutableStateMapOf<Int, TileBitmap>() }
    val currentInput by rememberUpdatedState(input)
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    var pendingTap by remember { mutableStateOf<Job?>(null) }

    BoxWithConstraints(modifier.clipToBounds().background(if (nightMode) Color(0xFF111111) else Color(0xFFE6E6E9))) {
        val vp = IntSize(constraints.maxWidth, constraints.maxHeight)
        LaunchedEffect(geometries, vp) { state.setup(geometries, vp, density.density) }

        // Render scheduling: base bitmaps for visible pages, then crisp tiles when zoomed in.
        LaunchedEffect(session, version) {
            snapshotFlow { Triple(state.visiblePages(), state.zoom, Offset(state.scrollX, state.scrollY)) to state.layoutVersion }
                .collectLatest { (t, _) ->
                    val (visible, zoom, _) = t
                    if (visible.isEmpty()) return@collectLatest
                    val center = state.currentPage()
                    val wanted = (listOf(center) + visible.toList() + listOf(visible.first - 1, visible.last + 1))
                        .distinct().filter { it in 0 until state.pageCount }
                    // Drop far-away bitmaps to bound memory.
                    pageBitmaps.keys.filter { it < visible.first - 4 || it > visible.last + 4 }.forEach { pageBitmaps.remove(it) }
                    tiles.keys.filter { it !in visible }.forEach { tiles.remove(it) }
                    val baseZoom = min(zoom, 1.6f)
                    for (i in wanted) {
                        val target = (((state.pageWidthPx(i) / zoom) * baseZoom) / 64f).roundToInt().coerceAtLeast(1) * 64
                        val have = pageBitmaps[i]
                        if (have != null && have.version == version && have.width >= target * 0.8f && have.width <= target * 1.3f) continue
                        val bmp = session.renderPage(i, target.coerceAtMost(3000), version) ?: continue
                        pageBitmaps[i] = PageBitmap(version, bmp.width, bmp.asImageBitmap())
                    }
                    if (zoom > baseZoom * 1.05f) {
                        delay(140) // wait for gestures to settle
                        for (i in visible) {
                            val r = state.pageRect(i)
                            val visLeft = max(0f, -r.left).toInt()
                            val visTop = max(0f, -r.top).toInt()
                            val visRight = min(r.width, state.viewport.width - r.left).toInt()
                            val visBottom = min(r.height, state.viewport.height - r.top).toInt()
                            if (visRight - visLeft < 2 || visBottom - visTop < 2) continue
                            val fullW = r.width.roundToInt()
                            val fullH = r.height.roundToInt()
                            val bmp: Bitmap = session.renderTile(i, fullW, fullH, visLeft, visTop, visRight, visBottom, version) ?: continue
                            tiles[i] = TileBitmap(version, fullW, visLeft, visTop, bmp.asImageBitmap())
                        }
                    } else {
                        tiles.clear()
                    }
                }
        }

        val decay = remember(density) { splineBasedDecay<Offset>(density) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(session) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val downHit = state.hit(down.position)
                        val toolDrag = currentInput.wantsDrag(downHit)
                        var mode = 0 // 0 pending, 1 pan, 2 tool drag, 3 multi-touch, 4 long press
                        var lastPos = down.position
                        var lockPage: Int? = downHit?.page
                        val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                        while (true) {
                            // Drawing tools never turn into long-presses: a resting finger may start a stroke.
                            val event = if (mode == 0 && !toolDrag) {
                                withTimeoutOrNull(max(1L, deadlineRemaining(deadline))) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (event == null) {
                                // Long press without movement.
                                mode = 4
                                currentInput.onLongPress(downHit)
                                continue
                            }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                when (mode) {
                                    0 -> handleTap(
                                        down.position, state, currentInput,
                                        lastTapTime, lastTapPos, slop,
                                        setLast = { t, p -> lastTapTime = t; lastTapPos = p },
                                        pending = pendingTap, setPending = { pendingTap = it },
                                        scope = scope,
                                        doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis,
                                    )
                                    1, 3 -> {
                                        val v = tracker.calculateVelocity()
                                        val vel = Offset(v.x, v.y)
                                        if (abs(vel.x) + abs(vel.y) > 300f) {
                                            flingJob = scope.launch {
                                                var last = Offset.Zero
                                                Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(vel, decay) {
                                                    val d = value - last
                                                    last = value
                                                    state.scrollBy(-d.x, -d.y)
                                                }
                                            }
                                        }
                                    }
                                    2 -> currentInput.onDragEnd()
                                    4 -> currentInput.onLongPressEnd()
                                }
                                break
                            }
                            if (pressed.size >= 2) {
                                if (mode == 2) currentInput.onDragCancel()
                                if (mode == 4) currentInput.onLongPressEnd()
                                mode = 3
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (zoom != 1f && centroid != Offset.Unspecified) state.zoomBy(zoom, centroid)
                                state.scrollBy(-pan.x, -pan.y)
                                event.changes.forEach { it.consume() }
                                tracker.resetTracking()
                                continue
                            }
                            val ch: PointerInputChange = pressed.first()
                            val pos = ch.position
                            when (mode) {
                                0 -> if ((pos - down.position).getDistance() > slop) {
                                    if (toolDrag && downHit != null) {
                                        mode = 2
                                        lockPage = downHit.page
                                        currentInput.onDragStart(downHit)
                                        state.hit(pos, lockPage)?.let { currentInput.onDrag(it) }
                                    } else {
                                        mode = 1
                                        pendingTap?.cancel()
                                        state.scrollBy(-(pos.x - lastPos.x), -(pos.y - lastPos.y))
                                    }
                                }
                                1 -> state.scrollBy(-(pos.x - lastPos.x), -(pos.y - lastPos.y))
                                // Back to one finger after a pinch: re-anchor without jumping.
                                3 -> mode = 1
                                2 -> state.hit(pos, lockPage)?.let { currentInput.onDrag(it) }
                                4 -> state.hit(pos, lockPage)?.let { currentInput.onLongPressDrag(it) }
                            }
                            tracker.addPosition(ch.uptimeMillis, pos)
                            lastPos = pos
                            if (mode != 0) ch.consume()
                        }
                    }
                }
        ) {
            val vis = state.visiblePages()
            val s = state.scale
            for (i in vis) {
                val r = state.pageRect(i)
                drawRect(Color(0x33000000), topLeft = Offset(r.left + 1.5f, r.top + 3f), size = Size(r.width, r.height))
                drawRect(if (nightMode) Color.Black else Color.White, topLeft = r.topLeft, size = r.size)
                pageBitmaps[i]?.let { pb ->
                    drawImage(
                        pb.image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(pb.image.width, pb.image.height),
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                        colorFilter = if (nightMode) INVERT else null,
                    )
                }
                tiles[i]?.let { t ->
                    if (t.version == version && abs(t.fullWidth - r.width) < 1.5f) {
                        drawImage(
                            t.image,
                            topLeft = Offset(r.left + t.left, r.top + t.top),
                            colorFilter = if (nightMode) INVERT else null,
                        )
                    }
                }
                withTransform({
                    translate(r.left, r.top)
                    scale(s, s, pivot = Offset.Zero)
                }) {
                    overlay(i)
                }
            }
        }

        FastScroller(state, Modifier.align(Alignment.CenterEnd))
    }
}

/** Pointer timestamps use the uptime clock. */
private fun deadlineRemaining(deadline: Long): Long = deadline - android.os.SystemClock.uptimeMillis()

private fun handleTap(
    pos: Offset,
    state: ViewerState,
    input: ViewerInput,
    lastTapTime: Long,
    lastTapPos: Offset,
    slop: Float,
    setLast: (Long, Offset) -> Unit,
    pending: Job?,
    setPending: (Job?) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    doubleTapTimeout: Long,
) {
    val now = android.os.SystemClock.uptimeMillis()
    val hit = state.hit(pos)
    if (input.tapsAreTools()) {
        if (!input.onTap(hit)) input.onToggleChrome()
        return
    }
    if (now - lastTapTime < doubleTapTimeout && (pos - lastTapPos).getDistance() < slop * 3) {
        pending?.cancel()
        setPending(null)
        setLast(0L, Offset.Zero)
        val target = if (state.zoom < 1.6f) 2.5f else 1f
        scope.launch {
            val start = state.zoom
            val steps = 10
            for (k in 1..steps) {
                val z = start + (target - start) * (k.toFloat() / steps)
                state.zoomTo(z, pos)
                delay(14)
            }
        }
        return
    }
    setLast(now, pos)
    setPending(scope.launch {
        delay(doubleTapTimeout)
        if (!input.onTap(hit)) input.onToggleChrome()
    })
}

/** Draggable scroll thumb for quickly moving through long documents. */
@Composable
private fun FastScroller(state: ViewerState, modifier: Modifier) {
    val vh = state.viewport.height.toFloat()
    val ch = state.contentHeight
    if (ch <= vh * 1.5f || vh <= 0f) return
    val thumbH = 56.dp
    val density = LocalDensity.current
    val thumbPx = with(density) { thumbH.toPx() }
    val track = vh - thumbPx
    val fraction = (state.scrollY / max(1f, ch - vh)).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxHeight()
            .width(28.dp)
            .pointerInput(state) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val total = max(1f, state.contentHeight - state.viewport.height)
                    state.scrollBy(0f, dragAmount * total / max(1f, track))
                }
            }
    ) {
        Box(
            Modifier
                .offset { IntOffset(0, (fraction * track).roundToInt()) }
                .padding(end = 4.dp)
                .align(Alignment.TopEnd)
                .size(width = 6.dp, height = thumbH)
                .background(Color(0x88555555), RoundedCornerShape(3.dp))
        )
    }
}
