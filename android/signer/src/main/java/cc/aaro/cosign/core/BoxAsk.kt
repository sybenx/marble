package cc.aaro.cosign.core

import cc.aaro.keykin.core.KeykinException

/**
 * Signing with a box that decides what is hard to undo (Keykin SPEC 11.6, "The tap"). The box is the authority, and
 * its answer carries the level, so this phone needs no copy of its rules:
 *
 * - Level 1, "Are you sure?": the box answers `needs_tap` with its question in `why`. This phone asks the person and,
 *   on Yes, sends the same request again with `tapped`.
 * - Level 2, never by accident (Aaron, 2026-11-20): the box answers `needs_fingerprint` with its question in `why`,
 *   and `tapped` alone never signs it. This phone asks the person and, on Yes, takes the fingerprint the way it does
 *   for an account change (the chip key signs the request, `fp`, and the box checks it), titled with the box's words.
 *
 * This phone's own rules ([Policy]) are a check on top (it sometimes knows a newer version of a list than the box), and
 * the only one when no box takes part. When they already asked and the person said Yes, `tapped` goes with the first
 * request, and at their level 2 the fingerprint too, so nobody is asked twice for one event. A Yes already given goes
 * straight to the fingerprint when the box then wants it.
 */
object BoxAsk {
    /** What was signed, and whether the person said Yes to it (now, or before [sign] was called). */
    data class Signed<T>(val value: T, val yes: Boolean)

    /** A question for the person: [says], in plain words, and its [level] (1: Yes or No; 2: Yes behind the fingerprint). */
    data class Question(val says: String, val level: Int = 1) : java.io.Serializable

    /**
     * [own]: this phone's own question, or null. [approved]: the person already said Yes. [ask] puts a question to the
     * person and returns their answer; it may throw instead when it cannot ask now ([cc.aaro.cosign.NeedsApproval]).
     * [unsaid] is the question when an older box asks without words. [sign] signs, sending `tapped` when it is true,
     * and the fingerprint when `fingerprint` is set: the words its prompt shows. A No leaves nothing signed and throws
     * [KeykinException] `denied`.
     */
    fun <T> sign(own: Question?, approved: Boolean, unsaid: String, ask: (Question) -> Boolean, sign: (tapped: Boolean, fingerprint: String?) -> T): Signed<T> {
        var yes = approved
        if (!yes && own != null) { if (!ask(own)) throw KeykinException("denied"); yes = true }
        val finger = own?.takeIf { it.level >= 2 }?.says
        return try { Signed(sign(yes, finger), yes) } catch (e: KeykinException) {
            val why = e.why
            when {
                e.code == "needs_tap" && !yes -> {
                    if (!ask(Question(why ?: unsaid, 1))) throw KeykinException("denied")
                    Signed(sign(true, finger), true)
                }
                // Level 2 from the box. Once: a fingerprint already sent and not taken is not asked for again. Without
                // words it is an account change's answer, not a question.
                e.code == "needs_fingerprint" && why != null && finger == null -> {
                    if (!yes && !ask(Question(why, 2))) throw KeykinException("denied")
                    Signed(sign(true, why), true)
                }
                else -> throw e
            }
        }
    }
}
