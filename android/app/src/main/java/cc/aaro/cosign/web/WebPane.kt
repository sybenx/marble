package cc.aaro.cosign.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import cc.aaro.cosign.Phone
import cc.aaro.cosign.ui.Vault
import kotlinx.coroutines.delay

/** A sign-in code a page's tap showed, waiting for SitePrompt. [site] is the WebView it came from. */
data class SignInAsk(val site: WebSite, val code: String)

/** A page's camera request, waiting for SitePrompt. */
class CameraAsk(val site: WebSite, val request: PermissionRequest)

/**
 * The web pane's state: which site is showing, its WebViews (one per site, at most [MAX_ALIVE], least recently used
 * dropped), and what the bar and prompts show. Lives as long as the panes do; MainActivity handles rotation and the
 * keyboard itself (configChanges), so the WebViews and what's typed in them survive both.
 */
@Stable
class WebPaneState(private val prefs: android.content.SharedPreferences) {
    var site by mutableStateOf(WebSites.byId(prefs.getString("webSite", null)) ?: WebSites.marble)
        private set
    /** A page that tried to move itself off its site, with no tap: the bar offers to open it. */
    var blocked by mutableStateOf<String?>(null)
    var signIn by mutableStateOf<SignInAsk?>(null)
    var camera by mutableStateOf<CameraAsk?>(null)
    var loading by mutableStateOf(false)
    /** Set the first time the pane is headed for: until then no WebView is made, so opening Marble costs nothing. */
    var started by mutableStateOf(false)
    var health by mutableStateOf(WebHealth.State.OK)
    /** Fullscreen video (onShowCustomView), and how to end it. */
    var fullscreen by mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null)
    /** Bumped when a WebView is made or dropped, so the pane attaches the right one. */
    var generation by mutableIntStateOf(0)
        private set
    internal var chooser: ValueCallback<Array<Uri>>? = null
    internal var pick: ((multiple: Boolean) -> Unit)? = null

    private val views = LinkedHashMap<String, WebView>(4, 0.75f, true)

    fun show(s: WebSite, url: String? = null) {
        if (s != site) { site = s; prefs.edit().putString("webSite", s.id).apply(); blocked = null }
        url?.let { u -> views[s.id]?.loadUrl(u) ?: run { pendingUrl[s.id] = u } }
    }
    private val pendingUrl = HashMap<String, String>()

    fun current(): WebView? = views[site.id]

    /** [s]'s WebView, made the first time (at [WebSite.start], or where a link to it pointed). */
    internal fun view(context: Context, s: WebSite): WebView {
        views[s.id]?.let { return it }
        val v = make(context, s)
        views[s.id] = v
        // Three alive at most: the one used longest ago goes (never the one showing).
        while (views.size > MAX_ALIVE) views.keys.firstOrNull { it != site.id }?.let { drop(it) } ?: break
        v.loadUrl(pendingUrl.remove(s.id) ?: s.start)
        generation++
        return v
    }

    private fun drop(id: String) {
        val v = views.remove(id) ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        v.stopLoading(); v.destroy()
        generation++
    }

    fun destroyAll() { views.keys.toList().forEach(::drop) }

    /** Back in the site's own history; false when there's none (a step a page added without a tap doesn't count: WebView skips it). */
    fun back(): Boolean = current()?.takeIf { it.canGoBack() }?.let { it.goBack(); true } ?: false

    @SuppressLint("SetJavaScriptEnabled")
    private fun make(context: Context, s: WebSite): WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            safeBrowsingEnabled = true
            mediaPlaybackRequiresUserGesture = true
            // target=_blank and window.open come to onCreateWindow, which sends them to a Custom Tab; never a second WebView.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        // What the page doesn't scroll stays the page's: never handed on (nested scrolling) to the pager, which would turn pane.
        isNestedScrollingEnabled = false
        webViewClient = Client(s)
        webChromeClient = Chrome(s)
        setDownloadListener { url, _, disposition, mime, _ -> download(context, s, url, disposition, mime) }
    }

    private inner class Client(val s: WebSite) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // The origin a sign-in is named after is the WebView's own, and only while it's still on it.
            val onSite = WebRules.originOf(view.url ?: "") == s.origin
            val n = WebRules.decide(s, request.url.toString(), request.isForMainFrame, request.hasGesture())
            if (debug) android.util.Log.d("MarbleWeb", "${s.id}: ${n::class.simpleName} main=${request.isForMainFrame} gesture=${request.hasGesture()} redirect=${request.isRedirect} onSite=$onSite ${request.url.toString().take(80)}")
            return when (n) {
                Nav.Stay -> false
                is Nav.OtherSite -> { show(n.site, n.url); true }
                is Nav.Outside -> { openOutside(view.context, n.url); true }
                is Nav.Blocked -> { blocked = n.url; true }
                is Nav.SignIn -> { if (onSite && signIn == null) signIn = SignInAsk(s, n.uri); true }
                Nav.Drop -> true
            }
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { if (s == site) loading = true }
        override fun onPageFinished(view: WebView, url: String?) { if (s == site) loading = false }
        // A crashed (or killed) renderer: this site's WebView goes, and a new one is made where it was.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (views[s.id] === view) { pendingUrl[s.id] = view.url ?: s.start; drop(s.id) }
            else { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
            return true
        }
    }

    private inner class Chrome(val s: WebSite) : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            // A throwaway WebView, never shown and never given anything, that only learns where the window was going.
            val catcher = WebView(view.context)
            var done = false
            fun went(url: String?) {
                if (done || url.isNullOrEmpty() || url == "about:blank") return
                done = true
                when (val n = WebRules.decide(s, url, mainFrame = true, gesture = true)) {
                    Nav.Stay -> view.loadUrl(url)
                    is Nav.OtherSite -> show(n.site, n.url)
                    is Nav.Outside -> openOutside(view.context, n.url)
                    else -> {} // a sign-in code or another scheme by window.open: nothing
                }
                catcher.post { catcher.destroy() }
            }
            catcher.settings.javaScriptEnabled = false
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { went(r.url.toString()); return true }
                override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) { v.stopLoading(); went(url) }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Only the camera, only for this site's own page, and only once you say yes (SitePrompt). Everything else: no.
            val onlyCamera = request.resources.isNotEmpty() && request.resources.all { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
            val fromSite = WebRules.originOf(request.origin.toString()) == s.origin
            val canCamera = androidx.core.content.ContextCompat.checkSelfPermission(cc.aaro.cosign.CosignApp.instance, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (onlyCamera && fromSite && canCamera && camera == null && s == site) camera = CameraAsk(s, request) else request.deny()
        }

        override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            // Pictures and videos only, from Android's photo picker: the page never sees any other file.
            chooser?.onReceiveValue(null)
            val p = pick ?: run { callback.onReceiveValue(null); return true }
            chooser = callback
            p(params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullscreen?.second?.onCustomViewHidden()
            fullscreen = view to callback
        }
        override fun onHideCustomView() { fullscreen = null }
        override fun onProgressChanged(view: WebView, newProgress: Int) { if (s == site) loading = newProgress < 100 }
    }

    /** A link off the pane's sites, after a tap: a Custom Tab (your browser's sign-ins, its back button, nothing of the pane's). */
    fun openOutside(context: Context, url: String) {
        blocked = null
        cc.aaro.cosign.Tabs.open(context, url, false, Vault.dark, Vault.Ground.toArgb())
    }

    /** A picture the page offers to save (a long-press Save, a download link): into Pictures/Marble. Nothing else. */
    private fun download(context: Context, s: WebSite, url: String, disposition: String?, mime: String?) {
        val u = Uri.parse(url)
        val image = mime?.startsWith("image/") == true
        if (!image || (u.scheme != "https" && u.scheme != "http")) {
            android.widget.Toast.makeText(context, "Marble only saves pictures from ${s.label}.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
        try {
            val r = android.app.DownloadManager.Request(u).setMimeType(mime)
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_PICTURES, "Marble/$name")
            context.getSystemService(android.app.DownloadManager::class.java).enqueue(r)
            android.widget.Toast.makeText(context, "Saving $name", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Couldn't save that picture.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val debug = cc.aaro.cosign.CosignApp.instance.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object { const val MAX_ALIVE = 3 }
}

@Composable
fun rememberWebPaneState(): WebPaneState {
    val context = LocalContext.current
    val state = remember { WebPaneState(context.getSharedPreferences("marble", Context.MODE_PRIVATE)) }
    DisposableEffect(state) {
        // Inspectable from Chrome's DevTools in debug builds only.
        WebView.setWebContentsDebuggingEnabled(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        onDispose { state.destroyAll() }
    }
    return state
}

/** The site strip's middle: one chip per site. Tap to switch. */
@Composable
fun WebChips(state: WebPaneState, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (s in WebSites.all) {
            val on = s == state.site
            Text(s.name, color = if (on) Vault.Ground else Vault.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Vault.Accent else Vault.Surface)
                    .border(1.dp, if (on) Vault.Accent else Vault.Edge, RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = "Open ${s.name}") { state.show(s) }.padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

/**
 * Marble's left pane: the site on its chip, live (marble-web-pane-plan M1). [current]: the pane is settled on screen
 * with nothing over it. [onReview]: open Approvals, for a change of a site's that's waiting for you.
 */
@Composable
fun WebPane(state: WebPaneState, phone: Phone, current: Boolean, onReview: () -> Unit) {
    val context = LocalContext.current
    val ui by phone.ui.collectAsState()

    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        state.chooser?.onReceiveValue(u?.let { arrayOf(it) }); state.chooser = null
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { l ->
        state.chooser?.onReceiveValue(l.takeIf { it.isNotEmpty() }?.toTypedArray()); state.chooser = null
    }
    DisposableEffect(state) {
        state.pick = { many ->
            val req = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            if (many) pickMany.launch(req) else pickOne.launch(req)
        }
        onDispose { state.pick = null }
    }

    // Health: when the pane comes on screen, and every minute while it stays.
    LaunchedEffect(current, phone) {
        if (!current) return@LaunchedEffect
        while (true) { state.health = WebHealth.check(context, phone); delay(60_000) }
    }
    // A hidden pane's pages keep their place but stop playing.
    LaunchedEffect(current, state.generation) { state.current()?.let { if (current) it.onResume() else it.onPause() } }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        WebBar(state, ui, context, onReview)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Vault.Accent, trackColor = Color.Transparent)
        else Box(Modifier.height(2.dp))
        if (state.started) AndroidView({ c -> FrameLayout(c) }, Modifier.fillMaxSize(), update = { frame ->
            // Read here, so a new site or a rebuilt WebView attaches again.
            state.generation
            val v = state.view(frame.context, state.site)
            if (frame.getChildAt(0) !== v || frame.childCount != 1) {
                frame.removeAllViews()
                (v.parent as? ViewGroup)?.removeView(v)
                frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        })
    }

    state.fullscreen?.let { (view, callback) ->
        Dialog({ callback.onCustomViewHidden(); state.fullscreen = null }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            AndroidView({ FrameLayout(it).apply { setBackgroundColor(android.graphics.Color.BLACK); (view.parent as? ViewGroup)?.removeView(view); addView(view) } }, Modifier.fillMaxSize())
        }
    }

}

/**
 * The web pane's prompts. Drawn outside the pager, over the panes (as Cosign's own connect sheet is): drawn inside the
 * pane's page, beside the WebView, they never showed over it.
 */
@Composable
fun WebPrompts(state: WebPaneState, phone: Phone) {
    val ui by phone.ui.collectAsState()
    val account = remember(phone) { CosignSiteAccount.of(phone) }
    state.signIn?.let { ask ->
        val label = ask.site.label
        val had = ui.sites.any { CosignSiteAccount.replaces(it, label, WebRules.clientOf(ask.code)) }
        SitePrompt("Sign in to $label?", "$label can post, like and follow as you. Anything destructive, like unfollowing everyone or deleting posts, comes to this phone first.",
            yes = "Sign in to $label", no = "Not now",
            note = if (had) "This replaces the sign-in by code $label already has. Sign-ins from your browser stay." else "Remove it under Your account any time.",
            onYes = { state.signIn = null; account.connectSite(ask.site.origin, ask.code) }, onNo = { state.signIn = null })
    }

    val followUp by WebSignIn.followUp.collectAsState()
    followUp?.let { s ->
        if (account.sharesLists) SitePrompt("Also let ${s.label} read your private mute list?", "${s.label} reads it right after you sign in, to hide what you muted privately.",
            yes = "Allow", no = "Not now", onYes = { WebSignIn.followUp.value = null; account.shareLists(s.origin) }, onNo = { WebSignIn.followUp.value = null })
        else SitePrompt("Your private mutes stay private", "${s.label} can't read your private mute list: Cosign doesn't share private lists with sites yet. Your public mutes still work there.",
            yes = "", no = "OK", onYes = {}, onNo = { WebSignIn.followUp.value = null })
    }

    state.camera?.let { ask ->
        SitePrompt("Let ${ask.site.label} use the camera?", "Only while this page is open.", yes = "Allow", no = "Don't allow",
            onYes = { state.camera = null; ask.request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) },
            onNo = { state.camera = null; ask.request.deny() })
    }
}

/** The bar over the pane: one thing at a time, the most pressing first. */
@Composable
private fun WebBar(state: WebPaneState, ui: cc.aaro.cosign.Ui, context: Context, onReview: () -> Unit) {
    val now = System.currentTimeMillis()
    val held = ui.asks.filter { it.expiresAt > now }
    val heldHere = held.firstOrNull { WebSites.all.any { s -> it.app == s.label } } ?: held.firstOrNull()
    val (text, action, onAction) = when {
        heldHere != null -> Triple("Held at ${heldHere.app}: " + heldHere.hold.ifBlank { "waiting for you" }, "Review", onReview)
        state.health == WebHealth.State.SIGNER_STUCK -> Triple("Cosign isn't answering", "Restart signer", { WebHealth.restartSigner(context); state.health = WebHealth.State.OK })
        state.blocked != null -> state.blocked!!.let { u -> Triple("Wants to open ${Uri.parse(u).host ?: u}", "Open", { state.openOutside(context, u) }) }
        state.health == WebHealth.State.SERVERS_DOWN -> Triple("Your servers aren't answering. Sign-ins and posts wait for them.", "", {})
        else -> return
    }
    Row(Modifier.fillMaxWidth().background(Vault.AccentGround).clickable(enabled = action.isNotEmpty(), onClick = onAction).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action.isNotEmpty()) Text(" · $action", color = Vault.Accent, style = MaterialTheme.typography.labelLarge)
        if (state.blocked != null && heldHere == null && state.health != WebHealth.State.SIGNER_STUCK)
            Text("✕", color = Vault.Muted, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Dismiss") { state.blocked = null }.padding(start = 12.dp, end = 4.dp))
    }
}
