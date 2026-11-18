// Contributions (spec §4.6, §8.7): the plaintext as an owner device opens it, and the `put`s an acceptance makes,
// a pure function of the event, so two devices (and two apps) accepting it make the same versions.
package cc.aaro.vaults

import cc.aaro.keykin.core.unhex
import org.json.JSONObject

object Contrib {
    /** The plaintext of a CONTRIB event, opened with the inbox secret of the epoch its `v` names. Null if it doesn't open. */
    fun open(ev: Ev, inboxSecret: ByteArray): JSONObject? = try {
        if (ev.kind != Kinds.CONTRIB || !ev.valid()) null else {
            val c = obj(ev.content)
            Keys.decrypt(c.str("ct"), Keys.conv(inboxSecret, c.hexOf("eph", 32)))?.let(::obj)
        }
    } catch (_: Exception) { null }

    fun entryOf(eventId: String) = thHex("nostr-vault/contrib/1", unhex(eventId))

    /** The puts accepting [item] (the plaintext's `item`) from [from], whose sub-cert is named [devName], in [profile]. */
    fun accepted(eventId: String, profile: String, item: JSONObject, from: String, devName: String): List<Put> {
        val entry = entryOf(eventId)
        val meta = item.objOr("meta").copy().apply { remove("ph"); put("from", from) }
        when (profile) {
            "drive" -> { meta.put("dev", devName); meta.put("parent", item.strOrNull("into") ?: "root") }
            "photos" -> meta.put("trashed", false)
        }
        val data = item.objOrNull("data")
        val out = mutableListOf(Put.make(entry, emptyList(), meta, data, false))
        val into = item.strOrNull("into")
        if (profile == "photos" && into != null) {
            val link = thHex("nostr-vault/link/1", unhex(into), unhex(entry))
            out += Put.make(link, emptyList(), jobj("kind" to "link", "album" to into, "photo" to entry), null, false)
        }
        return out
    }
}
