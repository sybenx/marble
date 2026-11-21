package cc.aaro.cosign

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OnDevice waits on ML Kit's Tasks. A model download is a Task<Void>: it succeeds with no value, and taking that
 * null for a failure is why "Translate from Spanish" downloaded the language and then showed nothing.
 */
class TasksTest {
    @Test fun aVoidTaskThatSucceededIsDone() = runBlocking {
        val download = Tasks.forResult<Void?>(null)
        assertNotNull("a finished download is not a failure", download.finish())
    }

    @Test fun aValueComesThrough() = runBlocking {
        assertEquals("hola → hello", Tasks.forResult("hola → hello").finish()?.value)
    }

    @Test fun failedAndCancelledAreNull() = runBlocking {
        assertNull(Tasks.forException<Void?>(RuntimeException("no network")).finish())
        assertNull(Tasks.forCanceled<Void?>().finish())
    }

    @Test fun waitsForOneStillRunning() = runBlocking {
        val src = TaskCompletionSource<Void?>()
        val waiting = async { src.task.finish() }
        Thread { Thread.sleep(50); src.setResult(null) }.start()
        assertNotNull(waiting.await())
    }
}
