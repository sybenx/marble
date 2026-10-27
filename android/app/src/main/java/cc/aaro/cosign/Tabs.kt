package cc.aaro.cosign

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
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
                    client.warmup(0); session = client.newSession(null)
                }
                override fun onServiceDisconnected(name: ComponentName) { session = null; bound = false }
            })
        } catch (_: Exception) { false }
    }

    fun open(context: Context, url: String, saved: Boolean, dark: Boolean, ground: Int) {
        val b = session?.let { CustomTabsIntent.Builder(it) } ?: CustomTabsIntent.Builder()
        b.setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(ground).build())
            .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setShowTitle(true)
        icon(context, saved)?.let { b.setActionButton(it, label(saved), saveIntent(context), true) }
        try { b.build().launchUrl(context, android.net.Uri.parse(url)) } catch (_: android.content.ActivityNotFoundException) {}
    }

    /** The page on screen is in Later now: its button, filled. */
    fun markSaved(context: Context) { icon(context, true)?.let { session?.setActionButton(it, label(true)) } }

    private fun label(saved: Boolean) = if (saved) "In Later" else "Save to Later"
    private fun icon(context: Context, saved: Boolean) = context.getDrawable(if (saved) R.drawable.ic_saved_page else R.drawable.ic_save_page)?.toBitmap()

    // Mutable: the browser fills in the address of the page on screen when Save is tapped.
    private fun saveIntent(context: Context) = android.app.PendingIntent.getBroadcast(context, 0,
        Intent(context, SavePageReceiver::class.java).putExtra("slot", CosignApp.instance.phone.slot),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
}
