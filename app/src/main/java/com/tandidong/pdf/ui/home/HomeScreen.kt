package com.tandidong.pdf.ui.home

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.tandidong.pdf.core.PageOps
import com.tandidong.pdf.core.PageSizes
import com.tandidong.pdf.core.PdfIO
import com.tandidong.pdf.data.FileUtil
import com.tandidong.pdf.data.RecentFile
import com.tandidong.pdf.ui.AppViewModel
import com.tandidong.pdf.ui.Screen
import com.tandidong.pdf.ui.ToolLaunch
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class HomeTool(val label: String, val icon: ImageVector, val launch: ToolLaunch? = null, val action: (() -> Unit)? = null)

@Composable
fun HomeScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingLaunch by remember { mutableStateOf<ToolLaunch?>(null) }
    var query by remember { mutableStateOf("") }
    var showBlank by remember { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openDocument(uri, pendingLaunch)
        pendingLaunch = null
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val res = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdfUri = res?.pdf?.uri
            if (pdfUri != null) {
                scope.launch {
                    val file = vm.scratchFile("scan-${System.currentTimeMillis()}.pdf")
                    try {
                        withContext(Dispatchers.IO) { FileUtil.copyToFile(context, pdfUri, file) }
                        vm.openGenerated(file, vm.newDocumentName("Scan"), ToolLaunch.OCR)
                    } catch (e: Exception) {
                        vm.message("Couldn't import scan: ${e.message}")
                    }
                }
            }
        }
    }

    fun pick(launch: ToolLaunch?) {
        pendingLaunch = launch
        openLauncher.launch(arrayOf("application/pdf"))
    }

    fun scan() {
        val activity = context as? Activity ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e -> vm.message("Scanner unavailable on this device: ${e.message}") }
    }

    val tools = listOf(
        HomeTool("Edit PDF", Icons.Outlined.Edit, ToolLaunch.EDIT),
        HomeTool("Comment", Icons.Outlined.ChatBubbleOutline, ToolLaunch.COMMENT),
        HomeTool("Fill & Sign", Icons.Outlined.Draw, ToolLaunch.FILL_SIGN),
        HomeTool("Redact", Icons.Outlined.HideSource, ToolLaunch.REDACT),
        HomeTool("Organize pages", Icons.Outlined.GridView, ToolLaunch.ORGANIZE),
        HomeTool("Combine files", Icons.Outlined.MergeType, action = { vm.navigate(Screen.Combine) }),
        HomeTool("Compress", Icons.Outlined.Compress, ToolLaunch.COMPRESS),
        HomeTool("Protect", Icons.Outlined.Lock, ToolLaunch.PROTECT),
        HomeTool("Export PDF", Icons.Outlined.IosShare, ToolLaunch.EXPORT),
        HomeTool("Scan", Icons.Outlined.DocumentScanner, action = { scan() }),
        HomeTool("Images to PDF", Icons.Outlined.AddPhotoAlternate, action = { vm.navigate(Screen.ImagesToPdf) }),
        HomeTool("Recognize text", Icons.Outlined.FindInPage, ToolLaunch.OCR),
        HomeTool("Watermark", Icons.Outlined.WaterDrop, ToolLaunch.WATERMARK),
        HomeTool("Page numbers", Icons.Outlined.FormatListNumbered, ToolLaunch.HEADER_FOOTER),
        HomeTool("Compare", Icons.Outlined.Compare, action = { vm.navigate(Screen.Compare) }),
        HomeTool("Read aloud", Icons.Outlined.RecordVoiceOver, ToolLaunch.READ_ALOUD),
        HomeTool("Liquid mode", Icons.Outlined.ChromeReaderMode, ToolLaunch.REFLOW),
        HomeTool("Sanitize", Icons.Outlined.CleaningServices, ToolLaunch.SANITIZE),
        HomeTool("Flatten", Icons.Outlined.Layers, ToolLaunch.FLATTEN),
        HomeTool("Print", Icons.Outlined.Print, ToolLaunch.PRINT),
        HomeTool("Blank PDF", Icons.Outlined.NoteAdd, action = { showBlank = true }),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tandidong PDF", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction("Open PDF", Icons.Outlined.FolderOpen, Modifier.weight(1f)) { pick(null) }
                    QuickAction("Scan", Icons.Outlined.DocumentScanner, Modifier.weight(1f)) { scan() }
                    QuickAction("Images", Icons.Outlined.Image, Modifier.weight(1f)) { vm.navigate(Screen.ImagesToPdf) }
                    QuickAction("Combine", Icons.Outlined.MergeType, Modifier.weight(1f)) { vm.navigate(Screen.Combine) }
                }
            }
            item { SectionTitle("All tools") }
            items(tools.chunked(4)) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in row) {
                        ToolTile(t.label, t.icon, Modifier.weight(1f)) {
                            if (t.action != null) t.action.invoke() else pick(t.launch)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (vm.recents.items.isNotEmpty()) TextButton(onClick = { vm.recents.clear() }) { Text("Clear") }
                }
            }
            val recents = vm.recents.items.filter { query.isBlank() || it.name.contains(query, true) }
                .sortedWith(compareByDescending<RecentFile> { it.starred }.thenByDescending { it.lastOpened })
            if (vm.recents.items.size > 6) {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        placeholder = { Text("Search recent files") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (recents.isEmpty()) {
                item {
                    Text(
                        "Files you open will appear here. Tap Open PDF to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(recents, key = { it.uri }) { r -> RecentRow(vm, r) }
        }
    }

    if (showBlank) BlankDocumentDialog(onDismiss = { showBlank = false }) { size, count ->
        showBlank = false
        scope.launch {
            val file = vm.scratchFile("blank-${System.currentTimeMillis()}.pdf")
            withContext(Dispatchers.IO) {
                PDDocument().use { d ->
                    repeat(count) { d.addPage(PDPage(size)) }
                    PdfIO.save(d, file)
                }
            }
            vm.openGenerated(file, vm.newDocumentName("New document"), ToolLaunch.EDIT)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
        }
    }
}

@Composable
private fun ToolTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun RecentRow(vm: AppViewModel, r: RecentFile) {
    var menu by remember { mutableStateOf(false) }
    val thumb = remember(r.uri, r.lastOpened) {
        val f = vm.recents.thumbFile(r.uri)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
    }
    ListItem(
        modifier = Modifier.clickable { vm.openDocument(Uri.parse(r.uri)) },
        leadingContent = {
            Box(
                Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 44.dp, height = 56.dp))
                else Icon(Icons.Outlined.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parts = listOfNotNull(
                if (r.pages > 0) "${r.pages} page${if (r.pages == 1) "" else "s"}" else null,
                FileUtil.formatSize(r.size).takeIf { it.isNotEmpty() },
                DateUtils.getRelativeTimeSpanString(r.lastOpened).toString(),
            )
            Text(parts.joinToString(" · "), maxLines = 1)
        },
        trailingContent = {
            Row {
                IconButton(onClick = { vm.recents.toggleStar(r.uri) }) {
                    Icon(if (r.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Star",
                        tint = if (r.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Remove from recent") }, onClick = { menu = false; vm.recents.remove(r.uri) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                    }
                }
            }
        },
    )
}

@Composable
private fun BlankDocumentDialog(onDismiss: () -> Unit, onCreate: (com.tom_roush.pdfbox.pdmodel.common.PDRectangle, Int) -> Unit) {
    val sizes = listOf("A4" to PageSizes.A4, "Letter" to PageSizes.LETTER, "Legal" to PageSizes.LEGAL, "A5" to PageSizes.A5)
    var selected by remember { mutableStateOf(0) }
    var count by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New blank PDF") },
        text = {
            Column {
                sizes.forEachIndexed { i, (name, _) ->
                    Row(Modifier.fillMaxWidth().clickable { selected = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == i, onClick = { selected = i })
                        Text(name)
                    }
                }
                OutlinedTextField(
                    value = count, onValueChange = { count = it.filter(Char::isDigit).take(3) },
                    label = { Text("Pages") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(sizes[selected].second, (count.toIntOrNull() ?: 1).coerceIn(1, 500)) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private fun unusedPageOps() = PageOps
