package com.tandidong.pdf.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tandidong.pdf.core.DigitalId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Metadata for a stored digital ID; the key material stays in a password-protected .p12 file. */
data class DigitalIdEntry(
    val id: String,
    val name: String,
    val issuer: String,
    val email: String?,
    val notAfter: Long,
    val selfSigned: Boolean,
) {
    val expired: Boolean get() = notAfter in 1 until System.currentTimeMillis()
}

class DigitalIdStore(context: Context) {
    private val dir = File(context.filesDir, "ids").apply { mkdirs() }
    private val index = File(dir, "ids.json")

    var items by mutableStateOf(load())
        private set

    private fun load(): List<DigitalIdEntry> = try {
        if (!index.exists()) emptyList() else {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DigitalIdEntry(
                    o.getString("id"), o.optString("name"), o.optString("issuer"),
                    o.optString("email").takeIf { it.isNotBlank() }, o.optLong("notAfter"), o.optBoolean("selfSigned"),
                )
            }.filter { File(dir, "${it.id}.p12").exists() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist(list: List<DigitalIdEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().put("id", e.id).put("name", e.name).put("issuer", e.issuer).put("email", e.email ?: "")
                .put("notAfter", e.notAfter).put("selfSigned", e.selfSigned))
        }
        index.writeText(arr.toString())
        items = list
    }

    private fun store(id: DigitalId, p12: ByteArray): DigitalIdEntry {
        val key = UUID.randomUUID().toString()
        File(dir, "$key.p12").writeBytes(p12)
        val entry = DigitalIdEntry(key, id.commonName, id.issuerName, id.email, id.certificate.notAfter.time, id.isSelfSigned)
        persist(items + entry)
        return entry
    }

    /** Imports a .p12/.pfx file; throws if the password is wrong or no key is present. */
    fun import(data: ByteArray, password: String): DigitalIdEntry {
        val id = DigitalId.fromPkcs12(data, password.toCharArray())
        return store(id, data)
    }

    fun create(name: String, email: String?, organization: String?, password: String): DigitalIdEntry {
        val id = DigitalId.createSelfSigned(name, email, organization)
        return store(id, id.toPkcs12(password.toCharArray()))
    }

    fun unlock(entry: DigitalIdEntry, password: String): DigitalId =
        DigitalId.fromPkcs12(File(dir, "${entry.id}.p12").readBytes(), password.toCharArray())

    fun exportBytes(entry: DigitalIdEntry): ByteArray = File(dir, "${entry.id}.p12").readBytes()

    fun delete(entry: DigitalIdEntry) {
        File(dir, "${entry.id}.p12").delete()
        persist(items.filter { it.id != entry.id })
    }
}
