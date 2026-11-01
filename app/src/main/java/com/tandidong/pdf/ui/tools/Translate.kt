package com.tandidong.pdf.ui.tools

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device translation (Google ML Kit); nothing is sent to a server except language-pack downloads. */
object DocTranslator {

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }

    /** Supported target languages as (code, display name), sorted by name. */
    fun languages(): List<Pair<String, String>> =
        TranslateLanguage.getAllLanguages().map { it to Locale(it).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { c -> c.titlecase() } }
            .sortedBy { it.second }

    /** The device language if translation supports it, else English. */
    fun defaultTarget(): String = TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

    /** Detects the language of [sample]; null when unknown. */
    suspend fun detect(sample: String): String? {
        val id = LanguageIdentification.getClient()
        try {
            val code = id.identifyLanguage(sample.take(4000)).await()
            return if (code == "und") null else TranslateLanguage.fromLanguageTag(code)
        } finally {
            id.close()
        }
    }

    suspend fun translate(texts: List<String>, source: String, target: String, onProgress: (Int, Int) -> Unit): List<String> {
        if (source == target) return texts
        val options = TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
        val translator = Translation.getClient(options)
        try {
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } catch (e: Exception) {
                throw IOException("Couldn't download the language pack. Check your internet connection.", e)
            }
            val out = ArrayList<String>(texts.size)
            for ((i, t) in texts.withIndex()) {
                out.add(if (t.isBlank()) t else translator.translate(t).await())
                onProgress(i + 1, texts.size)
            }
            return out
        } finally {
            translator.close()
        }
    }
}
