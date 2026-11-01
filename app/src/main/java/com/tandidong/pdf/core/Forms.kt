package com.tandidong.pdf.core

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.IOException
import java.util.IdentityHashMap

enum class FieldKind { TEXT, CHECKBOX, RADIO, COMBO, LIST, SIGNATURE, BUTTON }

data class FieldWidget(val pageIndex: Int, val displayRect: FRect, val onState: String?)

data class FormField(
    val name: String,
    val label: String,
    val kind: FieldKind,
    val value: String,
    /** Display values for choices / radio export values. */
    val options: List<String>,
    /** Export values matching [options]. */
    val exportValues: List<String>,
    val readOnly: Boolean,
    val required: Boolean,
    val multiline: Boolean,
    val maxLength: Int,
    val editableCombo: Boolean,
    val widgets: List<FieldWidget>,
) {
    val isChecked: Boolean get() = kind == FieldKind.CHECKBOX && value.isNotEmpty() && value != "Off"
}

/** Reading, filling and flattening AcroForm fields. */
object Forms {
    fun hasForm(doc: PDDocument): Boolean = doc.documentCatalog.acroForm?.fields?.isNotEmpty() == true

    fun list(doc: PDDocument): List<FormField> {
        val acro = doc.documentCatalog.acroForm ?: return emptyList()
        val pageOf = IdentityHashMap<COSDictionary, Int>()
        for (i in 0 until doc.numberOfPages) {
            val annots = try { doc.getPage(i).annotations } catch (e: IOException) { continue }
            for (a in annots) if (a.subtype == "Widget") pageOf[a.cosObject] = i
        }
        val out = ArrayList<FormField>()
        for (field in acro.fieldTree) {
            if (field !is PDTerminalField) continue
            val widgets = field.widgets.mapNotNull { w ->
                val pi = pageOf[w.cosObject] ?: return@mapNotNull null
                val r = w.rectangle ?: return@mapNotNull null
                val g = PageGeometry.of(doc.getPage(pi))
                val normal = w.appearance?.normalAppearance
                val on = if (normal != null && normal.isSubDictionary) {
                    normal.subDictionary.keys.firstOrNull { it.name != "Off" }?.name
                } else null
                FieldWidget(pi, g.rectToDisplay(FRect.of(r)), on)
            }
            out.add(describe(field, widgets) ?: continue)
        }
        return out
    }

    private fun describe(field: PDTerminalField, widgets: List<FieldWidget>): FormField? {
        val name = field.fullyQualifiedName ?: return null
        val label = field.alternateFieldName?.takeIf { it.isNotBlank() } ?: field.partialName ?: name
        val common = { kind: FieldKind, value: String, opts: List<String>, exports: List<String> ->
            FormField(
                name, label, kind, value, opts, exports, field.isReadOnly, field.isRequired,
                (field as? PDTextField)?.isMultiline == true, (field as? PDTextField)?.maxLen ?: -1,
                (field as? PDComboBox)?.isEdit == true, widgets,
            )
        }
        return when (field) {
            is PDTextField -> common(FieldKind.TEXT, field.value ?: "", emptyList(), emptyList())
            is PDCheckBox -> common(FieldKind.CHECKBOX, if (field.isChecked) field.onValue ?: "Yes" else "Off", listOf(field.onValue ?: "Yes"), listOf(field.onValue ?: "Yes"))
            is PDRadioButton -> {
                val ons = try { field.onValues.toList() } catch (e: Exception) { emptyList() }
                val exports = field.exportValues.takeIf { it.isNotEmpty() } ?: ons
                common(FieldKind.RADIO, field.value ?: "Off", exports, ons.ifEmpty { exports })
            }
            is PDComboBox -> common(FieldKind.COMBO, field.value.firstOrNull() ?: "", field.optionsDisplayValues, field.optionsExportValues)
            is PDListBox -> common(FieldKind.LIST, field.value.joinToString(", "), field.optionsDisplayValues, field.optionsExportValues)
            is PDSignatureField -> common(FieldKind.SIGNATURE, if (field.cosObject.getDictionaryObject(COSName.V) != null) "Signed" else "", emptyList(), emptyList())
            is PDPushButton -> common(FieldKind.BUTTON, "", emptyList(), emptyList())
            else -> null
        }
    }

    /**
     * Fills fields by fully qualified name. Checkbox values: "true"/"false" or an export value.
     * Returns the names of fields that could not be set.
     */
    fun fill(doc: PDDocument, values: Map<String, String>): List<String> {
        val acro = doc.documentCatalog.acroForm ?: return values.keys.toList()
        val failed = ArrayList<String>()
        var needAppearances = false
        acro.setNeedAppearances(false)
        for ((name, value) in values) {
            val field: PDField = acro.getField(name) ?: run { failed.add(name); null } ?: continue
            if (field.isReadOnly) { failed.add(name); continue }
            try {
                when (field) {
                    is PDCheckBox -> {
                        val on = value.equals("true", true) || value.equals("yes", true) || value == field.onValue || value == "1"
                        if (on) field.check() else field.unCheck()
                    }
                    is PDRadioButton -> field.setValue(value)
                    is PDChoice -> field.setValue(value)
                    is PDTextField -> field.setValue(value)
                    else -> failed.add(name)
                }
            } catch (e: Exception) {
                // Missing fonts or unsupported characters: store the value and let viewers render it.
                CoreLog.w("Could not build appearance for $name", e)
                try {
                    field.cosObject.setString(COSName.V, value)
                    needAppearances = true
                } catch (e2: Exception) {
                    failed.add(name)
                }
            }
        }
        if (needAppearances) acro.setNeedAppearances(true)
        return failed
    }

    fun flatten(doc: PDDocument) {
        val acro = doc.documentCatalog.acroForm ?: return
        try {
            acro.setNeedAppearances(false)
            acro.refreshAppearances()
        } catch (e: Exception) {
            CoreLog.w("refreshAppearances failed", e)
        }
        acro.flatten()
    }

    fun reset(doc: PDDocument) {
        val acro = doc.documentCatalog.acroForm ?: return
        for (field in acro.fieldTree) {
            if (field !is PDTerminalField || field.isReadOnly) continue
            try {
                when (field) {
                    is PDCheckBox -> field.unCheck()
                    is PDRadioButton -> field.setValue("Off")
                    is PDTextField -> field.setValue("")
                    is PDChoice -> field.setValue("")
                    else -> {}
                }
            } catch (e: Exception) {
                CoreLog.w("reset failed for ${field.fullyQualifiedName}", e)
            }
        }
    }
}
