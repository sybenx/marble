// This device as a remote signer for sites (NIP-46), reached through relays. A site gets in one of
// three ways, each after the person said yes on this device:
//   - a single-use ticket (QRST signer-ticket): the site showed a code, the ticket went to it sealed,
//     and the site presents it here as the secret of `connect`;
//   - a bunker:// link the person pasted into the site, whose secret is such a ticket;
//   - a nostrconnect:// code the site showed, which this device answers with the code's own secret.
// From then on the site has a key of its own on the list, which can be signed out. What a site asks
// for is done by `act`, so the app decides what needs the person first; every signature still needs
// the box.
//
// From the Keykin repo's android core, changed for Marble: the three ways in, `act`, and last use.
package cc.aaro.keykin.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SiteSigner(
    /** The account's public key, or null when this device has none. */
    private val account: () -> String?,
    /** Does one request of a site that is on the list: `sign_event`, `nip44_encrypt` and the rest. */
    private val act: (site: Site, method: String, params: List<String>) -> String,
    /** The saved state (see `fresh`), and what to call when it changes. */
    private val state: JSONObject,
    private val changed: (JSONObject) -> Unit,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val TICKET_SECONDS = 900L
        fun fresh(): JSONObject = JSONObject().put("sk", hex(random(32))).put("clients", JSONArray()).put("tickets", JSONArray())
    }
    /** `grant` is this site's id at the box (SPEC 11.5): the box refuses it everywhere once it is revoked. */
    data class Site(val key: String, val origin: String?, val relays: List<String>, val added: Long, val used: Long, val via: String, val grant: String)

    private val sk = unhex(state.getString("sk"))
    val pubkey: String = Secp.pub(sk)
    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val opened = ConcurrentHashMap.newKeySet<String>()
    private val seen = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var running = false
    private fun now() = System.currentTimeMillis() / 1000
    private fun <T> list(name: String, f: (JSONObject) -> T): List<T> = state.optJSONArray(name)?.let { a -> List(a.length()) { f(a.getJSONObject(it)) } } ?: emptyList()
    private fun strings(a: JSONArray?) = a?.let { List(it.length()) { i -> it.getString(i) } } ?: emptyList()

    fun sites(): List<Site> = synchronized(state) { list("clients") {
        Site(it.getString("key"), if (it.has("origin")) it.getString("origin") else null, strings(it.optJSONArray("relays")), it.optLong("added"), it.optLong("used"), it.optString("via", "A scanned code"),
            it.optString("grant").ifEmpty { hex(random(16)).also { g -> it.put("grant", g); changed(state) } })
    } }
    /** How many relays this signer is connected to, of how many it wants. */
    fun relaysUp(): Pair<Int, Int> = opened.size to sockets.size

    /** A ticket for `origin`, good once and for 15 minutes, to be handed to the site that showed the code. */
    fun mint(origin: String?, relays: List<String>, via: String = "A scanned code"): JSONObject {
        val npub = account() ?: refuse("no_account")
        val t = JSONObject().put("v", 1).put("pubkey", npub).put("signer", pubkey).put("relays", JSONArray(relays)).put("t", hex(random(16))).put("expires", now() + TICKET_SECONDS)
        synchronized(state) {
            state.getJSONArray("tickets").put(JSONObject().put("t", t.getString("t")).put("expires", t.getLong("expires")).put("relays", JSONArray(relays)).put("via", via).apply { if (origin != null) put("origin", origin) })
            changed(state)
        }
        start()
        return t
    }

    /** A link to paste into a site (NIP-46). Its secret is a ticket: once, and for 15 minutes. */
    fun bunker(relays: List<String>): String {
        val t = mint(null, relays, "A pasted bunker link")
        return "bunker://$pubkey?" + relays.joinToString("&") { "relay=" + java.net.URLEncoder.encode(it, "UTF-8") } + "&secret=" + t.getString("t")
    }

    /**
     * A site's nostrconnect:// code, after the person said yes: the site is put on the list and told so
     * with the code's own secret, on the relays the code names.
     */
    fun acceptNostrConnect(client: String, relays: List<String>, secret: String, origin: String?) {
        account() ?: refuse("no_account")
        synchronized(state) {
            if (!known(client)) state.getJSONArray("clients").put(JSONObject().put("key", client).put("relays", JSONArray(relays)).put("added", now()).put("used", now()).put("grant", hex(random(16))).put("via", "A nostrconnect code").apply { if (origin != null) put("origin", origin) })
            changed(state)
        }
        start()
        val body = JSONObject().put("id", hex(random(8))).put("result", secret).toString()
        val ev = reply(client, body)
        val end = System.currentTimeMillis() + 15_000
        var sent = 0
        // A relay may still be connecting: say it once on each as it opens.
        val told = HashSet<String>()
        while (System.currentTimeMillis() < end && told.size < relays.size) {
            for (url in relays) if (url !in told && url in opened) { sockets[url]?.let { if (it.send(JSONArray().put("EVENT").put(ev).toString())) { told.add(url); sent++ } } }
            if (told.size < relays.size) Thread.sleep(150)
        }
        if (sent == 0) { signOut(client); refuse("no_relay") }
    }

    fun signOut(key: String) {
        synchronized(state) {
            val keep = JSONArray(); val a = state.getJSONArray("clients")
            for (i in 0 until a.length()) if (a.getJSONObject(i).getString("key") != key) keep.put(a.getJSONObject(i))
            state.put("clients", keep); changed(state)
        }
    }

    /** Listens on every relay a site or a waiting ticket names. Safe to call again. */
    fun start() {
        running = true
        val wanted = synchronized(state) {
            val live = JSONArray(); val a = state.getJSONArray("tickets")
            for (i in 0 until a.length()) if (a.getJSONObject(i).getLong("expires") > now()) live.put(a.getJSONObject(i))
            state.put("tickets", live)
            (list("clients") { strings(it.optJSONArray("relays")) } + list("tickets") { strings(it.optJSONArray("relays")) }).flatten().toSet()
        }
        for (url in wanted) if (!sockets.containsKey(url)) open(url)
    }
    fun stop() { running = false; sockets.values.forEach { it.close(1000, null) }; sockets.clear(); opened.clear() }

    private fun open(url: String) {
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                log("listening on $url")
                opened.add(url)
                ws.send(JSONArray().put("REQ").put("keykin").put(JSONObject().put("kinds", JSONArray().put(24133)).put("#p", JSONArray().put(pubkey)).put("since", now() - 10)).toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try { val m = JSONArray(text); if (m.optString(0) == "EVENT") Thread { onEvent(ws, m.getJSONObject(2)) }.apply { isDaemon = true }.start() } catch (_: Exception) { }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = lost(url, ws)
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { log("relay $url failed: ${t.message}"); lost(url, ws) }
        })
        sockets[url] = ws
    }
    private fun lost(url: String, ws: WebSocket) {
        opened.remove(url)
        if (!sockets.remove(url, ws) || !running) return
        Thread { Thread.sleep(4000); if (running) start() }.apply { isDaemon = true }.start()
    }

    /** A kind 24133 event from this signer to `client`, carrying `body` sealed. */
    private fun reply(client: String, body: String): JSONObject {
        val key = Nip44.conversationKey(Secp.ecdhX(sk, client))
        val tags = listOf(listOf("p", client)); val at = now(); val content = Nip44.encrypt(body, key)
        val eid = eventId(pubkey, at, 24133, tags, content)
        return JSONObject().put("id", eid).put("pubkey", pubkey).put("created_at", at).put("kind", 24133)
            .put("tags", JSONArray(tags.map { JSONArray(it) })).put("content", content).put("sig", Secp.schnorrSign(unhex(eid), sk))
    }

    private fun onEvent(ws: WebSocket, ev: JSONObject) {
        val id = ev.optString("id"); val client = ev.optString("pubkey")
        if (ev.optInt("kind") != 24133 || !seen.add(id) || !Regex("^[0-9a-f]{64}$").matches(client)) return
        val key = Nip44.conversationKey(Secp.ecdhX(sk, client))
        val req = try { JSONObject(Nip44.decrypt(ev.getString("content"), key)) } catch (e: Exception) { log("unreadable request from ${client.take(8)}: ${e.message}"); return }
        if (!req.has("method")) return                      // an answer to something, not a request
        val body = JSONObject().put("id", req.optString("id"))
        try { body.put("result", answer(client, req.optString("method"), strings(req.optJSONArray("params")))) }
        catch (e: KeykinException) { body.put("error", e.code) } catch (e: Exception) { body.put("error", e.message ?: "failed") }
        log("site ${client.take(8)} ${req.optString("method")} -> ${body.optString("error", "ok")}")
        ws.send(JSONArray().put("EVENT").put(reply(client, body.toString())).toString())
    }

    private fun known(client: String) = sites().any { it.key == client }
    private fun used(client: String) = synchronized(state) {
        val a = state.getJSONArray("clients")
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.getString("key") == client && now() - it.optLong("used") > 60) { it.put("used", now()); changed(state) } }
    }

    /** One request from a site. Returns the NIP-46 `result`, or throws. */
    fun answer(client: String, method: String, params: List<String>): String {
        if (method == "connect") {
            if (!known(client)) synchronized(state) {
                val a = state.getJSONArray("tickets"); val keep = JSONArray(); var ticket: JSONObject? = null
                for (i in 0 until a.length()) { val t = a.getJSONObject(i); if (ticket == null && t.getString("t") == params.getOrNull(1) && t.getLong("expires") > now()) ticket = t else keep.put(t) }
                val t = ticket ?: refuse("bad_secret")          // a ticket works once
                state.put("tickets", keep)
                state.getJSONArray("clients").put(JSONObject().put("key", client).put("relays", t.optJSONArray("relays") ?: JSONArray()).put("added", now()).put("used", now()).put("grant", hex(random(16)))
                    .put("via", t.optString("via", "A scanned code")).apply { if (t.has("origin")) put("origin", t.getString("origin")) })
                changed(state)
            }
            return params.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "ack"
        }
        val site = sites().firstOrNull { it.key == client } ?: refuse("not_connected")
        val npub = account() ?: refuse("no_account")
        used(client)
        return when (method) {
            "ping" -> "pong"
            "get_public_key" -> npub
            "sign_event", "nip44_encrypt", "nip44_decrypt", "nip04_encrypt", "nip04_decrypt" -> act(site, method, params)
            else -> refuse("unsupported")
        }
    }
}
