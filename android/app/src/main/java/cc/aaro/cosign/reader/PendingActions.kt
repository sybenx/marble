package cc.aaro.cosign.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reader's Undo (as Marble Web): one pending action per item, held by the account (not a screen), so it's reachable
 * from anywhere and survives Reader closing. Each ends exactly once: committed when its time runs out or another for
 * the same item takes its place, undone when you tap Undo, or cancelled (neither) when you switch account.
 */
class PendingActions(private val scope: CoroutineScope, private val waitMs: Long = 5_000) {
    class Action(val key: String, val text: String, private val onUndo: () -> Unit, private val onCommit: (() -> Unit)?, private val onCancel: (() -> Unit)?, internal val seq: Long) {
        private val ended = AtomicBoolean(false)
        internal fun commit() { if (ended.compareAndSet(false, true)) onCommit?.invoke() }
        internal fun undo() { if (ended.compareAndSet(false, true)) onUndo() }
        internal fun cancel() { if (ended.compareAndSet(false, true)) onCancel?.invoke() }
        /** Taken over by a newer action for the same item that carries this one's undo: ends with nothing run. */
        internal fun fold() { ended.set(true) }
        val done get() = ended.get()
    }
    private val counter = java.util.concurrent.atomic.AtomicLong()

    private val held = ConcurrentHashMap<String, Action>()
    /** The latest still pending, for the message line. */
    val shown = MutableStateFlow<Action?>(null)

    /** [key]: the item and what's done to it. One there already for that key is committed first (never dropped). */
    /**
     * [merge]: the one there already for [key] is folded into this (its undo is this one's job now) instead of committed.
     * [cancel]: what to say if a switch of account cancels it.
     */
    fun offer(key: String, text: String, undo: () -> Unit, commit: (() -> Unit)? = null, merge: Boolean = false, cancel: (() -> Unit)? = null): Action {
        val a = Action(key, text, undo, commit, cancel, counter.incrementAndGet())
        held.put(key, a)?.let { if (merge) it.fold() else it.commit() }
        shown.value = a
        scope.launch { delay(waitMs); if (held.remove(key, a)) a.commit(); if (shown.value === a) shown.value = latest() }
        return a
    }
    fun undo(a: Action) { held.remove(a.key, a); a.undo(); if (shown.value === a) shown.value = latest() }
    /** The one pending for [key], if any. */
    fun get(key: String): Action? = held[key]
    /** Another account on screen: whatever this one had pending is neither done nor undone. */
    fun cancelAll() { held.values.forEach { it.cancel() }; held.clear(); shown.value = null }
    /** The newest still pending. */
    private fun latest() = held.values.filter { !it.done }.maxByOrNull { it.seq }
}
