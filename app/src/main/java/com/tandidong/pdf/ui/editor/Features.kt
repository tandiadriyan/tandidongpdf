package com.tandidong.pdf.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.DocumentsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tandidong.pdf.core.DocxWriter
import com.tandidong.pdf.core.PageSizes
import com.tandidong.pdf.data.FileUtil
import com.tandidong.pdf.session.DocumentSession
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Text-to-speech playback of the document, page by page. */
@Stable
class ReadAloudController(private val context: Context) {
    var active by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var page by mutableIntStateOf(0)
        private set

    private var tts: TextToSpeech? = null
    private var ready = false
    private var scope: CoroutineScope? = null
    private var textOf: (suspend (Int) -> String)? = null
    private var pageCount = 0
    private var onPage: ((Int) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            main.post {
                if (utteranceId?.endsWith("-last") == true && active && speaking) {
                    if (page + 1 < pageCount) speakPage(page + 1) else stop()
                }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { stop() }
        }
    }

    fun start(scope: CoroutineScope, fromPage: Int, pageCount: Int, textOf: suspend (Int) -> String, onPage: (Int) -> Unit) {
        this.scope = scope
        this.textOf = textOf
        this.pageCount = pageCount
        this.onPage = onPage
        page = fromPage
        active = true
        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(context) { status ->
                main.post {
                    if (status == TextToSpeech.SUCCESS) {
                        ready = true
                        tts?.setOnUtteranceProgressListener(listener)
                        if (active) speakPage(page)
                    } else {
                        active = false
                    }
                }
            }
        } else if (ready) {
            speakPage(page)
        }
    }

    private fun speakPage(p: Int) {
        page = p
        onPage?.invoke(p)
        speaking = true
        scope?.launch {
            val text = (textOf?.invoke(p) ?: "").ifBlank { "Page ${p + 1} has no readable text." }
            val chunks = chunk(text, 3500)
            chunks.forEachIndexed { i, c ->
                val id = if (i == chunks.lastIndex) "p$p-last" else "p$p-$i"
                tts?.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    private fun chunk(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text.replace('\n', ' ')
        while (rest.length > max) {
            var cut = rest.lastIndexOf(". ", max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out.add(rest.substring(0, cut + 1))
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out.add(rest)
        return out
    }

    fun pause() {
        speaking = false
        tts?.stop()
    }

    fun resume() {
        if (active) speakPage(page)
    }

    fun next() {
        if (page + 1 < pageCount) speakPage(page + 1)
    }

    fun previous() {
        if (page > 0) speakPage(page - 1)
    }

    fun stop() {
        active = false
        speaking = false
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

/** On-device OCR with ML Kit. */
object Ocr {
    data class Box(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    suspend fun recognize(bitmap: Bitmap): List<Box> = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            result.textBlocks.flatMap { b ->
                b.lines.flatMap { l ->
                    l.elements.mapNotNull { e -> e.boundingBox?.let { r -> Box(e.text, r.left, r.top, r.right, r.bottom) } }
                }
            }
        } finally {
            recognizer.close()
        }
    }
}

object Printing {
    fun print(context: Context, file: File, name: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(name, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?,
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?, destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?, callback: WriteResultCallback,
            ) {
                try {
                    FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }, null)
    }
}

object Exporter {
    /** Writes page images into the folder chosen with ACTION_OPEN_DOCUMENT_TREE. */
    suspend fun images(
        context: Context, session: DocumentSession, tree: Uri, png: Boolean, dpi: Int, pages: List<Int>,
        onProgress: (Int, Int) -> Unit,
    ): Int {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val base = FileUtil.baseName(session.displayName)
        var n = 0
        for ((k, p) in pages.withIndex()) {
            val g = session.geometries.getOrNull(p) ?: continue
            val width = (g.displayWidth * dpi / 72f).toInt().coerceIn(64, 6000)
            val bmp = session.renderPage(p, width, session.version) ?: continue
            withContext(Dispatchers.IO) {
                val mime = if (png) "image/png" else "image/jpeg"
                val name = "${base}_page${p + 1}.${if (png) "png" else "jpg"}"
                val doc = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("Cannot create $name")
                resolver.openOutputStream(doc)?.use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
            }
            bmp.recycle()
            n++
            onProgress(k + 1, pages.size)
        }
        return n
    }

    suspend fun text(context: Context, session: DocumentSession, uri: Uri) {
        val pages = session.allText()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(pages.joinToString("\n\n") { it.text }.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Cannot write file")
        }
    }

    suspend fun docx(context: Context, session: DocumentSession, uri: Uri) {
        val pages = session.allText { done, total -> session.progress = done.toFloat() / total }
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                DocxWriter.write(pages, out, FileUtil.baseName(session.displayName))
            } ?: throw IOException("Cannot write file")
        }
    }
}

/** Converts photos into PDF pages. */
object ImageToPdf {
    enum class PageSize(val label: String) { FIT("Fit to image"), A4("A4"), LETTER("Letter") }

    data class Options(val size: PageSize = PageSize.FIT, val margin: Float = 0f, val quality: Float = 0.85f, val maxEdge: Int = 2400)

    fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge > maxEdge) {
            val s = maxEdge.toFloat() / longEdge
            val scaled = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).toInt()), max(1, (bmp.height * s).toInt()), true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    fun addPage(doc: PDDocument, bmp: Bitmap, o: Options) {
        val landscape = bmp.width > bmp.height
        val pageRect = when (o.size) {
            PageSize.FIT -> {
                val shortEdge = 595f
                if (landscape) PDRectangle(shortEdge * bmp.width / bmp.height, shortEdge)
                else PDRectangle(shortEdge, shortEdge * bmp.height / bmp.width)
            }
            PageSize.A4 -> if (landscape) PDRectangle(PageSizes.A4.height, PageSizes.A4.width) else PageSizes.A4
            PageSize.LETTER -> if (landscape) PDRectangle(PageSizes.LETTER.height, PageSizes.LETTER.width) else PageSizes.LETTER
        }
        val page = PDPage(pageRect)
        doc.addPage(page)
        val img = if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, o.quality)
        val availW = pageRect.width - 2 * o.margin
        val availH = pageRect.height - 2 * o.margin
        val s = min(availW / bmp.width, availH / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(img, (pageRect.width - w) / 2f, (pageRect.height - h) / 2f, w, h)
        }
    }
}
