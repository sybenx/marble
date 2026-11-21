package cc.aaro.cosign.core

import cc.aaro.keykin.core.KeykinException

/**
 * Signing with a box that decides what is hard to undo (Keykin SPEC 11.6, "The tap"). The box is the authority: it
 * answers `needs_tap` with its question in `why`, and this phone asks the person and, on Yes, sends the same request
 * again with `tapped`. This phone needs no copy of the box's rules. Its own rules ([Policy]) are a check on top (it
 * sometimes knows a newer version of a list than the box): when they already asked and the person said Yes, `tapped`
 * goes with the first request, so nobody is asked twice for one event.
 */
object BoxAsk {
    /** What was signed, and whether the person said Yes to it (now, or before [sign] was called). */
    data class Signed<T>(val value: T, val yes: Boolean)

    /**
     * [own]: this phone's own question, or null. [approved]: the person already said Yes. [ask] puts a question to the
     * person and returns their answer; it may throw instead when it cannot ask now ([cc.aaro.cosign.NeedsApproval]).
     * [unsaid] is the question when an older box asks without words. [sign] signs, sending `tapped` when it is true.
     * A No leaves nothing signed and throws [KeykinException] `denied`.
     */
    fun <T> sign(own: String?, approved: Boolean, unsaid: String, ask: (String) -> Boolean, sign: (tapped: Boolean) -> T): Signed<T> {
        var yes = approved
        if (!yes && own != null) { if (!ask(own)) throw KeykinException("denied"); yes = true }
        return try { Signed(sign(yes), yes) } catch (e: KeykinException) {
            if (e.code != "needs_tap" || yes) throw e
            if (!ask(e.why ?: unsaid)) throw KeykinException("denied")
            Signed(sign(true), true)
        }
    }
}
