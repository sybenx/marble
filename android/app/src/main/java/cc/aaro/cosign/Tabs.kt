package cc.aaro.cosign

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.customtabs.EngagementSignalsCallback
import androidx.core.graphics.drawable.toBitmap

/**
 * Pages Marble opens, in the browser's Custom Tab: Marble's colours and one button, Save, filled once the page is in
 * Later. Kept connected to the browser (a session), so the button can turn filled the moment you save. Marble only
 * knows the page it opened, and the page you were on when you tapped Save: after you go on to another page, the
 * button still shows how the last of those stood. Main process.
 */
object Tabs {
    @Volatile private var session: CustomTabsSession? = null
    @Volatile private var bound = false

    /** Connect to the browser ahead of time (it also starts it warming up). */
    fun warm(context: Context) {
        if (bound) return
        val pkg = CustomTabsClient.getPackageName(context, null) ?: return
        bound = try {
            CustomTabsClient.bindCustomTabsService(context.applicationContext, pkg, object : CustomTabsServiceConnection() {
                override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                    client.warmup(0); session = client.newSession(logging)?.also { s ->
                        try { if (s.isEngagementSignalsApiAvailable(android.os.Bundle.EMPTY)) s.setEngagementSignalsCallback(engagement, android.os.Bundle.EMPTY) } catch (_: Exception) {}
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) { session = null; bound = false }
            })
        } catch (_: Exception) { false }
    }

    /** [item]: the Discover post or story this page is from, for the feed log (null: not from the feed). */
    fun open(context: Context, url: String, saved: Boolean, dark: Boolean, ground: Int, item: String? = null) {
        this.item = item; shownAt = 0; scrolls = 0; pages = 0
        val b = session?.let { CustomTabsIntent.Builder(it) } ?: CustomTabsIntent.Builder()
        b.setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(ground).build())
            .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setShowTitle(true)
        icon(context, saved)?.let { b.setActionButton(it, label(saved), saveIntent(context), true) }
        try { b.build().launchUrl(context, android.net.Uri.parse(url)) } catch (_: android.content.ActivityNotFoundException) {}
    }

    /** The page on screen is in Later now: its button, filled. */
    fun markSaved(context: Context) { icon(context, true)?.let { session?.setActionButton(it, label(true)) } }

    // The feed log (when it's on): how long a page from Discover stayed on screen, how far it was read, whether you went on from it.
    @Volatile private var item: String? = null
    @Volatile private var shownAt = 0L
    @Volatile private var scrolls = 0
    @Volatile private var pages = 0
    private fun ms() = if (shownAt > 0) android.os.SystemClock.elapsedRealtime() - shownAt else null
    private val logging = object : CustomTabsCallback() {
        override fun onNavigationEvent(event: Int, extras: android.os.Bundle?) {
            val id = item ?: return
            when (event) {
                TAB_SHOWN -> { shownAt = android.os.SystemClock.elapsedRealtime(); FeedLog.ev("tab", "id" to id, "e" to "shown") }
                TAB_HIDDEN -> FeedLog.ev("tab", "id" to id, "e" to "hidden", "ms" to ms(), "scrolls" to scrolls, "pages" to pages)
                NAVIGATION_STARTED -> pages++
            }
        }
    }
    private val engagement = object : EngagementSignalsCallback {
        override fun onVerticalScrollEvent(isDirectionUp: Boolean, extras: android.os.Bundle) { scrolls++ }
        override fun onGreatestScrollPercentageIncreased(scrollPercentage: Int, extras: android.os.Bundle) {
            item?.let { if (scrollPercentage % 10 == 0 || scrollPercentage >= 100) FeedLog.ev("tab", "id" to it, "e" to "read", "pct" to scrollPercentage, "ms" to ms()) }
        }
        override fun onSessionEnded(didUserInteract: Boolean, extras: android.os.Bundle) {
            item?.let { FeedLog.ev("tab", "id" to it, "e" to "end", "touched" to didUserInteract, "ms" to ms(), "pages" to pages) }
            item = null
        }
    }

    private fun label(saved: Boolean) = if (saved) "In Later" else "Save to Later"
    private fun icon(context: Context, saved: Boolean) = context.getDrawable(if (saved) R.drawable.ic_saved_page else R.drawable.ic_save_page)?.toBitmap()

    // Mutable: the browser fills in the address of the page on screen when Save is tapped.
    private fun saveIntent(context: Context) = android.app.PendingIntent.getBroadcast(context, 0,
        Intent(context, SavePageReceiver::class.java).putExtra("slot", CosignApp.instance.phone.slot),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
}
