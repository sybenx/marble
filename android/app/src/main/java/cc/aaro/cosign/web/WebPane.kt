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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
        if (WebSites.byOrigin(s.origin) == null) WebSites.put(s)
        val now = WebSites.byOrigin(s.origin) ?: s
        if (now.origin != site.origin) { if (now.kind != WebSite.Kind.VISITING) prefs.edit().putString("webSite", now.id).apply(); blocked = null; signInOff = null }
        site = now
        url?.let { u -> views[now.id]?.loadUrl(u) ?: run { pendingUrl[now.id] = u } }
    }

    /** An app from search: a visiting chip (in place of any other visit), loaded at its start. It can't sign in yet. */
    fun visit(s: WebSite) {
        WebSites.all.firstOrNull { it.kind == WebSite.Kind.VISITING && it.origin != s.origin }?.let { old -> drop(old.id); WebSites.drop(old) }
        show(s, s.start)
    }

    /** Keep: the visit stays, as a chip of its own on this phone. */
    fun keep(s: WebSite) {
        val k = (WebSites.byOrigin(s.origin) ?: s).copy(kind = WebSite.Kind.KEPT)
        WebSites.update(k)
        if (site.origin == k.origin) { site = k; prefs.edit().putString("webSite", k.id).apply() }
    }

    /** ✕ on a visit (and the end of Remove): its WebView goes, and the pane goes back to the site before it. */
    fun close(s: WebSite) {
        if (s.kind == WebSite.Kind.BUILT_IN) return
        drop(s.id); WebSites.drop(s)
        if (signInOff?.origin == s.origin) signInOff = null
        if (site.origin == s.origin) site = WebSites.byId(prefs.getString("webSite", null))?.takeIf { it.origin != s.origin } ?: WebSites.marble
    }

    /** "Can ask to sign in", from its sheet. Off also signs it out of what the pane signed it in to. */
    fun allowAsks(s: WebSite, on: Boolean, phone: Phone) {
        val n = (WebSites.byOrigin(s.origin) ?: s).copy(asks = on)
        WebSites.update(n)
        if (site.origin == n.origin) site = n
        if (on) { if (signInOff?.origin == n.origin) signInOff = null } else signOut(n, phone)
    }

    /** Sign [s] out: the sign-ins this pane made for it, for this account, end. Any made elsewhere stay. */
    fun signOut(s: WebSite, phone: Phone) {
        val grants = PaneGrants.of(cc.aaro.cosign.CosignApp.instance)
        for (pk in grants.of(phone.slot, s.label)) {
            phone.ui.value.sites.firstOrNull { it.pk.lowercase() == pk && it.origin == s.label }?.let { phone.removeSite(it) }
            grants.forget(phone.slot, s.label, pk)
        }
    }

    /** Whether the pane has [s] signed in, for this account. */
    fun signedIn(s: WebSite, phone: Phone, sites: List<cc.aaro.cosign.Site>): Boolean {
        val mine = PaneGrants.of(cc.aaro.cosign.CosignApp.instance).of(phone.slot, s.label)
        return sites.any { it.pk.lowercase() in mine && it.origin == s.label }
    }

    /** Remove [s]: signed out, what it stored on this phone wiped (storage, caches, service workers, cookies), its chip gone. */
    fun remove(s: WebSite, phone: Phone) {
        if (s.kind == WebSite.Kind.BUILT_IN) return
        signOut(s, phone)
        val wipe = "(async()=>{try{for(const r of await navigator.serviceWorker.getRegistrations())await r.unregister()}catch(e){}" +
            "try{for(const k of await caches.keys())await caches.delete(k)}catch(e){}" +
            "try{for(const d of (await indexedDB.databases()))indexedDB.deleteDatabase(d.name)}catch(e){}" +
            "try{localStorage.clear();sessionStorage.clear()}catch(e){}})()"
        val v = views[s.id]?.takeIf { WebRules.originOf(it.url ?: "") == s.origin }
        val done = {
            android.webkit.WebStorage.getInstance().deleteOrigin(s.origin)
            CookieManager.getInstance().let { cm ->
                cm.getCookie(s.origin)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { n ->
                    cm.setCookie(s.origin, "$n=; Max-Age=0; Path=/")
                    cm.setCookie(s.origin, "$n=; Max-Age=0; Path=/; Domain=${s.label.substringBefore(':')}")
                }
                cm.flush()
            }
            close(s)
        }
        if (v != null) { v.evaluateJavascript(wipe, null); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(done, 600) } else done()
    }

    fun refresh(s: WebSite) { views[s.id]?.reload() }

    /** The site sheet (long-press on a chip): the site it's open for. */
    var sheetFor by mutableStateOf<WebSite?>(null)
    /** Remove, waiting for its confirm. */
    var removeAsk by mutableStateOf<WebSite?>(null)
    /** A site whose page offered a sign-in while "Can ask to sign in" is off: the bar says so. */
    var signInOff by mutableStateOf<WebSite?>(null)

    /** A sign-in code from a tap in [s]'s page: the prompt, or, while it can't ask, the bar. */
    private fun askSignIn(s: WebSite, code: String) {
        val now = WebSites.byOrigin(s.origin) ?: return
        if (!now.asks) { signInOff = now; return }
        if (signIn == null) signIn = SignInAsk(now, code)
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
                is Nav.SignIn -> { if (onSite) askSignIn(s, n.uri); true }
                Nav.Drop -> true
            }
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { if (s.origin == site.origin) loading = true }
        override fun onPageFinished(view: WebView, url: String?) { if (s.origin == site.origin) loading = false }
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
                    // A sign-in link that opens a new window (noStrudel's Nostr Connect button), from a tap, while the
                    // page is still on its own site: the same prompt as a link in the page.
                    is Nav.SignIn -> if (WebRules.originOf(view.url ?: "") == s.origin) askSignIn(s, n.uri)
                    else -> {} // another scheme by window.open: nothing
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
            if (onlyCamera && fromSite && canCamera && camera == null && s.origin == site.origin) camera = CameraAsk(s, request) else request.deny()
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
        override fun onProgressChanged(view: WebView, newProgress: Int) { if (s.origin == site.origin) loading = newProgress < 100 }
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
    val state = remember {
        // The kept chips first: the pane may have been left on one.
        WebSites.load(context.getSharedPreferences("web-sites", Context.MODE_PRIVATE))
        WebPaneState(context.getSharedPreferences("marble", Context.MODE_PRIVATE))
    }
    DisposableEffect(state) {
        // Inspectable from Chrome's DevTools in debug builds only.
        WebView.setWebContentsDebuggingEnabled(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        onDispose { state.destroyAll() }
    }
    return state
}

/**
 * The site strip's middle: one chip per site (built in, kept, then a visit). Tap to switch; long-press for its sheet. A
 * visit says so, with Keep and ✕.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WebChips(state: WebPaneState, modifier: Modifier = Modifier) {
    val sites by WebSites.list.collectAsState()
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (s in sites) {
            val on = s.origin == state.site.origin
            val visit = s.kind == WebSite.Kind.VISITING
            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Vault.Accent else Vault.Surface)
                .border(1.dp, if (on) Vault.Accent else Vault.Edge, RoundedCornerShape(16.dp))
                .combinedClickable(onClickLabel = "Open ${s.name}", onLongClickLabel = "${s.name}'s settings", onLongClick = { state.sheetFor = s }) { state.show(s) }
                .padding(start = 14.dp, end = if (visit) 6.dp else 14.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (visit) "Visiting: ${s.name}" else s.name, color = if (on) Vault.Ground else Vault.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                if (visit) {
                    Text("Keep", color = if (on) Vault.Ground else Vault.Accent, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Keep ${s.name} as a chip") { state.keep(s) }.padding(horizontal = 4.dp))
                    Text("✕", color = if (on) Vault.Ground else Vault.Muted, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Close ${s.name}") { state.close(s) }.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

/** The site sheet (long-press on a chip): where it is, and what it may do. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SiteSheet(state: WebPaneState, phone: Phone) {
    val s0 = state.sheetFor ?: return
    val sites by WebSites.list.collectAsState()
    val ui by phone.ui.collectAsState()
    val s = sites.firstOrNull { it.origin == s0.origin } ?: run { state.sheetFor = null; return }
    val context = LocalContext.current
    val close = { state.sheetFor = null }
    val signedIn = state.signedIn(s, phone, ui.sites)
    val here = { (if (state.site.origin == s.origin) state.current()?.url?.takeIf { WebRules.originOf(it) == s.origin } else null) ?: s.start }
    androidx.compose.material3.ModalBottomSheet(close, containerColor = Vault.Surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(s.name, style = MaterialTheme.typography.titleLarge, color = Vault.Text)
            Text(s.label, style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
            if (signedIn) Text("Signed in here", style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
            Box(Modifier.height(12.dp))
            @Composable fun row(text: String, enabled: Boolean = true, onClick: () -> Unit) = Text(text, color = if (enabled) Vault.Text else Vault.Muted, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp))
            row("Copy the address") {
                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(s.name, here())); close()
            }
            row("Refresh") { state.refresh(s); close() }
            row("Open in your browser") { state.openOutside(context, here()); close() }
            if (s.kind == WebSite.Kind.VISITING) row("Keep as a chip") { state.keep(s); close() }
            if (s.kind != WebSite.Kind.BUILT_IN) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Can ask to sign in", color = Vault.Text, style = MaterialTheme.typography.bodyLarge)
                    Text(if (s.asks) "Each sign-in still asks you first. Turning this off signs it out." else "Off: its sign-in button does nothing here.",
                        color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.material3.Switch(s.asks, { on -> state.allowAsks(s, on, phone) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Vault.Accent, checkedThumbColor = Vault.Ground,
                        uncheckedTrackColor = Vault.Ground, uncheckedThumbColor = Vault.Muted, uncheckedBorderColor = Vault.Edge))
            }
            row("Sign out", enabled = signedIn) { state.signOut(s, phone); close() }
            if (s.kind != WebSite.Kind.BUILT_IN) row("Remove") { state.removeAsk = s; close() }
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
        val replacing = PaneGrants.of(LocalContext.current).toReplace(phone.slot, label, ui.sites, WebRules.clientOf(ask.code))
        val note = listOfNotNull(siteNote(ask.site),
            if (replacing.isNotEmpty()) "This replaces the sign-in this pane made for $label before. Your other sign-ins stay." else "Remove it under Your account any time.").joinToString(" ")
        val done = { pick: String? -> state.signIn = null; account.connectSite(ask.site.origin, ask.code, pick) }
        when (PaneChoice.decide(ask.site.origin, ui.sites, replacing)) {
            // A site new to this account: an account of its own is the main choice, the main account the small one.
            PaneChoice.ASK -> SitePrompt("Sign in to $label?", "A new account is made just for this site, empty to start. Nothing from your main account goes with it. Anything destructive comes to this phone first.",
                yes = "Sign in with a new account", no = "Not now", note = note, onYes = { done("new") }, onNo = { state.signIn = null },
                alt = "Use my main account", onAlt = { done("main") })
            PaneChoice.OWN -> SitePrompt("Sign in to $label?", "It signs in with its own account, as before. Anything destructive comes to this phone first.",
                yes = "Sign in to $label", no = "Not now", note = note, onYes = { done(null) }, onNo = { state.signIn = null })
            PaneChoice.MAIN -> SitePrompt("Sign in to $label?", "$label can post, like and follow as you. Anything destructive, like unfollowing everyone or deleting posts, comes to this phone first.",
                yes = "Sign in to $label", no = "Not now", note = note, onYes = { done(null) }, onNo = { state.signIn = null })
        }
    }

    SiteSheet(state, phone)
    state.removeAsk?.let { s ->
        SitePrompt("Remove ${s.name}?", "This signs ${s.label} out here and wipes what it saved on this phone. You can open it again from search.",
            yes = "Remove", no = "Keep it", onYes = { state.removeAsk = null; state.remove(s, phone) }, onNo = { state.removeAsk = null })
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
        state.signInOff != null -> state.signInOff!!.let { s -> Triple("Signing in is off for ${s.label}", "Settings", { state.signInOff = null; state.sheetFor = s }) }
        state.blocked != null -> state.blocked!!.let { u -> Triple("Wants to open ${Uri.parse(u).host ?: u}", "Open", { state.openOutside(context, u) }) }
        state.health == WebHealth.State.SERVERS_DOWN -> Triple("Your servers aren't answering. Sign-ins and posts wait for them.", "", {})
        else -> return
    }
    Row(Modifier.fillMaxWidth().background(Vault.AccentGround).clickable(enabled = action.isNotEmpty(), onClick = onAction).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action.isNotEmpty()) Text(" · $action", color = Vault.Accent, style = MaterialTheme.typography.labelLarge)
        if ((state.blocked != null || state.signInOff != null) && heldHere == null && state.health != WebHealth.State.SIGNER_STUCK)
            Text("✕", color = Vault.Muted, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Dismiss") { if (state.signInOff != null) state.signInOff = null else state.blocked = null }.padding(start = 12.dp, end = 4.dp))
    }
}

/** What a sign-in prompt adds for a site that isn't built in. */
fun siteNote(s: WebSite): String? = if (s.kind != WebSite.Kind.BUILT_IN) "Opened from search. Only allow sites you trust." else null

/**
 * Which account a sign-in from the pane gets, as the signer will decide it (SiteChoice): a site new to this account
 * gets the choice; one signed in before gets what it had; Marble's own site always the main account. [replacing]: the
 * pane's own earlier sign-ins for it, which end first, so they don't count.
 */
enum class PaneChoice { ASK, OWN, MAIN;
    companion object {
        private fun host(o: String) = o.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
        fun decide(origin: String, sites: List<cc.aaro.cosign.Site>, replacing: List<cc.aaro.cosign.Site> = emptyList()): PaneChoice {
            if (host(origin) in cc.aaro.cosign.SiteChoice.ALWAYS_MAIN.map(::host)) return MAIN
            val gone = replacing.map { it.pk.lowercase() }.toSet()
            val before = sites.filter { it.via != "App on this phone" && host(it.origin) == host(origin) && it.pk.lowercase() !in gone }
            if (before.isEmpty()) return ASK
            return if (before.any { it.own }) OWN else MAIN
        }
    }
}
