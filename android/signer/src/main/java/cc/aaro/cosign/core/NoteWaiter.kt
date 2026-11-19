package cc.aaro.cosign.core

import cc.aaro.keykin.core.Device
import cc.aaro.keykin.core.KeykinException

/**
 * Keeps one `notes_wait` open to the box at a time (Keykin SPEC 11.6), on its own thread, so a note from
 * the account page reaches this phone within about a second instead of on the next 30-second check.
 *
 * [wait] asks the box and blocks until it answers (up to about 25 seconds); it is given the ids of the
 * notes the last answer held, so those alone do not answer at once. Every answer goes to [take], the same
 * handling the 30-second check uses, which skips notes it already has.
 *
 * A network error waits 5 seconds, then 10, 20, 40 and 60 at most, until an answer comes. An older box
 * answers `unknown_op` (`unknown_call` is taken the same way): the loop stops for good and the
 * 30-second check carries on alone.
 */
class NoteWaiter(
    private val wait: (seen: List<String>) -> List<Device.Note>,
    private val take: (List<Device.Note>) -> Unit,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val millis: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var generation = 0
    @Volatile private var thread: Thread? = null
    /** True once the box said it does not know `notes_wait`. */
    @Volatile var unsupported = false; private set
    private var seen: List<String> = emptyList()
    private var backoff = 0L

    val running get() = thread != null

    /** Starts the loop, if it is not running and the box has not refused it. Safe to call again. */
    @Synchronized fun start() {
        if (thread != null || unsupported) return
        val mine = ++generation
        thread = Thread({
            try { while (generation == mine) { val next = once() ?: break; if (next > 0 && generation == mine) pause(next) } }
            catch (_: InterruptedException) { }
            finally { synchronized(this) { if (generation == mine) thread = null } }
        }, "keykin-notes-wait").apply { isDaemon = true; start() }
    }

    /** Stops the loop. A wait already open ends when the box answers; nothing is done with that answer. */
    @Synchronized fun stop() { generation++; thread?.interrupt(); thread = null }

    /**
     * One wait and what follows it. Returns how long to pause before the next, or null to stop for good.
     * The loop calls it; tests call it directly.
     */
    fun once(): Long? {
        val asked = millis()
        val mine = generation
        val got = try { wait(seen) } catch (e: KeykinException) {
            if (e.code == "unknown_op" || e.code == "unknown_call") { unsupported = true; return null }
            return grow()
        } catch (e: InterruptedException) { throw e } catch (_: Exception) { return grow() }
        backoff = 0
        if (generation != mine) return 0          // stopped while waiting: the answer is not ours to act on
        val fresh = got.any { it.id !in seen }
        seen = got.map { it.id }
        if (got.isNotEmpty()) try { take(got) } catch (e: InterruptedException) { throw e } catch (_: Exception) { }      // the loop goes on
        // Nothing new and back at once (something in between that does not hold, say): do not spin.
        return if (!fresh && millis() - asked < QUICK_MS) FIRST_BACKOFF else 0
    }

    private fun grow(): Long { backoff = if (backoff == 0L) FIRST_BACKOFF else minOf(backoff * 2, MOST_BACKOFF); return backoff }

    companion object {
        const val FIRST_BACKOFF = 5_000L
        const val MOST_BACKOFF = 60_000L
        /** An answer with nothing new that came back faster than this was not held. */
        const val QUICK_MS = 2_000L
    }
}
