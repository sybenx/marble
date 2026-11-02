package cc.aaro.cosign

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Translation on the phone (ML Kit): which language a post is in, and the translation, both on the device. A
 * language's model (~30 MB) is downloaded once, the first time it's needed; after that nothing leaves the phone.
 * The languages you read: the phone's, and any you add in Account.
 */
object OnDevice {
    private val prefs by lazy { CosignApp.instance.getSharedPreferences("translate", Context.MODE_PRIVATE) }
    /** Languages you added (Account › Languages you read). */
    val added = MutableStateFlow<Set<String>>(emptySet())
    fun load() { added.value = prefs.getStringSet("reads", emptySet())!!.toSet() }
    fun setAdded(s: Set<String>) { added.value = s; prefs.edit().putStringSet("reads", s).apply() }

    /** The phone's languages and yours: posts in these aren't translated. */
    fun reads(): Set<String> {
        val l = android.os.LocaleList.getDefault()
        return ((0 until l.size()).map { norm(l[it].language) } + added.value).toSet()
    }
    /** What posts are translated into: the phone's first language. */
    fun target(): String = norm(android.os.LocaleList.getDefault()[0]?.language ?: "en")
    private fun norm(c: String) = c.lowercase().let { mapOf("iw" to "he", "in" to "id", "ji" to "yi")[it] ?: it }

    fun supported(code: String) = TranslateLanguage.fromLanguageTag(code) != null
    /** Every language it can translate, for "Other". */
    fun all(): List<String> = TranslateLanguage.getAllLanguages().sortedBy { name(it) }
    /** "Spanish", in the phone's language. */
    fun name(code: String): String = java.util.Locale(code).getDisplayLanguage(java.util.Locale.getDefault()).ifEmpty { code }

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }; addOnFailureListener { c.resume(null) }; addOnCanceledListener { c.resume(null) }
    }

    private val identifier by lazy { LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.05f).build()) }

    /** The likeliest languages of [cleaned] (already stripped of links and the like), with confidence. */
    suspend fun candidates(cleaned: String): List<Pair<String, Float>> =
        identifier.identifyPossibleLanguages(cleaned).await()?.map { norm(it.languageTag.substringBefore('-')) to it.confidence } ?: emptyList()

    /** Whether translating from [from] needs a download first. */
    suspend fun needsDownload(from: String): Boolean {
        val have = RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()?.map { it.language }?.toSet() ?: return true
        return listOf(from, target()).any { it != TranslateLanguage.ENGLISH && it !in have }
    }

    /** [text] from [from] into your language; null if it can't (or the model isn't there and [download] is false). */
    suspend fun translate(text: String, from: String, download: Boolean): String? {
        val src = TranslateLanguage.fromLanguageTag(from) ?: return null
        val dst = TranslateLanguage.fromLanguageTag(target()) ?: return null
        if (src == dst) return null
        val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        try {
            if (download) t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() ?: return null
            else if (needsDownload(from)) return null
            val (protectedText, kept) = PostLanguage.protect(text)
            // Paragraph by paragraph, so line breaks stay where they were.
            val out = protectedText.split("\n").map { line -> if (line.isBlank()) line else t.translate(line).await() ?: return null }.joinToString("\n")
            return PostLanguage.restore(out, kept)
        } finally { t.close() }
    }
}
