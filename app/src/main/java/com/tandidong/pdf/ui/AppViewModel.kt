package com.tandidong.pdf.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tandidong.pdf.core.FontSelector
import com.tandidong.pdf.core.PdfIO
import com.tandidong.pdf.data.AppPrefs
import com.tandidong.pdf.data.FileUtil
import com.tandidong.pdf.data.RecentsRepository
import com.tandidong.pdf.data.SignatureStore
import com.tandidong.pdf.platform.AndroidFontProvider
import com.tandidong.pdf.session.DocumentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Tools that can be launched directly from the home screen. */
enum class ToolLaunch {
    COMMENT, FILL_SIGN, EDIT, REDACT, ORGANIZE, COMPRESS, PROTECT, EXPORT, OCR, WATERMARK,
    HEADER_FOOTER, READ_ALOUD, REFLOW, PRINT, SEARCH, BOOKMARKS, PROPERTIES, FLATTEN, SANITIZE,
    PREPARE_FORM, SIGN_CERT,
}

sealed interface Screen {
    data object Home : Screen
    data class Document(val sessionId: String, val launch: ToolLaunch? = null) : Screen
    data class Organize(val sessionId: String) : Screen
    data class Reflow(val sessionId: String) : Screen
    data object Combine : Screen
    data object ImagesToPdf : Screen
    data object Compare : Screen
    data object Settings : Screen
}

data class PasswordRequest(val fileName: String, val wrong: Boolean, val submit: (String?) -> Unit)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = AppPrefs(app)
    val recents = RecentsRepository(app)
    val signatures = SignatureStore(app)
    val digitalIds = com.tandidong.pdf.data.DigitalIdStore(app)
    val fonts = FontSelector(AndroidFontProvider)

    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()

    private val sessions = HashMap<String, DocumentSession>()
    private val controllers = HashMap<String, com.tandidong.pdf.ui.editor.DocumentController>()

    /** Editor state for a session; kept here so it survives navigating to other screens. */
    fun controllerFor(session: DocumentSession): com.tandidong.pdf.ui.editor.DocumentController =
        controllers.getOrPut(session.id) {
            com.tandidong.pdf.ui.editor.DocumentController(this, session, viewModelScope, getApplication())
        }

    var passwordRequest by mutableStateOf<PasswordRequest?>(null)
        private set
    var globalBusy by mutableStateOf<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    init {
        // Working copies do not survive process death; clear leftovers from earlier runs.
        File(app.filesDir, "work").deleteRecursively()
    }

    fun session(id: String): DocumentSession? = sessions[id]

    fun message(text: String) {
        messages.tryEmit(text)
    }

    fun navigate(s: Screen) {
        backStack.add(s)
    }

    fun replaceTop(s: Screen) {
        if (backStack.size > 1) backStack.removeAt(backStack.size - 1)
        backStack.add(s)
    }

    /** Pops the current screen; returns false when already at home. */
    fun back(): Boolean {
        if (backStack.size <= 1) return false
        val top = backStack.removeAt(backStack.size - 1)
        if (top is Screen.Document && backStack.none { it is Screen.Document && it.sessionId == top.sessionId }) {
            closeSession(top.sessionId)
        }
        return true
    }

    fun closeSession(id: String) {
        controllers.remove(id)?.readAloud?.shutdown()
        sessions.remove(id)?.close()
    }

    suspend fun requestPassword(name: String, wrong: Boolean): String? = suspendCancellableCoroutine { cont ->
        passwordRequest = PasswordRequest(name, wrong) { pw ->
            passwordRequest = null
            if (cont.isActive) cont.resume(pw)
        }
        cont.invokeOnCancellation { passwordRequest = null }
    }

    private fun newWorkDir(id: String) = File(getApplication<Application>().filesDir, "work/$id").apply { mkdirs() }

    /** Opens a document from a content URI, asking for a password if needed. */
    fun openDocument(uri: Uri, launch: ToolLaunch? = null) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            globalBusy = "Opening…"
            var dir: File? = null
            try {
                FileUtil.takePersistable(ctx, uri)
                val name = FileUtil.displayName(ctx, uri)
                val id = UUID.randomUUID().toString()
                dir = newWorkDir(id)
                val original = File(dir, "original.pdf")
                withContext(Dispatchers.IO) { FileUtil.copyToFile(ctx, uri, original) }
                val session = prepareSession(id, dir, original, uri, name)
                if (session == null) {
                    dir.deleteRecursively()
                    return@launch
                }
                sessions[id] = session
                recents.touch(uri, name, original.length().takeIf { it > 0 } ?: session.currentFile.length(), session.pageCount)
                makeThumbnail(session, uri.toString())
                navigate(Screen.Document(id, launch))
            } catch (e: Exception) {
                dir?.deleteRecursively()
                message("Couldn't open file: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                globalBusy = null
            }
        }
    }

    /** Opens a file produced inside the app (scan, merge, conversion) as a new unsaved document. */
    fun openGenerated(file: File, name: String, launch: ToolLaunch? = null) {
        viewModelScope.launch {
            globalBusy = "Opening…"
            try {
                val id = UUID.randomUUID().toString()
                val dir = newWorkDir(id)
                val original = File(dir, "original.pdf")
                withContext(Dispatchers.IO) { file.copyTo(original, overwrite = true) }
                val session = prepareSession(id, dir, original, null, name) ?: return@launch
                sessions[id] = session
                navigate(Screen.Document(id, launch))
            } catch (e: Exception) {
                message("Couldn't open document: ${e.message}")
            } finally {
                globalBusy = null
            }
        }
    }

    private suspend fun prepareSession(id: String, dir: File, original: File, uri: Uri?, name: String): DocumentSession? {
        var password: String? = null
        var check = withContext(Dispatchers.IO) { PdfIO.check(original, null) }
        while (check == PdfIO.OpenCheck.NEEDS_PASSWORD || check == PdfIO.OpenCheck.WRONG_PASSWORD) {
            globalBusy = null
            password = requestPassword(name, check == PdfIO.OpenCheck.WRONG_PASSWORD) ?: return null
            globalBusy = "Opening…"
            check = withContext(Dispatchers.IO) { PdfIO.check(original, password) }
        }
        if (check == PdfIO.OpenCheck.CORRUPT) throw IOException("The file is damaged or is not a PDF")
        val v0 = File(dir, "v0.pdf")
        var restricted = false
        var encrypted = false
        withContext(Dispatchers.IO) {
            val doc = PdfIO.load(original, password)
            try {
                if (doc.isEncrypted) {
                    encrypted = true
                    val ap = doc.currentAccessPermission
                    restricted = !ap.isOwnerPermission && !ap.canModify()
                    doc.isAllSecurityToBeRemoved = true
                    PdfIO.save(doc, v0)
                }
            } finally {
                doc.close()
            }
            if (!v0.exists()) {
                if (!original.renameTo(v0)) original.copyTo(v0, overwrite = true)
            }
        }
        val session = DocumentSession(
            context = getApplication(),
            id = id,
            sourceUri = uri,
            displayName = name,
            workDir = dir,
            firstVersion = v0,
            originalPassword = password?.takeIf { it.isNotEmpty() },
            restricted = restricted,
            originalFile = if (encrypted) original else null,
            originalWasEncrypted = encrypted,
        )
        session.init()
        if (!encrypted && original.exists() && original != v0) original.delete()
        if (restricted) message("This document restricts editing. You can view, search and print it.")
        return session
    }

    private fun makeThumbnail(session: DocumentSession, key: String) {
        viewModelScope.launch {
            val bmp = session.renderPage(0, 220, session.version) ?: return@launch
            withContext(Dispatchers.IO) { recents.saveThumb(key, bmp) }
        }
    }

    fun newDocumentName(prefix: String): String =
        "$prefix ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())}.pdf"

    /**
     * Copies a PDF from [uri] into a scratch file and resolves its password (prompting the user).
     * Returns null if the user cancels the password prompt.
     */
    suspend fun importForReading(uri: Uri): Pair<File, String?>? {
        val ctx = getApplication<Application>()
        val name = FileUtil.displayName(ctx, uri)
        val file = scratchFile("in-${System.nanoTime()}.pdf")
        withContext(Dispatchers.IO) { FileUtil.copyToFile(ctx, uri, file) }
        var pw: String? = null
        var check = withContext(Dispatchers.IO) { PdfIO.check(file, null) }
        while (check == PdfIO.OpenCheck.NEEDS_PASSWORD || check == PdfIO.OpenCheck.WRONG_PASSWORD) {
            pw = requestPassword(name, check == PdfIO.OpenCheck.WRONG_PASSWORD) ?: return null
            check = withContext(Dispatchers.IO) { PdfIO.check(file, pw) }
        }
        if (check == PdfIO.OpenCheck.CORRUPT) throw IOException("$name is damaged or is not a PDF")
        return file to pw
    }

    /** Scratch file in the cache directory for intermediate outputs. */
    fun scratchFile(name: String): File {
        val dir = File(getApplication<Application>().cacheDir, "scratch").apply { mkdirs() }
        return File(dir, name)
    }

    override fun onCleared() {
        controllers.values.forEach { it.readAloud.shutdown() }
        controllers.clear()
        sessions.values.forEach { it.close() }
        sessions.clear()
        super.onCleared()
    }
}
