package com.tandidong.pdf.core

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.IOException
import kotlin.math.min

enum class NewFieldType(val label: String, val prefix: String) {
    TEXT("Text field", "Text"),
    MULTILINE("Multi-line text", "Paragraph"),
    CHECKBOX("Checkbox", "Check"),
    RADIO("Radio button", "Choice"),
    DROPDOWN("Dropdown", "Dropdown"),
    LIST("List box", "List"),
    SIGNATURE("Signature field", "Signature"),
}

/** A form field to create; [rect] is in display space. */
data class NewField(
    val pageIndex: Int,
    val rect: FRect,
    val type: NewFieldType,
    val name: String,
    val options: List<String> = emptyList(),
    val required: Boolean = false,
    /** 0 = auto size. */
    val fontSize: Float = 0f,
    val defaultValue: String = "",
    /** Export value for checkboxes and radio buttons. */
    val exportValue: String = "Yes",
    val tooltip: String? = null,
)

/** "Prepare form": creates and removes interactive AcroForm fields. */
object FormBuilder {
    private val HELV = COSName.getPDFName("Helv")
    private val ZADB = COSName.getPDFName("ZaDb")

    fun acroForm(doc: PDDocument): PDAcroForm {
        val catalog = doc.documentCatalog
        val acro = catalog.acroForm ?: PDAcroForm(doc).also { catalog.acroForm = it }
        val dr = acro.defaultResources ?: PDResources().also { acro.defaultResources = it }
        if (dr.cosObject.getDictionaryObject(COSName.FONT) == null || !hasFont(dr, HELV)) dr.put(HELV, PDType1Font.HELVETICA)
        if (!hasFont(dr, ZADB)) dr.put(ZADB, PDType1Font.ZAPF_DINGBATS)
        if (acro.defaultAppearance.isNullOrBlank()) acro.defaultAppearance = "/Helv 0 Tf 0 g"
        acro.setNeedAppearances(false)
        return acro
    }

    private fun hasFont(dr: PDResources, name: COSName): Boolean =
        (dr.cosObject.getDictionaryObject(COSName.FONT) as? COSDictionary)?.containsKey(name) == true

    /** Returns [base] or base+N so the fully qualified name is unused. */
    fun uniqueName(doc: PDDocument, base: String): String {
        val acro = doc.documentCatalog.acroForm ?: return base
        val clean = base.replace('.', '_').ifBlank { "Field" }
        if (acro.getField(clean) == null) return clean
        var i = 2
        while (acro.getField("$clean$i") != null) i++
        return "$clean$i"
    }

    fun suggestName(doc: PDDocument, type: NewFieldType): String {
        val acro = doc.documentCatalog.acroForm
        var i = 1
        while (acro?.getField("${type.prefix}$i") != null) i++
        return "${type.prefix}$i"
    }

    fun addFields(doc: PDDocument, fields: List<NewField>) {
        val acro = acroForm(doc)
        for (f in fields) addField(doc, acro, f)
    }

    /** Configures [w] (border, background, rotation, placement) and adds it to the page. */
    private fun setupWidget(page: PDPage, g: PageGeometry, rect: FRect, w: PDAnnotationWidget): PDAnnotationWidget {
        w.rectangle = g.rectToUser(rect).toPdRectangle()
        w.page = page
        w.isPrinted = true
        val mk = PDAppearanceCharacteristicsDictionary(COSDictionary())
        mk.borderColour = AnnotationWriter.color(0xFF5C6BC0.toInt())
        mk.background = AnnotationWriter.color(0xFFEEF0FF.toInt())
        if (g.rotation != 0) mk.cosObject.setInt(COSName.getPDFName("R"), g.rotation)
        w.appearanceCharacteristics = mk
        w.borderStyle = PDBorderStyleDictionary().apply { width = 1f }
        val annots = page.annotations.toMutableList()
        annots.add(w)
        page.annotations = annots
        return w
    }

    private fun widgetFor(page: PDPage, g: PageGeometry, rect: FRect, field: PDTerminalField, tooltip: String?): PDAnnotationWidget {
        tooltip?.takeIf { it.isNotBlank() }?.let { field.alternateFieldName = it }
        return setupWidget(page, g, rect, field.widgets.first())
    }

    private fun addField(doc: PDDocument, acro: PDAcroForm, f: NewField) {
        if (f.pageIndex !in 0 until doc.numberOfPages) return
        val page = doc.getPage(f.pageIndex)
        val g = PageGeometry.of(page)
        val autoSize = if (f.fontSize > 0f) f.fontSize else 0f
        when (f.type) {
            NewFieldType.TEXT, NewFieldType.MULTILINE -> {
                val field = PDTextField(acro)
                field.partialName = uniqueName(doc, f.name)
                field.defaultAppearance = "/Helv ${AnnotationWriter.fmt(autoSize)} Tf 0 g"
                if (f.type == NewFieldType.MULTILINE) field.isMultiline = true
                field.isRequired = f.required
                widgetFor(page, g, f.rect, field, f.tooltip)
                acro.fields.add(field)
                setTextSafely(acro, field, f.defaultValue)
            }
            NewFieldType.DROPDOWN, NewFieldType.LIST -> {
                val field = if (f.type == NewFieldType.DROPDOWN) PDComboBox(acro) else PDListBox(acro)
                field.partialName = uniqueName(doc, f.name)
                field.defaultAppearance = "/Helv ${AnnotationWriter.fmt(autoSize)} Tf 0 g"
                val opts = f.options.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Option 1", "Option 2") }
                field.options = opts
                field.isRequired = f.required
                widgetFor(page, g, f.rect, field, f.tooltip)
                acro.fields.add(field)
                try {
                    field.setValue(f.defaultValue.takeIf { it in opts } ?: opts.first())
                } catch (e: Exception) {
                    CoreLog.w("Choice appearance failed", e)
                    acro.setNeedAppearances(true)
                }
            }
            NewFieldType.CHECKBOX -> {
                val field = PDCheckBox(acro)
                field.partialName = uniqueName(doc, f.name)
                field.isRequired = f.required
                val on = exportName(f.exportValue)
                val w = widgetFor(page, g, f.rect, field, f.tooltip)
                applyButtonAppearance(doc, w, g, f.rect, on, radio = false)
                acro.fields.add(field)
                field.cosObject.setItem(COSName.V, COSName.Off)
                if (f.defaultValue.equals("true", true) || f.defaultValue == on) setButtonState(field, w, on)
            }
            NewFieldType.RADIO -> {
                val on = exportName(f.exportValue)
                val existing = acro.getField(f.name) as? PDRadioButton
                val field = existing ?: PDRadioButton(acro).also {
                    it.partialName = uniqueName(doc, f.name)
                    it.isRequired = f.required
                    it.cosObject.setItem(COSName.V, COSName.Off)
                }
                val w = setupWidget(page, g, f.rect, PDAnnotationWidget())
                // setWidgets writes /Kids and each widget's /Parent.
                field.widgets = if (existing == null) listOf(w) else existing.widgets + w
                if (existing == null) acro.fields.add(field)
                f.tooltip?.takeIf { it.isNotBlank() }?.let { field.alternateFieldName = it }
                applyButtonAppearance(doc, w, g, f.rect, on, radio = true)
                if (f.defaultValue.equals("true", true)) setButtonState(field, w, on)
            }
            NewFieldType.SIGNATURE -> {
                val field = PDSignatureField(acro)
                field.partialName = uniqueName(doc, f.name)
                val w = widgetFor(page, g, f.rect, field, f.tooltip)
                val disp = f.rect
                w.appearance = Appearances.displayAligned(doc, g, disp) { cs, ww, hh ->
                    cs.setNonStrokingColor(0.93f, 0.94f, 1f)
                    cs.addRect(0f, 0f, ww, hh); cs.fill()
                    cs.setStrokingColor(0.36f, 0.42f, 0.75f)
                    cs.setLineWidth(1f)
                    cs.addRect(0.5f, 0.5f, ww - 1f, hh - 1f); cs.stroke()
                    val size = min(hh * 0.35f, 11f)
                    cs.setNonStrokingColor(0.36f, 0.42f, 0.75f)
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, size)
                    cs.newLineAtOffset(4f, hh / 2f - size * 0.35f)
                    cs.showText("Sign here")
                    cs.endText()
                }
                acro.fields.add(field)
            }
        }
    }

    private fun setTextSafely(acro: PDAcroForm, field: PDTextField, value: String) {
        try {
            field.setValue(value)
        } catch (e: Exception) {
            CoreLog.w("Text field appearance failed", e)
            field.cosObject.setString(COSName.V, value)
            acro.setNeedAppearances(true)
        }
    }

    private fun exportName(v: String): String = v.trim().replace(Regex("[\\s/()<>\\[\\]{}%#]"), "_").ifBlank { "Yes" }.let {
        if (it == "Off") "On" else it
    }

    private fun setButtonState(field: PDField, w: PDAnnotationWidget, on: String) {
        field.cosObject.setName(COSName.V, on)
        w.cosObject.setName(COSName.AS, on)
    }

    /** Builds On/Off appearance states for checkboxes (check mark) and radio buttons (dot). */
    private fun applyButtonAppearance(doc: PDDocument, w: PDAnnotationWidget, g: PageGeometry, disp: FRect, on: String, radio: Boolean) {
        fun state(checked: Boolean): PDAppearanceStream {
            val wd = disp.width
            val ht = disp.height
            val ap = Appearances.newStream(doc, FRect(0f, 0f, wd, ht), g.appearanceRotationMatrix()) { cs ->
                drawButton(cs, wd, ht, checked, radio)
            }
            return ap
        }
        val n = COSDictionary()
        n.setItem(COSName.getPDFName(on), state(true).cosObject)
        n.setItem(COSName.Off, state(false).cosObject)
        val dict = PDAppearanceDictionary()
        dict.cosObject.setItem(COSName.N, n)
        w.appearance = dict
        w.cosObject.setName(COSName.AS, "Off")
        val mk = w.appearanceCharacteristics ?: PDAppearanceCharacteristicsDictionary(COSDictionary())
        mk.cosObject.setString(COSName.CA, if (radio) "l" else "4")
        w.appearanceCharacteristics = mk
        w.cosObject.setString(COSName.DA, "/ZaDb 0 Tf 0 g")
    }

    private fun drawButton(cs: PDPageContentStream, w: Float, h: Float, checked: Boolean, radio: Boolean) {
        val s = min(w, h)
        val ox = (w - s) / 2f
        val oy = (h - s) / 2f
        cs.setNonStrokingColor(0.93f, 0.94f, 1f)
        cs.setStrokingColor(0.36f, 0.42f, 0.75f)
        cs.setLineWidth(1f)
        if (radio) {
            Appearances.ellipse(cs, FRect(ox + 0.5f, oy + 0.5f, ox + s - 0.5f, oy + s - 0.5f))
            cs.fillAndStroke()
            if (checked) {
                cs.setNonStrokingColor(0.1f, 0.1f, 0.1f)
                Appearances.ellipse(cs, FRect(ox + s * 0.28f, oy + s * 0.28f, ox + s * 0.72f, oy + s * 0.72f))
                cs.fill()
            }
        } else {
            cs.addRect(ox + 0.5f, oy + 0.5f, s - 1f, s - 1f)
            cs.fillAndStroke()
            if (checked) {
                cs.setStrokingColor(0.1f, 0.1f, 0.1f)
                cs.setLineWidth(kotlin.math.max(1f, s / 9f))
                cs.setLineCapStyle(1)
                cs.setLineJoinStyle(1)
                cs.moveTo(ox + s * 0.2f, oy + s * 0.52f)
                cs.lineTo(ox + s * 0.42f, oy + s * 0.28f)
                cs.lineTo(ox + s * 0.8f, oy + s * 0.75f)
                cs.stroke()
            }
        }
    }

    /** Removes a field (all its widgets) by fully qualified name. */
    fun deleteField(doc: PDDocument, name: String): Boolean {
        val acro = doc.documentCatalog.acroForm ?: return false
        val field = acro.getField(name) as? PDTerminalField ?: return false
        val widgets = field.widgets.map { it.cosObject }.toSet()
        for (i in 0 until doc.numberOfPages) {
            val page = doc.getPage(i)
            val annots = try { page.annotations } catch (e: IOException) { continue }
            val keep = annots.filter { it.cosObject !in widgets }
            if (keep.size != annots.size) page.annotations = keep
        }
        // Remove from the field tree (merged field/widget dicts or kids).
        val fieldsArr = acro.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray
        if (fieldsArr != null) removeFromTree(fieldsArr, field.cosObject, 0)
        FormFieldPruner.removeWidgets(doc, widgets)
        return true
    }

    private fun removeFromTree(arr: COSArray, target: COSDictionary, depth: Int): Boolean {
        if (depth > 32) return false
        for (i in arr.size() - 1 downTo 0) {
            val d = FormFieldPruner.resolve(arr.get(i)) as? COSDictionary ?: continue
            if (d === target) { arr.remove(i); return true }
            val kids = d.getDictionaryObject(COSName.KIDS) as? COSArray ?: continue
            if (removeFromTree(kids, target, depth + 1)) {
                if (kids.size() == 0) arr.remove(i)
                return true
            }
        }
        return false
    }

    fun renameField(doc: PDDocument, name: String, newName: String): Boolean {
        val acro = doc.documentCatalog.acroForm ?: return false
        val field = acro.getField(name) ?: return false
        if (acro.getField(newName) != null) return false
        field.partialName = newName.replace('.', '_')
        return true
    }

    @Suppress("unused")
    private fun unusedAnnotation(a: PDAnnotation) = a
}
