package com.tandidong.pdf.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tandidong.pdf.core.FieldKind
import com.tandidong.pdf.core.Outline
import com.tandidong.pdf.core.OutlineEntry

enum class MoreAction(val label: String) {
    ORGANIZE("Organize pages"), COMBINE("Combine files"), COMPRESS("Compress"), PROTECT("Protect"),
    EXPORT("Export"), OCR("Recognize text"), WATERMARK("Text watermark"), IMAGE_WATERMARK("Image watermark"),
    HEADER_FOOTER("Headers & page numbers"), FLATTEN("Flatten"), SANITIZE("Sanitize"), PROPERTIES("Properties"),
    READ_ALOUD("Read aloud"), REFLOW("Liquid mode"), BOOKMARKS("Bookmarks"), COMMENTS("Comments list"),
    FIELDS("Form fields"), PREPARE_FORM("Prepare form"), DIGITAL_SIGN("Digital signature"), SIGNATURES("Signatures"),
    ATTACHMENTS("Attachments"),
    COMPARE("Compare files"), PRINT("Print"), SHARE("Share"),
}

private fun moreIcon(a: MoreAction): ImageVector = when (a) {
    MoreAction.ORGANIZE -> Icons.Outlined.GridView
    MoreAction.COMBINE -> Icons.Outlined.MergeType
    MoreAction.COMPRESS -> Icons.Outlined.Compress
    MoreAction.PROTECT -> Icons.Outlined.Lock
    MoreAction.EXPORT -> Icons.Outlined.IosShare
    MoreAction.OCR -> Icons.Outlined.FindInPage
    MoreAction.WATERMARK -> Icons.Outlined.WaterDrop
    MoreAction.IMAGE_WATERMARK -> Icons.Outlined.Image
    MoreAction.HEADER_FOOTER -> Icons.Outlined.FormatListNumbered
    MoreAction.FLATTEN -> Icons.Outlined.Layers
    MoreAction.SANITIZE -> Icons.Outlined.CleaningServices
    MoreAction.PROPERTIES -> Icons.Outlined.Info
    MoreAction.READ_ALOUD -> Icons.Outlined.RecordVoiceOver
    MoreAction.REFLOW -> Icons.Outlined.ChromeReaderMode
    MoreAction.BOOKMARKS -> Icons.Outlined.Bookmarks
    MoreAction.COMMENTS -> Icons.Outlined.ChatBubbleOutline
    MoreAction.FIELDS -> Icons.Outlined.CheckBox
    MoreAction.SIGNATURES -> Icons.Outlined.VerifiedUser
    MoreAction.PREPARE_FORM -> Icons.Outlined.DynamicForm
    MoreAction.ATTACHMENTS -> Icons.Outlined.AttachFile
    MoreAction.DIGITAL_SIGN -> Icons.Outlined.HistoryEdu
    MoreAction.COMPARE -> Icons.Outlined.Compare
    MoreAction.PRINT -> Icons.Outlined.Print
    MoreAction.SHARE -> Icons.Outlined.Share
}

@Composable
fun MoreToolsSheet(onDismiss: () -> Unit, onAction: (MoreAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("All tools", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            for (row in MoreAction.values().toList().chunked(4)) {
                Row(Modifier.fillMaxWidth()) {
                    for (a in row) {
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onAction(a) }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(moreIcon(a), null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(a.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun DocPanel(
    c: DocumentController,
    panel: Panel,
    onAddAttachment: () -> Unit = {},
    onOpenAttachment: (com.tandidong.pdf.core.AttachmentInfo) -> Unit = {},
    onSaveAttachment: (com.tandidong.pdf.core.AttachmentInfo) -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = { c.panel = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        when (panel) {
            Panel.BOOKMARKS -> BookmarksPanel(c)
            Panel.COMMENTS -> CommentsPanel(c)
            Panel.FIELDS -> FieldsPanel(c)
            Panel.SIGNATURES -> SignaturesPanel(c)
            Panel.ATTACHMENTS -> AttachmentsPanel(c, onAddAttachment, onOpenAttachment, onSaveAttachment)
            Panel.THUMBNAILS -> ThumbnailsPanel(c)
        }
    }
}

@Composable
private fun ThumbnailsPanel(c: DocumentController) {
    val session = c.session
    val version = session.version
    val current = c.viewer.currentPage()
    val thumbs = remember(version) { androidx.compose.runtime.mutableStateMapOf<Int, androidx.compose.ui.graphics.ImageBitmap>() }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(initialFirstVisibleItemIndex = current)
    Text("Pages (${session.pageCount})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(104.dp),
        state = gridState,
        modifier = Modifier.heightIn(max = 600.dp).padding(horizontal = 8.dp),
    ) {
        items(session.pageCount) { i ->
            LaunchedEffect(i, version) {
                if (!thumbs.containsKey(i)) session.renderPage(i, 220, version)?.let { thumbs[i] = it.asImageBitmap() }
            }
            val geo = session.geometries.getOrNull(i)
            val aspect = if (geo != null && geo.displayHeight > 0f) geo.displayWidth / geo.displayHeight else 0.75f
            Column(
                Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).clickable {
                    c.panel = null
                    c.viewer.scrollToPage(i)
                }.semantics(mergeDescendants = true) { contentDescription = "Go to page ${i + 1}" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.3f, 3f))
                        .border(if (i == current) 3.dp else 1.dp,
                            if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(4.dp))
                        .background(Color.White),
                ) {
                    thumbs[i]?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun AttachmentsPanel(
    c: DocumentController,
    onAdd: () -> Unit,
    onOpen: (com.tandidong.pdf.core.AttachmentInfo) -> Unit,
    onSave: (com.tandidong.pdf.core.AttachmentInfo) -> Unit,
) {
    val list = c.attachments
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Attachments (${list.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (!c.session.restricted) {
            TextButton(onClick = { c.guardSigned(onAdd) }) {
                Icon(Icons.Outlined.AttachFile, null)
                Text(" Add file")
            }
        }
    }
    if (list.isEmpty()) {
        Text("No files are attached to this document. Attach spreadsheets, images or other files to keep them with the PDF.",
            modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            items(list) { a ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(a) },
                    leadingContent = { Icon(Icons.Outlined.Description, null) },
                    headlineContent = { Text(a.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val parts = listOfNotNull(
                            com.tandidong.pdf.data.FileUtil.formatSize(a.size).takeIf { a.size > 0 },
                            a.pageIndex?.let { "comment on page ${it + 1}" },
                            a.description?.takeIf { it.isNotBlank() },
                        )
                        Text(parts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onSave(a) }) { Icon(Icons.Outlined.Download, "Save attachment") }
                            if (!c.session.restricted) {
                                IconButton(onClick = { c.guardSigned { c.deleteAttachment(a) } }) { Icon(Icons.Outlined.Delete, "Remove attachment") }
                            }
                        }
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun BookmarksPanel(c: DocumentController) {
    var entries by remember { mutableStateOf<List<OutlineEntry>?>(null) }
    val version = c.session.version
    LaunchedEffect(version) {
        entries = try { c.session.read { Outline.read(it) } } catch (e: Exception) { emptyList() }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (!c.session.restricted) {
            TextButton(onClick = {
                val p = c.viewer.currentPage()
                c.dialog = DocDialog.BookmarkName(null, "Page ${p + 1}", p)
            }) {
                Icon(Icons.Outlined.BookmarkAdd, null)
                Text(" Add")
            }
        }
    }
    val list = entries
    when {
        list == null -> Text("Loading…", modifier = Modifier.padding(16.dp))
        list.isEmpty() -> Text("This document has no bookmarks. Tap Add to bookmark the current page.", modifier = Modifier.padding(16.dp))
        else -> LazyColumn(Modifier.heightIn(max = 520.dp)) {
            val flat = ArrayList<OutlineEntry>()
            fun walk(es: List<OutlineEntry>) { for (e in es) { flat.add(e); walk(e.children) } }
            walk(list)
            items(flat) { e -> BookmarkRow(c, e) }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun BookmarkRow(c: DocumentController, e: OutlineEntry) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable {
            e.pageIndex?.let { c.viewer.scrollToPage(it) }
            c.panel = null
        },
        headlineContent = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = (12 * (e.path.size - 1)).dp)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.pageIndex?.let { "p. ${it + 1}" } ?: "", style = MaterialTheme.typography.labelMedium)
                if (!c.session.restricted) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {
                            menu = false
                            c.dialog = DocDialog.BookmarkName(e.path, e.title, e.pageIndex ?: 0)
                        })
                        DropdownMenuItem(text = { Text("Add child bookmark here") }, onClick = {
                            menu = false
                            val p = c.viewer.currentPage()
                            c.dialog = DocDialog.BookmarkName(e.path + listOf(-1), "Page ${p + 1}", p)
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                            menu = false
                            c.launchJob {
                                try { c.session.edit("Updating bookmarks…") { Outline.delete(it, e.path) } } catch (ex: Exception) { c.vm.message("Couldn't delete") }
                            }
                        })
                    }
                }
            }
        },
    )
}

@Composable
private fun CommentsPanel(c: DocumentController) {
    val list = c.editor.annotations
    // Threads: each top-level comment followed by its replies (any depth), in page order.
    val byParent = list.filter { it.replyTo != null }.groupBy { it.pageIndex to it.replyTo }
    val ordered = ArrayList<Pair<com.tandidong.pdf.core.AnnotationInfo, Int>>()
    fun addThread(a: com.tandidong.pdf.core.AnnotationInfo, depth: Int) {
        if (depth > 8) return
        ordered.add(a to depth)
        for (r in byParent[a.pageIndex to a.index].orEmpty()) addThread(r, depth + 1)
    }
    for (a in list.filter { it.replyTo == null }) addThread(a, 0)
    val topLevel = list.count { it.replyTo == null }
    Text("Comments ($topLevel)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    if (list.isEmpty()) {
        Text("No comments or markups in this document.", modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            items(ordered) { (a, depth) ->
                ListItem(
                    modifier = Modifier.padding(start = (depth * 28).dp).clickable {
                        c.viewer.reveal(a.pageIndex, a.displayRect)
                        c.panel = null
                    },
                    leadingContent = {
                        Icon(if (depth > 0) Icons.AutoMirrored.Outlined.Reply else Icons.Outlined.ChatBubbleOutline, null)
                    },
                    headlineContent = { Text(a.contents?.takeIf { it.isNotBlank() } ?: a.subtype, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val who = a.author?.takeIf { it.isNotBlank() }
                        Text(
                            if (depth > 0) listOfNotNull(who, "reply").joinToString(" · ")
                            else listOfNotNull("Page ${a.pageIndex + 1}", a.subtype, who).joinToString(" · ")
                        )
                    },
                    trailingContent = {
                        if (!c.session.restricted) {
                            Row {
                                IconButton(onClick = { c.dialog = DocDialog.Reply(a) }) { Icon(Icons.AutoMirrored.Outlined.Reply, "Reply") }
                                IconButton(onClick = { c.deleteAnnotation(a) }) { Icon(Icons.Outlined.Delete, "Delete") }
                            }
                        }
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun FieldsPanel(c: DocumentController) {
    val fields = c.editor.formFields
    val preparing = c.editor.category == ToolCategory.FORMS
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Form fields (${fields.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (fields.isNotEmpty() && !c.session.restricted) {
            TextButton(onClick = {
                c.launchJob {
                    try {
                        c.session.edit("Clearing form…") { com.tandidong.pdf.core.Forms.reset(it) }
                        c.vm.message("Form cleared")
                    } catch (e: Exception) {
                        c.vm.message("Couldn't clear form: ${e.message}")
                    }
                }
            }) { Text("Clear form") }
        }
    }
    if (fields.isEmpty()) {
        Text("This document has no fillable form fields. Use the Text, Date and Check tools to fill it.", modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            items(fields) { f ->
                ListItem(
                    modifier = Modifier.clickable(enabled = preparing || (!f.readOnly && f.kind != FieldKind.BUTTON)) {
                        c.panel = null
                        f.widgets.firstOrNull()?.let { w -> if (w.pageIndex !in c.viewer.visiblePages()) c.viewer.scrollToPage(w.pageIndex) }
                        if (preparing) c.dialog = DocDialog.FieldProperties(f)
                        else c.openField(f, f.widgets.firstOrNull()?.pageIndex ?: 0)
                    },
                    headlineContent = { Text(f.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val v = when (f.kind) {
                            FieldKind.CHECKBOX -> if (f.isChecked) "Checked" else "Not checked"
                            FieldKind.SIGNATURE -> if (f.value.isNotEmpty()) "Signed" else "Signature field · tap to sign"
                            FieldKind.BUTTON -> "Button"
                            else -> f.value.ifBlank { "Empty" }
                        }
                        Text(v + (if (f.required) " · Required" else "") + (if (f.readOnly) " · Read-only" else ""), maxLines = 1)
                    },
                    trailingContent = {
                        Text(f.widgets.firstOrNull()?.let { "p. ${it.pageIndex + 1}" } ?: "", style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Suppress("unused")
private val unusedSize = Modifier.size(1.dp)

@Suppress("unused")
private val unusedArrangement = Arrangement.Center


@Composable
private fun SignaturesPanel(c: DocumentController) {
    val list = c.signatureStatuses
    Text("Signatures", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    when {
        list == null -> Text("Checking signatures…", modifier = Modifier.padding(16.dp))
        list.isEmpty() -> Text(
            "This document has no digital signatures. Use Fill & Sign → Digital ID to sign it with a certificate.",
            modifier = Modifier.padding(16.dp),
        )
        else -> LazyColumn(Modifier.heightIn(max = 600.dp)) {
            items(list) { s ->
                val ok = s.isValid
                val color = when {
                    s.error != null || !s.integrityValid -> MaterialTheme.colorScheme.error
                    !ok || s.selfSigned || !s.coversWholeDocument -> androidx.compose.ui.graphics.Color(0xFFB26A00)
                    else -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                }
                ListItem(
                    modifier = Modifier.clickable(enabled = s.pageIndex != null) {
                        val p = s.pageIndex ?: return@clickable
                        val r = s.displayRect
                        if (r != null) c.viewer.reveal(p, r) else c.viewer.scrollToPage(p)
                        c.panel = null
                    },
                    leadingContent = {
                        Icon(
                            if (s.error != null || !s.integrityValid) Icons.Outlined.ErrorOutline
                            else if (ok && s.coversWholeDocument && !s.selfSigned) Icons.Outlined.VerifiedUser else Icons.Outlined.WarningAmber,
                            null, tint = color,
                        )
                    },
                    headlineContent = { Text("Signed by ${s.signerName}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        val lines = buildList {
                            s.signDate?.let { add("Signed ${df.format(it.time)}") }
                            when {
                                s.error != null -> add("Could not validate: ${s.error}")
                                !s.integrityValid -> add("INVALID: the signed content has been altered")
                                !s.coversWholeDocument -> add("Valid for the signed revision; the document was changed after signing")
                                else -> add("The document has not been modified since it was signed")
                            }
                            if (s.error == null) {
                                if (!s.certificateValidAtSigning) add("The certificate was not valid at signing time")
                                add(if (s.selfSigned) "Self-signed certificate: the signer's identity isn't verified by an authority"
                                else "Certificate issued by ${s.certificateIssuer}")
                                s.certificateValidTo?.let { add("Certificate valid until ${java.text.DateFormat.getDateInstance().format(it)}") }
                            }
                            s.reason?.takeIf { it.isNotBlank() }?.let { add("Reason: $it") }
                            s.location?.takeIf { it.isNotBlank() }?.let { add("Location: $it") }
                            s.pageIndex?.let { add("Page ${it + 1}") }
                        }
                        Text(lines.joinToString("\n"))
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}
