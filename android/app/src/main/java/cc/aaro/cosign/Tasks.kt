package cc.aaro.cosign

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** A Play services Task that succeeded, with its value: null for a Task<Void> (a model download, say), which has none. */
class Done<T>(val value: T?)

/**
 * Wait for a Task: Done when it succeeded, null when it failed or was cancelled. Kept apart from the value, so a
 * Task<Void> that succeeded isn't taken for one that failed (as OnDevice's download once was: "Translate from…" did
 * nothing). Listeners run where the task finishes ([on]), not on the main thread.
 */
suspend fun <T> Task<T>.finish(on: Executor = Executor { it.run() }): Done<T>? = suspendCancellableCoroutine { c ->
    addOnCompleteListener(on) { t -> if (c.isActive) c.resume(if (t.isSuccessful) Done(t.result) else null) }
}
