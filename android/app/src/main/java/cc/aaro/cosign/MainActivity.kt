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
    /** The first real screen (home, or sign-in for nobody) is composed: the window may draw. */
    @Volatile private var drawn = false

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
        holdFirstFrame()
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
                // Until the signer has sent this account's state, it looks like no account: drawing then
                // would flash the sign-in screen at someone signed in. Draw nothing until it's known.
                val heard by phone.heard.collectAsState()
                if (heard) {
                androidx.compose.runtime.SideEffect { if (!drawn) Startup.mark("first screen"); drawn = true }
                val ui by phone.ui.collectAsState()
                val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                // Once in a group: stay reachable, and be allowed to say so.
                LaunchedEffect(ui.member != null) {
                    if (ui.member != null) {
                        // Not before the first screen is drawn.
                        kotlinx.coroutines.delay(1500)
                        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                        CosignService.start(this@MainActivity)
                        // Changes to your lists made in another app or device since last time (the signer also looks every 30 minutes).
                        phone.checkLists()
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
    }

    /**
     * Keep the window from drawing until [drawn]: the launch splash (a plain ground, no icon) stays
     * up until the first screen is the right one. On a phone without the splash (Android 11), the
     * window's own ground does the same. A signer that never answers lets go after a few seconds,
     * onto the plain ground, never the sign-in screen.
     */
    private fun holdFirstFrame() {
        val content = findViewById<android.view.View>(android.R.id.content)
        val start = android.os.SystemClock.uptimeMillis()
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!drawn && android.os.SystemClock.uptimeMillis() - start < 4_000) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                if (!drawn) Startup.mark("first screen late", once = false)
                return true
            }
        })
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
        // A notification's tap (a change made in another app or device): that key, at Recent changes.
        intent.getStringExtra("show")?.let { what ->
            intent.getStringExtra("slot")?.let { CosignApp.instance.accounts.show(it) }
            if (what == "changes") cc.aaro.cosign.ui.DebugOpen.screen.value = "changes"
            return
        }
        // A search from the launcher (marble://search?q=…, or Android's own): every time, as asked; it isn't a code.
        val searched = when {
            intent.action == Intent.ACTION_SEARCH -> intent.getStringExtra(android.app.SearchManager.QUERY)
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "marble" && intent.data?.host == "search" -> intent.data?.getQueryParameter("q")
            else -> null
        }
        // Back from the account page ("Use my name"), or a new device's code opened as a web page first (marble.syb.lol/add,
        // a tap there): handed to the signer. The second was not read by Marble's own camera, so it counts as pasted.
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "marble" && intent.data?.host == "joined") {
            intent.dataString?.takeIf { handled.take(it, fresh) }?.let { CosignApp.instance.phone.scanned(it) }
            return
        }
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "marble" && intent.data?.host == "add") {
            intent.data?.encodedQuery?.takeIf { handled.take(it, fresh) }?.let { CosignApp.instance.phone.pasted("https://marble.syb.lol/add#$it") }
            return
        }
        if (intent.action == Intent.ACTION_SEARCH || intent.data?.scheme == "marble") {
            searched?.trim()?.take(200)?.takeIf { it.isNotEmpty() }?.let { cc.aaro.cosign.ui.OpenSearch.query.value = it }
            return
        }
        // A marble.syb.lol/relay/… link: that relay, read here.
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let(cc.aaro.cosign.RelayFeeds::fromLink)?.let { r ->
            cc.aaro.cosign.ui.DebugOpen.screen.value = "relay:" + r.address
            return
        }
        // A marble.syb.lol link to a post or someone (an app link): that post or profile, in Marble.
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let(::marbleRef)?.let { r ->
            when (r) { is Ref.Note -> cc.aaro.cosign.ui.OpenPost.id.value = r.id; is Ref.Profile -> cc.aaro.cosign.ui.DebugOpen.profile.value = r.pubkey; is Ref.Addr -> cc.aaro.cosign.ui.OpenPost.ref.value = r }
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
        // The same as a pasted code (--es paste '<text>'), and one of the account's actions with no tap (--es act accept),
        // so an emulator with no camera can go through making, joining and changing an account.
        intent?.getStringExtra("paste")?.let { CosignApp.instance.phone.pasted(it) }
        intent?.getStringExtra("act")?.let { a -> CosignApp.instance.phone.let { p -> when (a) {
            "startSignIn" -> p.startSignIn(); "signInByName" -> p.signInByName(); "addYes" -> p.addDigits(true); "addNo" -> p.addDigits(false)
            "cancelAdd" -> p.cancelAddDevice()
            // A test's own relay for one-code sign-ins (--es arg ws://127.0.0.1:7777), and its own login page for "Use my name".
            "qrstRelays" -> p.testQrstRelays(intent.getStringExtra("arg") ?: ""); "marbleWeb" -> p.testMarbleWeb(intent.getStringExtra("arg") ?: "")
            "accept" -> p.accept(); "startJoin" -> p.startJoin(); "confirmAddDevice" -> p.confirmAddDevice(); "confirmConnect" -> p.confirmConnect(intent.getStringExtra("arg"))
            "digits" -> p.digits(true); "cycle" -> p.cycle(); "formKey" -> p.takeOutViaServers(); "bunker" -> p.makeBunker(); "leave" -> p.leave()
            "confirmReportLost" -> p.confirmReportLost(); "refresh" -> p.refresh()
            "reportLost" -> p.ui.value.member?.group?.members?.firstOrNull { it.name == intent.getStringExtra("arg") }?.let(p::askReportLost)
            "unblock" -> p.ui.value.member?.group?.members?.firstOrNull { it.name == intent.getStringExtra("arg") }?.let(p::unblock)
            "password" -> p.setPassword(intent.getStringExtra("arg") ?: "", intent.getStringExtra("arg2") ?: "")
            "loginPage" -> p.setLoginPage(intent.getStringExtra("arg") ?: "")
            "removeSite" -> p.ui.value.sites.firstOrNull { it.origin == intent.getStringExtra("arg") }?.let(p::removeSite)
            "undo" -> p.ui.value.changes.firstOrNull()?.let(p::undo)
            // Look on your relays now for changes to your lists made in another app or device (logged under MarbleState).
            "checkLists" -> p.checkLists()
            // Hand the signer a version of one of your lists as if the reader had found it (--es arg '<signed event JSON>').
            "seen" -> intent.getStringExtra("arg")?.let { j -> cc.aaro.qrst.core.Event.fromMap(cc.aaro.qrst.core.Json.parse(j))?.let(p::seen) }
            "sign" -> Thread { try { android.util.Log.i("MarbleState", "signed " + p.signOwn(cc.aaro.qrst.core.Event(p.ui.value.member!!.group.groupX, System.currentTimeMillis() / 1000, intent.getIntExtra("kind", 1), emptyList(), intent.getStringExtra("arg") ?: "test").withId()).let { it.toJson() + " verifies=" + it.verify() }) } catch (e: Exception) { android.util.Log.i("MarbleState", "sign failed: $e") } }.start()
            "nip44" -> Thread { try { val c = p.nip44Self(intent.getStringExtra("arg") ?: "hello", true); android.util.Log.i("MarbleState", "nip44 self: ${c?.take(24)}… -> ${c?.let { p.nip44Self(it, false) }}") } catch (e: Exception) { android.util.Log.i("MarbleState", "nip44 failed: $e") } }.start()
            "tapYes" -> p.tap(true); "tapNo" -> p.tap(false)
            "loginSwitch" -> p.answerLoginSwitch(true); "loginKeep" -> p.answerLoginSwitch(false)
            // The account page's "make this my sign-in page" (a pointer note): Yes or No, as the sheet's buttons.
            "pointerYes" -> p.tap(true); "pointerNo" -> p.tap(false)
            "createLocal" -> p.createLocal(); "receiveKey" -> p.receiveKey(); "keepKey" -> p.keepKey(); "sendKey" -> p.sendKey()
            "moveDigits" -> p.moveDigits(intent.getStringExtra("arg")); "closeMove" -> p.closeMove()
            "import" -> p.importKey((intent.getStringExtra("arg") ?: "").toCharArray()); "forgetKey" -> p.forgetKey()
            "addAccount" -> CosignApp.instance.accounts.add()
            // A child key for a place (--es arg <place>, "-<place>" to forget it first): signed and encrypted as it, logged under MarbleState.
            "child" -> p.testChild(intent.getStringExtra("arg") ?: "")
            "show" -> CosignApp.instance.accounts.all.getOrNull(intent.getIntExtra("n", 0))?.let { CosignApp.instance.accounts.show(it.slot) }
            "deny" -> p.ui.value.asks.firstOrNull()?.let { p.decide(it.id, false) }
            "testRelays" -> p.testRelays(intent.getStringExtra("arg") ?: "")
            // Run the login-page pointer step for this account again, as after joining a box (logged under Keykin).
            "pointer" -> p.pointerNow()
            "closeAll" -> { p.closeConnect(); p.closeJoinText(); p.closeBunker(); p.closeTakeOut(); p.clearError() }
            "approve" -> p.ui.value.asks.firstOrNull()?.let { p.decide(it.id, true) }
            "state" -> android.util.Log.i("MarbleState", p.ui.value.let { u -> "member=${u.member?.group?.npub} idx=${u.member?.idx} epoch=${u.member?.epoch} members=${u.member?.group?.members?.map { "${it.idx}:${it.name}:${it.role}${if (it.blocked) ":blocked" else ""}" }} box=${u.relaysUp} sites=${u.sites.map { it.origin + "/" + it.via }} asks=${u.asks.map { it.hold }} error=${u.error} offer=${u.offer} joinCode=${u.joinCode} joinText=${u.joinText} signIn=${u.signIn} adding=${u.adding} addDigits=${u.addDigits} connect=${u.connect} working=${u.working} takeOut=${u.takeOut?.stage} bunker=${u.bunker != null} changes=${u.changes.map { it.text + (if (it.outside) " (elsewhere)" else "") + (if (it.held) " (amber)" else "") + (if (it.undone) " (undone)" else "") }} needsCycle=${u.needsCycle} tap=${u.tap?.title} bringing=${u.bringing} keptKey=${u.keptKey} box=${u.member?.box} move=${u.move} username=${u.username} loginPage=${u.loginPage} removedBy=${u.removedBy} log=${u.log.take(6).map { it.text }}" })
            else -> {}
        } } }
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
        intent?.getStringExtra("daily")?.let { cc.aaro.cosign.ui.Daily.test(this, it) }
        if (intent?.hasExtra("marble_day") == true) cc.aaro.cosign.ui.Marbles.shift = intent.getIntExtra("marble_day", 0).toLong()
        // Your own events to the test relay alone (--ez local_only true).
        if (intent?.getBooleanExtra("local_only", false) == true) cc.aaro.cosign.Sources.localOnly = true
        // Notes vault tests: Blossom servers for a new vault (--es vault_servers http://10.0.2.2:7447,http://10.0.2.2:7448), and
        // the vault's actions (--es vault state|sync|checkpoint|lost|edit --es arg …), logged under MarbleVault.
        intent?.getStringExtra("vault_servers")?.let { cc.aaro.cosign.Sources.vaultServers = it.split(",").map(String::trim).filter(String::isNotEmpty) }
        intent?.getStringExtra("vault")?.let { cc.aaro.cosign.Social.of(CosignApp.instance.phone).vaultDebug(it, intent.getStringExtra("arg")) }
        // Back on the main page after this long away, instead of five minutes (--el away_ms 20000).
        intent?.getLongExtra("away_ms", 0L)?.takeIf { it > 0 }?.let { cc.aaro.cosign.ui.Away.after = it }
    }
}
