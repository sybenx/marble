package cc.aaro.cosign.net

import cc.aaro.cosign.reader.SyncGuard
import cc.aaro.qrst.core.Event
import okhttp3.WebSocket
import java.util.concurrent.Executors

/**
 * NIP-42 relay login. Marble logs in only to relays you chose yourself: your relay lists (10002, 10050) and your
 * private relays ([cc.aaro.cosign.PrivateRelays]). Any other relay that asks gets nothing, and its request ends as
 * before. A request the relay refused with `auth-required:` is sent again once after logging in, never more.
 */
object RelayAuth {
    const val KIND = 22242

    /** The relays you chose, normalised; set by the account on screen (empty: log in nowhere). */
    @Volatile var chosen: () -> Collection<String> = { emptyList() }
    /** Signs a login with the account's main key; null if it can't. Set by the account on screen. */
    @Volatile var signer: ((Event) -> Event?)? = null
    /** Whose key signs: the account on screen. */
    @Volatile var pubkey: String? = null

    /** May Marble log in to [relay]? Only if it's one of [chosen] (compared as both apps write addresses). */
    fun allowed(relay: String, chosen: Collection<String>): Boolean {
        val r = SyncGuard.normRelay(relay)
        if (!(r.startsWith("wss://") || r.startsWith("ws://"))) return false
        return chosen.any { SyncGuard.normRelay(it) == r }
    }

    /** The unsigned login for [relay]'s [challenge] (kind 22242, `relay` and `challenge` tags, no content). */
    fun loginEvent(pubkey: String, relay: String, challenge: String, now: Long): Event =
        Event(pubkey, now, KIND, listOf(listOf("relay", relay), listOf("challenge", challenge)), "").withId()

    /** A refusal that asks for a login first (CLOSED or OK false). */
    fun needsAuth(message: Any?): Boolean = (message as? String)?.startsWith("auth-required:") == true

    /** A [Session] for one socket to [relay]: allowed only if it's chosen and someone can sign. */
    fun session(relay: String): Session = Session(relay, signer != null && pubkey != null && allowed(relay, chosen()))

    private val signing = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-auth").apply { isDaemon = true } }

    /** Do [actions] on [ws]: sign and send a login off the socket's thread, then whatever it unblocks. */
    fun run(ws: WebSocket, session: Session, actions: List<Action>, resend: (String) -> Unit, giveUp: (String) -> Unit) {
        for (a in actions) when (a) {
            is Action.Resend -> resend(a.key)
            is Action.GiveUp -> giveUp(a.key)
            is Action.SendAuth -> signing.execute {
                val e = try { pubkey?.let { pk -> signer?.invoke(loginEvent(pk, session.relay, a.challenge, System.currentTimeMillis() / 1000)) } } catch (_: Exception) { null }
                if (e == null) run(ws, session, session.authSent(null), resend, giveUp)
                else { session.authSent(e.id).let { run(ws, session, it, resend, giveUp) }; ws.send("[\"AUTH\"," + e.toJson() + "]") }
            }
        }
    }

    sealed class Action {
        data class SendAuth(val challenge: String) : Action()
        /** Send request [key] (a subscription id or an event id) again. */
        data class Resend(val key: String) : Action()
        /** Request [key] stays refused. */
        data class GiveUp(val key: String) : Action()
    }

    /**
     * One socket's login, as a state machine with no network in it: what to do when the relay sends a challenge,
     * refuses a request with `auth-required:`, or answers the login. Each request is retried at most once.
     */
    class Session(val relay: String, val allowed: Boolean) {
        private var challenge: String? = null
        private var sentFor: String? = null
        private var authId: String? = null
        private var authed = false
        private var failed = false
        private val retried = HashSet<String>()
        private val waiting = LinkedHashSet<String>()

        /** `["AUTH", challenge]`: log in at once (a chosen relay may hold things only for you). */
        @Synchronized fun onChallenge(c: String): List<Action> {
            if (!allowed || c.isEmpty()) return emptyList()
            challenge = c
            if (sentFor == c) return emptyList()
            sentFor = c; authed = false; failed = false
            return listOf(Action.SendAuth(c))
        }

        /** Request [key] came back `auth-required:`. */
        @Synchronized fun onAuthRequired(key: String): List<Action> {
            if (!allowed || failed || !retried.add(key)) return listOf(Action.GiveUp(key))
            if (authed) return listOf(Action.Resend(key))
            waiting += key
            // No login under way yet: one goes now if there's a challenge; else we wait for the relay to send one.
            val c = challenge
            if (c != null && sentFor != c) { sentFor = c; return listOf(Action.SendAuth(c)) }
            return emptyList()
        }

        /** The signed login went out as [id], or couldn't be signed (null). */
        @Synchronized fun authSent(id: String?): List<Action> {
            if (id != null) { authId = id; return emptyList() }
            failed = true
            return drain { Action.GiveUp(it) }
        }

        /** `["OK", id, ok, …]`: true if it was our login's answer (the caller should look no further). */
        @Synchronized fun onOk(id: String?, ok: Boolean): Pair<Boolean, List<Action>> {
            if (id == null || id != authId) return false to emptyList()
            authId = null
            if (ok) { authed = true; return true to drain { Action.Resend(it) } }
            failed = true
            return true to drain { Action.GiveUp(it) }
        }

        private fun drain(f: (String) -> Action): List<Action> = waiting.map(f).also { waiting.clear() }
    }
}
