package com.tandidong.pdf.ui.tools

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tandidong.pdf.core.DocxWriter
import com.tandidong.pdf.core.PageOps
import com.tandidong.pdf.core.PageText
import com.tandidong.pdf.core.PdfIO
import com.tandidong.pdf.core.TextDiff
import com.tandidong.pdf.core.TextExtractor
import com.tandidong.pdf.data.FileUtil
import com.tandidong.pdf.session.DocumentSession
import com.tandidong.pdf.ui.AppViewModel
import com.tandidong.pdf.ui.BusyOverlay
import com.tandidong.pdf.ui.editor.ImageToPdf
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private val ids = AtomicLong(1)

@Composable
private fun ToolScaffold(
    title: String,
    vm: AppViewModel,
    fab: @Composable () -> Unit = {},
    bottom: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = fab,
        bottomBar = bottom,
        content = content,
    )
}

// ------------------------------------------------------------------ combine

private data class CombineItem(val id: Long, val uri: Uri, val name: String, val isImage: Boolean)

@Composable
fun CombineScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<CombineItem>() }
    var busy by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) {
            val type = context.contentResolver.getType(u) ?: ""
            items.add(CombineItem(ids.getAndIncrement(), u, FileUtil.displayName(context, u), type.startsWith("image/")))
        }
    }
    ToolScaffold(
        "Combine files", vm,
        fab = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/pdf", "image/*")) },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Add files") },
            )
        },
        bottom = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    enabled = items.size >= 1,
                    onClick = {
                        scope.launch {
                            busy = "Combining…"
                            val opened = ArrayList<PDDocument>()
                            try {
                                val dest = PDDocument()
                                opened.add(dest)
                                for (it in items) {
                                    if (it.isImage) {
                                        val bmp = withContext(Dispatchers.IO) { ImageToPdf.decode(context, it.uri, 2400) } ?: continue
                                        withContext(Dispatchers.IO) { ImageToPdf.addPage(dest, bmp, ImageToPdf.Options()) }
                                    } else {
                                        val imported = vm.importForReading(it.uri) ?: continue
                                        withContext(Dispatchers.IO) {
                                            val src = PdfIO.load(imported.first, imported.second)
                                            opened.add(src)
                                            PageOps.appendAll(dest, listOf(src))
                                        }
                                    }
                                }
                                if (dest.numberOfPages == 0) {
                                    vm.message("Nothing to combine")
                                } else {
                                    val out = vm.scratchFile("combined-${System.nanoTime()}.pdf")
                                    withContext(Dispatchers.IO) { PdfIO.save(dest, out) }
                                    vm.openGenerated(out, vm.newDocumentName("Combined"))
                                }
                            } catch (e: Exception) {
                                vm.message("Couldn't combine: ${e.message}")
                            } finally {
                                withContext(Dispatchers.IO) { opened.forEach { d -> try { d.close() } catch (e: Exception) { } } }
                                busy = null
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text(if (items.isEmpty()) "Add PDFs or images to combine" else "Combine ${items.size} file${if (items.size == 1) "" else "s"}") }
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Add PDFs and images, arrange them, then combine into one PDF.", modifier = Modifier.padding(32.dp))
            }
        } else {
            LazyColumn(contentPadding = padding) {
                itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
                    ListItem(
                        leadingContent = { Icon(if (it.isImage) Icons.Outlined.Image else Icons.Outlined.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${i + 1}") },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { if (i > 0) items.add(i - 1, items.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Outlined.ArrowUpward, "Up") }
                                IconButton(onClick = { if (i < items.size - 1) items.add(i + 1, items.removeAt(i)) }, enabled = i < items.size - 1) { Icon(Icons.Outlined.ArrowDownward, "Down") }
                                IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Outlined.Close, "Remove") }
                            }
                        },
                    )
                }
            }
        }
    }
    busy?.let { BusyOverlay(it, null) }
}

// ------------------------------------------------------------------ images to PDF

private data class ImgItem(val id: Long, val uri: Uri, val thumb: Bitmap?)

@Composable
fun ImagesToPdfScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<ImgItem>() }
    var size by remember { mutableStateOf(ImageToPdf.PageSize.FIT) }
    var margin by remember { mutableStateOf(0f) }
    var quality by remember { mutableStateOf(0.85f) }
    var busy by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        scope.launch {
            for (u in uris) {
                val t = withContext(Dispatchers.IO) { ImageToPdf.decode(context, u, 240) }
                items.add(ImgItem(ids.getAndIncrement(), u, t))
            }
        }
    }
    LaunchedEffect(Unit) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    ToolScaffold(
        "Images to PDF", vm,
        fab = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                icon = { Icon(Icons.Outlined.AddPhotoAlternate, null) }, text = { Text("Add images") },
            )
        },
        bottom = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in ImageToPdf.PageSize.values()) FilterChip(selected = size == s, onClick = { size = s }, label = { Text(s.label) })
                        FilterChip(selected = margin > 0f, onClick = { margin = if (margin > 0f) 0f else 24f }, label = { Text("Margins") })
                        FilterChip(selected = quality < 0.8f, onClick = { quality = if (quality < 0.8f) 0.85f else 0.65f }, label = { Text("Smaller file") })
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = items.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                busy = "Creating PDF…"
                                try {
                                    val out = vm.scratchFile("images-${System.nanoTime()}.pdf")
                                    val opts = ImageToPdf.Options(size, margin, quality)
                                    withContext(Dispatchers.IO) {
                                        PDDocument().use { d ->
                                            for (it in items) ImageToPdf.decode(context, it.uri, 2400)?.let { b -> ImageToPdf.addPage(d, b, opts); b.recycle() }
                                            PdfIO.save(d, out)
                                        }
                                    }
                                    vm.back()
                                    vm.openGenerated(out, vm.newDocumentName("Images"))
                                } catch (e: Exception) {
                                    vm.message("Couldn't create PDF: ${e.message}")
                                } finally {
                                    busy = null
                                }
                            }
                        },
                    ) { Text("Create PDF (${items.size} page${if (items.size == 1) "" else "s"})") }
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
                ListItem(
                    leadingContent = {
                        val t = it.thumb
                        if (t != null) Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
                        else Icon(Icons.Outlined.Image, null)
                    },
                    headlineContent = { Text("Page ${i + 1}") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { if (i > 0) items.add(i - 1, items.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Outlined.ArrowUpward, "Up") }
                            IconButton(onClick = { if (i < items.size - 1) items.add(i + 1, items.removeAt(i)) }, enabled = i < items.size - 1) { Icon(Icons.Outlined.ArrowDownward, "Down") }
                            IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Outlined.Close, "Remove") }
                        }
                    },
                )
            }
        }
    }
    busy?.let { BusyOverlay(it, null) }
}

// ------------------------------------------------------------------ compare

private data class PageDiff(val page: Int, val inserted: Int, val deleted: Int, val chunks: List<TextDiff.Chunk>)

@Composable
fun CompareScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf<Uri?>(null) }
    var b by remember { mutableStateOf<Uri?>(null) }
    var result by remember { mutableStateOf<List<PageDiff>?>(null) }
    var summary by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var visual by remember { mutableStateOf<List<VisualDiff>?>(null) }
    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) { a = it; result = null; visual = null } }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) { b = it; result = null; visual = null } }

    /** Renders both documents page by page and highlights pixel differences. */
    suspend fun compareVisually(ua: Uri, ub: Uri): List<VisualDiff>? {
        val ia = vm.importForReading(ua) ?: return null
        val ib = vm.importForReading(ub) ?: return null
        return withContext(com.tandidong.pdf.render.PdfiumSupport.dispatcher) {
            val ra = com.tandidong.pdf.render.RendererFactory.open(context, ia.first, ia.second)
            val rb = com.tandidong.pdf.render.RendererFactory.open(context, ib.first, ib.second)
            try {
                val ga = PdfIO.load(ia.first, ia.second).use { d -> (0 until d.numberOfPages).map { com.tandidong.pdf.core.PageGeometry.of(d.getPage(it)) } }
                val gb = PdfIO.load(ib.first, ib.second).use { d -> (0 until d.numberOfPages).map { com.tandidong.pdf.core.PageGeometry.of(d.getPage(it)) } }
                val width = 720
                val out = ArrayList<VisualDiff>()
                for (i in 0 until maxOf(ga.size, gb.size)) {
                    val pa = ga.getOrNull(i)
                    val pb = gb.getOrNull(i)
                    if (pa == null || pb == null) {
                        out.add(VisualDiff(i, null, 1f, if (pa == null) "Page added" else "Page removed", 0))
                        continue
                    }
                    val ba = com.tandidong.pdf.render.RendererFactory.renderPage(ra, i, pa, width)
                    val bb = com.tandidong.pdf.render.RendererFactory.renderPage(rb, i, pb, width)
                    val pxa = IntArray(ba.width * ba.height).also { ba.getPixels(it, 0, ba.width, 0, 0, ba.width, ba.height) }
                    val pxb = IntArray(bb.width * bb.height).also { bb.getPixels(it, 0, bb.width, 0, 0, bb.width, bb.height) }
                    val r = com.tandidong.pdf.core.PixelDiff.compare(pxa, ba.width, ba.height, pxb, bb.width, bb.height)
                    val wa = ba.width
                    ba.recycle(); bb.recycle()
                    if (r.changedPixels == 0) continue
                    val img = android.graphics.Bitmap.createBitmap(r.overlay, r.width, r.height, android.graphics.Bitmap.Config.ARGB_8888)
                    // Outline each changed area so small edits stand out.
                    val canvas = android.graphics.Canvas(img)
                    val paint = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFE53935.toInt() }
                    for (reg in r.regions) canvas.drawRect(reg.x0 - 3, reg.y0 - 3, reg.x1 + 3, reg.y1 + 3, paint)
                    out.add(VisualDiff(i, img.asImageBitmap(), r.changedFraction, null, r.regions.size))
                    check(wa > 0)
                }
                out
            } finally {
                ra.close(); rb.close()
                ia.first.delete(); ib.first.delete()
            }
        }
    }

    suspend fun texts(uri: Uri): List<String>? {
        val imported = vm.importForReading(uri) ?: return null
        return withContext(Dispatchers.IO) {
            val d = PdfIO.load(imported.first, imported.second)
            try { (0 until d.numberOfPages).map { TextExtractor.extract(d, it).text } } finally { d.close(); imported.first.delete() }
        }
    }

    ToolScaffold("Compare files", vm) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickA.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f)) {
                    Text(a?.let { FileUtil.displayName(context, it) } ?: "Original…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { pickB.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f)) {
                    Text(b?.let { FileUtil.displayName(context, it) } ?: "Revised…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(
                enabled = a != null && b != null,
                onClick = {
                    val ua = a ?: return@Button
                    val ub = b ?: return@Button
                    scope.launch {
                        busy = "Comparing…"
                        try {
                            val ta = texts(ua) ?: return@launch
                            val tb = texts(ub) ?: return@launch
                            val diffs = withContext(Dispatchers.Default) {
                                (0 until maxOf(ta.size, tb.size)).mapNotNull { i ->
                                    val s = TextDiff.compare(ta.getOrElse(i) { "" }, tb.getOrElse(i) { "" })
                                    if (s.insertedWords + s.deletedWords == 0) null else PageDiff(i, s.insertedWords, s.deletedWords, s.chunks)
                                }
                            }
                            result = diffs
                            visual = null
                            summary = if (diffs.isEmpty()) "No text differences found (${ta.size} vs ${tb.size} pages)."
                            else "${diffs.size} page(s) changed · ${diffs.sumOf { it.inserted }} words added · ${diffs.sumOf { it.deleted }} words removed" +
                                if (ta.size != tb.size) " · page count ${ta.size} → ${tb.size}" else ""
                        } catch (e: Exception) {
                            vm.message("Compare failed: ${e.message}")
                        } finally {
                            busy = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) { Text("Compare text") }
            OutlinedButton(
                enabled = a != null && b != null,
                onClick = {
                    val ua = a ?: return@OutlinedButton
                    val ub = b ?: return@OutlinedButton
                    scope.launch {
                        busy = "Comparing pages…"
                        try {
                            val v = compareVisually(ua, ub) ?: return@launch
                            result = null
                            visual = v
                            summary = if (v.isEmpty()) "The pages look identical." else "${v.size} page(s) look different."
                        } catch (e: Exception) {
                            vm.message("Compare failed: ${e.message}")
                        } finally {
                            busy = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("Compare visually") }
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp))
            visual?.let { v ->
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(v) { _, d ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                val pct = (d.changed * 100f)
                                Text(
                                    "Page ${d.page + 1}  ·  " + (d.note ?: ("${d.regions} changed area${if (d.regions == 1) "" else "s"} · " +
                                        (if (pct < 0.1f) "<0.1" else "%.1f".format(pct)) + "% of the page")),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                d.image?.let {
                                    Spacer(Modifier.height(6.dp))
                                    androidx.compose.foundation.Image(it, "Differences on page ${d.page + 1}", modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
            val r = result
            if (r != null) {
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(r) { _, d ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Page ${d.page + 1}  ·  +${d.inserted} / −${d.deleted}", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Text(diffText(d.chunks), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    busy?.let { BusyOverlay(it, null) }
}

/** One page of a visual comparison; [image] shows the revised page with changes in red. */
class VisualDiff(val page: Int, val image: androidx.compose.ui.graphics.ImageBitmap?, val changed: Float, val note: String?, val regions: Int)

private fun diffText(chunks: List<TextDiff.Chunk>) = buildAnnotatedString {
    for (c in chunks) {
        when (c.op) {
            TextDiff.Op.EQUAL -> {
                val words = c.text.split(' ')
                // Collapse long unchanged stretches to keep the focus on changes.
                if (words.size > 30) append(words.take(12).joinToString(" ") + " … " + words.takeLast(12).joinToString(" ")) else append(c.text)
            }
            TextDiff.Op.INSERT -> withStyle(SpanStyle(background = Color(0x6643A047), color = Color(0xFF1B5E20))) { append(c.text) }
            TextDiff.Op.DELETE -> withStyle(SpanStyle(background = Color(0x55E53935), color = Color(0xFFB71C1C), textDecoration = TextDecoration.LineThrough)) { append(c.text) }
        }
        append(' ')
    }
}

// ------------------------------------------------------------------ liquid mode

@Composable
fun ReflowScreen(vm: AppViewModel, session: DocumentSession) {
    var pages by remember { mutableStateOf<List<PageText>?>(null) }
    var scale by remember { mutableStateOf(1f) }
    val scope = rememberCoroutineScope()
    // Translation state: translated paragraph texts keyed by page, null when showing the original.
    var translated by remember { mutableStateOf<Map<Int, List<String>>?>(null) }
    var showTranslation by remember { mutableStateOf(true) }
    var translating by remember { mutableStateOf<Float?>(null) }
    var pickLanguage by remember { mutableStateOf(false) }
    LaunchedEffect(session.version) { pages = try { session.allText() } catch (e: Exception) { emptyList() }; translated = null }
    val paragraphs = remember(pages) { pages?.map { DocxWriter.paragraphs(it) } }

    fun startTranslation(target: String) {
        val paras = paragraphs ?: return
        scope.launch {
            translating = 0f
            try {
                val flat = paras.flatten().map { it.text }
                val source = DocTranslator.detect(flat.joinToString(" ").take(4000))
                if (source == null) { vm.message("Couldn't detect the document's language"); return@launch }
                if (source == target) { vm.message("The document is already in that language"); return@launch }
                val out = DocTranslator.translate(flat, source, target) { d, t -> translating = d.toFloat() / t }
                var k = 0
                translated = paras.withIndex().associate { (i, list) -> i to list.map { out[k++] } }
                showTranslation = true
            } catch (e: Exception) {
                vm.message(e.message ?: "Translation failed")
            } finally {
                translating = null
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Liquid mode", maxLines = 1) },
                    navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        if (translated != null) {
                            TextButton(onClick = { showTranslation = !showTranslation }) { Text(if (showTranslation) "Original" else "Translation") }
                        }
                        IconButton(onClick = { pickLanguage = true }, enabled = translating == null) { Icon(Icons.Outlined.Translate, "Translate") }
                        IconButton(onClick = { scale = (scale - 0.1f).coerceAtLeast(0.7f) }) { Icon(Icons.Outlined.TextDecrease, "Smaller text") }
                        IconButton(onClick = { scale = (scale + 0.1f).coerceAtMost(2.2f) }) { Icon(Icons.Outlined.TextIncrease, "Larger text") }
                    },
                )
                translating?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            }
        },
    ) { padding ->
        val list = pages
        val paras = paragraphs
        if (list == null || paras == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Preparing reading view…") }
        } else if (list.all { it.isBlank }) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No text found. If this is a scanned document, use Recognize text (OCR) first.", modifier = Modifier.padding(32.dp))
            }
        } else {
            val tr = translated?.takeIf { showTranslation }
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp)) {
                itemsIndexed(paras) { i, pageParas ->
                    Column {
                        Text("Page ${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                        val body = pageParas.map { it.sizePt }.sorted().let { if (it.isEmpty()) 11f else it[it.size / 2] }
                        for ((k, p) in pageParas.withIndex()) {
                            val heading = p.sizePt > body * 1.25f
                            Text(
                                tr?.get(i)?.getOrNull(k) ?: p.text,
                                fontSize = ((if (heading) 20f else 16f) * scale).sp,
                                lineHeight = ((if (heading) 26f else 24f) * scale).sp,
                                fontWeight = if (heading || p.bold) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (pickLanguage) {
        val langs = remember { DocTranslator.languages() }
        val def = remember { DocTranslator.defaultTarget() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickLanguage = false },
            title = { Text("Translate to") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(langs.sortedByDescending { it.first == def }) { _, (code, name) ->
                        Text(
                            name + if (code == def) " (device language)" else "",
                            modifier = Modifier.fillMaxWidth().clickable { pickLanguage = false; startTranslation(code) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickLanguage = false }) { Text("Cancel") } },
        )
    }
}

@Suppress("unused")
private val unusedWidth = Modifier.width(1.dp).background(Color.Transparent)
