// What a vault needs from outside: relays, Blossom servers, the account (its npub's signatures and ECDH), Keykin's
// view of the account, and somewhere to keep its own state. All blocking: call from a background thread.
package cc.aaro.vaults

import org.json.JSONObject

/** What relays sent back, and which of them finished (EOSE) in time. */
class Answer(val events: List<Ev>, val answered: Set<String>)

interface RelayNet {
    fun query(relays: List<String>, filter: JSONObject, timeoutMs: Long = 10_000): Answer
    /** The relays that said OK. */
    fun publish(relays: List<String>, ev: Ev): Set<String>
}

interface Blobs {
    /**
     * BUD-02 upload with [auth] as the Authorization header. 200 when the server took it with this hash; otherwise the
     * HTTP status, -2 for a wrong hash back, -1 for no answer. A refusal (402, 403, 413, 429, 507, a 503 with
     * Retry-After) may instead throw [BlobRefused] with the server's X-Reason and Retry-After; so may [get] and [delete].
     */
    fun upload(server: String, bytes: ByteArray, sha256: String, auth: String): Int
    /** BUD-01: the bytes, or null. The caller checks the hash. */
    fun get(server: String, sha256: String): ByteArray?
    fun delete(server: String, sha256: String, auth: String): Boolean
}

/** The account the vaults belong to (§12). */
interface Account {
    /** The npub, x-only hex. */
    val npub: String
    /** An event signed by the npub (one signer round). */
    fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): Ev
    /** NIP-44 decrypt of [ct] with the conversation key of the npub and [eph] (one `ecdh` with a Keykin box). Null: refused or doesn't open. */
    fun openSelf(eph: String, ct: String): String?
    /** Keykin v2's view of the account, or null if it isn't a Keykin account (a plain key). Null members: couldn't ask now. */
    fun keykin(): KeykinView?
    /** The account's Blossom servers (kind 10063, BUD-03), normalized and in order. Empty: none published (or not known now). */
    fun servers(): List<String> = emptyList()
}

/**
 * [me]: this device's Keykin member key (its device cert's `keykin` label). [epoch]: the account's key epoch from the
 * same answer. [members]: member key → blocked, or null if the box couldn't be asked. [revokedGrants]: grant ids the box
 * reported revoked to this device, ever.
 */
class KeykinView(val me: String, val epoch: Int, val members: Map<String, Boolean>?, val revokedGrants: Set<String>)

interface Store { fun get(k: String): String?; fun put(k: String, v: String?) }

/** Wall clock in seconds, replaceable for tests. */
fun interface Clock { fun now(): Long }
