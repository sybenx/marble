package cc.aaro.cosign.notes

import cc.aaro.cosign.Phone
import cc.aaro.cosign.net.Fetch
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json
import cc.aaro.vaults.Account
import cc.aaro.vaults.Answer
import cc.aaro.vaults.Blobs
import cc.aaro.vaults.Ev
import cc.aaro.vaults.KeykinView
import cc.aaro.vaults.RelayNet
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The vault engine's relays and Blossom servers, as Marble reaches them (Fetch, OkHttp). Blocking: background threads only. */
object VaultNet : RelayNet, Blobs {
    private fun toMap(o: JSONObject): Map<String, Any> = o.keys().asSequence().associateWith { k ->
        when (val v = o.get(k)) { is JSONArray -> List(v.length()) { v.get(it) }; else -> v }
    }
    fun ev(e: Event): Ev? = try { Ev.of(JSONObject(e.toJson())) } catch (_: Exception) { null }
    fun event(e: Ev): Event = Event.fromMap(Json.parse(e.json().toString())) ?: throw IllegalStateException("bad event")

    override fun query(relays: List<String>, filter: JSONObject, timeoutMs: Long): Answer = runBlocking {
        val (found, answered) = Fetch.queryAnswered(relays, toMap(filter), timeoutMs) { it.size == relays.size }
        Answer(found.mapNotNull(::ev), answered)
    }
    override fun publish(relays: List<String>, ev: Ev): Set<String> = runBlocking { Fetch.publishOk(relays, event(ev), 8000) }

    private val http by lazy { Fetch.http.newBuilder().callTimeout(60, TimeUnit.SECONDS).build() }
    override fun upload(server: String, bytes: ByteArray, sha256: String, auth: String): Int =
        http.newCall(Request.Builder().url("${server.trimEnd('/')}/upload").put(bytes.toRequestBody("application/octet-stream".toMediaType())).header("Authorization", auth).build()).execute().use { r ->
            if (!r.isSuccessful) r.code else if ((try { JSONObject(r.body?.string() ?: "").optString("sha256") } catch (_: Exception) { "" }) == sha256) 200 else -2
        }
    override fun get(server: String, sha256: String): ByteArray? =
        http.newCall(Request.Builder().url("${server.trimEnd('/')}/$sha256").build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    override fun delete(server: String, sha256: String, auth: String): Boolean =
        http.newCall(Request.Builder().url("${server.trimEnd('/')}/$sha256").delete().header("Authorization", auth).build()).execute().use { it.isSuccessful }
}

/** The account, through Marble's signer: signatures (blob auth included) and the seal's `ecdh` go to the signer process (and the box). */
class PhoneAccount(private val phone: Phone, override val npub: String, private val blossom: () -> List<String> = { emptyList() }) : Account {
    override fun servers(): List<String> = blossom()
    override fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): Ev =
        VaultNet.ev(phone.signOwn(Event(npub, createdAt, kind, tags, content).withId(), approved = true)) ?: throw IllegalStateException("bad event from the signer")
    override fun openSelf(eph: String, ct: String): String? = phone.vaultSeal(eph, ct)
    override fun keykin(): KeykinView? {
        val o = phone.vaultKeykin()?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return null
        val m = o.optJSONObject("members")?.let { j -> j.keys().asSequence().associateWith { j.optBoolean(it) } }
        val g = o.optJSONArray("revokedGrants")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet()
        return KeykinView(o.getString("me"), o.optInt("epoch"), m, g)
    }
}
