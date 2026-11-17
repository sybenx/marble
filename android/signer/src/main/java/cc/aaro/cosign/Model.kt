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
/** Something destructive that an app or a site asked for, waiting for you. */
data class Ask(val id: String, val app: String, val event: Event, val hold: String, val expiresAt: Long) : java.io.Serializable
/** A site or an app that can sign as you. [via] says how it got in. */
data class Site(val pk: String, val origin: String, val added: Long, val lastUsed: Long, val parent: String? = null, val via: String? = null) : java.io.Serializable
data class Change(val id: String, val text: String, val by: String, val at: Long, val held: Boolean, val undone: Boolean) : java.io.Serializable
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
                   val digits: String? = null) : java.io.Serializable { enum class Stage { CONFIRM, COMPARE, WORKING, DONE, FAILED } }
data class Line(val ts: Long, val text: String, val kind: String = "info") : java.io.Serializable
/** The whole key, formed after a fingerprint. [nsec] never leaves the signer process. */
data class TakeOut(val stage: Stage, val text: String = "", val until: Long? = null, val nsec: String? = null, val npub: String? = null, val twinUnknown: Boolean = false) : java.io.Serializable {
    enum class Stage { WAITING, SHOWING, FAILED }
}
/** Something hard to undo that the account page asked for: Yes or No, on this phone. */
data class Tap(val id: String, val title: String, val text: String) : java.io.Serializable
/** A browser asks to log in to the account page: where, as whom, for how long. */
data class LoginAsk(val host: String, val account: String, val lasts: String) : java.io.Serializable
/** Asking a box for an account: the box, your note, and how far it got (1 asked, 2 waiting, 3 making the key). */
data class Asking(val box: String, val note: String, val step: Int, val error: String? = null) : java.io.Serializable
data class AppGrant(val pkg: String, val pk: String, val label: String, val dm: Boolean)
class NeedsApproval(val what: String) : Exception(what)
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
    val asking: Asking? = null,
    /** An nsec was pasted and waits for the box's code. The key itself stays in the signer process. */
    val bringing: Boolean = false,
    /** The whole key of a brought-in account is kept on this phone, sealed, as its backup, until you choose to forget it. */
    val keptKey: Boolean = false,
    /** The account page's username, if this phone set one. */
    val username: String? = null,
    /** Where this account's login page is, as this phone last published it (Keykin SPEC 11.1). */
    val loginPage: String? = null,
) : java.io.Serializable

object Describe {
    private val names = mapOf(
        0 to "profile", 1 to "note", 3 to "follow list", 4 to "DM", 5 to "deletion", 6 to "repost", 7 to "reaction",
        13 to "DM", 14 to "DM", 16 to "repost", 62 to "request to vanish", 1111 to "comment", 9734 to "zap request", 10000 to "mute list",
        10002 to "relay list", 10003 to "bookmarks", 22242 to "relay login", 24242 to "upload login", 27235 to "web login", 30023 to "article", 30078 to "app data",
    )
    fun kind(k: Int) = names[k] ?: "kind $k event"
}
