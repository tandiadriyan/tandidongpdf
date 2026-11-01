package com.tandidong.pdf.ui.editor

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tandidong.pdf.data.FileUtil
import com.tandidong.pdf.session.DocumentSession
import com.tandidong.pdf.ui.AppViewModel
import com.tandidong.pdf.ui.BusyOverlay
import com.tandidong.pdf.ui.Screen
import com.tandidong.pdf.ui.ToolLaunch
import com.tandidong.pdf.ui.viewer.PdfDocumentView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DocumentScreen(vm: AppViewModel, session: DocumentSession, launch: ToolLaunch?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = vm.controllerFor(session)
    val editor = c.editor
    val version = session.version

    LaunchedEffect(version) { c.onVersion(version) }
    // Preload link areas for visible pages so the first tap on a link works.
    LaunchedEffect(version) {
        androidx.compose.runtime.snapshotFlow { c.viewer.visiblePages() }.collect { pages ->
            for (p in pages) {
                c.loadLinks(p)
                if (c.editor.tool == Tool.EDIT_IMAGE) c.loadImages(p)
            }
        }
    }
    DisposableEffect(c) { onDispose { c.readAloud.pause() } }

    // ---------------------------------------------------------------- launchers
    var pendingSaveThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    session.saveTo(uri)
                    session.displayName = FileUtil.displayName(context, uri)
                    FileUtil.takePersistable(context, uri)
                    vm.recents.touch(uri, session.displayName, session.currentFile.length(), session.pageCount)
                    vm.message("Saved")
                    pendingSaveThen?.invoke()
                } catch (e: Exception) {
                    vm.message("Save failed: ${e.message}")
                }
                pendingSaveThen = null
            }
        } else {
            pendingSaveThen = null
        }
    }
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try { session.task("Exporting text…") { Exporter.text(context, session, uri) }; vm.message("Text exported") }
            catch (e: Exception) { vm.message("Export failed: ${e.message}") }
        }
    }
    val docxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri ->
        if (uri != null) scope.launch {
            try { session.task("Exporting to Word…") { Exporter.docx(context, session, uri) }; vm.message("Word document exported") }
            catch (e: Exception) { vm.message("Export failed: ${e.message}") }
        }
    }
    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) scope.launch {
            try { session.task("Exporting to Excel…") { Exporter.xlsx(context, session, uri) }; vm.message("Excel workbook exported") }
            catch (e: Exception) { vm.message("Export failed: ${e.message}") }
        }
    }
    val pptxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.presentationml.presentation")
    ) { uri ->
        if (uri != null) scope.launch {
            try { session.task("Exporting to PowerPoint…") { Exporter.pptx(context, session, uri) }; vm.message("PowerPoint deck exported") }
            catch (e: Exception) { vm.message("Export failed: ${e.message}") }
        }
    }
    var imageExport by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val spec = imageExport
        if (tree != null && spec != null) scope.launch {
            try {
                val n = session.task("Exporting images…") {
                    Exporter.images(context, session, tree, spec.first, spec.second, (0 until session.pageCount).toList()) { d, t -> session.progress = d.toFloat() / t }
                }
                vm.message("Exported $n image${if (n == 1) "" else "s"}")
            } catch (e: Exception) {
                vm.message("Export failed: ${e.message}")
            }
        }
    }
    var imagePurpose by remember { mutableStateOf("place") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageToPdf.decode(context, uri, 1600) }
            if (bmp == null) { vm.message("Couldn't read image"); return@launch }
            if (imagePurpose == "watermark") {
                c.pendingWatermarkImage = bmp
                c.dialog = DocDialog.Watermark
            } else if (imagePurpose == "replace") {
                val target = editor.selectedImage
                if (target != null) c.replaceImage(target, bmp) else vm.message("Select an image first")
            } else {
                editor.imageToPlace = bmp
                editor.category = ToolCategory.EDIT
                editor.tool = Tool.ADD_IMAGE
                vm.message("Tap the page to place the image")
            }
        }
    }

    var importThen by remember { mutableStateOf<DocDialog?>(null) }
    val idPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                if (bytes == null) vm.message("Couldn't read the file") else c.dialog = DocDialog.ImportDigitalId(bytes, importThen)
            } catch (e: Exception) {
                vm.message("Couldn't read the file: ${e.message}")
            }
        }
    }

    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    fun saveBytesLauncherResult(uri: Uri?) {
        val bytes = pendingBytes
        pendingBytes = null
        if (uri != null && bytes != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { FileUtil.openOutput(context, uri).use { it.write(bytes) } }
                vm.message("Saved")
            } catch (e: Exception) {
                vm.message("Save failed: ${e.message}")
            }
        }
    }
    val saveJpegLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { saveBytesLauncherResult(it) }
    val savePngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { saveBytesLauncherResult(it) }
    val saveAnyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { saveBytesLauncherResult(it) }
    val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val name = FileUtil.displayName(context, uri)
                val mime = context.contentResolver.getType(uri)
                val size = FileUtil.size(context, uri)
                if (size > 50L * 1024 * 1024) { vm.message("Files larger than 50 MB can't be attached"); return@launch }
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                if (bytes == null) vm.message("Couldn't read the file") else c.addAttachment(name, mime, bytes, null)
            } catch (e: Exception) {
                vm.message("Couldn't attach the file: ${e.message}")
            }
        }
    }

    fun saveSelectedImage() {
        val img = editor.selectedImage ?: return
        scope.launch {
            val res = try { c.imageBytes(img) } catch (e: Exception) { null }
            if (res == null) { vm.message("This image can't be saved"); return@launch }
            pendingBytes = res.first
            val base = FileUtil.baseName(session.displayName) + "-image-p${img.pageIndex + 1}"
            if (res.second == "jpg") saveJpegLauncher.launch("$base.jpg") else savePngLauncher.launch("$base.png")
        }
    }

    fun saveAttachment(a: com.tandidong.pdf.core.AttachmentInfo) {
        scope.launch {
            try {
                pendingBytes = c.attachmentBytes(a)
                saveAnyLauncher.launch(a.fileName)
            } catch (e: Exception) {
                vm.message("Couldn't read the attachment: ${e.message}")
            }
        }
    }

    fun openAttachment(a: com.tandidong.pdf.core.AttachmentInfo) {
        scope.launch {
            try {
                val bytes = c.attachmentBytes(a)
                val f = java.io.File(context.cacheDir, "attachment.bin")
                withContext(Dispatchers.IO) { f.writeBytes(bytes) }
                val mime = a.mimeType?.takeIf { it.contains('/') && it != "application/octet-stream" } ?: FileUtil.mimeFor(a.fileName)
                if (mime == "application/pdf") {
                    vm.openGenerated(f, a.fileName, null)
                } else {
                    FileUtil.openWith(context, f, a.fileName, mime)
                }
            } catch (e: Exception) {
                vm.message("Couldn't open the attachment: ${e.message}")
            }
        }
    }

    fun pickImage(purpose: String) {
        imagePurpose = purpose
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun save(then: (() -> Unit)? = null) {
        c.commitPending {
            val uri = session.lastSavedUri
            if (uri != null && FileUtil.canWrite(context, uri)) {
                scope.launch {
                    try {
                        session.saveTo(uri)
                        vm.message("Saved")
                        then?.invoke()
                    } catch (e: Exception) {
                        vm.message("Save failed: ${e.message}")
                    }
                }
            } else {
                pendingSaveThen = then
                saveAsLauncher.launch(session.displayName)
            }
        }
    }

    fun share() {
        c.commitPending {
            scope.launch {
                try {
                    val f = session.exportFile()
                    FileUtil.share(context, f, session.displayName)
                } catch (e: Exception) {
                    vm.message("Share failed: ${e.message}")
                }
            }
        }
    }

    fun print() {
        c.commitPending {
            scope.launch {
                try {
                    val f = session.exportFile()
                    Printing.print(context, f, session.displayName)
                } catch (e: Exception) {
                    vm.message("Print failed: ${e.message}")
                }
            }
        }
    }

    fun startReadAloud() {
        c.readAloud.start(c.viewerScope(), c.viewer.currentPage(), session.pageCount, { p -> c.pageText(p)?.text ?: "" }) { p ->
            c.viewer.scrollToPage(p)
        }
    }

    fun handleLaunch(l: ToolLaunch) {
        when (l) {
            ToolLaunch.COMMENT -> c.openCategory(ToolCategory.COMMENT)
            ToolLaunch.FILL_SIGN -> c.openCategory(ToolCategory.FILL_SIGN)
            ToolLaunch.EDIT -> c.openCategory(ToolCategory.EDIT)
            ToolLaunch.REDACT -> c.openCategory(ToolCategory.REDACT)
            ToolLaunch.ORGANIZE -> c.guardSigned { vm.navigate(Screen.Organize(session.id)) }
            ToolLaunch.COMPRESS -> c.guardSigned { c.dialog = DocDialog.Compress }
            ToolLaunch.PROTECT -> c.dialog = DocDialog.Protect
            ToolLaunch.EXPORT -> c.dialog = DocDialog.Export
            ToolLaunch.OCR -> c.guardSigned { c.dialog = DocDialog.Ocr }
            ToolLaunch.WATERMARK -> c.guardSigned { c.dialog = DocDialog.Watermark }
            ToolLaunch.HEADER_FOOTER -> c.guardSigned { c.dialog = DocDialog.HeaderFooter }
            ToolLaunch.READ_ALOUD -> startReadAloud()
            ToolLaunch.REFLOW -> vm.navigate(Screen.Reflow(session.id))
            ToolLaunch.PRINT -> print()
            ToolLaunch.SEARCH -> c.searchOpen = true
            ToolLaunch.BOOKMARKS -> c.panel = Panel.BOOKMARKS
            ToolLaunch.PROPERTIES -> c.dialog = DocDialog.Properties
            ToolLaunch.FLATTEN -> c.guardSigned { c.dialog = DocDialog.Flatten }
            ToolLaunch.SANITIZE -> c.guardSigned { c.dialog = DocDialog.Sanitize }
            ToolLaunch.PREPARE_FORM -> c.openCategory(ToolCategory.FORMS)
            ToolLaunch.SIGN_CERT -> c.openSigning()
        }
    }

    LaunchedEffect(launch) {
        if (launch != null && !c.launchHandled) {
            c.launchHandled = true
            handleLaunch(launch)
        }
    }

    BackHandler {
        when {
            c.searchOpen -> c.closeSearch()
            editor.selection != null -> editor.selection = null
            editor.selectedAnnot != null -> editor.selectedAnnot = null
            editor.category != null -> c.requestCloseCategory()
            c.readAloud.active -> c.readAloud.stop()
            session.dirty || editor.pending.isNotEmpty() -> c.dialog = DocDialog.ConfirmClose
            else -> vm.back()
        }
    }

    var moreMenu by remember { mutableStateOf(false) }
    var toolsSheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = c.chrome || editor.category != null || c.searchOpen) {
                Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                    if (c.searchOpen) {
                        SearchBar(c)
                    } else {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        (if (session.dirty || editor.pending.isNotEmpty()) "• " else "") + session.displayName,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        "Page ${c.viewer.currentPage() + 1} of ${session.pageCount}" + if (session.restricted) " · Read-only" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable { c.panel = Panel.THUMBNAILS },
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = {
                                    if (session.dirty || editor.pending.isNotEmpty()) c.dialog = DocDialog.ConfirmClose else vm.back()
                                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                            },
                            actions = {
                                IconButton(onClick = { c.undo() }, enabled = c.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                                IconButton(onClick = { c.redo() }, enabled = c.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                                IconButton(onClick = { c.searchOpen = true }) { Icon(Icons.Outlined.Search, "Search") }
                                Box {
                                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                        MenuRow("Save", Icons.Outlined.Save) { moreMenu = false; save() }
                                        MenuRow("Save as…", Icons.Outlined.SaveAs) {
                                            moreMenu = false
                                            c.commitPending { saveAsLauncher.launch(session.displayName) }
                                        }
                                        MenuRow("Share", Icons.Outlined.Share) { moreMenu = false; share() }
                                        MenuRow("Print", Icons.Outlined.Print) { moreMenu = false; print() }
                                        HorizontalDivider()
                                        MenuRow("Go to page", Icons.Outlined.Pages) { moreMenu = false; c.dialog = DocDialog.GoToPage }
                                        MenuRow("Page thumbnails", Icons.Outlined.GridView) { moreMenu = false; c.panel = Panel.THUMBNAILS }
                                        MenuRow("Bookmarks", Icons.Outlined.Bookmarks) { moreMenu = false; c.panel = Panel.BOOKMARKS }
                                        MenuRow("Comments list", Icons.Outlined.ChatBubbleOutline) { moreMenu = false; c.loadAnnotations(); c.panel = Panel.COMMENTS }
                                        MenuRow("Attachments", Icons.Outlined.AttachFile) { moreMenu = false; c.loadAttachments(); c.panel = Panel.ATTACHMENTS }
                                        MenuRow(if (c.night) "Day mode" else "Night mode", if (c.night) Icons.Outlined.LightMode else Icons.Outlined.DarkMode) {
                                            moreMenu = false; c.night = !c.night
                                        }
                                        MenuRow("Read aloud", Icons.Outlined.RecordVoiceOver) { moreMenu = false; startReadAloud() }
                                        MenuRow("Liquid mode", Icons.Outlined.ChromeReaderMode) { moreMenu = false; vm.navigate(Screen.Reflow(session.id)) }
                                        MenuRow("Document properties", Icons.Outlined.Info) { moreMenu = false; c.dialog = DocDialog.Properties }
                                    }
                                }
                            },
                        )
                    }
                    session.busy?.let {
                        LinearProgressIndicator(
                            progress = { session.progress.coerceAtLeast(0f) },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                        )
                    }
                    SignatureBanner(c)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                PdfDocumentView(
                    session = session,
                    state = c.viewer,
                    nightMode = c.night,
                    input = c,
                    modifier = Modifier.fillMaxSize(),
                ) { page -> drawOverlay(c, page) }

                // Floating bars
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (editor.selection != null) SelectionBar(c)
                    editor.selectedAnnot?.let { AnnotationBar(c, it) }
                    if (editor.tool == Tool.EDIT_IMAGE) editor.selectedImage?.let { img ->
                        ImageBar(c, img, onReplace = { pickImage("replace") }, onSave = { saveSelectedImage() })
                    }
                    if (c.readAloud.active) ReadAloudBar(c)
                    if (editor.category == ToolCategory.REDACT && editor.redactions.isNotEmpty()) {
                        FilledTonalButton(onClick = { c.dialog = DocDialog.ApplyRedactions }) {
                            Icon(Icons.Outlined.HideSource, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Apply ${editor.redactions.size} redaction${if (editor.redactions.size == 1) "" else "s"}")
                        }
                    }
                }
                if (!c.chrome && editor.category == null) {
                    Surface(
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                            .clip(RoundedCornerShape(50)).clickable { c.panel = Panel.THUMBNAILS },
                        shape = RoundedCornerShape(50), color = Color(0x99000000),
                    ) {
                        Text("${c.viewer.currentPage() + 1} / ${session.pageCount}", color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            AnimatedVisibility(visible = c.chrome || editor.category != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.navigationBarsPadding()) {
                        val cat = editor.category
                        if (cat == null) {
                            MainToolbar(
                                onCategory = { c.openCategory(it) },
                                onPages = {
                                    if (session.restricted) vm.message("Editing is restricted")
                                    else c.guardSigned { vm.navigate(Screen.Organize(session.id)) }
                                },
                                onMore = { toolsSheet = true },
                            )
                        } else {
                            ToolOptionsRow(c)
                            CategoryToolbar(c, cat, onPickImage = { pickImage("place") })
                        }
                    }
                }
            }
        }

        session.busy?.let { label -> if (session.progress >= 0f || label.endsWith("…")) BusyOverlay(label, session.progress.takeIf { it >= 0f }) }
    }

    if (toolsSheet) {
        MoreToolsSheet(
            onDismiss = { toolsSheet = false },
            onAction = { action ->
                toolsSheet = false
                when (action) {
                    MoreAction.ORGANIZE -> handleLaunch(ToolLaunch.ORGANIZE)
                    MoreAction.COMPRESS -> handleLaunch(ToolLaunch.COMPRESS)
                    MoreAction.PROTECT -> handleLaunch(ToolLaunch.PROTECT)
                    MoreAction.EXPORT -> handleLaunch(ToolLaunch.EXPORT)
                    MoreAction.OCR -> handleLaunch(ToolLaunch.OCR)
                    MoreAction.WATERMARK -> c.guardSigned { c.dialog = DocDialog.Watermark }
                    MoreAction.IMAGE_WATERMARK -> c.guardSigned { pickImage("watermark") }
                    MoreAction.HEADER_FOOTER -> handleLaunch(ToolLaunch.HEADER_FOOTER)
                    MoreAction.FLATTEN -> handleLaunch(ToolLaunch.FLATTEN)
                    MoreAction.SANITIZE -> handleLaunch(ToolLaunch.SANITIZE)
                    MoreAction.PROPERTIES -> handleLaunch(ToolLaunch.PROPERTIES)
                    MoreAction.READ_ALOUD -> startReadAloud()
                    MoreAction.REFLOW -> handleLaunch(ToolLaunch.REFLOW)
                    MoreAction.PRINT -> print()
                    MoreAction.SHARE -> share()
                    MoreAction.BOOKMARKS -> c.panel = Panel.BOOKMARKS
                    MoreAction.COMMENTS -> { c.loadAnnotations(); c.panel = Panel.COMMENTS }
                    MoreAction.FIELDS -> { c.loadFormFields(); c.panel = Panel.FIELDS }
                    MoreAction.SIGNATURES -> { c.verifySignatures(); c.panel = Panel.SIGNATURES }
                    MoreAction.PREPARE_FORM -> handleLaunch(ToolLaunch.PREPARE_FORM)
                    MoreAction.ATTACHMENTS -> { c.loadAttachments(); c.panel = Panel.ATTACHMENTS }
                    MoreAction.DIGITAL_SIGN -> handleLaunch(ToolLaunch.SIGN_CERT)
                    MoreAction.COMPARE -> vm.navigate(Screen.Compare)
                    MoreAction.COMBINE -> vm.navigate(Screen.Combine)
                }
            },
        )
    }

    c.panel?.let { p ->
        DocPanel(
            c, p,
            onAddAttachment = { attachPicker.launch(arrayOf("*/*")) },
            onOpenAttachment = { openAttachment(it) },
            onSaveAttachment = { saveAttachment(it) },
        )
    }
    DocDialogs(
        c = c,
        onExportText = { textLauncher.launch(FileUtil.baseName(session.displayName) + ".txt") },
        onExportDocx = { docxLauncher.launch(FileUtil.baseName(session.displayName) + ".docx") },
        onExportImages = { png, dpi -> imageExport = png to dpi; treeLauncher.launch(null) },
        onExportPdf = { share() },
        onSaveThenClose = { save { vm.back() } },
        onDiscardClose = { vm.back() },
        onExportXlsx = { xlsxLauncher.launch(FileUtil.baseName(session.displayName) + ".xlsx") },
        onExportPptx = { pptxLauncher.launch(FileUtil.baseName(session.displayName) + ".pptx") },
        onImportDigitalId = { then ->
            importThen = then
            idPicker.launch(arrayOf("application/x-pkcs12", "application/pkcs12", "application/octet-stream", "*/*"))
        },
    )
    c.pendingExternalUri?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { c.pendingExternalUri = null },
            title = { Text("Open link?") },
            text = { Text(uri) },
            confirmButton = {
                TextButton(onClick = {
                    c.pendingExternalUri = null
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } catch (e: Exception) { vm.message("No app can open this link") }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { c.pendingExternalUri = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, leadingIcon = { Icon(icon, null) })
}

@Composable
private fun SearchBar(c: DocumentController) {
    var q by remember { mutableStateOf(c.editor.searchQuery) }
    val e = c.editor
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { c.closeSearch() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Search in document") },
                modifier = Modifier.weight(1f),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { c.search(q) }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                trailingIcon = { IconButton(onClick = { c.search(q) }) { Icon(Icons.Outlined.Search, "Search") } },
            )
            IconButton(onClick = { c.nextMatch(-1) }, enabled = e.searchResults.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous") }
            IconButton(onClick = { c.nextMatch(1) }, enabled = e.searchResults.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowDown, "Next") }
        }
        val status = when {
            e.searching -> "Searching… ${(e.searchProgress * 100).toInt()}%"
            e.searchResults.isNotEmpty() -> "${e.searchIndex + 1} of ${e.searchResults.size} on page ${e.searchResults.getOrNull(e.searchIndex)?.pageIndex?.plus(1) ?: "-"}"
            e.searchQuery.isNotBlank() -> "No matches"
            else -> ""
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 56.dp, bottom = 4.dp))
    }
}

@Composable
private fun MainToolbar(onCategory: (ToolCategory) -> Unit, onPages: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        BarButton("Comment", Icons.Outlined.ChatBubbleOutline) { onCategory(ToolCategory.COMMENT) }
        BarButton("Fill & Sign", Icons.Outlined.Draw) { onCategory(ToolCategory.FILL_SIGN) }
        BarButton("Edit", Icons.Outlined.Edit) { onCategory(ToolCategory.EDIT) }
        BarButton("Redact", Icons.Outlined.HideSource) { onCategory(ToolCategory.REDACT) }
        BarButton("Pages", Icons.Outlined.GridView, onClick = onPages)
        BarButton("Tools", Icons.Outlined.Apps, onClick = onMore)
    }
}

@Composable
private fun BarButton(label: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}


fun toolIcon(t: Tool): ImageVector = when (t) {
    Tool.NONE -> Icons.Outlined.PanTool
    Tool.HIGHLIGHT -> Icons.Outlined.BorderColor
    Tool.UNDERLINE -> Icons.Outlined.FormatUnderlined
    Tool.STRIKEOUT -> Icons.Outlined.FormatStrikethrough
    Tool.SQUIGGLY -> Icons.Outlined.Gesture
    Tool.PEN -> Icons.Outlined.Brush
    Tool.RECTANGLE -> Icons.Outlined.CropSquare
    Tool.ELLIPSE -> Icons.Outlined.Circle
    Tool.LINE -> Icons.Outlined.HorizontalRule
    Tool.ARROW -> Icons.Outlined.NorthEast
    Tool.MEASURE -> Icons.Outlined.Straighten
    Tool.NOTE -> Icons.Outlined.StickyNote2
    Tool.TEXT_BOX -> Icons.Outlined.TextFields
    Tool.STAMP -> Icons.Outlined.Approval
    Tool.ERASER -> Icons.Outlined.Delete
    Tool.SELECT -> Icons.Outlined.TouchApp
    Tool.FILL_TEXT -> Icons.Outlined.TextFields
    Tool.FILL_DATE -> Icons.Outlined.CalendarToday
    Tool.FILL_CHECK -> Icons.Outlined.Check
    Tool.FILL_CROSS -> Icons.Outlined.Close
    Tool.FILL_DOT -> Icons.Outlined.FiberManualRecord
    Tool.SIGNATURE -> Icons.Outlined.Draw
    Tool.INITIALS -> Icons.Outlined.Badge
    Tool.FORM -> Icons.Outlined.CheckBox
    Tool.CERT_SIGN -> Icons.Outlined.VerifiedUser
    Tool.ADD_TEXT -> Icons.Outlined.Title
    Tool.EDIT_TEXT -> Icons.Outlined.EditNote
    Tool.ADD_IMAGE -> Icons.Outlined.AddPhotoAlternate
    Tool.WHITEOUT -> Icons.Outlined.FormatColorFill
    Tool.REDACT_AREA -> Icons.Outlined.Rectangle
    Tool.REDACT_TEXT -> Icons.Outlined.SelectAll
    Tool.EDIT_IMAGE -> Icons.Outlined.Image
    Tool.LINK -> Icons.Outlined.Link
    Tool.FIELD_TEXT -> Icons.Outlined.Input
    Tool.FIELD_MULTILINE -> Icons.Outlined.Article
    Tool.FIELD_CHECKBOX -> Icons.Outlined.CheckBox
    Tool.FIELD_RADIO -> Icons.Outlined.RadioButtonChecked
    Tool.FIELD_DROPDOWN -> Icons.Outlined.ArrowDropDownCircle
    Tool.FIELD_LIST -> Icons.Outlined.TableRows
    Tool.FIELD_SIGNATURE -> Icons.Outlined.HistoryEdu
    Tool.FIELD_EDIT -> Icons.Outlined.Tune
}

@Composable
private fun CategoryToolbar(c: DocumentController, cat: ToolCategory, onPickImage: () -> Unit) {
    val e = c.editor
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (e.pending.isNotEmpty() || (cat == ToolCategory.REDACT && e.redactions.isNotEmpty())) c.dialog = DocDialog.ConfirmDiscardTool
            else c.closeCategory(commit = false)
        }) { Icon(Icons.Outlined.Close, "Discard and close") }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (t in Tool.forCategory(cat)) {
                BarButton(t.label, toolIcon(t), selected = e.tool == t) {
                    if (t == Tool.ADD_IMAGE) onPickImage() else c.selectTool(t)
                }
            }
            if (cat == ToolCategory.EDIT) {
                BarButton("Background", Icons.Outlined.Wallpaper) { c.dialog = DocDialog.Background }
            }
            if (cat == ToolCategory.REDACT) {
                BarButton("Search & redact", Icons.Outlined.FindInPage) { c.dialog = DocDialog.SearchRedact }
                BarButton("Redact pages", Icons.Outlined.Pages) { c.dialog = DocDialog.RedactPages }
                BarButton("Sanitize", Icons.Outlined.CleaningServices) { c.dialog = DocDialog.Sanitize }
            }
            if ((cat == ToolCategory.FILL_SIGN || cat == ToolCategory.FORMS) && c.editor.formFields.isNotEmpty()) {
                BarButton("Fields list", Icons.Outlined.ViewAgenda) { c.panel = Panel.FIELDS }
            }
        }
        IconButton(onClick = {
            if (cat == ToolCategory.REDACT) {
                if (e.redactions.isNotEmpty()) c.dialog = DocDialog.ApplyRedactions else c.closeCategory(false)
            } else c.closeCategory(commit = true)
        }) {
            Icon(Icons.Outlined.Done, "Done", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private val PALETTE = listOf(
    0xFFFFEB3B.toInt(), 0xFF8BC34A.toInt(), 0xFF4FC3F7.toInt(), 0xFFF48FB1.toInt(), 0xFFFF9800.toInt(),
    0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF000000.toInt(), 0xFF8E24AA.toInt(),
)

@Composable
private fun ToolOptionsRow(c: DocumentController) {
    val t = c.editor.tool
    val prefs = c.vm.prefs
    val target: Int? = when {
        t == Tool.HIGHLIGHT -> prefs.highlightColor
        t == Tool.PEN -> prefs.penColor
        t.isMarkup || t.shapeKind != null || t == Tool.MEASURE -> prefs.shapeColor
        t == Tool.TEXT_BOX || t == Tool.FILL_TEXT || t == Tool.ADD_TEXT || t == Tool.FILL_DATE ||
            t == Tool.FILL_CHECK || t == Tool.FILL_CROSS || t == Tool.FILL_DOT -> prefs.textColor
        else -> null
    }
    if (target == null && t != Tool.STAMP && t != Tool.SIGNATURE && t != Tool.INITIALS) {
        val hint = when (t) {
            Tool.NONE -> "Choose a tool below"
            Tool.EDIT_TEXT -> "Tap a line of text to edit it"
            Tool.WHITEOUT -> "Drag over content to permanently cover it with white"
            Tool.REDACT_AREA -> "Drag to mark areas; tap a mark to remove it"
            Tool.REDACT_TEXT -> "Drag across text or tap a word to mark it"
            Tool.ERASER -> "Tap a comment to delete it"
            Tool.SELECT -> "Tap a comment to select; drag it to move"
            Tool.FORM -> "Tap a form field to fill it"
            Tool.CERT_SIGN -> "Drag a box where the digital signature should appear"
            Tool.FIELD_EDIT -> "Tap a field to rename or delete it"
            Tool.EDIT_IMAGE -> "Tap an image to select it, drag to move, drag a corner to resize"
            Tool.LINK -> "Drag a box to add a link; tap a link to remove it"
            Tool.FIELD_CHECKBOX, Tool.FIELD_RADIO -> "Tap where the ${t.label.lowercase()} should go"
            Tool.FIELD_TEXT, Tool.FIELD_MULTILINE, Tool.FIELD_DROPDOWN, Tool.FIELD_LIST, Tool.FIELD_SIGNATURE ->
                "Drag to draw the ${t.label.lowercase()}, or tap to place one"
            Tool.ADD_IMAGE -> "Tap the page to place the image"
            else -> ""
        }
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (t) {
            Tool.STAMP -> {
                TextButton(onClick = { c.dialog = DocDialog.StampPicker }) { Text("Stamp: ${c.editor.stamp.label}") }
            }
            Tool.SIGNATURE, Tool.INITIALS -> {
                TextButton(onClick = { c.dialog = DocDialog.Signature(t == Tool.INITIALS, null) }) {
                    Text(if (t == Tool.SIGNATURE) "Choose signature" else "Choose initials")
                }
                Text("Tap where to sign", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                for (col in PALETTE) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(Color(col))
                            .then(if (col == target) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable {
                                when {
                                    t == Tool.HIGHLIGHT -> prefs.updateHighlightColor(col)
                                    t == Tool.PEN -> prefs.updatePenColor(col)
                                    t.isMarkup || t.shapeKind != null || t == Tool.MEASURE -> prefs.updateShapeColor(col)
                                    else -> prefs.updateTextColor(col)
                                }
                            }
                    )
                }
                if (t == Tool.PEN || t.shapeKind != null) {
                    Spacer(Modifier.width(8.dp))
                    for (w in listOf(1f, 2f, 4f, 8f)) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape)
                                .background(if (prefs.penWidth == w) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { prefs.updatePenWidth(w) },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size((4 + w * 1.5f).dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)) }
                    }
                }
                if (t == Tool.TEXT_BOX || t == Tool.FILL_TEXT || t == Tool.ADD_TEXT || t == Tool.FILL_DATE) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { prefs.updateTextSize((prefs.textSize - 1f).coerceAtLeast(6f)) }) { Icon(Icons.Outlined.TextDecrease, "Smaller") }
                    Text("${prefs.textSize.toInt()} pt", fontWeight = FontWeight.Medium)
                    IconButton(onClick = { prefs.updateTextSize((prefs.textSize + 1f).coerceAtMost(72f)) }) { Icon(Icons.Outlined.TextIncrease, "Larger") }
                }
                if (t == Tool.MEASURE) {
                    Spacer(Modifier.width(8.dp))
                    for (u in listOf("mm", "cm", "m", "in", "ft", "pt")) {
                        androidx.compose.material3.FilterChip(
                            selected = prefs.measureUnit == u, onClick = { prefs.updateMeasureUnit(u) }, label = { Text(u) },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    for (sc in listOf(1f, 10f, 50f, 100f, 1000f)) {
                        androidx.compose.material3.FilterChip(
                            selected = prefs.measureScale == sc, onClick = { prefs.updateMeasureScale(sc) },
                            label = { Text("1:${sc.toInt()}") },
                        )
                    }
                }
                if (t == Tool.FILL_TEXT) {
                    // Autofill: pick a saved detail, then tap where it goes.
                    for (key in com.tandidong.pdf.core.Autofill.KEYS) {
                        val v = prefs.autofill[key.id] ?: continue
                        androidx.compose.material3.FilterChip(
                            selected = c.editor.autofillText == v,
                            onClick = { c.editor.autofillText = if (c.editor.autofillText == v) null else v },
                            label = { Text(v, maxLines = 1) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(c: DocumentController) {
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { c.copySelection() }) { Icon(Icons.Outlined.ContentCopy, "Copy") }
            if (!c.session.restricted) {
                IconButton(onClick = { c.markupSelection(Tool.HIGHLIGHT) }) { Icon(Icons.Outlined.BorderColor, "Highlight") }
                IconButton(onClick = { c.markupSelection(Tool.UNDERLINE) }) { Icon(Icons.Outlined.FormatUnderlined, "Underline") }
                IconButton(onClick = { c.markupSelection(Tool.STRIKEOUT) }) { Icon(Icons.Outlined.FormatStrikethrough, "Strikethrough") }
                IconButton(onClick = { c.redactSelection() }) { Icon(Icons.Outlined.HideSource, "Redact") }
            }
            val ctx = LocalContext.current
            IconButton(onClick = { c.selectionText()?.let { FileUtil.shareText(ctx, it) }; c.editor.selection = null }) { Icon(Icons.Outlined.Share, "Share text") }
        }
    }
}

@Composable
private fun AnnotationBar(c: DocumentController, a: com.tandidong.pdf.core.AnnotationInfo) {
    var colors by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = Modifier.padding(bottom = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (colors) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in PALETTE) Box(Modifier.size(24.dp).clip(CircleShape).background(Color(col)).clickable {
                        colors = false
                        c.recolorAnnotation(a, col)
                    })
                }
            }
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.subtype, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = { colors = !colors }) { Icon(Icons.Outlined.Palette, "Color") }
                IconButton(onClick = { c.dialog = DocDialog.EditComment(a) }) { Icon(Icons.Outlined.EditNote, "Edit note") }
                IconButton(onClick = { c.dialog = DocDialog.Reply(a) }) { Icon(Icons.AutoMirrored.Outlined.Reply, "Reply") }
                IconButton(onClick = { c.deleteAnnotation(a) }) { Icon(Icons.Outlined.Delete, "Delete") }
                IconButton(onClick = { c.editor.selectedAnnot = null }) { Icon(Icons.Outlined.Close, "Deselect") }
            }
        }
    }
}

@Composable
private fun ImageBar(c: DocumentController, img: com.tandidong.pdf.core.PlacedImage, onReplace: () -> Unit, onSave: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(img.sizeLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onReplace) { Icon(Icons.Outlined.SwapHoriz, "Replace image") }
            IconButton(onClick = onSave, enabled = !img.inline) { Icon(Icons.Outlined.Download, "Save image") }
            IconButton(onClick = { c.deleteImage(img) }) { Icon(Icons.Outlined.Delete, "Delete image") }
            IconButton(onClick = { c.editor.selectedImage = null }) { Icon(Icons.Outlined.Close, "Deselect") }
        }
    }
}

@Composable
private fun ReadAloudBar(c: DocumentController) {
    val r = c.readAloud
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp, modifier = Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.RecordVoiceOver, null, modifier = Modifier.padding(horizontal = 8.dp))
            Text("Page ${r.page + 1}", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { r.previous() }) { Icon(Icons.Outlined.SkipPrevious, "Previous page") }
            if (r.speaking) IconButton(onClick = { r.pause() }) { Icon(Icons.Outlined.Pause, "Pause") }
            else IconButton(onClick = { r.resume() }) { Icon(Icons.Outlined.PlayArrow, "Play") }
            IconButton(onClick = { r.next() }) { Icon(Icons.Outlined.SkipNext, "Next page") }
            IconButton(onClick = { r.stop() }) { Icon(Icons.Outlined.Stop, "Stop") }
        }
    }
}


@Composable
private fun SignatureBanner(c: DocumentController) {
    val list = c.signatureStatuses
    if (list.isNullOrEmpty() || c.searchOpen) return
    val allGood = list.all { it.isValid && it.coversWholeDocument }
    val broken = list.any { it.error != null || !it.integrityValid }
    val (bg, fg, text) = when {
        broken -> Triple(Color(0xFFFFEBEE), Color(0xFFB71C1C), "At least one signature is invalid")
        allGood -> Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20),
            if (list.size == 1) "Signed by ${list[0].signerName} · signature valid" else "Signed · all ${list.size} signatures valid")
        else -> Triple(Color(0xFFFFF8E1), Color(0xFF8D6E00), "Signed · see signature details")
    }
    Row(
        Modifier.fillMaxWidth().background(bg).clickable { c.panel = Panel.SIGNATURES }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (broken) Icons.Outlined.ErrorOutline else Icons.Outlined.VerifiedUser, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
