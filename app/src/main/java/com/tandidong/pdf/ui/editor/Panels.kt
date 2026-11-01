package com.tandidong.pdf.ui.editor

import androidx.compose.foundation.clickable
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
    FIELDS("Form fields"), COMPARE("Compare files"), PRINT("Print"), SHARE("Share"),
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
fun DocPanel(c: DocumentController, panel: Panel) {
    ModalBottomSheet(onDismissRequest = { c.panel = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        when (panel) {
            Panel.BOOKMARKS -> BookmarksPanel(c)
            Panel.COMMENTS -> CommentsPanel(c)
            Panel.FIELDS -> FieldsPanel(c)
        }
    }
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
    Text("Comments (${list.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    if (list.isEmpty()) {
        Text("No comments or markups in this document.", modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            items(list) { a ->
                ListItem(
                    modifier = Modifier.clickable {
                        c.viewer.reveal(a.pageIndex, a.displayRect)
                        c.panel = null
                    },
                    leadingContent = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                    headlineContent = { Text(a.contents?.takeIf { it.isNotBlank() } ?: a.subtype, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull("Page ${a.pageIndex + 1}", a.subtype, a.author?.takeIf { it.isNotBlank() }).joinToString(" · "))
                    },
                    trailingContent = {
                        if (!c.session.restricted) IconButton(onClick = { c.deleteAnnotation(a) }) { Icon(Icons.Outlined.Delete, "Delete") }
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
                    modifier = Modifier.clickable(enabled = !f.readOnly && f.kind != FieldKind.SIGNATURE && f.kind != FieldKind.BUTTON) {
                        c.panel = null
                        c.dialog = DocDialog.FieldEdit(f)
                    },
                    headlineContent = { Text(f.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val v = when (f.kind) {
                            FieldKind.CHECKBOX -> if (f.isChecked) "Checked" else "Not checked"
                            FieldKind.SIGNATURE -> "Signature field"
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
