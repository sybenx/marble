package cc.aaro.cosign

import cc.aaro.qrst.core.Event

// What an account looks like to the screens (Keykin v2: every device and the box hold one piece,
// any two sign). These cross from the signer process to the app's, so none of them holds a piece,
// a member key or an nsec once SignerService has passed them on.

/** One holder of a piece: a device or a box. [pk] is its member key. */
data class Member(val idx: Int, val name: String, val role: String, val pk: String?, val blocked: Boolean = false, val chip: Boolean = false, val added: Long = 0) : java.io.Serializable {
    val isBox get() = role == "box"
}
/** [threshold] is always 2 now; [relays] are where this phone's site signer listens. */
data class Group(val groupX: String, val npub: String, val threshold: Int, val relays: List<String>, val members: List<Member>) : java.io.Serializable
/** This phone in its account. [signer] is the key sites talk to (NIP-46); [box] is the box's address. */
data class Membership(val idx: Int, val signer: String, val group: Group, val epoch: Int = 0, val box: String = "", val boxKey: String = "",
                      /** This phone has a chip key, so it can make account changes. */
                      val chip: Boolean = false) : java.io.Serializable

/** A code was read and waits for a yes: make an account with the box at [box], or join the account [npub]. */
data class Offer(val box: String, val create: Boolean, val npub: String? = null,
                 /** The account is one you already have: its key was pasted and waits, in the signer, to be split with this box. */
                 val bringing: Boolean = false) : java.io.Serializable
/**
 * Something destructive that an app or a site asked for, waiting for you. [level]: 1 asks Yes or No; 2 is never done by
 * accident, and a Yes goes on to the fingerprint (Keykin SPEC 11.6, The tap).
 */
data class Ask(val id: String, val app: String, val event: Event, val hold: String, val expiresAt: Long, val level: Int = 1) : java.io.Serializable
/** A site or an app that can sign as you. [via] says how it got in. */
data class Site(val pk: String, val origin: String, val added: Long, val lastUsed: Long, val parent: String? = null, val via: String? = null,
                /** The site signed in with an account of its own (made for it), not the main account. */
                val own: Boolean = false,
                /** A site (not an app on this phone), with its switches: "Read your private lists" and "Read and send DMs". */
                val scoped: Boolean = false, val lists: Boolean = false, val dms: Boolean = false) : java.io.Serializable
data class Change(val id: String, val text: String, val by: String, val at: Long, val held: Boolean, val undone: Boolean,
                  /** Made somewhere else (another app with your key, another device with your box), found on your relays. */
                  val outside: Boolean = false,
                  /** For one made somewhere else: when the version Undo puts back was made (seconds). */
                  val since: Long = 0) : java.io.Serializable
data class Deleting(val batch: String, val by: String, val count: Int, val until: Long) : java.io.Serializable
data class BatchAsk(val batch: String, val text: String, val by: String, val expiresAt: Long) : java.io.Serializable
/** Something you deleted through this phone, with the copy it kept. */
data class Deleted(val id: String, val kind: String, val at: Long, val text: String) : java.io.Serializable
data class SiteSummary(val pk: String, val origin: String, val total: Int, val counts: Map<String, Int>, val lists: List<String>, val deletable: Int) : java.io.Serializable
/** A new device's invite, read by this phone: waiting for your fingerprint. */
data class AddingDevice(val pk: String, val name: String, val relays: List<String> = emptyList()) : java.io.Serializable
data class Future(val id: String, val text: String) : java.io.Serializable
data class Connect(val origin: String, val stage: Stage, val message: String? = null,
                   /** Five digits to compare with the site's, when the code was pasted and not scanned. */
                   val digits: String? = null,
                   /** A site new to this account: offer an account of its own (the main choice) or the main account. */
                   val ask: Boolean = false,
                   /** The site already has an account of its own here, and signs in with it again. */
                   val own: Boolean = false,
                   /** A Keykin account page: it gets the main account only (no choice), and the card says so. */
                   val accountPage: Boolean = false,
                   /** The site asked to decrypt at sign-in (NIP-46 perms): "Read your private lists" starts on. */
                   val wantsLists: Boolean = false) : java.io.Serializable { enum class Stage { CONFIRM, COMPARE, WORKING, DONE, FAILED } }
data class Line(val ts: Long, val text: String, val kind: String = "info") : java.io.Serializable
/** The whole key, formed after a fingerprint. [nsec] never leaves the signer process. */
data class TakeOut(val stage: Stage, val text: String = "", val until: Long? = null, val nsec: String? = null, val npub: String? = null, val twinUnknown: Boolean = false) : java.io.Serializable {
    enum class Stage { WAITING, SHOWING, FAILED }
}
/**
 * Something the account page asked for that needs a Yes or No on this phone: removing blocked devices for good
 * ([kind] "cycle"), or making [origin] the account's sign-in page ("pointer"). [no] and [yes] are the buttons.
 */
data class Tap(val id: String, val title: String, val text: String, val kind: String = "cycle", val origin: String? = null,
               val no: String = "No", val yes: String = "Yes, remove them", val danger: Boolean = true) : java.io.Serializable
/**
 * The whole key moving between your devices by QRST: this phone receiving it ("receive"), or sending it
 * ("consent" before anything moves, then "send"). Only the session's public state; never the key.
 */
data class KeyMove(val kind: String, val uri: String? = null, val phase: String = "WAITING", val check: String = "TYPE",
                   val code: String? = null, val compare: String? = null, val rendering: String? = null,
                   val outcome: String? = null, val connected: Boolean = false, val viaCamera: Boolean = true,
                   val kept: Boolean = false, val problem: String? = null) : java.io.Serializable
/** A browser asks to log in to the account page: where, as whom, for how long. */
data class LoginAsk(val host: String, val account: String, val lasts: String) : java.io.Serializable
/** Right after a join: the account's sign-in page is [named], a page this phone never used; switch it to this box's, [current]? Hosts. */
data class LoginSwitch(val named: String, val current: String) : java.io.Serializable
/** Asking a box for an account: the box, your note, and how far it got (1 asked, 2 waiting, 3 making the key). */
data class Asking(val box: String, val note: String, val step: Int, val error: String? = null) : java.io.Serializable
/**
 * "Sign in" on a new device (Keykin SPEC 8b): its one code ([uri], once a relay is ready), the five digits when the
 * other device asks to compare, and a page to open for "Use my name" ([open]).
 */
data class SignInCode(val uri: String? = null, val digits: String? = null, val connected: Boolean = false, val open: String? = null) : java.io.Serializable
/** Adding a device whose code was pasted: does [name] show these [digits]? */
data class AddDigits(val name: String, val digits: String) : java.io.Serializable
/** [place]: the child key this app signs as (Keykin SPEC 8k), named by the place it was made for; null: the main account. */
data class AppGrant(val pkg: String, val pk: String, val label: String, val dm: Boolean, val place: String? = null)
/** [level]: 2 when a Yes to [what] goes on to the fingerprint. */
class NeedsApproval(val what: String, val level: Int = 1) : Exception(what) {
    /**
     * Across the line between the app and the signer process (SignerService answers a Bundle): the words and the level,
     * so the app's own dialog knows that a Yes goes on to the fingerprint.
     */
    object Wire {
        const val WHAT = "approval"
        const val LEVEL = "approvalLevel"
        fun write(e: NeedsApproval, putString: (String, String) -> Unit, putInt: (String, Int) -> Unit) { putString(WHAT, e.what); putInt(LEVEL, e.level) }
        /** The answer's NeedsApproval, or null when it has none. An answer without a level is level 1. */
        fun read(getString: (String) -> String?, getInt: (String, Int) -> Int): NeedsApproval? =
            getString(WHAT)?.let { NeedsApproval(it, getInt(LEVEL, 1).coerceIn(1, 2)) }
    }
}
class Revoked : Exception("revoked")

data class Ui(
    val member: Membership? = null,
    /** Working on making or joining an account: what to say meanwhile. */
    val joining: String? = null,
    val offer: Offer? = null,
    val error: String? = null,
    val asks: List<Ask> = emptyList(),
    /** Which members can be reached, by index. Only the box is known. */
    val online: Map<Int, Boolean> = emptyMap(),
    /** 1 when the box (or a device that helps when it is off) answered last time, else 0. */
    val relaysUp: Int = 0,
    val log: List<Line> = emptyList(),
    val sites: List<Site> = emptyList(),
    val changes: List<Change> = emptyList(),
    val future: List<Future> = emptyList(),
    val deleting: List<Deleting> = emptyList(),
    val batchAsks: List<BatchAsk> = emptyList(),
    val deleted: List<Deleted> = emptyList(),
    val summary: SiteSummary? = null,
    val adding: AddingDevice? = null,
    /** This phone's invite, to show as a QR on a phone that is joining an account. */
    val joinCode: String? = null,
    /** Signing in on this new device with one code. */
    val signIn: SignInCode? = null,
    /** Adding a device by a pasted code: the digits to compare. */
    val addDigits: AddDigits? = null,
    /** After adding a device: the text that device needs to find the box, to show as a QR. */
    val joinText: String? = null,
    val connect: Connect? = null,
    val reporting: Member? = null,
    /** The box refuses this phone: who blocked it, as far as this phone can tell. */
    val removedBy: String? = null,
    val takeOut: TakeOut? = null,
    /** A bunker:// link to paste into a site, good once and for 15 minutes. Signer process only. */
    val bunker: String? = null,
    /** A blocked device still holds a piece that fits: cycle the keys to end that. */
    val needsCycle: Boolean = false,
    /** Something that needs the fingerprint is under way: what it is. */
    val working: String? = null,
    val tap: Tap? = null,
    val loginAsk: LoginAsk? = null,
    val loginSwitch: LoginSwitch? = null,
    val move: KeyMove? = null,
    val asking: Asking? = null,
    /** An nsec was pasted and waits for the box's code. The key itself stays in the signer process. */
    val bringing: Boolean = false,
    /** The whole key of a brought-in account is kept on this phone, sealed, as its backup, until you choose to forget it. */
    val keptKey: Boolean = false,
    /** The account page's username, if this phone set one. */
    val username: String? = null,
    /** Whether the account has a password, as the box last said (Keykin SPEC 11.3); null until it says. */
    val hasPassword: Boolean? = null,
    /** Where this account's login page is, as this phone last published it (Keykin SPEC 11.1). */
    val loginPage: String? = null,
) : java.io.Serializable

object Describe {
    private val names = mapOf(
        0 to "profile", 1 to "note", 3 to "follow list", 4 to "DM", 5 to "deletion", 6 to "repost", 7 to "reaction",
        13 to "DM", 14 to "DM", 16 to "repost", 62 to "request to vanish", 1111 to "comment", 9734 to "zap request", 10000 to "mute list",
        10002 to "relay list", 10003 to "bookmarks", 22242 to "relay login", 24242 to "upload login", 27235 to "web login", 30023 to "article", 30078 to "app data", 10046 to "login page",
    )
    fun kind(k: Int) = names[k] ?: cc.aaro.cosign.core.Policy.nameOf(k) ?: "kind $k event"
}
