package com.tandidong.pdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tandidong.pdf.ui.editor.DocumentScreen
import com.tandidong.pdf.ui.home.HomeScreen
import com.tandidong.pdf.ui.home.SettingsScreen
import com.tandidong.pdf.ui.organize.OrganizeScreen
import com.tandidong.pdf.ui.tools.CombineScreen
import com.tandidong.pdf.ui.tools.CompareScreen
import com.tandidong.pdf.ui.tools.ImagesToPdfScreen
import com.tandidong.pdf.ui.tools.ReflowScreen

@Composable
fun AppRoot(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        when (val s = vm.screen) {
            Screen.Home -> HomeScreen(vm)
            is Screen.Document -> {
                val session = vm.session(s.sessionId)
                if (session != null) DocumentScreen(vm, session, s.launch) else LaunchedEffect(s) { vm.back() }
            }
            is Screen.Organize -> {
                val session = vm.session(s.sessionId)
                if (session != null) OrganizeScreen(vm, session) else LaunchedEffect(s) { vm.back() }
            }
            is Screen.Reflow -> {
                val session = vm.session(s.sessionId)
                if (session != null) ReflowScreen(vm, session) else LaunchedEffect(s) { vm.back() }
            }
            Screen.Combine -> CombineScreen(vm)
            Screen.ImagesToPdf -> ImagesToPdfScreen(vm)
            Screen.Compare -> CompareScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
        vm.globalBusy?.let { BusyOverlay(it, null) }
        vm.passwordRequest?.let { PasswordDialog(it) }
    }
}

@Composable
fun BusyOverlay(label: String, progress: Float?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(28.dp).height(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    if (progress != null && progress >= 0f) {
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(req: PasswordRequest) {
    var pw by remember(req) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { req.submit(null) },
        title = { Text("Password required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\"${req.fileName}\" is protected. Enter its password to open it.")
                if (req.wrong) Text("Incorrect password, try again.", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { req.submit(pw) }, enabled = pw.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = { req.submit(null) }) { Text("Cancel") } },
    )
}
