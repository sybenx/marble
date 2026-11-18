package cc.aaro.cosign.core

/**
 * What a signed-in site may do with your key (0.15.1). Three switches, set at sign-in and in its sheet:
 *  - "Sign in and post as you": always on. Any event but a DM, signed as before (anything destructive still asks).
 *  - "Read your private lists" ([lists]): decrypt and encrypt with your own key, which is what private lists use.
 *  - "Read and send DMs" ([dms]): decrypt and encrypt with anyone, and sign DMs (kinds 4, 13, 14, 15, gift wraps).
 * Anything else is refused outright, never asked.
 */
object SiteScopes {
    /** Direct messages: NIP-04 (4), NIP-17's seal (13), chat message (14) and file message (15), and gift wraps (1059). */
    val DM_KINDS = setOf(4, 13, 14, 15, 1059)

    /**
     * Whether a site may run [method] with [params], acting as [me] (the account it signs in as: yours, or its own).
     * [kind] is the event's kind, for `sign_event`. Null: allowed. Otherwise the scope it lacks: "lists" or "dms".
     */
    fun lacks(method: String, params: List<String>, me: String, lists: Boolean, dms: Boolean, kind: Int? = null): String? = when (method) {
        "sign_event" -> if (kind in DM_KINDS && !dms) "dms" else null
        "nip44_encrypt", "nip44_decrypt", "nip04_encrypt", "nip04_decrypt" -> {
            val peer = params.getOrNull(0)?.lowercase()
            when {
                dms -> null
                peer == me.lowercase() -> if (lists) null else "lists"
                else -> "dms"
            }
        }
        else -> null
    }

    /**
     * Whether [plain], from a NIP-44 decrypt with someone else's key that a site with private lists on (but DMs off)
     * asked for, is the account's own vault data, which private lists covers, so it may be answered. Two shapes, parsed
     * strictly (RFC 8259, no duplicate keys, nothing after the value), their required fields checked; other members are
     * allowed (§2: an unknown member is ignored), so a field added later doesn't lock browsers out. That leaks nothing:
     * whoever seals something to you only gets back what they sealed, and a real DM never has the required fields.
     *  - a keyring payload (vaults §3.6), sealed to your own npub with a one-time key: an owner's entry names you as
     *    owner and carries its generations; a reader's carries the keys it was given;
     *  - a key wrap (§3.5): `{"vault", "keys", "inboxes"}`, epoch keys sent to you.
     * Anything else (a DM's text, a NIP-17 seal or rumor) is never answered. Invitations (§4.5) aren't covered: their
     * first layer is a gift wrap, which opens to a seal that looks the same for a DM, and answering it would tell a site
     * who messages you.
     */
    /** Whether a request [lacks] refused for lacking DMs may still be opened, to see if it is the account's own vault data. */
    fun mayOpenAsVault(lacking: String, method: String, lists: Boolean) = lacking == "dms" && method == "nip44_decrypt" && lists

    fun vaultData(plain: String, me: String): Boolean {
        val o = StrictJson.parse(plain) as? Map<*, *> ?: return false
        // Something with a role is a keyring entry, and judged only as one (a reader's entry with gens isn't a key wrap).
        return if ("role" in o) keyring(o, me.lowercase()) else keyWrap(o)
    }

    private fun keyring(o: Map<*, *>, me: String): Boolean {
        if (!hex(o["vault"], 32) || !hex(o["genesis"], 32) || !hex(o["owner"], 32) || !hex(o["d"], 16)) return false
        if (!optString(o, "name") || !optString(o, "profile") || !opt(o, "start") { hex(it, 16) }) return false
        if (!opt(o, "keys") { keyMap(it, 32) } || !opt(o, "inboxes") { keyMap(it, 32) } || !opt(o, "keys_ref", ::blobRef)) return false
        if (!opt(o, "relays", ::strings) || !opt(o, "servers", ::strings)) return false
        return when (o["role"]) {
            // Your own vault: you're its owner, and the entry holds its generations.
            "owner" -> o["owner"] == me && (o["gens"] as? List<*>)?.let { g -> g.isNotEmpty() && g.all(::gen) } == true
            // Shared with you: the owner is whoever shared it; no generations, but the keys you were given.
            "reader" -> "gens" !in o && ("keys" in o || "keys_ref" in o)
            else -> false
        }
    }
    private fun keyWrap(o: Map<*, *>): Boolean =
        hex(o["vault"], 32) &&
            keyMap(o["keys"], 32) && (o["keys"] as Map<*, *>).isNotEmpty() && opt(o, "inboxes") { keyMap(it, 32) }

    private fun gen(g: Any?): Boolean {
        val m = g as? Map<*, *> ?: return false
        if (!hex(m["id"], 16) || !hex(m["secret"], 32) || "prev" !in m || !(m["prev"] == null || hex(m["prev"], 16))) return false
        val b = m["because"] as? Map<*, *> ?: return false
        return listOf("roots", "members", "grants").all { opt(b, it, ::strings) } && opt(b, "fresh") { it == true }
    }
    /** A blob ref (§6.2): `{"alg", "key", "size", "chunks", "parts"}`. */
    private fun blobRef(r: Any?): Boolean {
        val m = r as? Map<*, *> ?: return false
        return m["alg"] is String && hex(m["key"], 32) &&
            (m["size"] as? Long ?: -1) >= 0 && opt(m, "chunks") { (it as? Long ?: -1) >= 0 } &&
            (m["parts"] as? List<*>)?.let { p -> p.isNotEmpty() && p.all { hex(it, 32) } } == true
    }
    /** `{"<kid>": "<32-byte hex>"}`: kids are 16 bytes. */
    private fun keyMap(v: Any?, valueBytes: Int): Boolean = (v as? Map<*, *>)?.all { (k, x) -> hex(k, 16) && hex(x, valueBytes) } == true
    private fun strings(v: Any?) = (v as? List<*>)?.all { it is String } == true
    private fun optString(o: Map<*, *>, k: String) = opt(o, k) { it is String }
    private inline fun opt(o: Map<*, *>, k: String, ok: (Any?) -> Boolean) = k !in o || ok(o[k])
    private fun hex(v: Any?, bytes: Int) = v is String && v.length == bytes * 2 && v.all { it in '0'..'9' || it in 'a'..'f' }

    /** What a site's request is refused with, when it lacks a scope. */
    fun refusal(lacking: String) = if (lacking == "dms") "not_allowed: DMs are off for this site" else "not_allowed: private lists are off for this site"
}

/**
 * JSON exactly as RFC 8259 has it, for deciding what a site may be answered: no leniency (org.json takes single quotes,
 * bare words and comments, and keeps the last of two equal keys), no duplicate keys, nothing after the value.
 * Objects come back as maps, arrays as lists, numbers as Long (integers) or Double. Null on anything else.
 */
internal object StrictJson {
    private object Bad : Exception()
    fun parse(text: String): Any? = try {
        val p = P(text); p.ws(); val v = p.value(0); p.ws(); if (p.i != text.length) null else v
    } catch (_: Bad) { null } catch (_: NumberFormatException) { null }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\n\r") i++ }
        fun value(depth: Int): Any? {
            if (depth > 32 || i >= s.length) throw Bad
            return when (s[i]) {
                '{' -> obj(depth); '[' -> arr(depth); '"' -> str()
                't' -> word("true", true); 'f' -> word("false", false); 'n' -> word("null", null)
                else -> num()
            }
        }
        fun word(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw Bad; i += w.length; return v }
        fun obj(depth: Int): Map<String, Any?> {
            i++; ws(); val m = LinkedHashMap<String, Any?>()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') throw Bad
                val k = str(); ws(); if (i >= s.length || s[i] != ':') throw Bad; i++; ws()
                if (k in m) throw Bad
                m[k] = value(depth + 1); ws()
                if (i >= s.length) throw Bad
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw Bad }
            }
        }
        fun arr(depth: Int): List<Any?> {
            i++; ws(); val l = ArrayList<Any?>()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value(depth + 1)); ws()
                if (i >= s.length) throw Bad
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw Bad }
            }
        }
        fun str(): String {
            i++; val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw Bad
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c < ' ' -> throw Bad
                    c == '\\' -> {
                        if (i >= s.length) throw Bad
                        when (s[i++]) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> { if (i + 4 > s.length) throw Bad; val h = s.substring(i, i + 4); if (!h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) throw Bad; b.append(h.toInt(16).toChar()); i += 4 }
                            else -> throw Bad
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
        fun num(): Any {
            val m = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matchAt(s, i) ?: throw Bad
            i += m.value.length
            return if (m.groupValues[2].isEmpty() && m.groupValues[3].isEmpty()) m.value.toLong() else m.value.toDouble()
        }
    }
}
