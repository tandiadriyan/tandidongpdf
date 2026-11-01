package com.tandidong.pdf.core

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException

/** Loading/saving helpers shared by all operations. */
object PdfIO {
    /** Keep up to this many bytes of parsed data in RAM before spilling to temp files. */
    var maxMainMemoryBytes: Long = 48L * 1024 * 1024

    fun memorySetting(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(maxMainMemoryBytes)

    @Throws(IOException::class)
    fun load(file: File, password: String? = null): PDDocument =
        PDDocument.load(file, password ?: "", memorySetting())

    /** Full (non-incremental) save, which drops unreachable objects such as redacted content. */
    @Throws(IOException::class)
    fun save(doc: PDDocument, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            doc.save(tmp)
            if (target.exists() && !target.delete()) throw IOException("Cannot replace $target")
            if (!tmp.renameTo(target)) throw IOException("Cannot move temp file to $target")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Loads [input], runs [block], saves the result to [output] and closes the document. */
    @Throws(IOException::class)
    inline fun <T> transform(input: File, output: File, password: String? = null, block: (PDDocument) -> T): T {
        val doc = load(input, password)
        try {
            val result = block(doc)
            if (doc.isEncrypted && !doc.isAllSecurityToBeRemoved) {
                // Working copies are kept decrypted; callers re-protect on export if needed.
                doc.isAllSecurityToBeRemoved = true
            }
            save(doc, output)
            return result
        } finally {
            doc.close()
        }
    }

    @Throws(IOException::class)
    inline fun <T> read(input: File, password: String? = null, block: (PDDocument) -> T): T {
        val doc = load(input, password)
        try {
            return block(doc)
        } finally {
            doc.close()
        }
    }

    enum class OpenCheck { OK, NEEDS_PASSWORD, WRONG_PASSWORD, CORRUPT }

    /** Probes whether [file] opens with [password] (null means "no password"). */
    fun check(file: File, password: String?): OpenCheck = try {
        load(file, password).close()
        OpenCheck.OK
    } catch (e: InvalidPasswordException) {
        if (password.isNullOrEmpty()) OpenCheck.NEEDS_PASSWORD else OpenCheck.WRONG_PASSWORD
    } catch (e: IOException) {
        CoreLog.w("PDF failed to open", e)
        OpenCheck.CORRUPT
    }
}
