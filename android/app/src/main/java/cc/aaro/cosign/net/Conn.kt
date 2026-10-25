package cc.aaro.cosign.net

import cc.aaro.cosign.core.Event
import cc.aaro.cosign.core.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One socket to one relay, shared by every query to it (a tab, its counts), so switching tabs
 * doesn't pay for a new connection each time. Opened when asked, closed after [idleMs] with
 * nothing asked: nothing stays open while you're not looking.
 */
class Conn(val url: String, private val idleMs: Long = 45_000) {
    private class Sub(val events: ArrayList<Event> = ArrayList(), val done: CompletableDeferred<Long?> = CompletableDeferred())

    private val subs = ConcurrentHashMap<String, Sub>()
    private val n = AtomicInteger()
    private var ws: WebSocket? = null
    private var open = false
    private val queue = ArrayList<String>()
    private var idle: ScheduledFuture<*>? = null

    /** What the relay has for [filters], until it says it's done or [timeoutMs] passes. Signatures checked. */
    suspend fun req(filters: List<Map<String, Any>>, timeoutMs: Long = 8000): List<Event> {
        val id = "q" + n.incrementAndGet()
        val sub = Sub()
        subs[id] = sub
        send(Json.stringify(listOf("REQ", id) + filters))
        withTimeoutOrNull(timeoutMs) { sub.done.await() }
        finish(id, close = true)
        return synchronized(sub.events) { sub.events.toList() }
    }

    /** NIP-45: how many events match, or null if the relay won't say. */
    suspend fun count(filter: Map<String, Any>, timeoutMs: Long = 8000): Long? {
        val id = "c" + n.incrementAndGet()
        val sub = Sub()
        subs[id] = sub
        send(Json.stringify(listOf("COUNT", id, filter)))
        val got = withTimeoutOrNull(timeoutMs) { sub.done.await() }
        finish(id, close = false)
        return got
    }

    private fun finish(id: String, close: Boolean) {
        subs.remove(id)
        if (close) synchronized(this) { if (open) ws?.send(Json.stringify(listOf("CLOSE", id))) }
        synchronized(this) { if (subs.isEmpty()) { idle?.cancel(false); idle = timer.schedule({ closeIfIdle() }, idleMs, TimeUnit.MILLISECONDS) } }
    }

    @Synchronized private fun closeIfIdle() { if (subs.isEmpty()) { ws?.close(1000, null); ws = null; open = false } }

    @Synchronized private fun send(text: String) {
        idle?.cancel(false)
        if (open) { ws?.send(text); return }
        queue += text
        if (ws == null) connect()
    }

    private fun connect() {
        ws = Fetch.http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@Conn) {
                if (ws !== webSocket) return
                open = true
                queue.forEach { webSocket.send(it) }; queue.clear()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Json.parse(text) as? List<*> ?: return
                val sub = subs[msg.getOrNull(1) as? String ?: return] ?: return
                when (msg.getOrNull(0)) {
                    "EVENT" -> Event.fromMap(msg.getOrNull(2))?.let { e -> if (e.verify()) synchronized(sub.events) { if (sub.events.none { it.id == e.id }) sub.events += e } }
                    "EOSE", "CLOSED" -> sub.done.complete(null)
                    "COUNT" -> sub.done.complete(((msg.getOrNull(2) as? Map<*, *>)?.get("count") as? Long))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
        })
    }

    /** Gone: whatever was waiting ends with what it has; the next query connects again. */
    private fun dropped(socket: WebSocket) {
        synchronized(this) { if (ws !== socket) return; ws = null; open = false; queue.clear() }
        subs.values.forEach { it.done.complete(null) }
    }

    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor()
        private val all = ConcurrentHashMap<String, Conn>()
        fun of(url: String): Conn = all.getOrPut(url) { Conn(url) }
    }
}
