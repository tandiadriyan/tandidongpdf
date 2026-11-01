package com.tandidong.pdf.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tandidong.pdf.core.Anchor
import com.tandidong.pdf.core.CompressOptions
import com.tandidong.pdf.core.Compressor
import com.tandidong.pdf.core.DocInfo
import com.tandidong.pdf.core.DocProperties
import com.tandidong.pdf.core.FRect
import com.tandidong.pdf.core.FieldKind
import com.tandidong.pdf.core.Flattener
import com.tandidong.pdf.core.HeaderFooter
import com.tandidong.pdf.core.ImageWatermark
import com.tandidong.pdf.core.OcrLayerWriter
import com.tandidong.pdf.core.OcrWord
import com.tandidong.pdf.core.Outline
import com.tandidong.pdf.core.PageOps
import com.tandidong.pdf.core.Permissions
import com.tandidong.pdf.core.Pt
import com.tandidong.pdf.core.RedactionOptions
import com.tandidong.pdf.core.SanitizeOptions
import com.tandidong.pdf.core.Sanitizer
import com.tandidong.pdf.core.StampPreset
import com.tandidong.pdf.core.Stamper
import com.tandidong.pdf.core.TextWatermark
import com.tandidong.pdf.data.FileUtil
import com.tandidong.pdf.data.SavedSignature
import com.tandidong.pdf.platform.AndroidImageCodec
import com.tandidong.pdf.session.ProtectionSpec
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.text.DateFormat
import kotlin.math.max

@Composable
fun DocDialogs(
    c: DocumentController,
    onExportText: () -> Unit,
    onExportDocx: () -> Unit,
    onExportImages: (png: Boolean, dpi: Int) -> Unit,
    onExportPdf: () -> Unit,
    onSaveThenClose: () -> Unit,
    onDiscardClose: () -> Unit,
    onImportDigitalId: (then: DocDialog?) -> Unit = {},
    onExportXlsx: () -> Unit = {},
    onExportPptx: () -> Unit = {},
) {
    val d = c.dialog ?: return
    val dismiss = { c.dialog = null }
    when (d) {
        is DocDialog.CertSign -> CertSignDialog(c, d, dismiss, onImportDigitalId)
        is DocDialog.CreateDigitalId -> CreateDigitalIdDialog(c, d, dismiss)
        is DocDialog.ImportDigitalId -> ImportDigitalIdDialog(c, d, dismiss)
        is DocDialog.NewFormField -> NewFormFieldDialog(c, d, dismiss)
        is DocDialog.NewLink -> NewLinkDialog(c, d, dismiss)
        is DocDialog.LinkActions -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Link") },
            text = { Text(d.link.targetPage?.let { "Goes to page ${it + 1}" } ?: d.link.uri?.let { "Opens $it" } ?: "Link") },
            confirmButton = { TextButton(onClick = { dismiss(); c.removeLinkAt(d.page, d.at) }) { Text("Remove link") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
        DocDialog.Background -> BackgroundDialog(c, dismiss)
        is DocDialog.Reply -> TextInputDialog(
            "Reply to ${d.info.author?.takeIf { it.isNotBlank() } ?: "comment"}", "", label = d.info.contents?.take(80) ?: "",
            multiline = true, onDismiss = dismiss,
        ) { t ->
            dismiss(); c.replyToComment(d.info, t)
        }
        is DocDialog.FieldProperties -> FieldPropertiesDialog(c, d, dismiss)
        is DocDialog.SignedWarning -> ConfirmDialog(
            "This document is digitally signed",
            "Changing it (comments, fields, pages or other edits) will invalidate the existing digital signatures. " +
                "Adding another digital signature keeps them valid. Continue?",
            "Continue", dismiss,
        ) {
            dismiss()
            d.proceed()
        }
        is DocDialog.Note -> TextInputDialog("Add note", "", multiline = true, onDismiss = dismiss) { t ->
            dismiss(); c.addNote(d.page, d.at, t)
        }
        is DocDialog.TextEntry -> TextEntryDialog(c, d, dismiss)
        is DocDialog.EditText -> EditTextDialog(c, d, dismiss)
        is DocDialog.FieldEdit -> FieldDialog(c, d, dismiss)
        is DocDialog.EditComment -> TextInputDialog("Comment text", d.info.contents ?: "", multiline = true, onDismiss = dismiss) { t ->
            dismiss(); c.setAnnotationText(d.info, t)
        }
        is DocDialog.Signature -> SignatureDialog(c, d, dismiss)
        is DocDialog.BookmarkName -> TextInputDialog(if (d.path == null || d.path.lastOrNull() == -1) "New bookmark" else "Rename bookmark", d.initial, onDismiss = dismiss) { t ->
            dismiss()
            c.launchJob {
                try {
                    c.session.edit("Updating bookmarks…") { doc ->
                        when {
                            d.path == null -> Outline.add(doc, t, d.page)
                            d.path.lastOrNull() == -1 -> Outline.add(doc, t, d.page, d.path.dropLast(1))
                            else -> Outline.rename(doc, d.path, t)
                        }
                    }
                } catch (e: Exception) {
                    c.vm.message("Couldn't update bookmarks: ${e.message}")
                }
            }
        }
        is DocDialog.Info -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(d.title) },
            text = { Text(d.text) },
            confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
        )
        DocDialog.StampPicker -> StampDialog(c, dismiss)
        DocDialog.ApplyRedactions -> ApplyRedactionsDialog(c, dismiss)
        DocDialog.SearchRedact -> SearchRedactDialog(c, dismiss)
        DocDialog.RedactPages -> TextInputDialog("Redact pages", "", label = "Pages, e.g. 1-3, 7", onDismiss = dismiss) { t ->
            val ranges = PageOps.parseRanges(t, c.session.pageCount)
            if (ranges == null) c.vm.message("Invalid page range") else { dismiss(); c.markPages(ranges.flatten()) }
        }
        DocDialog.Sanitize -> SanitizeDialog(c, dismiss)
        DocDialog.Protect -> ProtectDialog(c, dismiss)
        DocDialog.Compress -> CompressDialog(c, dismiss)
        DocDialog.Watermark -> WatermarkDialog(c, dismiss)
        DocDialog.HeaderFooter -> HeaderFooterDialog(c, dismiss)
        DocDialog.Properties -> PropertiesDialog(c, dismiss)
        DocDialog.GoToPage -> TextInputDialog("Go to page", "", label = "1 – ${c.session.pageCount}", number = true, onDismiss = dismiss) { t ->
            val p = t.toIntOrNull()
            if (p != null && p in 1..c.session.pageCount) { dismiss(); c.viewer.scrollToPage(p - 1) } else c.vm.message("Enter a page between 1 and ${c.session.pageCount}")
        }
        DocDialog.Export -> ExportDialog(dismiss, onExportText, onExportDocx, onExportImages, onExportPdf, onExportXlsx, onExportPptx)
        DocDialog.Ocr -> OcrDialog(c, dismiss)
        DocDialog.Flatten -> ConfirmDialog(
            "Flatten document?",
            "Comments, stamps, signatures and form fields become a permanent part of the pages and can no longer be edited.",
            "Flatten", dismiss,
        ) {
            dismiss()
            c.launchJob {
                try {
                    val n = c.session.edit("Flattening…") { Flattener.flatten(it) }
                    c.vm.message("Flattened $n item${if (n == 1) "" else "s"}")
                } catch (e: Exception) {
                    c.vm.message("Flatten failed: ${e.message}")
                }
            }
        }
        DocDialog.ConfirmClose -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Save changes?") },
            text = { Text("\"${c.session.displayName}\" has unsaved changes.") },
            confirmButton = { TextButton(onClick = { dismiss(); onSaveThenClose() }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { dismiss(); onDiscardClose() }) { Text("Discard") }
                    TextButton(onClick = dismiss) { Text("Cancel") }
                }
            },
        )
        DocDialog.ConfirmDiscardTool -> ConfirmDialog(
            "Discard changes?", "Unsaved marks and comments from this tool will be lost.", "Discard", dismiss,
        ) {
            dismiss()
            c.editor.redactions.clear()
            c.closeCategory(commit = false)
        }
    }
}

// ------------------------------------------------------------------ generic dialogs

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String = "",
    multiline: Boolean = false,
    number: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        // Wait a frame for the dialog window, then show the keyboard in the field.
        kotlinx.coroutines.delay(150)
        try { focus.requestFocus() } catch (e: IllegalStateException) { }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = label.takeIf { it.isNotEmpty() }?.let { l -> @Composable { Text(l, maxLines = 2) } },
                singleLine = !multiline, minLines = if (multiline) 3 else 1,
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LabeledCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun LabeledRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private val COLORS = listOf(
    0xFF000000.toInt(), 0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF9E9E9E.toInt(),
)

@Composable
private fun ColorRow(selected: Int, colors: List<Int> = COLORS, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (col in colors) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Color(col))
                    .then(if (col == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier.border(1.dp, Color(0x33000000), CircleShape))
                    .clickable { onSelect(col) }
            )
        }
    }
}

// ------------------------------------------------------------------ text & fields

@Composable
private fun TextEntryDialog(c: DocumentController, d: DocDialog.TextEntry, dismiss: () -> Unit) {
    val prefs = c.vm.prefs
    var text by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(prefs.textSize) }
    var color by remember { mutableStateOf(prefs.textColor) }
    val title = when (d.purpose) {
        TextPurpose.COMMENT_BOX -> "Text box"
        TextPurpose.FILL -> "Add text"
        TextPurpose.CONTENT -> "Add text to page"
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text("Size: ${size.toInt()} pt")
                Slider(value = size, onValueChange = { size = it }, valueRange = 6f..48f)
                ColorRow(color) { color = it }
                if (d.purpose == TextPurpose.CONTENT) {
                    Text("Text is added to the page content (not as a comment).", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                prefs.updateTextSize(size)
                prefs.updateTextColor(color)
                c.addTextFromDialog(d.page, d.at, text, size, color, d.purpose)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditTextDialog(c: DocumentController, d: DocDialog.EditText, dismiss: () -> Unit) {
    val para = d.paragraph
    var wholeParagraph by remember { mutableStateOf(para != null) }
    var text by remember { mutableStateOf(para?.text ?: d.text) }
    var style by remember { mutableStateOf<com.tandidong.pdf.core.TextStyle?>(null) }
    var size by remember { mutableStateOf(d.line.fontSize.coerceIn(4f, 72f)) }
    var family by remember { mutableStateOf<com.tandidong.pdf.core.FontFamily?>(null) }
    var bold by remember { mutableStateOf<Boolean?>(null) }
    var italic by remember { mutableStateOf<Boolean?>(null) }
    var color by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(d) {
        style = try { c.session.read { com.tandidong.pdf.core.TextStyles.probe(it, d.page, d.line.box) } } catch (e: Exception) { null }
        style?.let { size = it.size.coerceIn(4f, 72f) }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Edit text") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (para != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = wholeParagraph, onClick = {
                            if (!wholeParagraph) { wholeParagraph = true; text = para.text }
                        }, label = { Text("Paragraph (${para.lineCount} lines)") })
                        FilterChip(selected = !wholeParagraph, onClick = {
                            if (wholeParagraph) { wholeParagraph = false; text = d.text }
                        }, label = { Text("This line") })
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    minLines = if (wholeParagraph) 4 else 1,
                )
                style?.let {
                    Text("Original: ${it.description}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Font", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = family == null, onClick = { family = null }, label = { Text("Match original") })
                    for (f in com.tandidong.pdf.core.FontFamily.values()) {
                        FilterChip(selected = family == f, onClick = { family = f }, label = { Text(f.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val isBold = bold ?: style?.bold ?: d.line.bold
                    val isItalic = italic ?: style?.italic ?: false
                    FilterChip(selected = isBold, onClick = { bold = !isBold }, label = { Text("Bold") })
                    FilterChip(selected = isItalic, onClick = { italic = !isItalic }, label = { Text("Italic") })
                }
                Text("Size: ${"%.1f".format(size)} pt")
                Slider(value = size, onValueChange = { size = it }, valueRange = 4f..72f)
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FilterChip(selected = color == null, onClick = { color = null }, label = { Text("Original color") })
                ColorRow(color ?: 0) { color = it }
                Text(
                    "The original text is permanently removed and replaced. The document's own font is reused when it " +
                        "contains every letter you type; otherwise the closest standard font is used.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                val p = para?.takeIf { wholeParagraph }
                val leading = if (p != null) p.leading * size / p.fontSize.coerceAtLeast(1f) else null
                c.replaceText(
                    d.page, p?.rect ?: d.line.box, text,
                    com.tandidong.pdf.core.TextFormat(
                        size = size, argb = color, family = family, bold = bold, italic = italic,
                        wrapWidth = p?.rect?.width, leading = leading,
                    ),
                )
            }) { Text("Replace") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FieldDialog(c: DocumentController, d: DocDialog.FieldEdit, dismiss: () -> Unit) {
    val f = d.field
    var text by remember { mutableStateOf(f.value.takeIf { it != "Off" } ?: "") }
    var checked by remember { mutableStateOf(f.isChecked) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(f.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (f.kind) {
                    FieldKind.TEXT -> {
                        OutlinedTextField(
                            value = text, onValueChange = { if (f.maxLength <= 0 || it.length <= f.maxLength) text = it },
                            singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1, modifier = Modifier.fillMaxWidth(),
                        )
                        val suggestion = com.tandidong.pdf.core.Autofill.suggest(f.label, f.name, c.vm.prefs.autofill)
                        if (suggestion != null && suggestion.second != text) {
                            FilterChip(
                                selected = false, onClick = { text = suggestion.second },
                                label = { Text("Use ${suggestion.first.label.lowercase()}: ${suggestion.second}", maxLines = 1) },
                            )
                        }
                    }
                    FieldKind.CHECKBOX -> LabeledCheck("Checked", checked) { checked = it }
                    FieldKind.RADIO, FieldKind.COMBO, FieldKind.LIST -> {
                        val values = if (f.kind == FieldKind.RADIO) f.exportValues else f.exportValues.ifEmpty { f.options }
                        val labels = f.options.ifEmpty { values }
                        values.forEachIndexed { i, v -> LabeledRadio(labels.getOrElse(i) { v }, text == v) { text = v } }
                        if (f.kind == FieldKind.COMBO && f.editableCombo) {
                            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Custom value") }, singleLine = true)
                        }
                    }
                    else -> Text("This field can't be edited here.")
                }
                if (f.required) Text("Required field", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                val value = if (f.kind == FieldKind.CHECKBOX) (if (checked) "true" else "false") else text
                c.fillField(f, value)
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ signatures & stamps

@Composable
private fun SignatureDialog(c: DocumentController, d: DocDialog.Signature, dismiss: () -> Unit) {
    val store = c.vm.signatures
    val saved = store.items.filter { it.initials == d.initials }
    var drawing by remember { mutableStateOf(saved.isEmpty()) }
    fun use(sig: SavedSignature) {
        dismiss()
        if (d.initials) {
            c.editor.initials = sig
            c.editor.tool = Tool.INITIALS
        } else {
            c.editor.signature = sig
            c.editor.tool = Tool.SIGNATURE
        }
        c.editor.category = ToolCategory.FILL_SIGN
        d.placeAt?.let { (p, pt) -> c.placeSignature(sig, p, pt, d.initials) } ?: c.vm.message("Tap where you want to sign")
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(if (d.initials) "Initials" else "Signature", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                if (drawing) {
                    SignaturePad(
                        initials = d.initials,
                        onCancel = { if (saved.isEmpty()) dismiss() else drawing = false },
                    ) { strokes, aspect, color, width ->
                        val sig = store.add(strokes, aspect, d.initials, color, width)
                        use(sig)
                    }
                } else {
                    for (sig in saved) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .background(Color.White).clickable { use(sig) }.padding(8.dp)
                            ) { SignaturePreview(sig) }
                            IconButton(onClick = { store.delete(sig.id) }) { Icon(Icons.Outlined.Delete, "Delete") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dismiss) { Text("Cancel") }
                        TextButton(onClick = { drawing = true }) { Text(if (d.initials) "Draw new initials" else "Draw new signature") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignaturePreview(sig: SavedSignature) {
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val boxAspect = size.width / size.height
        val w: Float
        val h: Float
        if (sig.aspect > boxAspect) { w = size.width; h = size.width / sig.aspect } else { h = size.height; w = size.height * sig.aspect }
        val ox = (size.width - w) / 2f
        val oy = (size.height - h) / 2f
        for (s in sig.strokes) {
            val path = Path()
            s.forEachIndexed { i, p -> if (i == 0) path.moveTo(ox + p.x * w, oy + p.y * h) else path.lineTo(ox + p.x * w, oy + p.y * h) }
            if (s.size == 1) path.lineTo(ox + s[0].x * w + 0.5f, oy + s[0].y * h)
            drawPath(path, Color(sig.argb), style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun SignaturePad(
    initials: Boolean,
    onCancel: () -> Unit,
    onSave: (strokes: List<List<Pt>>, aspect: Float, color: Int, width: Float) -> Unit,
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(0xFF0D47A1.toInt()) }
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }
    Column {
        Box(
            Modifier.fillMaxWidth().height(if (initials) 150.dp else 210.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDrag = { change, _ -> change.consume(); current = current + change.position },
                        onDragEnd = { if (current.isNotEmpty()) strokes.add(current); current = emptyList() },
                        onDragCancel = { current = emptyList() },
                    )
                }
                .pointerInput(Unit) { detectTapGestures { strokes.add(listOf(it)) } }
        ) {
            Canvas(Modifier.fillMaxWidth().height(if (initials) 150.dp else 210.dp)) {
                val baseline = size.height * 0.75f
                drawLine(Color(0x33000000), Offset(24f, baseline), Offset(size.width - 24f, baseline), strokeWidth = 2f)
                for (s in strokes + listOf(current)) {
                    if (s.isEmpty()) continue
                    val path = Path()
                    path.moveTo(s[0].x, s[0].y)
                    if (s.size == 1) path.lineTo(s[0].x + 0.5f, s[0].y)
                    for (i in 1 until s.size) path.lineTo(s[i].x, s[i].y)
                    drawPath(path, Color(color), style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (strokes.isEmpty() && current.isEmpty()) {
                Text(if (initials) "Draw your initials" else "Sign here", color = Color(0x66000000), modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorRow(color, listOf(0xFF000000.toInt(), 0xFF0D47A1.toInt(), 0xFFB71C1C.toInt())) { color = it }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { strokes.clear() }) { Text("Clear") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                val all = strokes.flatten()
                val minX = all.minOf { it.x } - strokePx
                val maxX = all.maxOf { it.x } + strokePx
                val minY = all.minOf { it.y } - strokePx
                val maxY = all.maxOf { it.y } + strokePx
                val w = max(1f, maxX - minX)
                val h = max(1f, maxY - minY)
                val norm = strokes.map { s -> s.map { Pt((it.x - minX) / w, (it.y - minY) / h) } }
                onSave(norm, w / h, color, strokePx * 300f / w)
            }) { Text("Save & use") }
        }
    }
}

@Composable
private fun StampDialog(c: DocumentController, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Choose a stamp") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (s in StampPreset.values()) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
                            .border(2.dp, Color(s.argb), RoundedCornerShape(8.dp))
                            .clickable {
                                c.editor.stamp = s
                                c.editor.tool = Tool.STAMP
                                dismiss()
                            }.padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(s.label, color = Color(s.argb), style = MaterialTheme.typography.titleMedium) }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

// ------------------------------------------------------------------ redaction

@Composable
private fun ApplyRedactionsDialog(c: DocumentController, dismiss: () -> Unit) {
    val prefs = c.vm.prefs
    var fill by remember { mutableStateOf(prefs.redactFill) }
    var label by remember { mutableStateOf(prefs.redactLabel) }
    var removeAnnots by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Apply ${c.editor.redactions.size} redaction${if (c.editor.redactions.size == 1) "" else "s"}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Marked content is permanently removed: text, images and graphics under each mark are deleted from the file, not just covered. This can be undone only before you save.")
                Text("Box color", style = MaterialTheme.typography.labelLarge)
                ColorRow(fill, listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF424242.toInt(), 0xFFB71C1C.toInt(), 0xFF0D47A1.toInt())) { fill = it }
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Overlay text (optional)") }, placeholder = { Text("e.g. REDACTED") }, singleLine = true)
                LabeledCheck("Remove overlapping comments and form fields", removeAnnots) { removeAnnots = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                prefs.updateRedactFill(fill)
                prefs.updateRedactLabel(label)
                c.applyRedactions(RedactionOptions(fillArgb = fill, overlayText = label.ifBlank { null },
                    overlayTextArgb = if (fill == 0xFFFFFFFF.toInt()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), removeAnnotations = removeAnnots))
            }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { dismiss(); c.editor.redactions.clear() }) { Text("Clear marks") }
                TextButton(onClick = dismiss) { Text("Cancel") }
            }
        },
    )
}

private val REDACT_PATTERNS = listOf(
    "Email addresses" to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
    "Phone numbers" to Regex("(?<!\\w)(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(\\d{2,4}\\)|\\d{2,4})[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,4}(?!\\w)"),
    "Credit card numbers" to Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)"),
    "ID / SSN numbers" to Regex("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)|(?<!\\d)\\d{16}(?!\\d)"),
    "Dates" to Regex("(?<!\\d)\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}(?!\\d)|(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)"),
    "Web addresses" to Regex("https?://\\S+|www\\.\\S+"),
    "Long numbers (5+ digits)" to Regex("(?<!\\d)\\d{5,}(?!\\d)"),
)

@Composable
private fun SearchRedactDialog(c: DocumentController, dismiss: () -> Unit) {
    var terms by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Int>() }
    var matchCase by remember { mutableStateOf(false) }
    var whole by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Search & redact") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = terms, onValueChange = { terms = it }, minLines = 2,
                    label = { Text("Words or phrases (one per line)") }, modifier = Modifier.fillMaxWidth(),
                )
                LabeledCheck("Match case", matchCase) { matchCase = it }
                LabeledCheck("Whole words only", whole) { whole = it }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Patterns", style = MaterialTheme.typography.labelLarge)
                REDACT_PATTERNS.forEachIndexed { i, (label, _) ->
                    LabeledCheck(label, i in selected) { if (it) selected.add(i) else selected.remove(i) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                c.markMatches(terms.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }, selected.map { REDACT_PATTERNS[it].second }, matchCase, whole)
            }) { Text("Find & mark") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SanitizeDialog(c: DocumentController, dismiss: () -> Unit) {
    var o by remember { mutableStateOf(SanitizeOptions()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Sanitize document") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Remove hidden information that may be sensitive.", style = MaterialTheme.typography.bodyMedium)
                LabeledCheck("Metadata (author, title, XMP)", o.metadata) { o = o.copy(metadata = it) }
                LabeledCheck("Scripts and actions", o.javascript) { o = o.copy(javascript = it) }
                LabeledCheck("Attached files", o.attachments) { o = o.copy(attachments = it) }
                LabeledCheck("Thumbnails & private app data", o.thumbnails) { o = o.copy(thumbnails = it, privateAppData = it) }
                LabeledCheck("Comments and markups", o.comments) { o = o.copy(comments = it) }
                LabeledCheck("Links", o.links) { o = o.copy(links = it) }
                LabeledCheck("Form fields (flatten)", o.formFields) { o = o.copy(formFields = it) }
                LabeledCheck("Bookmarks", o.bookmarks) { o = o.copy(bookmarks = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                c.launchJob {
                    try {
                        val r = c.session.edit("Sanitizing…") { Sanitizer.sanitize(it, o) }
                        c.vm.message("Removed ${r.itemsRemoved} hidden item${if (r.itemsRemoved == 1) "" else "s"}")
                    } catch (e: Exception) {
                        c.vm.message("Sanitize failed: ${e.message}")
                    }
                }
            }) { Text("Sanitize") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ document tools

@Composable
private fun ProtectDialog(c: DocumentController, dismiss: () -> Unit) {
    val current = c.session.protection
    var user by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var p by remember { mutableStateOf(current?.permissions ?: Permissions()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Protect with password") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (current != null) {
                    Text("This document is password protected.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        c.session.protection = null
                        dismiss()
                        c.vm.message("Password will be removed when you save")
                    }) { Text("Remove password") }
                    HorizontalDivider()
                    Text("Or set a new password:")
                }
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Password to open") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(value = confirm, onValueChange = { confirm = it }, label = { Text("Confirm password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = confirm.isNotEmpty() && confirm != user)
                OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("Permissions password (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("Allow others to:", style = MaterialTheme.typography.labelLarge)
                LabeledCheck("Print", p.print) { p = p.copy(print = it, printHighQuality = it) }
                LabeledCheck("Copy text and images", p.copy) { p = p.copy(copy = it) }
                LabeledCheck("Edit content", p.modify) { p = p.copy(modify = it, assemble = it) }
                LabeledCheck("Add comments", p.annotate) { p = p.copy(annotate = it) }
                LabeledCheck("Fill forms", p.fillForms) { p = p.copy(fillForms = it) }
                Text("Uses 256-bit AES encryption. Applied when you save, share or print.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = user.isNotEmpty() && user == confirm, onClick = {
                c.session.protection = ProtectionSpec(user, owner.ifEmpty { user }, p)
                dismiss()
                c.vm.message("Password protection will be applied when you save")
            }) { Text("Protect") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CompressDialog(c: DocumentController, dismiss: () -> Unit) {
    var level by remember { mutableStateOf(1) }
    val levels = listOf(
        Triple("Low", "Best quality, smaller savings", CompressOptions.LOW),
        Triple("Medium", "Good balance for sharing", CompressOptions.MEDIUM),
        Triple("High", "Smallest file, lower image quality", CompressOptions.HIGH),
    )
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Compress PDF") },
        text = {
            Column {
                Text("Current size: ${FileUtil.formatSize(c.session.currentFile.length())}")
                levels.forEachIndexed { i, (name, desc, _) ->
                    Row(Modifier.fillMaxWidth().clickable { level = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = level == i, onClick = { level = i })
                        Column { Text(name); Text(desc, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                c.launchJob {
                    try {
                        val before = c.session.currentFile.length()
                        val report = c.session.edit("Compressing…") { doc ->
                            Compressor(AndroidImageCodec).compress(doc, levels[level].third) { d, t -> c.session.progress = d.toFloat() / max(1, t) }
                        }
                        val after = c.session.currentFile.length()
                        val pct = if (before > 0) ((before - after) * 100 / before) else 0
                        c.dialog = DocDialog.Info(
                            "Compressed",
                            "${FileUtil.formatSize(before)} → ${FileUtil.formatSize(after)} (${if (pct >= 0) "$pct% smaller" else "no reduction"}).\n" +
                                "${report.imagesRecompressed} image(s) optimized.",
                        )
                    } catch (e: Exception) {
                        c.vm.message("Compression failed: ${e.message}")
                    }
                }
            }) { Text("Compress") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

private val ANCHORS = listOf(Anchor.CENTER to "Center", Anchor.TOP_CENTER to "Top", Anchor.BOTTOM_CENTER to "Bottom",
    Anchor.TOP_LEFT to "Top left", Anchor.TOP_RIGHT to "Top right", Anchor.BOTTOM_LEFT to "Bottom left", Anchor.BOTTOM_RIGHT to "Bottom right")

@Composable
private fun WatermarkDialog(c: DocumentController, dismiss: () -> Unit) {
    val image = c.pendingWatermarkImage
    var text by remember { mutableStateOf("CONFIDENTIAL") }
    var size by remember { mutableStateOf(60f) }
    var opacity by remember { mutableStateOf(0.3f) }
    var rotation by remember { mutableStateOf(45f) }
    var color by remember { mutableStateOf(0xFF9E9E9E.toInt()) }
    var anchor by remember { mutableStateOf(Anchor.CENTER) }
    var behind by remember { mutableStateOf(false) }
    var tiled by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(0.5f) }
    var pages by remember { mutableStateOf("") }
    val close = { c.pendingWatermarkImage = null; dismiss() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (image != null) "Image watermark" else "Text watermark") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 520.dp)) {
                if (image == null) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") }, singleLine = true)
                    Text("Size: ${size.toInt()} pt"); Slider(value = size, onValueChange = { size = it }, valueRange = 12f..144f)
                    ColorRow(color) { color = it }
                    LabeledCheck("Repeat across the page", tiled) { tiled = it }
                } else {
                    Text("Width: ${(scale * 100).toInt()}% of page"); Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.1f..1f)
                }
                Text("Opacity: ${(opacity * 100).toInt()}%"); Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.05f..1f)
                Text("Rotation: ${rotation.toInt()}°"); Slider(value = rotation, onValueChange = { rotation = it }, valueRange = -90f..90f, steps = 11)
                if (!tiled) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((a, label) in ANCHORS) FilterChip(selected = anchor == a, onClick = { anchor = a }, label = { Text(label) })
                    }
                }
                LabeledCheck("Place behind page content", behind) { behind = it }
                OutlinedTextField(value = pages, onValueChange = { pages = it }, label = { Text("Pages (blank = all)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = c.session.pageCount
                val target = if (pages.isBlank()) (0 until n).toList() else PageOps.parseRanges(pages, n)?.flatten()
                if (target == null) { c.vm.message("Invalid page range"); return@TextButton }
                close()
                c.launchJob {
                    try {
                        c.session.edit("Adding watermark…") { doc ->
                            val stamper = Stamper(c.vm.fonts)
                            if (image != null) {
                                val x = if (image.hasAlpha()) LosslessFactory.createFromImage(doc, image) else JPEGFactory.createFromImage(doc, image, 0.9f)
                                stamper.imageWatermark(doc, target, x, ImageWatermark(scale, opacity, rotation, anchor, behind))
                            } else {
                                stamper.textWatermark(doc, target, TextWatermark(text, size, color, opacity, rotation, anchor, behind, tiled))
                            }
                        }
                    } catch (e: Exception) {
                        c.vm.message("Watermark failed: ${e.message}")
                    }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun HeaderFooterDialog(c: DocumentController, dismiss: () -> Unit) {
    var tl by remember { mutableStateOf("") }
    var tc by remember { mutableStateOf("") }
    var tr by remember { mutableStateOf("") }
    var bl by remember { mutableStateOf("") }
    var bc by remember { mutableStateOf("Page {page} of {pages}") }
    var br by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(10f) }
    var start by remember { mutableStateOf("1") }
    var batesPrefix by remember { mutableStateOf("") }
    var batesStart by remember { mutableStateOf("1") }
    var batesDigits by remember { mutableStateOf("6") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Headers, footers & numbering") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tokens: {page} {pages} {date} {file} {bates}", style = MaterialTheme.typography.bodySmall)
                Text("Header", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = tl, onValueChange = { tl = it }, label = { Text("Left") }, singleLine = true)
                OutlinedTextField(value = tc, onValueChange = { tc = it }, label = { Text("Center") }, singleLine = true)
                OutlinedTextField(value = tr, onValueChange = { tr = it }, label = { Text("Right") }, singleLine = true)
                Text("Footer", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = bl, onValueChange = { bl = it }, label = { Text("Left") }, singleLine = true)
                OutlinedTextField(value = bc, onValueChange = { bc = it }, label = { Text("Center") }, singleLine = true)
                OutlinedTextField(value = br, onValueChange = { br = it }, label = { Text("Right") }, singleLine = true)
                Text("Font size: ${size.toInt()} pt"); Slider(value = size, onValueChange = { size = it }, valueRange = 6f..24f)
                OutlinedTextField(value = start, onValueChange = { start = it.filter(Char::isDigit) }, label = { Text("First page number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Bates numbering (use {bates})", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = batesPrefix, onValueChange = { batesPrefix = it }, label = { Text("Prefix") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = batesStart, onValueChange = { batesStart = it.filter(Char::isDigit) }, label = { Text("Start") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = batesDigits, onValueChange = { batesDigits = it.filter(Char::isDigit).take(2) }, label = { Text("Digits") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                val hf = HeaderFooter(
                    topLeft = tl, topCenter = tc, topRight = tr, bottomLeft = bl, bottomCenter = bc, bottomRight = br,
                    fontSize = size, startNumber = start.toIntOrNull() ?: 1, fileName = c.session.displayName,
                    batesPrefix = batesPrefix, batesStart = batesStart.toIntOrNull() ?: 1, batesDigits = (batesDigits.toIntOrNull() ?: 6).coerceIn(1, 12),
                )
                c.launchJob {
                    try {
                        c.session.edit("Adding headers & footers…") { doc -> Stamper(c.vm.fonts).headerFooter(doc, (0 until doc.numberOfPages).toList(), hf) }
                    } catch (e: Exception) {
                        c.vm.message("Couldn't add: ${e.message}")
                    }
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PropertiesDialog(c: DocumentController, dismiss: () -> Unit) {
    var props by remember { mutableStateOf<DocProperties?>(null) }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val p = try { c.session.read { DocInfo.read(it) } } catch (e: Exception) { null }
        props = p
        if (p != null) { title = p.title; author = p.author; subject = p.subject; keywords = p.keywords }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Document properties") },
        text = {
            val p = props
            if (p == null) Text("Loading…") else Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val editable = !c.session.restricted
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, enabled = editable)
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Author") }, singleLine = true, enabled = editable)
                OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject") }, singleLine = true, enabled = editable)
                OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text("Keywords") }, singleLine = true, enabled = editable)
                Spacer(Modifier.height(8.dp))
                val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                val info = listOf(
                    "File" to c.session.displayName,
                    "Size" to FileUtil.formatSize(c.session.currentFile.length()),
                    "Pages" to p.pageCount.toString(),
                    "Page size" to "${(p.firstPageWidth / 72f * 25.4f).toInt()} × ${(p.firstPageHeight / 72f * 25.4f).toInt()} mm",
                    "PDF version" to p.pdfVersion.toString(),
                    "Created" to (p.created?.time?.let { df.format(it) } ?: "—"),
                    "Modified" to (p.modified?.time?.let { df.format(it) } ?: "—"),
                    "Application" to p.creator.ifBlank { "—" },
                    "PDF producer" to p.producer.ifBlank { "—" },
                    "Tagged (accessible)" to if (p.tagged) "Yes" else "No",
                    "Form fields" to if (p.hasForm) "Yes" else "No",
                    "Attachments" to p.attachmentCount.toString(),
                    "Security" to if (c.session.protection != null) "Password protected" else "None",
                )
                for ((k, v) in info) Row { Text("$k: ", style = MaterialTheme.typography.labelLarge); Text(v, style = MaterialTheme.typography.bodyMedium) }
                if (p.fonts.isNotEmpty()) {
                    Text("Fonts", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    for (f in p.fonts.take(40)) Text("${f.name} (${f.type}${if (f.embedded) ", embedded" else ""})", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (!c.session.restricted) TextButton(onClick = {
                dismiss()
                c.launchJob {
                    try { c.session.edit("Saving properties…") { DocInfo.write(it, title, author, subject, keywords) } } catch (e: Exception) { c.vm.message("Couldn't save: ${e.message}") }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

@Composable
private fun ExportDialog(
    dismiss: () -> Unit,
    onText: () -> Unit,
    onDocx: () -> Unit,
    onImages: (Boolean, Int) -> Unit,
    onPdf: () -> Unit,
    onXlsx: () -> Unit,
    onPptx: () -> Unit,
) {
    var png by remember { mutableStateOf(false) }
    var dpi by remember { mutableStateOf(150) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Export PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { dismiss(); onDocx() }, modifier = Modifier.fillMaxWidth()) { Text("Microsoft Word (.docx)") }
                OutlinedButton(onClick = { dismiss(); onXlsx() }, modifier = Modifier.fillMaxWidth()) { Text("Microsoft Excel (.xlsx)") }
                OutlinedButton(onClick = { dismiss(); onPptx() }, modifier = Modifier.fillMaxWidth()) { Text("Microsoft PowerPoint (.pptx)") }
                OutlinedButton(onClick = { dismiss(); onText() }, modifier = Modifier.fillMaxWidth()) { Text("Plain text (.txt)") }
                OutlinedButton(onClick = { dismiss(); onPdf() }, modifier = Modifier.fillMaxWidth()) { Text("PDF (share)") }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Images (one per page)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !png, onClick = { png = false }, label = { Text("JPEG") })
                    FilterChip(selected = png, onClick = { png = true }, label = { Text("PNG") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (v in listOf(72, 150, 300)) FilterChip(selected = dpi == v, onClick = { dpi = v }, label = { Text("$v dpi") })
                }
                OutlinedButton(onClick = { dismiss(); onImages(png, dpi) }, modifier = Modifier.fillMaxWidth()) { Text("Choose folder & export images") }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

@Composable
private fun OcrDialog(c: DocumentController, dismiss: () -> Unit) {
    var all by remember { mutableStateOf(true) }
    var skip by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Recognize text (OCR)") },
        text = {
            Column {
                Text("Makes scanned pages searchable and selectable by adding an invisible text layer. Runs entirely on your device (Latin-script languages).")
                Spacer(Modifier.height(8.dp))
                LabeledRadio("All pages", all) { all = true }
                LabeledRadio("Current page (${c.viewer.currentPage() + 1})", !all) { all = false }
                LabeledCheck("Skip pages that already contain text", skip) { skip = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                val pages = if (all) (0 until c.session.pageCount).toList() else listOf(c.viewer.currentPage())
                runOcr(c, pages, skip)
            }) { Text("Recognize") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

private fun runOcr(c: DocumentController, pages: List<Int>, skipText: Boolean) {
    c.launchJob {
        val s = c.session
        try {
            val results = HashMap<Int, List<OcrWord>>()
            s.task("Recognizing text…") {
                for ((k, p) in pages.withIndex()) {
                    s.progress = k.toFloat() / pages.size
                    if (skipText) {
                        val t = c.pageText(p)
                        if (t != null && t.text.count { it.isLetterOrDigit() } > 25) continue
                    }
                    val g = s.geometries.getOrNull(p) ?: continue
                    val width = (g.displayWidth * 3f).toInt().coerceIn(1400, 2600)
                    val bmp = s.renderPage(p, width, s.version, annotations = false) ?: continue
                    val boxes = Ocr.recognize(bmp)
                    val scale = bmp.width / g.displayWidth
                    results[p] = boxes.map { OcrWord(it.text, FRect(it.left / scale, it.top / scale, it.right / scale, it.bottom / scale)) }
                    bmp.recycle()
                }
            }
            val total = results.values.sumOf { it.size }
            if (total == 0) {
                c.vm.message(if (results.isEmpty()) "All pages already contain text" else "No text recognized")
                return@launchJob
            }
            s.edit("Adding text layer…") { doc ->
                val writer = OcrLayerWriter(c.vm.fonts)
                for ((p, words) in results) writer.addTextLayer(doc, p, words)
            }
            c.vm.message("Recognized $total words on ${results.size} page(s). The document is now searchable.")
        } catch (e: Exception) {
            c.vm.message("Text recognition failed: ${e.message}")
        }
    }
}

@Suppress("unused")
private val unusedSwitch: @Composable () -> Unit = { Switch(checked = false, onCheckedChange = {}) }

@Suppress("unused")
private val unusedWidth = Modifier.width(1.dp)


// ------------------------------------------------------------------ digital IDs

@Composable
private fun CertSignDialog(c: DocumentController, d: DocDialog.CertSign, dismiss: () -> Unit, onImport: (DocDialog?) -> Unit) {
    val ids = c.vm.digitalIds.items
    var selected by remember { mutableStateOf(ids.firstOrNull { !it.expired }?.id ?: ids.firstOrNull()?.id) }
    var password by remember { mutableStateOf("") }
    val reasons = listOf("I am the author of this document", "I approve this document", "I have reviewed this document", "I agree to the terms")
    var reason by remember { mutableStateOf(reasons[1]) }
    var location by remember { mutableStateOf("") }
    val hasInk = c.vm.signatures.items.any { !it.initials }
    var includeInk by remember { mutableStateOf(hasInk) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Sign with digital ID") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ids.isEmpty()) {
                    Text("You don't have a digital ID yet. Create a self-signed ID, or import one issued by a certificate authority (.p12 / .pfx).")
                } else {
                    for (e in ids) {
                        Row(Modifier.fillMaxWidth().clickable { selected = e.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected == e.id, onClick = { selected = e.id })
                            Column {
                                Text(e.name, style = MaterialTheme.typography.bodyLarge)
                                val exp = java.text.DateFormat.getDateInstance().format(java.util.Date(e.notAfter))
                                Text(
                                    (if (e.selfSigned) "Self-signed" else "Issued by ${e.issuer}") + " · " + (if (e.expired) "expired $exp" else "valid until $exp"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (e.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Digital ID password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Reason", style = MaterialTheme.typography.labelLarge)
                    for (r in reasons) LabeledRadio(r, reason == r) { reason = r }
                    OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Location (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (hasInk) LabeledCheck("Include my handwritten signature", includeInk) { includeInk = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { c.dialog = DocDialog.CreateDigitalId(d) }) { Text("Create ID") }
                    OutlinedButton(onClick = { dismiss(); onImport(d) }) { Text("Import .p12") }
                }
            }
        },
        confirmButton = {
            val entry = ids.firstOrNull { it.id == selected }
            TextButton(enabled = entry != null && password.isNotEmpty(), onClick = {
                if (entry != null) {
                    dismiss()
                    c.signWithCertificate(d.page, d.rect, entry, password, reason, location, includeInk, d.fieldName)
                }
            }) { Text("Sign") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CreateDigitalIdDialog(c: DocumentController, d: DocDialog.CreateDigitalId, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(c.vm.prefs.authorName) }
    var email by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) dismiss() },
        title = { Text("Create a digital ID") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("A self-signed ID proves the document wasn't changed after signing. Others can trust your identity only if they trust your certificate.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(value = org, onValueChange = { org = it }, label = { Text("Organization (optional)") }, singleLine = true)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (min. 6 characters)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(value = confirm, onValueChange = { confirm = it }, label = { Text("Confirm password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = confirm.isNotEmpty() && confirm != password)
                if (working) Text("Generating keys…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !working && name.isNotBlank() && password.length >= 6 && password == confirm, onClick = {
                working = true
                c.launchJob {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            c.vm.digitalIds.create(name.trim(), email.trim().ifBlank { null }, org.trim().ifBlank { null }, password)
                        }
                        c.vm.message("Digital ID created")
                        c.dialog = d.then
                    } catch (e: Exception) {
                        working = false
                        c.vm.message("Couldn't create ID: ${e.message}")
                    }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = { c.dialog = d.then }) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDigitalIdDialog(c: DocumentController, d: DocDialog.ImportDigitalId, dismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Import digital ID") },
        text = {
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password of the .p12 / .pfx file") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val e = c.vm.digitalIds.import(d.data, password)
                    c.vm.message("Imported digital ID for ${e.name}")
                    c.dialog = d.then
                } catch (ex: Exception) {
                    c.vm.message("Couldn't import: wrong password or unsupported file")
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = { c.dialog = d.then }) { Text("Cancel") } },
    )
}


// ------------------------------------------------------------------ prepare form

@Composable
private fun NewFormFieldDialog(c: DocumentController, d: DocDialog.NewFormField, dismiss: () -> Unit) {
    val type = d.type
    val radio = type == com.tandidong.pdf.core.NewFieldType.RADIO
    val choice = type == com.tandidong.pdf.core.NewFieldType.DROPDOWN || type == com.tandidong.pdf.core.NewFieldType.LIST
    val textual = type == com.tandidong.pdf.core.NewFieldType.TEXT || type == com.tandidong.pdf.core.NewFieldType.MULTILINE
    val radioGroups = remember { c.editor.formFields.filter { it.kind == FieldKind.RADIO }.map { it.name } }
    var name by remember { mutableStateOf("") }
    var tooltip by remember { mutableStateOf("") }
    var required by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf("Option 1\nOption 2\nOption 3") }
    val lastGroup = remember { c.editor.formFields.lastOrNull { it.kind == FieldKind.RADIO } }
    var exportValue by remember { mutableStateOf(if (radio) "Choice${(lastGroup?.widgets?.size ?: 0) + 1}" else "Yes") }
    var fontSize by remember { mutableStateOf(0f) }
    var defaultValue by remember { mutableStateOf("") }
    LaunchedEffect(type) {
        if (name.isEmpty()) {
            name = lastGroup?.name ?: try {
                c.session.read { com.tandidong.pdf.core.FormBuilder.suggestName(it, type) }
            } catch (e: Exception) {
                type.prefix + "1"
            }
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("New ${type.label.lowercase()}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.replace(".", "_") }, singleLine = true,
                    label = { Text(if (radio) "Group name" else "Field name") }, modifier = Modifier.fillMaxWidth(),
                )
                if (radio) {
                    if (radioGroups.isNotEmpty()) {
                        Text("Buttons with the same group name work together: choosing one clears the others.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (g in radioGroups.distinct()) FilterChip(selected = name == g, onClick = { name = g }, label = { Text(g) })
                        }
                    }
                    OutlinedTextField(
                        value = exportValue, onValueChange = { exportValue = it }, singleLine = true,
                        label = { Text("Value of this button") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (type == com.tandidong.pdf.core.NewFieldType.CHECKBOX) {
                    OutlinedTextField(
                        value = exportValue, onValueChange = { exportValue = it }, singleLine = true,
                        label = { Text("Value when checked") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (choice) {
                    OutlinedTextField(
                        value = options, onValueChange = { options = it }, minLines = 3,
                        label = { Text("Choices (one per line)") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (textual) {
                    OutlinedTextField(
                        value = defaultValue, onValueChange = { defaultValue = it }, singleLine = type == com.tandidong.pdf.core.NewFieldType.TEXT,
                        label = { Text("Default text (optional)") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (textual || choice) {
                    Text("Font size", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in listOf(0f, 8f, 10f, 12f, 14f, 18f)) {
                            FilterChip(selected = fontSize == s, onClick = { fontSize = s }, label = { Text(if (s == 0f) "Auto" else "${s.toInt()}") })
                        }
                    }
                }
                if (type != com.tandidong.pdf.core.NewFieldType.SIGNATURE) {
                    OutlinedTextField(
                        value = tooltip, onValueChange = { tooltip = it }, singleLine = true,
                        label = { Text("Tooltip / label (optional)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    LabeledCheck("Required", required) { required = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                if (n.isEmpty()) { c.vm.message("Enter a field name"); return@TextButton }
                dismiss()
                c.addFormField(
                    com.tandidong.pdf.core.NewField(
                        pageIndex = d.page, rect = d.rect, type = type, name = n,
                        options = if (choice) options.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
                        required = required, fontSize = fontSize, defaultValue = defaultValue,
                        exportValue = exportValue.ifBlank { "Yes" }, tooltip = tooltip.ifBlank { null },
                    )
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FieldPropertiesDialog(c: DocumentController, d: DocDialog.FieldProperties, dismiss: () -> Unit) {
    val f = d.field
    var name by remember { mutableStateOf(f.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDialog("Delete field?", "\"${f.name}\" and everything typed into it will be removed.", "Delete", { confirmDelete = false }) {
            dismiss()
            c.deleteFormField(f)
        }
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Field properties") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val kind = when (f.kind) {
                    FieldKind.TEXT -> if (f.multiline) "Multi-line text" else "Text"
                    FieldKind.CHECKBOX -> "Checkbox"
                    FieldKind.RADIO -> "Radio group (${f.widgets.size} buttons)"
                    FieldKind.COMBO -> "Dropdown"
                    FieldKind.LIST -> "List box"
                    FieldKind.SIGNATURE -> "Signature"
                    FieldKind.BUTTON -> "Button"
                }
                Text("Type: $kind" + (if (f.required) " · required" else "") + if (f.readOnly) " · read-only" else "",
                    style = MaterialTheme.typography.bodyMedium)
                if (f.options.isNotEmpty()) Text("Choices: " + f.options.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.replace(".", "_") }, singleLine = true,
                    label = { Text("Field name") }, modifier = Modifier.fillMaxWidth(),
                    enabled = !f.name.contains('.'),
                )
                if (f.name.contains('.')) Text("Fields inside a group can't be renamed here.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dismiss()
                if (name.trim() != f.name) c.renameFormField(f, name)
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = dismiss) { Text("Cancel") }
            }
        },
    )
}


// ------------------------------------------------------------------ links & background

@Composable
private fun NewLinkDialog(c: DocumentController, d: DocDialog.NewLink, dismiss: () -> Unit) {
    var toPage by remember { mutableStateOf(false) }
    var uri by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Add link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabeledRadio("Web address or email", !toPage) { toPage = false }
                LabeledRadio("Page in this document", toPage) { toPage = true }
                if (toPage) {
                    OutlinedTextField(
                        value = page, onValueChange = { page = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        label = { Text("Page (1 – ${c.session.pageCount})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = uri, onValueChange = { uri = it }, singleLine = true, label = { Text("https://… or name@example.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (toPage) {
                    val p = page.toIntOrNull()
                    if (p == null || p !in 1..c.session.pageCount) { c.vm.message("Enter a page between 1 and ${c.session.pageCount}"); return@TextButton }
                    dismiss(); c.addLink(d.page, d.rect, null, p - 1)
                } else {
                    if (uri.isBlank()) { c.vm.message("Enter a web address"); return@TextButton }
                    dismiss(); c.addLink(d.page, d.rect, uri.trim(), null)
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

private val BACKGROUNDS = listOf(
    0xFFFFF8E1.toInt(), 0xFFFFF59D.toInt(), 0xFFE3F2FD.toInt(), 0xFFE8F5E9.toInt(), 0xFFFCE4EC.toInt(),
    0xFFF3E5F5.toInt(), 0xFFEEEEEE.toInt(), 0xFFFFE0B2.toInt(),
)

@Composable
private fun BackgroundDialog(c: DocumentController, dismiss: () -> Unit) {
    var color by remember { mutableStateOf(BACKGROUNDS[0]) }
    var scope by remember { mutableStateOf(0) }
    var range by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Page background") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Adds a color behind the page content.", style = MaterialTheme.typography.bodySmall)
                ColorRow(color, BACKGROUNDS) { color = it }
                LabeledRadio("All pages", scope == 0) { scope = 0 }
                LabeledRadio("Current page (${c.viewer.currentPage() + 1})", scope == 1) { scope = 1 }
                LabeledRadio("Pages…", scope == 2) { scope = 2 }
                if (scope == 2) OutlinedTextField(value = range, onValueChange = { range = it }, singleLine = true, label = { Text("e.g. 1-3, 7") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pages = when (scope) {
                    0 -> (0 until c.session.pageCount).toList()
                    1 -> listOf(c.viewer.currentPage())
                    else -> PageOps.parseRanges(range, c.session.pageCount)?.flatten()
                }
                if (pages.isNullOrEmpty()) { c.vm.message("Invalid page range"); return@TextButton }
                dismiss()
                c.applyBackground(pages, color)
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
