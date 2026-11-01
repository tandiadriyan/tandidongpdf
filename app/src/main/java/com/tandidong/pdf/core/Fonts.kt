package com.tandidong.pdf.core

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

/**
 * Chooses fonts for text the app writes into PDFs. Uses the standard Helvetica family when the
 * text is representable in WinAnsiEncoding, otherwise an embedded Unicode font from [provider].
 */
class FontSelector(private val provider: FontProvider?) {

    fun helvetica(bold: Boolean = false): PDFont =
        if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA

    /** Returns a font and a sanitized string that the font can encode. */
    fun fontFor(doc: PDDocument, text: String, bold: Boolean = false): Pair<PDFont, String> {
        val base = helvetica(bold)
        if (canEncode(base, text)) return base to text
        val unicode = try {
            provider?.loadUnicodeFont(doc, text)
        } catch (e: Exception) {
            CoreLog.w("Unicode font unavailable", e)
            null
        }
        if (unicode != null) {
            val cleaned = sanitize(unicode, text)
            if (cleaned.isNotEmpty() || text.isEmpty()) return unicode to cleaned
        }
        return base to sanitize(base, text)
    }

    companion object {
        fun canEncode(font: PDFont, text: String): Boolean = try {
            font.encode(text)
            true
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: Exception) {
            false
        }

        /** Replaces characters the font cannot encode with '?', dropping them if '?' fails too. */
        fun sanitize(font: PDFont, text: String): String {
            if (canEncode(font, text)) return text
            val sb = StringBuilder()
            var i = 0
            val questionOk = canEncode(font, "?")
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val s = String(Character.toChars(cp))
                when {
                    cp == '\n'.code || cp == '\r'.code -> sb.append(s)
                    canEncode(font, s) -> sb.append(s)
                    cp == 0x2019 || cp == 0x2018 -> if (canEncode(font, "'")) sb.append('\'')
                    cp == 0x201C || cp == 0x201D -> if (canEncode(font, "\"")) sb.append('"')
                    questionOk -> sb.append('?')
                }
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        /** Width of [text] in points at [fontSize]. */
        fun width(font: PDFont, text: String, fontSize: Float): Float = try {
            font.getStringWidth(text) / 1000f * fontSize
        } catch (e: Exception) {
            text.length * fontSize * 0.5f
        }

        /** Greedy word wrap that respects explicit newlines. */
        fun wrap(font: PDFont, text: String, fontSize: Float, maxWidth: Float): List<String> {
            val lines = ArrayList<String>()
            for (paragraph in text.replace("\r\n", "\n").split('\n')) {
                if (paragraph.isEmpty()) { lines.add(""); continue }
                var current = StringBuilder()
                for (word in paragraph.split(' ')) {
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (width(font, candidate, fontSize) <= maxWidth || current.isEmpty()) {
                        if (current.isEmpty() && width(font, word, fontSize) > maxWidth) {
                            // Hard-break a single word that does not fit on one line.
                            var chunk = StringBuilder()
                            for (ch in word) {
                                if (width(font, chunk.toString() + ch, fontSize) > maxWidth && chunk.isNotEmpty()) {
                                    lines.add(chunk.toString()); chunk = StringBuilder()
                                }
                                chunk.append(ch)
                            }
                            current = chunk
                        } else {
                            current = StringBuilder(candidate)
                        }
                    } else {
                        lines.add(current.toString())
                        current = StringBuilder(word)
                    }
                }
                lines.add(current.toString())
            }
            return lines
        }
    }
}
