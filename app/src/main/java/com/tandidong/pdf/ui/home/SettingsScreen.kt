package com.tandidong.pdf.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tandidong.pdf.BuildConfig
import com.tandidong.pdf.ui.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val p = vm.prefs
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("Appearance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            listOf("Follow system", "Light", "Dark").forEachIndexed { i, label ->
                Row(Modifier.fillMaxWidth().clickable { p.updateTheme(i) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p.themeMode == i, onClick = { p.updateTheme(i) })
                    Text(label)
                }
            }
            ListItem(
                headlineContent = { Text("Night reading by default") },
                supportingContent = { Text("Invert page colors for reading in the dark") },
                trailingContent = { Switch(checked = p.nightReading, onCheckedChange = { p.updateNightReading(it) }) },
            )
            ListItem(
                headlineContent = { Text("Keep screen on") },
                supportingContent = { Text("Prevent the screen from turning off while reading") },
                trailingContent = { Switch(checked = p.keepScreenOn, onCheckedChange = { p.updateKeepScreenOn(it) }) },
            )
            HorizontalDivider()
            Text("Comments", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            OutlinedTextField(
                value = p.authorName,
                onValueChange = { p.updateAuthor(it) },
                label = { Text("Author name for comments") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Text("Digital IDs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            val ids = vm.digitalIds.items
            if (ids.isEmpty()) {
                Text(
                    "No digital IDs yet. Create or import one from Fill & Sign → Digital ID in any document.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            for (e in ids) {
                ListItem(
                    headlineContent = { Text(e.name) },
                    supportingContent = {
                        val exp = java.text.DateFormat.getDateInstance().format(java.util.Date(e.notAfter))
                        Text((if (e.selfSigned) "Self-signed" else "Issued by ${e.issuer}") + " · valid until $exp")
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                try {
                                    val f = java.io.File(context.cacheDir, "id-export.p12")
                                    f.writeBytes(vm.digitalIds.exportBytes(e))
                                    com.tandidong.pdf.data.FileUtil.share(context, f, e.name.replace(' ', '_') + ".p12", "application/x-pkcs12")
                                } catch (ex: Exception) {
                                    vm.message("Couldn't export: ${ex.message}")
                                }
                            }) { Icon(androidx.compose.material.icons.Icons.Outlined.IosShare, "Back up") }
                            IconButton(onClick = { vm.digitalIds.delete(e); vm.message("Digital ID deleted") }) {
                                Icon(androidx.compose.material.icons.Icons.Outlined.Delete, "Delete")
                            }
                        }
                    },
                )
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Text("Storage", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            ListItem(
                headlineContent = { Text("Clear recent files") },
                supportingContent = { Text("Removes the list and thumbnails, not the files themselves") },
                modifier = Modifier.clickable { vm.recents.clear(); vm.message("Recent files cleared") },
            )
            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
            ListItem(
                headlineContent = { Text("Tandidong PDF ${BuildConfig.VERSION_NAME}") },
                supportingContent = {
                    Text(
                        "Everything runs on your device; documents are never uploaded.\n\n" +
                            "Open-source components: PdfBox-Android (Apache License 2.0), " +
                            "PdfiumAndroid / PDFium (Apache License 2.0 / BSD), " +
                            "Jetpack Compose (Apache License 2.0), Google ML Kit (text recognition and document scanner).",
                    )
                },
            )
        }
    }
}
