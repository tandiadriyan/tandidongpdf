package com.tandidong.pdf.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.tandidong.pdf.core.Pt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Simple preferences backed by SharedPreferences, exposed as Compose state. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("tandidong_prefs", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(sp.getInt("theme", 0)) // 0 system, 1 light, 2 dark
        private set
    var authorName by mutableStateOf(sp.getString("author", "") ?: "")
        private set
    var keepScreenOn by mutableStateOf(sp.getBoolean("keep_screen_on", true))
        private set
    var nightReading by mutableStateOf(sp.getBoolean("night_reading", false))
        private set
    var redactFill by mutableStateOf(sp.getInt("redact_fill", 0xFF000000.toInt()))
        private set
    var redactLabel by mutableStateOf(sp.getString("redact_label", "") ?: "")
        private set
    var penColor by mutableStateOf(sp.getInt("pen_color", 0xFF1E88E5.toInt()))
        private set
    var penWidth by mutableStateOf(sp.getFloat("pen_width", 2f))
        private set
    var highlightColor by mutableStateOf(sp.getInt("highlight_color", 0xFFFFEB3B.toInt()))
        private set
    var shapeColor by mutableStateOf(sp.getInt("shape_color", 0xFFE53935.toInt()))
        private set
    var textColor by mutableStateOf(sp.getInt("text_color", 0xFF000000.toInt()))
        private set
    var textSize by mutableStateOf(sp.getFloat("text_size", 12f))
        private set

    /** Saved personal details for form filling, keyed by [com.tandidong.pdf.core.AutofillKey.id]. */
    var autofill by mutableStateOf(loadAutofill())
        private set

    private fun loadAutofill(): Map<String, String> = try {
        val o = org.json.JSONObject(sp.getString("autofill", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
    } catch (e: Exception) {
        emptyMap()
    }

    fun updateAutofill(id: String, value: String) {
        autofill = (autofill + (id to value)).filterValues { it.isNotBlank() }
        sp.edit().putString("autofill", org.json.JSONObject(autofill).toString()).apply()
    }

    var measureUnit by mutableStateOf(sp.getString("measure_unit", "cm") ?: "cm")
        private set
    /** Real-world units per paper unit (1 = actual size, 100 = 1:100). */
    var measureScale by mutableStateOf(sp.getFloat("measure_scale", 1f))
        private set

    fun updateMeasureUnit(v: String) { measureUnit = v; sp.edit().putString("measure_unit", v).apply() }
    fun updateMeasureScale(v: Float) { measureScale = v; sp.edit().putFloat("measure_scale", v).apply() }

    fun updateTheme(v: Int) { themeMode = v; sp.edit().putInt("theme", v).apply() }
    fun updateAuthor(v: String) { authorName = v; sp.edit().putString("author", v).apply() }
    fun updateKeepScreenOn(v: Boolean) { keepScreenOn = v; sp.edit().putBoolean("keep_screen_on", v).apply() }
    fun updateNightReading(v: Boolean) { nightReading = v; sp.edit().putBoolean("night_reading", v).apply() }
    fun updateRedactFill(v: Int) { redactFill = v; sp.edit().putInt("redact_fill", v).apply() }
    fun updateRedactLabel(v: String) { redactLabel = v; sp.edit().putString("redact_label", v).apply() }
    fun updatePenColor(v: Int) { penColor = v; sp.edit().putInt("pen_color", v).apply() }
    fun updatePenWidth(v: Float) { penWidth = v; sp.edit().putFloat("pen_width", v).apply() }
    fun updateHighlightColor(v: Int) { highlightColor = v; sp.edit().putInt("highlight_color", v).apply() }
    fun updateShapeColor(v: Int) { shapeColor = v; sp.edit().putInt("shape_color", v).apply() }
    fun updateTextColor(v: Int) { textColor = v; sp.edit().putInt("text_color", v).apply() }
    fun updateTextSize(v: Float) { textSize = v; sp.edit().putFloat("text_size", v).apply() }
}

data class RecentFile(
    val uri: String,
    val name: String,
    val size: Long,
    val pages: Int,
    val lastOpened: Long,
    val starred: Boolean,
) {
    val key: String get() = hash(uri)
}

fun hash(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

/** Recently opened documents with thumbnails. */
class RecentsRepository(private val context: Context) {
    private val sp = context.getSharedPreferences("tandidong_recents", Context.MODE_PRIVATE)
    private val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }

    var items by mutableStateOf(load())
        private set

    private fun load(): List<RecentFile> = try {
        val arr = JSONArray(sp.getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentFile(o.getString("uri"), o.optString("name"), o.optLong("size"), o.optInt("pages"), o.optLong("t"), o.optBoolean("star"))
        }.sortedByDescending { it.lastOpened }
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist(list: List<RecentFile>) {
        val arr = JSONArray()
        for (r in list.take(100)) {
            arr.put(JSONObject().put("uri", r.uri).put("name", r.name).put("size", r.size).put("pages", r.pages).put("t", r.lastOpened).put("star", r.starred))
        }
        sp.edit().putString("items", arr.toString()).apply()
        items = list.sortedByDescending { it.lastOpened }
    }

    fun touch(uri: Uri, name: String, size: Long, pages: Int) {
        val existing = items.firstOrNull { it.uri == uri.toString() }
        val entry = RecentFile(uri.toString(), name, size, pages, System.currentTimeMillis(), existing?.starred ?: false)
        persist(listOf(entry) + items.filter { it.uri != uri.toString() })
    }

    fun remove(uri: String) {
        thumbFile(uri).delete()
        persist(items.filter { it.uri != uri })
    }

    fun toggleStar(uri: String) = persist(items.map { if (it.uri == uri) it.copy(starred = !it.starred) else it })

    fun clear() {
        thumbDir.listFiles()?.forEach { it.delete() }
        persist(emptyList())
    }

    fun thumbFile(uri: String) = File(thumbDir, hash(uri) + ".png")

    fun saveThumb(uri: String, bitmap: Bitmap) {
        try {
            FileOutputStream(thumbFile(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (e: IOException) {
            // thumbnails are optional
        }
    }
}

/** A hand-drawn signature or initials, strokes normalized to 0..1 (y down) with an aspect ratio. */
data class SavedSignature(
    val id: String,
    val strokes: List<List<Pt>>,
    val aspect: Float,
    val initials: Boolean,
    val argb: Int,
    val width: Float,
)

class SignatureStore(context: Context) {
    private val file = File(context.filesDir, "signatures.json")

    var items by mutableStateOf(load())
        private set

    private fun load(): List<SavedSignature> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val strokesJson = o.getJSONArray("strokes")
                val strokes = (0 until strokesJson.length()).map { s ->
                    val pts = strokesJson.getJSONArray(s)
                    (0 until pts.length() / 2).map { k -> Pt(pts.getDouble(k * 2).toFloat(), pts.getDouble(k * 2 + 1).toFloat()) }
                }
                SavedSignature(o.getString("id"), strokes, o.getDouble("aspect").toFloat(), o.optBoolean("initials"), o.optInt("argb", 0xFF0D47A1.toInt()), o.optDouble("width", 2.0).toFloat())
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist(list: List<SavedSignature>) {
        val arr = JSONArray()
        for (s in list) {
            val strokes = JSONArray()
            for (st in s.strokes) {
                val pts = JSONArray()
                st.forEach { pts.put(it.x.toDouble()); pts.put(it.y.toDouble()) }
                strokes.put(pts)
            }
            arr.put(JSONObject().put("id", s.id).put("strokes", strokes).put("aspect", s.aspect.toDouble()).put("initials", s.initials).put("argb", s.argb).put("width", s.width.toDouble()))
        }
        file.writeText(arr.toString())
        items = list
    }

    fun add(strokes: List<List<Pt>>, aspect: Float, initials: Boolean, argb: Int, width: Float): SavedSignature {
        val sig = SavedSignature(UUID.randomUUID().toString(), strokes, aspect, initials, argb, width)
        persist(items + sig)
        return sig
    }

    fun delete(id: String) = persist(items.filter { it.id != id })
}

object FileUtil {
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i)?.let { return it }
                    }
                }
            } catch (e: Exception) {
                // fall through
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "document.pdf"
    }

    fun size(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return -1
    }

    @Throws(IOException::class)
    fun copyToFile(context: Context, uri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
    }

    @Throws(IOException::class)
    fun writeToUri(context: Context, file: File, uri: Uri) {
        // Prefer truncate mode; some providers reject "wt" (throwing) but truncate on "w".
        val out = try { context.contentResolver.openOutputStream(uri, "wt") } catch (e: Exception) { null }
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Cannot write to $uri")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Opens [uri] for writing, truncating where the provider supports it. */
    @Throws(IOException::class)
    fun openOutput(context: Context, uri: Uri): java.io.OutputStream =
        (try { context.contentResolver.openOutputStream(uri, "wt") } catch (e: Exception) { null })
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Cannot write to $uri")

    fun takePersistable(context: Context, uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: Exception) {
                // not a persistable grant (e.g. opened from another app); fine for this session
            }
        }
    }

    fun canWrite(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** Copies [file] into the shareable cache dir under [name] and returns a content URI. */
    fun shareableUri(context: Context, file: File, name: String): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val out = File(dir, safe)
        file.copyTo(out, overwrite = true)
        return FileProvider.getUriForFile(context, context.packageName + ".files", out)
    }

    fun share(context: Context, file: File, name: String, mime: String = "application/pdf") {
        val uri = shareableUri(context, file, name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens [file] in another app (falls back to the share sheet when none can view it). */
    fun openWith(context: Context, file: File, name: String, mime: String) {
        val uri = shareableUri(context, file, name)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(view)
        } catch (e: android.content.ActivityNotFoundException) {
            share(context, file, name, mime)
        }
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun baseName(name: String) = name.removeSuffix(".pdf").removeSuffix(".PDF")
}
