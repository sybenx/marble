package cc.aaro.cosign.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Which account-page notes this phone has handled (Keykin SPEC 11.6). A note counts as handled only once
 * doing it worked, so one that failed (no network for a moment, say) is tried again on the next check,
 * up to [most] times in all. Only one check at a time works on a note: the 30-second check and the held
 * `notes_wait` can both be handed the same one.
 */
class NoteTries(private val most: Int = 3) {
    private val done = ConcurrentHashMap.newKeySet<String>()
    private val working = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Int>()

    /** True if the note is finished with: handled, or given up on. */
    fun has(id: String) = id in done
    /** Claims the note for this check. False if it is finished with, or another check is working on it. */
    fun begin(id: String): Boolean = id !in done && working.add(id)
    /** Doing it worked. */
    fun worked(id: String) { done.add(id); failed.remove(id); working.remove(id) }
    /** Doing it failed. True when this was the last try, so the person should be told. */
    fun failedOnce(id: String): Boolean {
        val n = failed.merge(id, 1, Int::plus)!!
        val last = n >= most
        if (last) { done.add(id); failed.remove(id) }
        working.remove(id)
        return last
    }
}
