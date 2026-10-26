package cc.aaro.cosign

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Save, tapped in a page Marble opened (a Custom Tab): the page on screen goes in the Later of the account
 * that opened it. The browser puts the page's address in the intent's data. Main process.
 */
class SavePageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.dataString?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return
        val slot = intent.getStringExtra("slot")
        val done = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val saved = account(slot)?.let { Social.of(it).savePage(url) } != null
                android.widget.Toast.makeText(context.applicationContext, if (saved) "Saved to Later" else "Couldn't save this page", android.widget.Toast.LENGTH_SHORT).show()
            } finally { done.finish() }
        }
    }

    /** The account, once the signer has said who it is (Marble may have been started just for this). */
    private suspend fun account(slot: String?): Phone? = withTimeoutOrNull(8_000) {
        val accounts = CosignApp.instance.accounts
        var p: Phone? = null
        while (p?.isMember != true) {
            delay(100)
            p = slot?.let { accounts.phone(it) } ?: accounts.active.takeIf { slot == null }
        }
        p
    }
}
