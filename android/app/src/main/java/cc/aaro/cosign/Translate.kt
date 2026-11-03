package cc.aaro.cosign

import android.content.Context
import cc.aaro.qrst.core.Json
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Your own LibreTranslate (Account › Languages you read), if you set one: posts are translated through it rather
 * than on the phone (OnDevice), and it gives a second opinion on a language the phone isn't sure of. Down, slow or
 * wrong, the phone translates instead, and the server isn't asked again for ten minutes. Its address and key stay
 * on this phone.
 */
object Translate {
    private val prefs by lazy { CosignApp.instance.getSharedPreferences("translate", Context.MODE_PRIVATE) }
    val url by lazy { MutableStateFlow(prefs.getString("url", "") ?: "") }
    /** LibreTranslate's `api_key`, for a server that asks for one; empty if yours doesn't. */
    val key by lazy { MutableStateFlow(prefs.getString("key", "") ?: "") }
    fun setKey(k: String) { key.value = k.trim(); prefs.edit().putString("key", key.value).apply(); waitUntil = 0; status.value = null }
    /** How the last request went, for the setting to show. */
    val status = MutableStateFlow<String?>(null)
    @Volatile private var waitUntil = 0L
    private val http by lazy { Fetch.http.newBuilder().callTimeout(2, TimeUnit.SECONDS).build() }

    val on get() = url.value.isNotEmpty()

    fun setUrl(u: String) {
        // https only: a plain http address is refused, and one without a scheme gets https.
        val clean = u.trim().trimEnd('/').let { if (it.isEmpty() || it.startsWith("https://")) it else if (it.startsWith("http://")) "" else "https://$it" }
        if (clean.isEmpty() && u.trim().startsWith("http://")) { status.value = "Use an https address (on a tailnet, tailscale serve gives one)."; return }
        url.value = clean
        prefs.edit().putString("url", clean).apply()
        waitUntil = 0
        status.value = null
    }

    /** The phone's own language: what everything is translated into. */
    fun target(): String = android.os.LocaleList.getDefault()[0]?.language?.lowercase()?.let { mapOf("iw" to "he", "in" to "id")[it] ?: it } ?: "en"

    /** Each text translated into [target], with the language it was in; null when the server is off, resting, down, slow or wrong. */
    suspend fun batch(texts: List<String>, target: String, source: String = "auto"): List<Pair<String, String?>>? = withContext(Dispatchers.IO) {
        if (!on || texts.isEmpty() || System.currentTimeMillis() < waitUntil) return@withContext null
        val body = Json.stringify(mapOf("q" to texts, "source" to source, "target" to target, "format" to "text") + (key.value.takeIf { it.isNotEmpty() }?.let { mapOf("api_key" to it) } ?: emptyMap()))
        try {
            http.newCall(Request.Builder().url(url.value + "/translate").post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext fail("answered ${r.code}")
                val m = Json.parse(r.body?.string().orEmpty()) as? Map<*, *> ?: return@withContext fail("answered something else")
                val out = (m["translatedText"] as? List<*>)?.map { it as? String ?: "" } ?: return@withContext fail("answered something else")
                val langs = (m["detectedLanguage"] as? List<*>)?.map { ((it as? Map<*, *>)?.get("language") as? String)?.lowercase() }
                if (out.size != texts.size) return@withContext fail("answered for ${out.size} of ${texts.size}")
                status.value = "Working: translated ${texts.size} just now"
                android.util.Log.i("CosignTranslate", "translated ${texts.size}")
                out.mapIndexed { i, t -> t to langs?.getOrNull(i) }
            }
        } catch (e: Exception) { fail(e.message ?: "no answer") }
    }

    /** One text from [from] into [target] through your server; null when it's off, resting, down or wrong. */
    suspend fun one(text: String, from: String, target: String): String? = batch(listOf(text), target, from)?.firstOrNull()?.first?.takeIf { it.isNotBlank() }

    /** Your server's guess at a text's language (a second opinion), likeliest first, confidence 0 to 1; null when it's off or down. */
    suspend fun detect(text: String): List<Pair<String, Float>>? = withContext(Dispatchers.IO) {
        if (!on || System.currentTimeMillis() < waitUntil) return@withContext null
        val body = Json.stringify(mapOf("q" to text) + (key.value.takeIf { it.isNotEmpty() }?.let { mapOf("api_key" to it) } ?: emptyMap()))
        try {
            http.newCall(Request.Builder().url(url.value + "/detect").post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                (Json.parse(r.body?.string().orEmpty()) as? List<*>)?.mapNotNull { x -> (x as? Map<*, *>)?.let { m ->
                    (m["language"] as? String)?.lowercase()?.let { it to ((m["confidence"] as? Number)?.toFloat() ?: 0f) / 100f } } }
            }
        } catch (_: Exception) { null }
    }

    /** Is it there? For the setting, once you've typed an address. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        if (!on) return@withContext false
        try {
            Fetch.http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build().newCall(Request.Builder().url(url.value + "/languages").build()).execute().use { r ->
                (r.isSuccessful).also { ok -> status.value = if (ok) "Working" else "Not working: answered ${r.code}"; if (ok) waitUntil = 0 }
            }
        } catch (e: Exception) { status.value = "Not working: ${e.message ?: "no answer"}"; false }
    }

    private fun fail(why: String): List<Pair<String, String?>>? {
        waitUntil = System.currentTimeMillis() + 10 * 60_000L
        status.value = "Not working ($why): translating on this phone instead; trying again in 10 minutes"
        android.util.Log.i("CosignTranslate", "failed: $why")
        return null
    }

    /** "Portuguese", for "Translated from Portuguese". */
    fun name(code: String?): String? = code?.takeIf { it.isNotEmpty() && it != "auto" }?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale.getDefault()).ifEmpty { null } }
}
