package cc.aaro.vaults

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real relays and Blossom servers over OkHttp, for the end-to-end tests (the app has its own, over Fetch). */
class OkNet : RelayNet, Blobs {
    val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    override fun query(relays: List<String>, filter: JSONObject, timeoutMs: Long): Answer {
        val got = ConcurrentHashMap<String, Ev>(); val answered = ConcurrentHashMap.newKeySet<String>()
        val done = CountDownLatch(relays.size)
        for (r in relays) http.newWebSocket(Request.Builder().url(r).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { ws.send(JSONArray().put("REQ").put("q").put(filter).toString()) }
            override fun onMessage(ws: WebSocket, text: String) {
                val m = JSONArray(text)
                when (m.getString(0)) {
                    "EVENT" -> try { Ev.of(m.getJSONObject(2)).let { got[it.id] = it } } catch (_: Exception) {}
                    "EOSE" -> { answered += r; ws.close(1000, null); done.countDown() }
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { done.countDown() }
        })
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return Answer(got.values.toList(), answered.toSet())
    }

    override fun publish(relays: List<String>, ev: Ev): Set<String> {
        val ok = ConcurrentHashMap.newKeySet<String>(); val done = CountDownLatch(relays.size)
        for (r in relays) http.newWebSocket(Request.Builder().url(r).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { ws.send(JSONArray().put("EVENT").put(ev.json()).toString()) }
            override fun onMessage(ws: WebSocket, text: String) {
                val m = JSONArray(text)
                if (m.getString(0) == "OK" && m.optString(1) == ev.id) { if (m.optBoolean(2)) ok += r; ws.close(1000, null); done.countDown() }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { done.countDown() }
        })
        done.await(8, TimeUnit.SECONDS)
        return ok.toSet()
    }

    override fun upload(server: String, bytes: ByteArray, sha256: String, auth: String): Boolean =
        http.newCall(Request.Builder().url("$server/upload").put(bytes.toRequestBody("application/octet-stream".toMediaType())).header("Authorization", auth).build()).execute().use { r ->
            r.isSuccessful && JSONObject(r.body!!.string()).optString("sha256") == sha256
        }
    override fun get(server: String, sha256: String): ByteArray? = http.newCall(Request.Builder().url("$server/$sha256").build()).execute().use { r -> if (r.isSuccessful) r.body!!.bytes() else null }
    override fun delete(server: String, sha256: String, auth: String): Boolean =
        http.newCall(Request.Builder().url("$server/$sha256").delete().header("Authorization", auth).build()).execute().use { it.isSuccessful }
}

/** An account whose nsec is here (a test key, or a plain-key account). */
class NsecAccount(private val sk: ByteArray) : Account {
    override val npub = Keys.pub(sk)
    override fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String) = Ev.sign(sk, createdAt, kind, tags, content)
    override fun openSelf(eph: String, ct: String) = Keys.decrypt(ct, Keys.conv(sk, eph))
    override fun keykin(): KeykinView? = null
}

object WallClock : Clock { override fun now() = System.currentTimeMillis() / 1000 }
