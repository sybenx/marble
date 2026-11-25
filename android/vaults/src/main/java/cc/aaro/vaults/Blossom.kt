// Blob storage for one vault (§6.3, §6.4): parts uploaded to the account's own Blossom servers (kind 10063, in its
// order) under the account's npub, to the first two that accept; fetched from any of them and checked.
//
// (Decided by the person, 2026-11-18: personal servers allowlist the account, so the npub signs the BUD-02/BUD-11
// auth, through the signer or Keykin, with no prompt. Downloads stay unauthenticated, by hash.)
package cc.aaro.vaults

import cc.aaro.keykin.core.hex
import cc.aaro.keykin.core.random
import cc.aaro.keykin.core.sha256
import java.util.Base64

class Storage(private val blobs: Blobs, private val clock: Clock) {
    /** Signs blob auth: the account's npub (set by [Vaults]); a test key elsewhere. */
    var signer: ((Long, Int, List<List<String>>, String) -> Ev)? = null

    /** Why each server last refused, by server: an HTTP status ("403"), or "error". For logs. */
    val refused = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Servers that said no (good-guest §3.5): left alone for that kind of request until they said, or a while. */
    val refusals = Refusals()
    /** Whether the last [get] came back empty because servers refused, not because none had it (not a §7.4 gap). */
    @Volatile var lastGetHeld = false; private set
    /** The last blob put: its first part and the servers that took it. For logs. */
    @Volatile var lastPut: Pair<String, List<String>>? = null

    /** The BUD-11 server tag: the lowercase domain (with a port when it has one). */
    fun domain(server: String): String = try { java.net.URI(server).let { u -> u.host.lowercase() + if (u.port > 0) ":${u.port}" else "" } } catch (_: Exception) { server.lowercase() }

    private fun tags(verb: String, shas: List<String>, server: String, now: Long) =
        listOf(listOf("t", verb)) + shas.map { listOf("x", it) } + listOf(listOf("expiration", (now + 300).toString()), listOf("server", domain(server)))
    private fun header(e: Ev) = "Nostr " + Base64.getEncoder().encodeToString(e.json().toString().toByteArray())

    /** BUD-02/BUD-11 auth: kind 24242 by the account, for [verb] on [shas], on [server] only. */
    fun auth(verb: String, shas: List<String>, server: String): String {
        val now = clock.now()
        val sign = signer ?: throw IllegalStateException("no signer for blob auth")
        return header(sign(now, Kinds.BLOSSOM_AUTH, tags(verb, shas, server, now), "nostr-vault"))
    }
    /** The same, signed by [key] (the §6.4 probe's keys). */
    fun auth(key: ByteArray, verb: String, shas: List<String>, server: String): String {
        val now = clock.now()
        return header(Ev.sign(key, now, Kinds.BLOSSOM_AUTH, tags(verb, shas, server, now), "nostr-vault"))
    }

    /**
     * Every part to the first [want] of [servers] (already normalized, in the account's order) that take them all; one
     * auth per server covers every part. A 401, 403 or 415 (or any failure) skips to the next server; a refusal (§3.5)
     * also leaves that server alone for a while, and one waiting out a refusal isn't asked. The servers that took it.
     */
    fun put(sealed: Nv1.Sealed, servers: List<String>, want: Int = 2): List<String> {
        val took = ArrayList<String>()
        val shas = sealed.parts.map { hex(sha256(it)) }
        for (s in refusals.open(servers, "upload", clock.now())) {
            if (took.size >= want) break
            val a = try { auth("upload", shas, s) } catch (e: Exception) { refused[s] = "sign"; continue }
            var why: String? = null
            for ((i, p) in sealed.parts.withIndex()) {
                var noted = false
                val code = try { blobs.upload(s, p, shas[i], a) } catch (e: BlobRefused) { refusals.refused(s, "upload", e, clock.now()); noted = true; e.status } catch (_: Exception) { -1 }
                if (!noted && Refusing.isRefusal(code, null)) refusals.refused(s, "upload", code, null, null, clock.now())
                if (code != 200) { why = when (code) { -1 -> "error"; -2 -> "hash"; else -> code.toString() }; break }
            }
            if (why == null) { took += s; refused.remove(s); refusals.answered(s, "upload") } else refused[s] = why
        }
        lastPut = shas.first() to took.toList()
        return took
    }

    /**
     * The plaintext, each part from the first server whose bytes hash right (§6.2). Null: not found whole anywhere, and
     * [lastGetHeld] says whether that was because servers that might have it refused (or are waiting out a refusal).
     */
    fun get(ref: BlobRef, servers: List<String>): ByteArray? {
        var held = false
        val parts = ref.parts.mapIndexed { i, sha ->
            servers.firstNotNullOfOrNull { s ->
                val t = clock.now()
                if (refusals.waiting(s, "get", t) != null) { held = true; return@firstNotNullOfOrNull null }
                try { blobs.get(s, sha)?.takeIf { Nv1.partOK(ref, i, it) }?.also { refusals.answered(s, "get") } }
                catch (e: BlobRefused) { if (Refusing.isRefusal(e.status, e.retryAfter)) { refusals.refused(s, "get", e, t); held = true }; null }
                catch (_: Exception) { null }
            } ?: return null.also { lastGetHeld = held }
        }
        lastGetHeld = false
        return Nv1.open(ref, parts)
    }

    fun delete(sha: String, servers: List<String>) {
        for (s in refusals.open(servers, "delete", clock.now())) try {
            if (blobs.delete(s, sha, auth("delete", listOf(sha), s))) refusals.answered(s, "delete")
        } catch (e: BlobRefused) { if (Refusing.isRefusal(e.status, e.retryAfter)) refusals.refused(s, "delete", e, clock.now()) } catch (_: Exception) {}
    }

    /**
     * §6.4's probe: does [server] keep a blob per owner? Two probe keys A and B upload the same random blob; a DELETE by
     * one must leave it for the other. (Each uploads rather than mirrors: the same test of ownership, without BUD-04.)
     * Not used to choose servers any more (the account's own list is used as it is); kept for testing servers.
     */
    fun probe(server: String, storageKey: ByteArray): Boolean {
        val a = Keys.scalar(storageKey, "nostr-vault/probe/1".toByteArray()); val b = Keys.scalar(storageKey, "nostr-vault/probe/2".toByteArray())
        val bytes = random(64); val sha = hex(sha256(bytes))
        fun up(k: ByteArray) = try { blobs.upload(server, bytes, sha, auth(k, "upload", listOf(sha), server)) == 200 } catch (_: Exception) { false }
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

    companion object {
        /** A server list as the account published it: trailing slashes off, http(s) only, duplicates dropped, order kept. */
        fun normalize(servers: List<String>): List<String> =
            servers.map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") || it.startsWith("http://") }.distinctBy { it.lowercase() }
    }
}
