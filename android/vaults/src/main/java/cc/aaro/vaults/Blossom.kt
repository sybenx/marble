// Blob storage for one vault (§6.3, §6.4): parts uploaded under this device's storage key to every vault server,
// fetched from any of them and checked.
package cc.aaro.vaults

import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.random
import cc.aaro.keykin.core.sha256
import java.util.Base64

class Storage(private val blobs: Blobs, private val clock: Clock) {
    /** The BUD-11 server tag: the lowercase domain (with a port when it has one). */
    fun domain(server: String): String = try { java.net.URI(server).let { u -> u.host.lowercase() + if (u.port > 0) ":${u.port}" else "" } } catch (_: Exception) { server.lowercase() }

    /** BUD-02/BUD-11 auth: kind 24242 by [key], for [verb] on [shas], on [server] only. */
    fun auth(key: ByteArray, verb: String, shas: List<String>, server: String): String {
        val now = clock.now()
        val tags = listOf(listOf("t", verb)) + shas.map { listOf("x", it) } + listOf(listOf("expiration", (now + 300).toString()), listOf("server", domain(server)))
        val e = Ev.sign(key, now, Kinds.BLOSSOM_AUTH, tags, "nostr-vault")
        return "Nostr " + Base64.getEncoder().encodeToString(e.json().toString().toByteArray())
    }

    /** Every part to every server; the servers that took every part. */
    fun put(sealed: Nv1.Sealed, servers: List<String>, key: ByteArray): List<String> = servers.filter { s ->
        sealed.parts.all { p -> val sha = hex(sha256(p)); try { blobs.upload(s, p, sha, auth(key, "upload", listOf(sha), s)) } catch (_: Exception) { false } }
    }

    /** The plaintext, each part from the first server whose bytes hash right (§6.2). Null: not found whole anywhere. */
    fun get(ref: BlobRef, servers: List<String>): ByteArray? {
        val parts = ref.parts.mapIndexed { i, sha ->
            servers.firstNotNullOfOrNull { s -> try { blobs.get(s, sha)?.takeIf { Nv1.partOK(ref, i, it) } } catch (_: Exception) { null } } ?: return null
        }
        return Nv1.open(ref, parts)
    }

    fun delete(sha: String, servers: List<String>, key: ByteArray) { for (s in servers) try { blobs.delete(s, sha, auth(key, "delete", listOf(sha), s)) } catch (_: Exception) {} }

    /**
     * §6.4's probe: does [server] keep a blob per owner? Two probe keys A and B upload the same random blob; a DELETE by
     * one must leave it for the other. (Each uploads rather than mirrors: the same test of ownership, without BUD-04.)
     */
    fun probe(server: String, storageKey: ByteArray): Boolean {
        val a = Keys.scalar(storageKey, "nostr-vault/probe/1".toByteArray()); val b = Keys.scalar(storageKey, "nostr-vault/probe/2".toByteArray())
        val bytes = random(64); val sha = hex(sha256(bytes))
        fun up(k: ByteArray) = try { blobs.upload(server, bytes, sha, auth(k, "upload", listOf(sha), server)) } catch (_: Exception) { false }
        fun del(k: ByteArray) = try { blobs.delete(server, sha, auth(k, "delete", listOf(sha), server)) } catch (_: Exception) { false }
        fun there() = try { blobs.get(server, sha)?.contentEquals(bytes) == true } catch (_: Exception) { false }
        try {
            if (!up(a) || !up(b)) return false
            del(b); if (!there()) return false
            if (!up(b)) return false
            del(a); if (!there()) return false
            return true
        } finally { del(a); del(b) }
    }
}
