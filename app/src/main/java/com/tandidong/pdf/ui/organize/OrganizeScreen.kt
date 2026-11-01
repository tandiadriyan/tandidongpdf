package com.tandidong.pdf.ui.organize

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tandidong.pdf.core.PageOps
import com.tandidong.pdf.core.PageSpec
import com.tandidong.pdf.core.PdfIO
import com.tandidong.pdf.data.FileUtil
import com.tandidong.pdf.session.DocumentSession
import com.tandidong.pdf.ui.AppViewModel
import com.tandidong.pdf.ui.BusyOverlay
import com.tandidong.pdf.ui.editor.ImageToPdf
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private data class OrgItem(val key: Long, val source: Int, val rotation: Int = 0, val blankW: Float = 0f, val blankH: Float = 0f) {
    val isBlank: Boolean get() = source < 0
}

private val keys = AtomicLong(1)

private fun LazyGridItemInfo.contains(p: Offset) =
    p.x >= offset.x && p.x <= offset.x + size.width && p.y >= offset.y && p.y <= offset.y + size.height

@Composable
fun OrganizeScreen(vm: AppViewModel, session: DocumentSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = session.version
    val items = remember(version) {
        mutableStateListOf<OrgItem>().apply { addAll((0 until session.pageCount).map { OrgItem(keys.getAndIncrement(), it) }) }
    }
    val selected = remember(version) { mutableStateListOf<Long>() }
    var changed by remember(version) { mutableStateOf(false) }
    val thumbs = remember(version) { mutableStateMapOf<Int, ImageBitmap>() }
    var menu by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var splitDialog by remember { mutableStateOf(false) }
    var cropDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    var dragKey by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    fun plan(list: List<OrgItem> = items) = list.map { if (it.isBlank) PageSpec.Blank(it.blankW, it.blankH) else PageSpec.Existing(it.source, it.rotation) }

    fun insertIndex(): Int {
        val last = items.indexOfLast { it.key in selected }
        return if (last >= 0) last + 1 else items.size
    }

    suspend fun applyPlanNow(): Boolean {
        if (!changed) return true
        return try {
            val p = plan()
            session.edit("Organizing pages…") { PageOps.applyPlan(it, p) }
            true
        } catch (e: Exception) {
            vm.message("Couldn't reorganize: ${e.message}")
            false
        }
    }

    /** Produces a new version by inserting pages from [file] at [at]. */
    suspend fun insertFile(file: File, password: String?, at: Int) {
        if (!applyPlanNow()) return
        busy = "Inserting pages…"
        try {
            val out = vm.scratchFile("insert-${System.nanoTime()}.pdf")
            val current = session.currentFile
            withContext(Dispatchers.IO) {
                val dest = PdfIO.load(current)
                try {
                    val src = PageOps.insertFrom(dest, file, password, null, at)
                    try { PdfIO.save(dest, out) } finally { src.close() }
                } finally {
                    dest.close()
                }
            }
            session.adopt(out, "Inserting pages…")
            out.delete()
        } catch (e: Exception) {
            vm.message("Couldn't insert: ${e.message}")
        } finally {
            busy = null
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val at = insertIndex()
            try {
                val imported = vm.importForReading(uri) ?: return@launch
                insertFile(imported.first, imported.second, at)
                imported.first.delete()
            } catch (e: Exception) {
                vm.message("Couldn't insert: ${e.message}")
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val at = insertIndex()
            busy = "Converting images…"
            try {
                val tmp = vm.scratchFile("images-${System.nanoTime()}.pdf")
                withContext(Dispatchers.IO) {
                    PDDocument().use { d ->
                        for (u in uris) ImageToPdf.decode(context, u, 2400)?.let { ImageToPdf.addPage(d, it, ImageToPdf.Options()) }
                        PdfIO.save(d, tmp)
                    }
                }
                busy = null
                insertFile(tmp, null, at)
                tmp.delete()
            } catch (e: Exception) {
                vm.message("Couldn't add images: ${e.message}")
            } finally {
                busy = null
            }
        }
    }
    var extractKeys by remember { mutableStateOf<List<OrgItem>>(emptyList()) }
    val extractLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val chosen = extractKeys
        if (uri != null && chosen.isNotEmpty()) scope.launch {
            busy = "Extracting pages…"
            try {
                val out = vm.scratchFile("extract-${System.nanoTime()}.pdf")
                val current = session.currentFile
                withContext(Dispatchers.IO) {
                    PdfIO.transform(current, out) { PageOps.applyPlan(it, plan(chosen)) }
                    FileUtil.writeToUri(context, out, uri)
                    out.delete()
                }
                vm.message("Extracted ${chosen.size} page${if (chosen.size == 1) "" else "s"}")
            } catch (e: Exception) {
                vm.message("Extract failed: ${e.message}")
            } finally {
                busy = null
            }
        }
    }
    var splitRanges by remember { mutableStateOf<List<List<Int>>>(emptyList()) }
    val splitLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val ranges = splitRanges
        if (tree != null && ranges.isNotEmpty()) scope.launch {
            busy = "Splitting…"
            try {
                val current = session.currentFile
                val base = FileUtil.baseName(session.displayName)
                withContext(Dispatchers.IO) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    ranges.forEachIndexed { i, r ->
                        val out = vm.scratchFile("part-$i.pdf")
                        PageOps.extract(current, null, r, out)
                        val name = "${base}_part${i + 1}.pdf"
                        val target: Uri = DocumentsContract.createDocument(context.contentResolver, parent, "application/pdf", name)
                            ?: throw java.io.IOException("Cannot create $name")
                        FileUtil.writeToUri(context, out, target)
                        out.delete()
                    }
                }
                vm.message("Created ${ranges.size} file${if (ranges.size == 1) "" else "s"}")
            } catch (e: Exception) {
                vm.message("Split failed: ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    BackHandler {
        if (changed) confirmLeave = true else vm.back()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Organize pages", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (selected.isEmpty()) "${items.size} pages · long-press to drag" else "${selected.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = { if (changed) confirmLeave = true else vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (changed) TextButton(onClick = { scope.launch { if (applyPlanNow()) vm.message("Pages updated") } }) { Text("Apply") }
                    IconButton(onClick = {
                        if (selected.size == items.size) selected.clear() else { selected.clear(); selected.addAll(items.map { it.key }) }
                    }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Insert blank page") }, leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) }, onClick = {
                                menu = false
                                val ref = items.firstOrNull { !it.isBlank }
                                val g = ref?.let { session.geometries.getOrNull(it.source) }
                                items.add(insertIndex(), OrgItem(keys.getAndIncrement(), -1, 0, g?.cropWidth ?: 595f, g?.cropHeight ?: 842f))
                                changed = true
                            })
                            DropdownMenuItem(text = { Text("Insert from PDF…") }, leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) }, onClick = {
                                menu = false; pdfPicker.launch(arrayOf("application/pdf"))
                            })
                            DropdownMenuItem(text = { Text("Insert images…") }, leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, null) }, onClick = {
                                menu = false
                                imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text("Split document…") }, leadingIcon = { Icon(Icons.Outlined.ContentCut, null) }, onClick = {
                                menu = false
                                if (changed) vm.message("Apply your changes before splitting") else splitDialog = true
                            })
                            DropdownMenuItem(text = { Text("Crop pages…") }, leadingIcon = { Icon(Icons.Outlined.Crop, null) }, onClick = {
                                menu = false
                                if (changed) vm.message("Apply your changes before cropping") else cropDialog = true
                            })
                            DropdownMenuItem(text = { Text("Reverse order") }, leadingIcon = { Icon(Icons.Outlined.SwapVert, null) }, onClick = {
                                menu = false; items.reverse(); changed = true
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionButton("Rotate left", Icons.Outlined.RotateLeft) {
                            for (i in items.indices) if (items[i].key in selected) items[i] = items[i].copy(rotation = (items[i].rotation + 270) % 360)
                            changed = true
                        }
                        ActionButton("Rotate right", Icons.Outlined.RotateRight) {
                            for (i in items.indices) if (items[i].key in selected) items[i] = items[i].copy(rotation = (items[i].rotation + 90) % 360)
                            changed = true
                        }
                        ActionButton("Duplicate", Icons.Outlined.ContentCopy) {
                            val result = ArrayList<OrgItem>()
                            for (it in items) {
                                result.add(it)
                                if (it.key in selected) result.add(it.copy(key = keys.getAndIncrement()))
                            }
                            items.clear(); items.addAll(result); changed = true
                        }
                        ActionButton("Extract", Icons.Outlined.FileCopy) {
                            extractKeys = items.filter { it.key in selected }
                            extractLauncher.launch(FileUtil.baseName(session.displayName) + "_extract.pdf")
                        }
                        ActionButton("Delete", Icons.Outlined.Delete) {
                            if (selected.size >= items.size) vm.message("A document must keep at least one page")
                            else {
                                items.removeAll { it.key in selected }
                                selected.clear()
                                changed = true
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            modifier = Modifier.fillMaxSize().pointerInput(version) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        dragKey = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(pos) }?.key as? Long
                        dragOffset = Offset.Zero
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val key = dragKey
                        if (key != null) {
                            dragOffset += amount
                            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                            if (info != null) {
                                val center = Offset(info.offset.x + info.size.width / 2f, info.offset.y + info.size.height / 2f) + dragOffset
                                val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key != key && it.contains(center) }
                                if (target != null) {
                                    val from = items.indexOfFirst { it.key == key }
                                    val to = items.indexOfFirst { it.key == target.key }
                                    if (from >= 0 && to >= 0) {
                                        items.add(to, items.removeAt(from))
                                        dragOffset += Offset((info.offset.x - target.offset.x).toFloat(), (info.offset.y - target.offset.y).toFloat())
                                        changed = true
                                    }
                                }
                                val vh = gridState.layoutInfo.viewportSize.height
                                if (center.y > vh - 120) scope.launch { gridState.scrollBy(24f) }
                                else if (center.y < 120) scope.launch { gridState.scrollBy(-24f) }
                            }
                        }
                    },
                    onDragEnd = { dragKey = null; dragOffset = Offset.Zero },
                    onDragCancel = { dragKey = null; dragOffset = Offset.Zero },
                )
            },
        ) {
            items(items, key = { it.key }) { item ->
                val dragging = item.key == dragKey
                val index = items.indexOf(item)
                PageThumb(
                    session = session,
                    item = item,
                    number = index + 1,
                    selected = item.key in selected,
                    thumbs = thumbs,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            if (dragging) {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                                scaleX = 1.06f
                                scaleY = 1.06f
                                shadowElevation = 12f
                            }
                        }
                        .then(if (dragging) Modifier else Modifier.animateItem()),
                ) {
                    if (item.key in selected) selected.remove(item.key) else selected.add(item.key)
                }
            }
        }
    }

    busy?.let { BusyOverlay(it, null) }
    session.busy?.let { BusyOverlay(it, session.progress.takeIf { p -> p >= 0f }) }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Apply page changes?") },
            text = { Text("You reorganized pages. Apply the changes to the document?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch { if (applyPlanNow()) vm.back() }
                }) { Text("Apply") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false; vm.back() }) { Text("Discard") }
                    TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
                }
            },
        )
    }
    if (splitDialog) SplitDialog(session.pageCount, onDismiss = { splitDialog = false }) { ranges ->
        splitDialog = false
        splitRanges = ranges
        splitLauncher.launch(null)
    }
    if (cropDialog) CropDialog(onDismiss = { cropDialog = false }) { l, t, r, b ->
        cropDialog = false
        val targets = if (selected.isEmpty()) items.map { it.source } else items.filter { it.key in selected }.map { it.source }
        scope.launch {
            try {
                session.edit("Cropping…") { PageOps.crop(it, targets.filter { s -> s >= 0 }, l, t, r, b) }
            } catch (e: Exception) {
                vm.message("Crop failed: ${e.message}")
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PageThumb(
    session: DocumentSession,
    item: OrgItem,
    number: Int,
    selected: Boolean,
    thumbs: MutableMap<Int, ImageBitmap>,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val version = session.version
    if (!item.isBlank && !thumbs.containsKey(item.source)) {
        LaunchedEffect(item.source, version) {
            session.renderPage(item.source, 260, version)?.let { thumbs[item.source] = it.asImageBitmap() }
        }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Page $number" + if (selected) ", selected" else "" }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f)
                .clip(RoundedCornerShape(6.dp))
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val img = if (item.isBlank) null else thumbs[item.source]
            if (item.isBlank) {
                Box(Modifier.fillMaxSize().padding(10.dp).background(Color.White))
            } else if (img != null) {
                Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp).rotate(item.rotation.toFloat()))
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(if (item.isBlank) "$number (blank)" else "$number", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SplitDialog(pageCount: Int, onDismiss: () -> Unit, onSplit: (List<List<Int>>) -> Unit) {
    var mode by remember { mutableStateOf(0) }
    var n by remember { mutableStateOf("1") }
    var ranges by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split document") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().clickable { mode = 0 }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == 0, onClick = { mode = 0 }); Text("Every N pages")
                }
                if (mode == 0) OutlinedTextField(value = n, onValueChange = { n = it.filter(Char::isDigit).take(4) }, label = { Text("Pages per file") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(Modifier.fillMaxWidth().clickable { mode = 1 }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == 1, onClick = { mode = 1 }); Text("Custom ranges")
                }
                if (mode == 1) OutlinedTextField(value = ranges, onValueChange = { ranges = it }, label = { Text("e.g. 1-3, 4-6, 7-") }, singleLine = true)
                Text("Each part is saved as a separate PDF in the folder you choose.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = if (mode == 0) PageOps.everyN(pageCount, n.toIntOrNull() ?: 1) else PageOps.parseRanges(ranges, pageCount)
                if (r != null && r.isNotEmpty()) onSplit(r)
            }) { Text("Choose folder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CropDialog(onDismiss: () -> Unit, onCrop: (Float, Float, Float, Float) -> Unit) {
    var l by remember { mutableStateOf("36") }
    var t by remember { mutableStateOf("36") }
    var r by remember { mutableStateOf("36") }
    var b by remember { mutableStateOf("36") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop pages") },
        text = {
            Column {
                Text("Margins to remove, in points (72 points = 1 inch). Applies to selected pages, or all pages if none are selected.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = l, onValueChange = { l = it }, label = { Text("Left") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = r, onValueChange = { r = it }, label = { Text("Right") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = t, onValueChange = { t = it }, label = { Text("Top") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = b, onValueChange = { b = it }, label = { Text("Bottom") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCrop(l.toFloatOrNull() ?: 0f, t.toFloatOrNull() ?: 0f, r.toFloatOrNull() ?: 0f, b.toFloatOrNull() ?: 0f)
            }) { Text("Crop") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
