package cc.aaro.cosign

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cc.aaro.cosign.ui.App
import cc.aaro.cosign.ui.CosignTheme
import org.unifiedpush.android.connector.UnifiedPush

class MainActivity : ComponentActivity() {
    /** A sign-in that came by a link from another app (a browser, the extension): when it's over, go back there. */
    private var backToCaller = false
    /** When a link last brought a code: the sign-in it starts shows up a moment later, from the signer. */
    private var linkAt = 0L
    /** A link that came before the signer said which account is on screen, and whether it was new then. */
    private var waiting: Pair<Intent, Boolean>? = null
    private val handled by lazy { HandledLinks(getSharedPreferences("links", MODE_PRIVATE)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated by Android with your place saved: Discover comes back as it was (see Sources.resumed).
        if (savedInstanceState != null) Sources.resumed = true
        // Debug builds: the home feed as another account sees it, read-only (--es as <hex key>), to compare with a mock made for it.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) intent?.getStringExtra("as")?.let { Sources.viewAs = it }
        Startup.mark("activity", once = false)
        // The app is always dark; keep the system bar icons light whatever the phone's theme.
        // The bars' icons follow the phone's light or dark setting, as the app does.
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        val accounts = CosignApp.instance.accounts
        // The link this activity was created with may be one from long ago (see [HandledLinks]).
        if (savedInstanceState == null) opened(intent, fresh = false)
        setContent {
            CosignTheme {
                // Nothing to draw until the signer process has said how each account is.
                val ready by accounts.ready.collectAsState()
                if (!ready) return@CosignTheme
                LaunchedEffect(Unit) { waiting?.let { (i, fresh) -> waiting = null; opened(i, fresh) } }
                // The key on screen; switching keys swaps everything below.
                val slot by accounts.activeSlot.collectAsState()
                val phone = accounts.phone(slot) ?: accounts.active
                key(slot) {
                val ui by phone.ui.collectAsState()
                val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                // Once in a group: stay reachable, and be allowed to say so.
                LaunchedEffect(ui.member != null) {
                    if (ui.member != null) {
                        // Not before the first screen is drawn.
                        kotlinx.coroutines.delay(1500)
                        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                        CosignService.start(this@MainActivity)
                        // ntfy, if it's installed. Without it, approvals reach you while Cosign is running.
                        UnifiedPush.tryUseCurrentOrDefaultDistributor(this@MainActivity) { ok -> if (ok) UnifiedPush.register(this@MainActivity) }
                    }
                }
                // Connected, cancelled or failed and closed: the sign-in sheet is gone. If a link
                // from another app brought it, that app is where the site is waiting.
                // Go back as soon as you've said yes: the app that asked (a browser extension, say)
                // may only be able to answer once it's in front again, so waiting here for it
                // would wait forever. The hand-over finishes in the background; a failure is a
                // notification.
                var hadConnect by remember { mutableStateOf(false) }
                LaunchedEffect(ui.connect?.stage) {
                    val stage = ui.connect?.stage
                    if (stage != null && !hadConnect && android.os.SystemClock.uptimeMillis() - linkAt < 10_000) backToCaller = true
                    if (stage != null) hadConnect = true
                    if (backToCaller && (stage == Connect.Stage.WORKING || (stage == null && hadConnect))) {
                        backToCaller = false
                        if (stage == Connect.Stage.WORKING) phone.connectInBackground = true
                        moveTaskToBack(true)
                    }
                    if (stage == null) hadConnect = false
                }
                // Something went wrong in the background (a step of yours that failed, a server out of reach):
                // said for a while, then gone, so it doesn't greet you every time you open Marble.
                // Not while setting up, where the message is what you're looking at.
                LaunchedEffect(ui.error) { if (ui.error != null && ui.member != null) { kotlinx.coroutines.delay(12_000); phone.clearError() } }
                App(phone, ui)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opened(intent, fresh = true)
    }

    /** A code handed over by the camera app or a link; the app asks before doing anything with it. */
    private fun opened(intent: Intent?, fresh: Boolean) {
        // Reopened from Recents, Android hands back the link that first opened Cosign, maybe
        // hours old: that's not something you just scanned.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (!CosignApp.instance.accounts.ready.value) { waiting = intent to fresh; return }
        // A search from the launcher (marble://search?q=…, or Android's own): every time, as asked; it isn't a code.
        val searched = when {
            intent.action == Intent.ACTION_SEARCH -> intent.getStringExtra(android.app.SearchManager.QUERY)
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "marble" && intent.data?.host == "search" -> intent.data?.getQueryParameter("q")
            else -> null
        }
        if (intent.action == Intent.ACTION_SEARCH || intent.data?.scheme == "marble") {
            searched?.trim()?.take(200)?.takeIf { it.isNotEmpty() }?.let { cc.aaro.cosign.ui.OpenSearch.query.value = it }
            return
        }
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.takeIf { handled.take(it, fresh) }?.let {
            CosignApp.instance.phone.scanned(it)
            linkAt = android.os.SystemClock.uptimeMillis()
        }
        testHook(intent)
    }

    // Debug builds only: hand the app a pairing code without a camera, for the
    // emulator (adb shell am start -n cc.aaro.cosign/.MainActivity --es pair '<code>'), and
    // extra relays to read from (--es relay ws://10.0.2.2:10547).
    private fun testHook(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent?.getStringExtra("pair")?.let { CosignApp.instance.phone.scanned(it) }
        // A relay of the test's own (a local one, say) for the home screen to read from too.
        intent?.getStringExtra("relay")?.let { cc.aaro.cosign.Sources.extra.value = it.split(',').filter(String::isNotBlank) }
        // Stand-in videos from "Tube" in Discover, until its endpoint is live (--es places fake).
        if (intent?.getStringExtra("places") == "fake") Places.fake = true
        // Open someone's profile (--es profile <hex key>), for timing how fast one opens.
        intent?.getStringExtra("profile")?.let { cc.aaro.cosign.ui.DebugOpen.profile.value = it }
        // A screen, opened for a test (--es open marble|stories|account|rename|compose).
        intent?.getStringExtra("open")?.let { cc.aaro.cosign.ui.DebugOpen.screen.value = it }
        // Marble of the day for another day (--ei marble_day n), to look at the collection.
        // The nightly marble from a test list instead of the site's (--es daily http://10.0.2.2:8765/manifest.json).
        intent?.getStringExtra("daily")?.let { cc.aaro.cosign.ui.Daily.manifest = it }
        if (intent?.hasExtra("marble_day") == true) cc.aaro.cosign.ui.Marbles.shift = intent.getIntExtra("marble_day", 0).toLong()
        // Your own events to the test relay alone (--ez local_only true).
        if (intent?.getBooleanExtra("local_only", false) == true) cc.aaro.cosign.Sources.localOnly = true
        // Back on the main page after this long away, instead of five minutes (--el away_ms 20000).
        intent?.getLongExtra("away_ms", 0L)?.takeIf { it > 0 }?.let { cc.aaro.cosign.ui.Away.after = it }
    }
}
