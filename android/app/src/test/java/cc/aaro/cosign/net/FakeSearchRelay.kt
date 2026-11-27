package cc.aaro.cosign.net

import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.Json
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake relays for the search smoke test, on 127.0.0.1 (a bare WebSocket server, nothing else needed): each path answers
 * the way a real relay does at its worst, as tools/search-fake-relay.mjs does for the web test, from the same world
 * (tools/search-fixtures.json).
 *   /loose-<world>-…   NIP-50 that takes a post with ANY one of the words (Chronicle, a sloppy search relay); operators dropped.
 *   /ignore-<world>-…  a relay that ignores `search` and sends whatever else matches.
 * COUNT (NIP-45) on kind 3 #p answers from the world's follower numbers. [log]: every filter asked, with its path.
 */
class FakeSearchRelay(private val world: Map<String, List<Event>>, private val followers: Map<String, Long>) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    val log = CopyOnWriteArrayList<Pair<String, Map<*, *>>>()
    val port get() = server.localPort
    fun url(path: String) = "ws://127.0.0.1:$port/$path"

    init {
        Thread({ while (!server.isClosed) { val s = try { server.accept() } catch (_: Exception) { break }; sockets += s; Thread({ serve(s) }, "fake-relay-conn").apply { isDaemon = true }.start() } }, "fake-relay").apply { isDaemon = true }.start()
    }

    override fun close() { server.close(); sockets.forEach { try { it.close() } catch (_: Exception) {} } }

    private fun serve(s: Socket) = try {
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        val out = s.getOutputStream()
        val head = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
        val path = head.first().split(" ")[1].trim('/')
        val key = head.first { it.lowercase().startsWith("sec-websocket-key:") }.substringAfter(":").trim()
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray()); out.flush()
        val events = world[path.split('-').getOrNull(1)].orEmpty()
        val how = path.substringBefore('-')
        val text = StringBuilder()
        while (true) {
            val b0 = input.readUnsignedByte(); val b1 = input.readUnsignedByte()
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
            val data = ByteArray(len.toInt()).also { input.readFully(it) }
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (b0 and 0x0f) {
                0x8 -> { send(out, 0x8, ByteArray(0)); break }
                0x9 -> send(out, 0xA, data)
                0x1, 0x0 -> { text.append(String(data, Charsets.UTF_8)); if (b0 and 0x80 != 0) { answer(path, how, events, text.toString()) { send(out, 0x1, it.toByteArray()) }; text.clear() } }
            }
        }
    } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} }

    private fun answer(path: String, how: String, events: List<Event>, raw: String, send: (String) -> Unit) {
        val msg = Json.parse(raw) as? List<*> ?: return
        when (msg.getOrNull(0)) {
            "REQ" -> {
                val id = msg[1] as String
                val seen = HashSet<String>()
                for (f in msg.drop(2).filterIsInstance<Map<*, *>>()) {
                    log += path to f
                    for (e in matches(how, events, f)) if (seen.add(e.id)) send(Json.stringify(listOf("EVENT", id, e.toMap())))
                }
                send(Json.stringify(listOf("EOSE", id)))
            }
            "COUNT" -> {
                val id = msg[1] as String; val f = msg.getOrNull(2) as? Map<*, *> ?: return
                log += path to f
                val ps = (f["#p"] as? List<*>)?.filterIsInstance<String>()
                val n = if (ps != null && (f["kinds"] as? List<*>)?.contains(3L) == true) ps.sumOf { followers[it] ?: 0L } else matches("ignore", events, f).size.toLong()
                send(Json.stringify(listOf("COUNT", id, mapOf("count" to n))))
            }
            "EVENT" -> send(Json.stringify(listOf("OK", (msg.getOrNull(1) as? Map<*, *>)?.get("id"), false, "blocked: read-only test relay")))
        }
    }

    companion object {
        private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        private fun words(q: String) = norm(q.split(Regex("\\s+")).filterNot { Regex("^[a-zA-Z_]+:\\S.*").matches(it) }.joinToString(" ")).split(' ').filter { it.isNotEmpty() }
        private fun body(e: Event): String = if (e.kind == 0) {
            val p = try { Json.parse(e.content) as? Map<*, *> } catch (_: Exception) { null } ?: emptyMap<String, Any>()
            norm(listOf("name", "display_name", "about", "nip05").joinToString(" ") { (p[it] as? String).orEmpty() })
        } else norm(e.content + " " + (e.firstTag("title") ?: ""))

        /** What a relay of [how] sends for [f] out of [events], newest first, at most its limit. */
        fun matches(how: String, events: List<Event>, f: Map<*, *>): List<Event> {
            fun strings(k: String) = (f[k] as? List<*>)?.filterIsInstance<String>()
            val kinds = (f["kinds"] as? List<*>)?.map { (it as Number).toInt() }
            var list = events.filter { e ->
                (strings("ids")?.contains(e.id) ?: true) && (kinds?.contains(e.kind) ?: true) && (strings("authors")?.contains(e.pubkey) ?: true) &&
                    ((f["since"] as? Number)?.let { e.createdAt >= it.toLong() } ?: true) && ((f["until"] as? Number)?.let { e.createdAt <= it.toLong() } ?: true) &&
                    f.keys.filterIsInstance<String>().filter { it.startsWith("#") }.all { k -> e.tags.any { t -> t.size > 1 && t[0] == k.drop(1) && strings(k)!!.contains(t[1]) } }
            }
            val search = f["search"] as? String
            if (how == "loose" && search != null) {
                val ws = words(search)
                list = list.filter { e -> val b = " ${body(e)} "; ws.any { it in b } }
            }
            return list.sortedByDescending { it.createdAt }.take((f["limit"] as? Number)?.toInt() ?: 500)
        }

        private fun readLine(input: DataInputStream): String {
            val b = StringBuilder()
            while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }
            return b.toString()
        }
    }

    @Synchronized private fun send(out: OutputStream, op: Int, data: ByteArray) {
        out.write(0x80 or op)
        when { data.size < 126 -> out.write(data.size); data.size < 65536 -> { out.write(126); out.write(data.size shr 8); out.write(data.size and 0xff) }
            else -> { out.write(127); for (i in 7 downTo 0) out.write(((data.size.toLong() shr (8 * i)) and 0xff).toInt()) } }
        out.write(data); out.flush()
    }
}
