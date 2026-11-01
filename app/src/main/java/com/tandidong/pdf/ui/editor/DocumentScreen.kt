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
            } else {
                editor.imageToPlace = bmp
                editor.category = ToolCategory.EDIT
                editor.tool = Tool.ADD_IMAGE
                vm.message("Tap the page to place the image")
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
            ToolLaunch.ORGANIZE -> vm.navigate(Screen.Organize(session.id))
            ToolLaunch.COMPRESS -> c.dialog = DocDialog.Compress
            ToolLaunch.PROTECT -> c.dialog = DocDialog.Protect
            ToolLaunch.EXPORT -> c.dialog = DocDialog.Export
            ToolLaunch.OCR -> c.dialog = DocDialog.Ocr
            ToolLaunch.WATERMARK -> c.dialog = DocDialog.Watermark
            ToolLaunch.HEADER_FOOTER -> c.dialog = DocDialog.HeaderFooter
            ToolLaunch.READ_ALOUD -> startReadAloud()
            ToolLaunch.REFLOW -> vm.navigate(Screen.Reflow(session.id))
            ToolLaunch.PRINT -> print()
            ToolLaunch.SEARCH -> c.searchOpen = true
            ToolLaunch.BOOKMARKS -> c.panel = Panel.BOOKMARKS
            ToolLaunch.PROPERTIES -> c.dialog = DocDialog.Properties
            ToolLaunch.FLATTEN -> c.dialog = DocDialog.Flatten
            ToolLaunch.SANITIZE -> c.dialog = DocDialog.Sanitize
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
                                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
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
                                        MenuRow("Bookmarks", Icons.Outlined.Bookmarks) { moreMenu = false; c.panel = Panel.BOOKMARKS }
                                        MenuRow("Comments list", Icons.Outlined.ChatBubbleOutline) { moreMenu = false; c.loadAnnotations(); c.panel = Panel.COMMENTS }
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
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
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
                                onPages = { if (session.restricted) vm.message("Editing is restricted") else vm.navigate(Screen.Organize(session.id)) },
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
                    MoreAction.WATERMARK -> c.dialog = DocDialog.Watermark
                    MoreAction.IMAGE_WATERMARK -> pickImage("watermark")
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
                    MoreAction.COMPARE -> vm.navigate(Screen.Compare)
                    MoreAction.COMBINE -> vm.navigate(Screen.Combine)
                }
            },
        )
    }

    c.panel?.let { p -> DocPanel(c, p) }
    DocDialogs(
        c = c,
        onExportText = { textLauncher.launch(FileUtil.baseName(session.displayName) + ".txt") },
        onExportDocx = { docxLauncher.launch(FileUtil.baseName(session.displayName) + ".docx") },
        onExportImages = { png, dpi -> imageExport = png to dpi; treeLauncher.launch(null) },
        onExportPdf = { share() },
        onSaveThenClose = { save { vm.back() } },
        onDiscardClose = { vm.back() },
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
        BarButton("More", Icons.Outlined.Apps, onClick = onMore)
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
    Tool.ADD_TEXT -> Icons.Outlined.Title
    Tool.EDIT_TEXT -> Icons.Outlined.EditNote
    Tool.ADD_IMAGE -> Icons.Outlined.AddPhotoAlternate
    Tool.WHITEOUT -> Icons.Outlined.FormatColorFill
    Tool.REDACT_AREA -> Icons.Outlined.Rectangle
    Tool.REDACT_TEXT -> Icons.Outlined.SelectAll
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
            if (cat == ToolCategory.REDACT) {
                BarButton("Search & redact", Icons.Outlined.FindInPage) { c.dialog = DocDialog.SearchRedact }
                BarButton("Redact pages", Icons.Outlined.Pages) { c.dialog = DocDialog.RedactPages }
                BarButton("Sanitize", Icons.Outlined.CleaningServices) { c.dialog = DocDialog.Sanitize }
            }
            if (cat == ToolCategory.FILL_SIGN && c.editor.formFields.isNotEmpty()) {
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
        t.isMarkup || t.shapeKind != null -> prefs.shapeColor
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
                                    t.isMarkup || t.shapeKind != null -> prefs.updateShapeColor(col)
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
                IconButton(onClick = { c.deleteAnnotation(a) }) { Icon(Icons.Outlined.Delete, "Delete") }
                IconButton(onClick = { c.editor.selectedAnnot = null }) { Icon(Icons.Outlined.Close, "Deselect") }
            }
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
