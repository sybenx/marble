package cc.aaro.cosign

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Android signer, in the background (NIP-55's Content Resolver): apps you've connected
 * ask here and get an answer without Cosign opening. Everyday signing answers at once.
 * Anything that needs you (an app not connected yet, something destructive, the first
 * time an app wants your messages) answers null, and the app opens its NIP-55 activity ([Nip55Request]) instead.
 * A message that doesn't open with this account's key answers "rejected": there is nothing
 * for you to say about it, and an app that reads a stack of them must not open Cosign once
 * for each.
 */
class Nip55Provider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val type = uri.authority?.substringAfterLast('.') ?: return null
        if (!Cosign.awaitInit()) return null.also { ErrorLog.record("provider", null, "type" to type, "why" to "Marble didn't finish starting") }
        // NIP-55 puts [payload, pubkey, current_user] in the projection; some apps use selectionArgs.
        val args = projection?.takeIf { it.isNotEmpty() } ?: selectionArgs ?: return null
        val current = args.getOrNull(2)
        // The key this app connected to: the one it names, if it does, else the one holding its grant.
        val accounts = Cosign.signers
        val pkg = callingPackage ?: return null
        val phone = accounts.all.firstOrNull { it.ui.value.member?.group?.groupX == current && it.appGrant(pkg) != null } ?: accounts.forApp(pkg) ?: return null
        val member = phone.ui.value.member ?: return null
        val grant = phone.appGrant(pkg) ?: return null
        // The key it has: its own child, or the main account. An app moved to a key of its own that still names the main
        // account has not signed in again yet: it opens the sheet, which says so.
        val key = try { phone.appKey(grant) } catch (_: Exception) { return null }
        if (!current.isNullOrBlank() && current.length == 64 && current != key) return null
        val payload = args.getOrNull(0) ?: ""
        val rawPeer = args.getOrNull(1) ?: ""
        return try {
            when (type) {
                "GET_PUBLIC_KEY" -> if (phone.moved(pkg) != null) null else row("result" to key)
                "SIGN_EVENT" -> phone.signForApp(grant, phone.appEvent(payload), approved = false).let { row("result" to it.sig, "signature" to it.sig, "event" to it.toJson()) }
                "NIP44_ENCRYPT", "NIP44_DECRYPT", "NIP04_ENCRYPT", "NIP04_DECRYPT" -> {
                    if (!grant.dm) return null
                    // No one to write to (or read from) that Marble can make out: the app's mistake, so a quiet no, not the sheet.
                    val peer = peerKey(rawPeer) ?: return row("rejected" to "true").also {
                        ErrorLog.record("provider", null, "app" to pkg, "type" to type, "why" to "bad peer", "peer" to peerShape(rawPeer))
                    }
                    val encrypt = type.endsWith("ENCRYPT")
                    try {
                        row("result" to if (type.startsWith("NIP44")) phone.nip44(grant, peer, payload, encrypt) else phone.nip04(grant, peer, payload, encrypt))
                    } catch (e: Exception) {
                        if (!encrypt && unreadable(e)) row("rejected" to "true") else throw e
                    }
                }
                // Amethyst asks this for every private zap it shows, dozens at a time: answered here, never with the sheet.
                // Only with messages allowed (it reads what someone wrote to you); a no is quiet (see Signer.decryptZap).
                "DECRYPT_ZAP_EVENT" -> {
                    val out = if (!grant.dm) null else try { phone.decryptZap(grant, payload) } catch (e: Exception) {
                        ErrorLog.record("provider", e, "app" to pkg, "type" to type); null
                    }
                    if (out != null) row("result" to out) else row("rejected" to "true")
                }
                else -> null
            }
        } catch (e: Exception) {
            if (e !is NeedsApproval) ErrorLog.record("provider", e, "app" to pkg, "type" to type)
            null // NeedsApproval, a removed app, or servers out of reach: the app opens Cosign, which says which
        }
    }

    private fun row(vararg cols: Pair<String, String>) = MatrixCursor(cols.map { it.first }.toTypedArray()).apply { addRow(cols.map { it.second }.toTypedArray()) }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

/**
 * The message itself is the trouble: it isn't to this account, or it's damaged (NIP-44's MAC or
 * padding, NIP-04's padding or base64). Asking again, or asking you, can't change that. Your
 * servers being away or an app losing its access are not this; those still go to the sheet.
 */
internal fun unreadable(e: Throwable): Boolean =
    (e is cc.aaro.keykin.core.KeykinException && e.code == "bad_message") ||
        e is java.security.GeneralSecurityException || e is IllegalArgumentException

/** The other side's key as NIP-55 hands it over: 64 hex (any case) or an npub, as lowercase hex; null if neither. */
internal fun peerKey(s: String): String? {
    val t = s.trim()
    if (Regex("^[0-9a-fA-F]{64}$").matches(t)) return t.lowercase()
    if (!t.startsWith("npub1")) return null
    return try { cc.aaro.qrst.core.Bech32.decode(t).takeIf { it.first == "npub" && it.second.size == 32 }?.second?.joinToString("") { "%02x".format(it) } } catch (_: Exception) { null }
}

/** What a peer that didn't read looked like, for ErrorLog: its length and first few characters, never more. */
internal fun peerShape(s: String) = "len=${s.length} start=${s.take(6)}"
