package cc.aaro.cosign.web

import android.app.ActivityManager
import android.content.Context
import cc.aaro.cosign.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Whether the signer answers, for the bar over the web pane (decision 1: on main the health check asks Cosign, not
 * Keykin). A cheap read through the signer process ([Phone.latest]) on a thread of its own that's abandoned after 3 s,
 * so a stuck signer never holds the UI or a coroutine.
 */
object WebHealth {
    enum class State { OK, SIGNER_STUCK, SERVERS_DOWN }

    suspend fun check(context: Context, phone: Phone): State {
        if (!phone.isMember) return State.OK
        val answered = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { c ->
                Thread({ phone.latest("3"); if (c.isActive) c.resume(true) }, "marble-web-health").apply { isDaemon = true }.start()
            }
        }
        if (answered == null || withContext(Dispatchers.Default) { signerPid(context) } == null) return State.SIGNER_STUCK
        val online = phone.ui.value.online
        if (online.isNotEmpty() && online.values.none { it }) return State.SERVERS_DOWN
        return State.OK
    }

    private fun signerPid(context: Context): Int? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return am.runningAppProcesses?.firstOrNull { it.processName == context.packageName + ":signer" }?.pid
    }

    /** Restart signer: its process ends (same app, so allowed); Android starts it again for the bound UI. */
    fun restartSigner(context: Context) {
        signerPid(context)?.let { android.os.Process.killProcess(it) }
    }
}
