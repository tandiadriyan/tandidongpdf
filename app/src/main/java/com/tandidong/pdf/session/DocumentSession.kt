package com.tandidong.pdf.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tandidong.pdf.core.PageGeometry
import com.tandidong.pdf.core.PageText
import com.tandidong.pdf.core.PdfIO
import com.tandidong.pdf.core.Permissions
import com.tandidong.pdf.core.Security
import com.tandidong.pdf.core.TextExtractor
import com.tandidong.pdf.core.TextMatch
import com.tandidong.pdf.data.FileUtil
import com.tandidong.pdf.render.PageLink
import com.tandidong.pdf.render.PageRenderer
import com.tandidong.pdf.render.RendererFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * An open document. Every edit produces a new immutable version file in [workDir], which gives
 * reliable multi-level undo/redo for every kind of operation. The original file is only touched
 * when the user saves.
 */
class DocumentSession(
    private val context: Context,
    val id: String,
    val sourceUri: Uri?,
    displayName: String,
    private val workDir: File,
    firstVersion: File,
    /** Password of the original file; re-applied on save while [keepProtection] is true. */
    val originalPassword: String?,
    /** True when the file's owner restricted editing and no owner password was supplied. */
    val restricted: Boolean,
    /** The untouched encrypted original, exported as-is for restricted documents. */
    private val originalFile: File?,
) {
    private val versions = ArrayList<File>().apply { add(firstVersion) }
    private var index = 0
    private var savedFile: File? = firstVersion
    private var counter = 0

    var displayName by mutableStateOf(displayName)
    var version by mutableIntStateOf(0)
        private set
    @Volatile
    var currentFile: File = firstVersion
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var dirty by mutableStateOf(false)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableFloatStateOf(-1f)
    /** Password protection applied whenever the document leaves the app (save, share, print). */
    var protection by mutableStateOf(
        originalPassword?.takeIf { it.isNotEmpty() }?.let { ProtectionSpec(it, it, Permissions()) }
    )
    var geometries by mutableStateOf<List<PageGeometry>>(emptyList())
        private set
    var lastSavedUri by mutableStateOf(sourceUri)

    val pageCount: Int get() = geometries.size

    private val pdfExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-$id") }
    private val pdf = pdfExecutor.asCoroutineDispatcher()
    private val renderExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "render-$id") }
    private val render = renderExecutor.asCoroutineDispatcher()
    private val mutex = Mutex()

    private var reader: PDDocument? = null
    private var readerFile: File? = null
    private val textCache = HashMap<Int, PageText>()

    private var renderer: PageRenderer? = null
    private var rendererFile: File? = null

    suspend fun init() = withContext(pdf) { mutex.withLock { reloadInfoLocked() } }

    private fun openReaderLocked(): PDDocument {
        val file = currentFile
        val r = reader
        if (r != null && readerFile == file) return r
        r?.close()
        textCache.clear()
        return PdfIO.load(file).also { reader = it; readerFile = file }
    }

    private fun reloadInfoLocked() {
        val d = openReaderLocked()
        geometries = (0 until d.numberOfPages).map { PageGeometry.of(d.getPage(it)) }
    }

    /** Runs a read-only operation against the current version. */
    suspend fun <T> read(block: (PDDocument) -> T): T = withContext(pdf) { mutex.withLock { block(openReaderLocked()) } }

    suspend fun pageText(page: Int): PageText = withContext(pdf) {
        mutex.withLock {
            val d = openReaderLocked()
            textCache.getOrPut(page) { TextExtractor.extract(d, page) }
        }
    }

    suspend fun allText(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<PageText> {
        val n = pageCount
        val out = ArrayList<PageText>(n)
        for (i in 0 until n) {
            coroutineContext.ensureActive()
            out.add(pageText(i))
            onProgress(i + 1, n)
        }
        return out
    }

    suspend fun search(query: String, matchCase: Boolean, wholeWord: Boolean, onPage: (Int, Int) -> Unit = { _, _ -> }): List<TextMatch> {
        val out = ArrayList<TextMatch>()
        val n = pageCount
        for (i in 0 until n) {
            coroutineContext.ensureActive()
            out.addAll(pageText(i).find(query, matchCase, wholeWord))
            onPage(i + 1, n)
        }
        return out
    }

    /** Applies an edit, producing and activating a new version. */
    suspend fun <T> edit(label: String, block: (PDDocument) -> T): T {
        if (restricted) throw IllegalStateException("This document is protected against editing")
        busy = label
        try {
            return withContext(pdf) {
                mutex.withLock {
                    val out = File(workDir, "v${++counter}.pdf")
                    try {
                        val result = PdfIO.transform(currentFile, out) { doc -> block(doc) }
                        commitLocked(out)
                        result
                    } catch (e: Throwable) {
                        out.delete()
                        throw e
                    }
                }
            }
        } finally {
            busy = null
            progress = -1f
        }
    }

    /** Activates an externally produced file (e.g. merged/scanned output) as the next version. */
    suspend fun adopt(file: File, label: String) {
        busy = label
        try {
            withContext(pdf) {
                mutex.withLock {
                    val out = File(workDir, "v${++counter}.pdf")
                    file.copyTo(out, overwrite = true)
                    commitLocked(out)
                }
            }
        } finally {
            busy = null
        }
    }

    /** Runs a long task with the busy indicator (no new version). */
    suspend fun <T> task(label: String, block: suspend () -> T): T {
        busy = label
        try {
            return block()
        } finally {
            busy = null
            progress = -1f
        }
    }

    private fun commitLocked(file: File) {
        while (versions.size > index + 1) {
            val f = versions.removeAt(versions.size - 1)
            if (f != savedFile) f.delete()
        }
        versions.add(file)
        index = versions.size - 1
        while (versions.size > MAX_HISTORY) {
            val f = versions.removeAt(0)
            index--
            if (f != savedFile) f.delete()
        }
        afterVersionChangeLocked()
    }

    private fun afterVersionChangeLocked() {
        currentFile = versions[index]
        reloadInfoLocked()
        canUndo = index > 0
        canRedo = index < versions.size - 1
        dirty = currentFile != savedFile
        version++
    }

    suspend fun undo() = withContext(pdf) {
        mutex.withLock { if (index > 0) { index--; afterVersionChangeLocked() } }
    }

    suspend fun redo() = withContext(pdf) {
        mutex.withLock { if (index < versions.size - 1) { index++; afterVersionChangeLocked() } }
    }

    // ------------------------------------------------------------------ rendering

    private fun rendererFor(file: File): PageRenderer {
        val r = renderer
        if (r != null && rendererFile == file) return r
        r?.close()
        return RendererFactory.open(context, file).also { renderer = it; rendererFile = file }
    }

    /** Renders a full page at [width] px. Returns null if the version changed meanwhile. */
    suspend fun renderPage(page: Int, width: Int, forVersion: Int, annotations: Boolean = true): Bitmap? = withContext(render) {
        if (forVersion != version) return@withContext null
        val geo = geometries.getOrNull(page) ?: return@withContext null
        val file = currentFile
        try {
            val start = android.os.SystemClock.elapsedRealtime()
            val r = rendererFor(file)
            RendererFactory.renderPage(r, page, geo, width, annotations).also {
                android.util.Log.d("TandidongRender", "page=$page width=$width ms=${android.os.SystemClock.elapsedRealtime() - start} engine=${r.javaClass.simpleName}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.e("TandidongRender", "render failed for page $page", t)
            null
        }
    }

    /**
     * Renders the region [left, top, right, bottom] (pixels) of the page drawn at fullWidth x fullHeight.
     */
    suspend fun renderTile(page: Int, fullWidth: Int, fullHeight: Int, left: Int, top: Int, right: Int, bottom: Int, forVersion: Int): Bitmap? =
        withContext(render) {
            if (forVersion != version) return@withContext null
            val w = (right - left).coerceAtLeast(1)
            val h = (bottom - top).coerceAtLeast(1)
            try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                rendererFor(currentFile).render(page, bmp, left, top, fullWidth, fullHeight, true)
                bmp
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
        }

    suspend fun links(page: Int): List<PageLink> = withContext(render) {
        val geo = geometries.getOrNull(page) ?: return@withContext emptyList()
        try { rendererFor(currentFile).links(page, geo) } catch (t: Throwable) { emptyList() }
    }

    // ------------------------------------------------------------------ saving

    /** The file to hand to the outside world (re-protected if the original was password protected). */
    suspend fun exportFile(): File = withContext(pdf) {
        mutex.withLock {
            val orig = originalFile
            if (restricted && orig != null) return@withLock orig
            val spec = protection
            if (spec != null) {
                val out = File(workDir, "export-protected.pdf")
                PdfIO.transform(currentFile, out) { Security.protect(it, spec.userPassword, spec.ownerPassword, spec.permissions) }
                out
            } else {
                currentFile
            }
        }
    }

    suspend fun saveTo(uri: Uri) {
        busy = "Saving…"
        try {
            val file = exportFile()
            withContext(pdf) { FileUtil.writeToUri(context, file, uri) }
            savedFile = currentFile
            dirty = false
            lastSavedUri = uri
        } finally {
            busy = null
        }
    }

    fun markSaved() {
        savedFile = currentFile
        dirty = false
    }

    fun close() {
        try {
            pdfExecutor.execute {
                try { reader?.close() } catch (e: Exception) { }
                reader = null
            }
            renderExecutor.execute {
                try { renderer?.close() } catch (e: Exception) { }
                renderer = null
            }
            pdfExecutor.execute { workDir.deleteRecursively() }
        } finally {
            pdfExecutor.shutdown()
            renderExecutor.shutdown()
        }
    }

    companion object {
        const val MAX_HISTORY = 25
    }
}

data class ProtectionSpec(val userPassword: String, val ownerPassword: String, val permissions: Permissions)
