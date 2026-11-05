package cc.aaro.cosign.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import cc.aaro.cosign.Nip05
import cc.aaro.cosign.LookAlikes
import cc.aaro.cosign.Hit
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.em
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.core.graphics.drawable.toBitmap
import cc.aaro.cosign.CosignApp
import cc.aaro.cosign.Notice
import cc.aaro.cosign.People
import cc.aaro.cosign.Person
import cc.aaro.cosign.Phone
import cc.aaro.cosign.PlaceCard
import cc.aaro.cosign.TopicCard
import cc.aaro.cosign.TopicFeeds
import cc.aaro.cosign.Pick
import cc.aaro.cosign.Preview
import cc.aaro.cosign.Calm
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.repeatOnLifecycle
import cc.aaro.cosign.OtherApps
import cc.aaro.cosign.Ref
import cc.aaro.cosign.Social
import cc.aaro.cosign.FeedLog
import cc.aaro.cosign.Startup
import cc.aaro.cosign.Translate
import cc.aaro.cosign.OnDevice
import cc.aaro.cosign.Ui
import cc.aaro.cosign.appName
import cc.aaro.qrst.core.Event
import cc.aaro.qrst.core.hexToBytes
import cc.aaro.cosign.parentOf
import cc.aaro.cosign.shortNpub
import kotlinx.coroutines.delay

// The home screen, laid out like a search app: a search box, a bell, your picture (which
// opens your account: devices, connected sites, backup; how it works is under Advanced), at most one card that needs you, and a
// Discover feed. No posting, no replies, no zaps: you can like a note and follow someone.

private sealed class Route {
    data object Account : Route()
    data object Bell : Route()
    data object Search : Route()
    data object Stories : Route()
    /** Discover: what people liked this week, with your Special Follow people woven in, and today's news a tap away. */
    data object Feed : Route()
    data class Thread(val id: String, val reply: Boolean = false) : Route()
    data class Profile(val pubkey: String) : Route()
    /** Your engine's results for [q], inside Marble (its own pages only). */
    data class Results(val q: String) : Route()
    data object Later : Route()
    /** People and posts for a search (Marble's own search, through Ditto's relays): what the row on results opens. */
    data class PeoplePosts(val q: String) : Route()
    /** What you searched (this phone, 30 days), with the pages you opened from each. */
    data object Searches : Route()
    /** The marbles you kept. */
    data object Shelf : Route()
    /** A new post; [link]: a story to post with a comment. */
    data class Compose(val link: String = "") : Route()
    /** Feed 3: one section's whole list, full-size posts ("special", "people" or "beyond"). */
    data class Section(val which: String) : Route()
    /** Account › Special Follow: the people on your list. */
    data object SpecialList : Route()
    /** Account › News sources: your own feeds, and Marble's (each can be removed). */
    data object News : Route()
    /** Following › Relays: your favourite relays and sets, relays your people use, and any relay by its address. */
    data object RelayList : Route()
    /** A relay, read like a channel. */
    data class Relay(val address: String) : Route()
    /** Everything waiting for your key, in one place (marble-2009-spec step 3): what the bell's first half was. */
    data object Approvals : Route()
    /** Reader's folders and sources: the Add box, the people toggle, Mute and Unsubscribe. */
    data object ReaderSources : Route()
    /** One of your relay sets, its relays read together. */
    data class RelaySet(val d: String) : Route()

    /** As text, so the stack of screens survives Android recreating Marble. */
    fun save(): String = when (this) {
        Account -> "account"; Bell -> "bell"; Search -> "search"; Stories -> "stories"; Feed -> "discover"
        is Thread -> "thread:$id:$reply"; is Profile -> "profile:$pubkey"; is Compose -> "compose:$link"; is Results -> "results:$q"; is PeoplePosts -> "pp:$q"; is Section -> "section:$which"; Later -> "later"; Searches -> "searches"; Shelf -> "marbles"; SpecialList -> "special"; News -> "news"; RelayList -> "relays"; Approvals -> "approvals"; ReaderSources -> "readersources"
        is Relay -> "relay:$address"; is RelaySet -> "relayset:$d"
    }
    companion object {
        fun of(s: String): Route? = when {
            s == "account" -> Account; s == "bell" -> Bell; s == "search" -> Search; s == "stories" -> Stories; s == "discover" -> Feed
            s.startsWith("thread:") -> s.split(':').let { Thread(it[1], it.getOrNull(2) == "true") }
            s.startsWith("profile:") -> Profile(s.removePrefix("profile:"))
            s.startsWith("compose:") -> Compose(s.removePrefix("compose:"))
            s.startsWith("results:") -> Results(s.removePrefix("results:"))
            s.startsWith("pp:") -> PeoplePosts(s.removePrefix("pp:"))
            s.startsWith("section:") -> Section(s.removePrefix("section:"))
            s == "later" -> Later
            s == "searches" -> Searches
            s == "marbles" -> Shelf
            s == "special" -> SpecialList
            s == "news" -> News
            s == "relays" -> RelayList
            s == "approvals" -> Approvals
            s == "readersources" -> ReaderSources
            s.startsWith("relay:") -> Relay(s.removePrefix("relay:"))
            s.startsWith("relayset:") -> RelaySet(s.removePrefix("relayset:"))
            else -> null
        }
    }
}

@Composable
fun Discover(phone: Phone, ui: Ui, onScan: () -> Unit) {
    val social = remember(phone) { Social.of(phone) }
    LaunchedEffect(social) { social.shown() }
    LaunchedEffect(Unit) { cc.aaro.cosign.Tabs.warm(CosignApp.instance) }
    val appContext = LocalContext.current
    remember { cc.aaro.cosign.SearchPrefs.load(appContext) }
    // Which screens are open, kept when Android recreates Marble (after the browser, say), so you come back to the same one.
    val stack = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Route>, String>(
        save = { l -> l.map { it.save() } }, restore = { l -> mutableStateListOf<Route>().apply { addAll(l.mapNotNull { Route.of(it) }) } })) { mutableStateListOf() }
    val feedState = rememberLazyListState()
    val readerState = rememberLazyListState()
    // Following's place: kept while you read a story, a post or a thread, and while you're on Search.
    val followingPlace = remember { FollowingPlace() }
    // The panes: Following on the left, Search (home) in the middle, Messages on the right.
    // (Messages, the third pane, is hidden until messages work: marble-2009-spec §6. MessagesPane is kept.)
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = PANE_SEARCH) { 2 }
    // How far the header had folded, kept with the list's place when Android recreates Marble.
    val header = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.Saver<TopCollapse, Float>({ it.px }, { TopCollapse().apply { px = it } })) { TopCollapse() }
    // Each screen on the stack keeps its own state (where it was scrolled to) while another is on top of it;
    // a screen that's closed forgets it.
    val kept = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun keyOf(i: Int) = "$i:${stack[i]}"
    val open: (Route) -> Unit = { stack.add(it) }
    val back: () -> Unit = { kept.removeState(keyOf(stack.lastIndex)); stack.removeAt(stack.lastIndex) }
    if (stack.isNotEmpty()) BackHandler(onBack = back)
    // A search submitted: its results take the typing screen's place (and an earlier results page's), as the Google
    // app does, so Back from them goes where you were before searching, with the box empty again.
    val searched: (Route) -> Unit = { r ->
        while (stack.lastOrNull().let { it == Route.Search || it is Route.Results || it is Route.PeoplePosts }) back()
        stack.add(r)
    }
    // A note's pictures, full screen, over everything else.
    var viewing by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    // A marble, full screen, grown from where it was tapped: today's, or one you kept.
    var bigMarble by remember { mutableStateOf<BigMarble?>(null) }
    // Away a while ([Away.after]): back on the main page, at the top, its search box showing (no keyboard), the
    // feed as it was. Away a moment (copying a link, answering a text): just where you were. Kept through Android
    // recreating Marble, as the screens are.
    var awaySince by androidx.compose.runtime.saveable.rememberSaveable { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { awaySince = android.os.SystemClock.elapsedRealtime(); FeedLog.ev("away"); social.left() }
    // Read on open and when you come back to it; nothing in between.
    // Home: the main page, at the top, its search box showing, the feed as it was (the swirl in any screen's bar, or back after a while).
    val home: () -> Unit = {
        stack.indices.forEach { kept.removeState(keyOf(it)) }; stack.clear()
        viewing = null; bigMarble = null
        header.px = 0f
        // Back on Search; Following keeps its place.
        scope.launch { pager.scrollToPage(PANE_SEARCH) }
    }
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val gone = if (awaySince > 0) android.os.SystemClock.elapsedRealtime() - awaySince else 0L
        if (debugBuild && awaySince > 0) android.util.Log.i("CosignAway", "back after ${gone / 1000} s (home from ${Away.after / 1000} s)")
        val away = awaySince > 0 && gone >= Away.after
        if (away) FeedLog.start("back") else if (awaySince > 0) FeedLog.ev("back", "gap" to gone / 1000)
        awaySince = 0
        // Back after a while: home (Search), and Following brought up to date without moving what you were reading.
        if (away) { home(); social.returned(fetchAfter = Away.after * 2) } else social.loadFeed()
        social.refreshLater()
        social.answerDevices()
        social.knowMe()
        // Your profile and follows on the relays your relay list names (when that list changed, or never had them).
        social.spreadIdentity()
        // The night's marble (and where it's claimed), then today's award if it's kept and not yet claimed.
        Daily.refresh(context)
    }
    // And every 30 minutes while it's open: new things come in above what you're reading, never moving it.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(30 * 60_000L); social.loadFeed() } } }
    // The feed log is how Following learns: on, once, with this build (on this phone only; Account › Advanced turns it off).
    LaunchedEffect(Unit) {
        val p = context.getSharedPreferences("marble", android.content.Context.MODE_PRIVATE)
        if (!p.getBoolean("feedLogOn3p", false)) { if (!FeedLog.on.value) FeedLog.set(context, true); p.edit().putBoolean("feedLogOn3p", true).apply() }
    }

    val nav = Nav(open = open, back = back, searched = searched, social = social, pictures = { urls, i -> viewing = urls to i }, marble = { bigMarble = BigMarble(it) }, home = home)
    // Debug builds: a profile asked for by intent (MainActivity's test hook).
    val postAsked by OpenPost.id.collectAsState()
    LaunchedEffect(postAsked) { postAsked?.let { id -> nav.thread(id); OpenPost.id.value = null } }
    val refAsked by OpenPost.ref.collectAsState()
    LaunchedEffect(refAsked) { refAsked?.let { r -> OpenPost.ref.value = null; nav.ref(r, context) } }
    val searchAsked by OpenSearch.query.collectAsState()
    LaunchedEffect(searchAsked) { searchAsked?.let { q -> stack.indices.forEach { kept.removeState(keyOf(it)) }; stack.clear(); social.search(q); stack.add(Route.Search); OpenSearch.query.value = null } }
    val askedScreen by DebugOpen.screen.collectAsState()
    LaunchedEffect(askedScreen) {
        when (askedScreen) {
            "marble" -> { delay(1500); bigMarble = BigMarble(androidx.compose.ui.geometry.Offset.Zero) }
            "shelf" -> stack.add(Route.Shelf)
            "stories" -> stack.add(Route.Stories)
            "compose" -> { delay(1500); nav.compose(social.feed.value.stories.firstOrNull()?.url ?: "") }
            "account", "rename" -> { DebugOpen.renameThis = askedScreen == "rename"; stack.add(Route.Account) }
            "later" -> stack.add(Route.Later)
            else -> askedScreen?.let { android.net.Uri.decode(it) }?.let { v ->
                when {
                    v.startsWith("search:") -> { SearchText.q.value = v.removePrefix("search:"); stack.add(Route.Search) }
                    v.startsWith("results:") -> nav.results(v.removePrefix("results:"))
                    // (also a marble.syb.lol/relay/… link, from MainActivity)
                    v.startsWith("relay:") -> stack.add(Route.Relay(v.removePrefix("relay:")))
                    v.startsWith("save:") -> v.removePrefix("save:").split('|').forEach { social.later.save(it) }
                }
            }
        }
        DebugOpen.screen.value = null
    }
    val asked by DebugOpen.profile.collectAsState()
    LaunchedEffect(asked) { asked?.let { stack.indices.forEach { kept.removeState(keyOf(it)) }; stack.clear(); nav.profile(it); DebugOpen.profile.value = null } }
    // The box is for typing in: out of search and its results (home, or anywhere else), the next search starts empty.
    // While a results page is still open behind you, the box keeps its query, as a browser's does.
    val inSearch = stack.any { it == Route.Search || it is Route.Results || it is Route.PeoplePosts }
    LaunchedEffect(inSearch) { if (!inSearch) { SearchText.q.value = ""; SearchText.search.value = null; social.clearSearch() } }
    val top = stack.lastOrNull()
    // The panes stay composed under any screen on top of them, so Back to them is instant, with Following where it was.
    Panes(phone, ui, social, nav, pager, feedState, followingPlace, covered = top != null, readerState = readerState)
    // A screen on top: its own ground, and every touch is its (none reaches the panes under it).
    if (top != null) Box(Modifier.fillMaxSize().background(Vault.Ground).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) { kept.SaveableStateProvider(keyOf(stack.lastIndex)) {
        when (top) {
            Route.Account -> AccountScreen(phone, ui, onScan, onProfile = { ui.member?.group?.groupX?.let { nav.profile(it) } }, onBack = back,
                onSpecial = { nav.open(Route.SpecialList) }, onNews = { nav.open(Route.News) })
            Route.Bell -> BellScreen(phone, ui, social, nav)
            Route.Search -> SearchScreen(social, nav)
            Route.Stories -> StoriesScreen(social, nav)
            Route.Feed -> FeedScreen(social, nav, pane = false)
            is Route.Thread -> ThreadScreen(top.id, social, nav, top.reply)
            is Route.Profile -> ProfileScreen(top.pubkey, social, nav)
            is Route.Compose -> ComposeScreen(top.link, social, nav)
            is Route.Results -> ResultsScreen(top.q, social, nav)
            is Route.PeoplePosts -> PeoplePostsScreen(top.q, social, nav)
            is Route.Section -> SectionScreen(top.which, social, nav)
            Route.Later -> LaterScreen(social, nav)
            Route.Searches -> SearchesScreen(social, nav)
            Route.SpecialList -> SpecialListScreen(social, nav)
            Route.News -> NewsSourcesScreen(social, nav)
            Route.RelayList -> RelayListScreen(social, nav)
            Route.Approvals -> ApprovalsScreen(phone, ui, social, nav)
            Route.ReaderSources -> ReaderSourcesScreen(social, nav)
            is Route.Relay -> RelayScreen(top.address, social, nav)
            is Route.RelaySet -> RelaySetScreen(top.d, social, nav)
            Route.Shelf -> ShelfScreen(social, nav) { from, m, d -> bigMarble = BigMarble(from, m, d) }
        }
    } }

    viewing?.let { (urls, i) -> ImageViewer(urls, i) { viewing = null } }
    bigMarble?.let { b -> MarbleView(b, social, onShelf = { bigMarble = null; if (stack.lastOrNull() != Route.Shelf) stack.add(Route.Shelf) }) { bigMarble = null } }
    VideoFullScreen()

    social.held.collectAsState().value?.let { h ->
        AlertDialog(
            onDismissRequest = { social.decideHeld(false) },
            title = { Text(question(h.what)) },
            text = { Text("Your account asks you first before anything like this.") },
            confirmButton = { TextButton({ social.decideHeld(true) }) { Text("Yes", color = Vault.Amber) } },
            dismissButton = { TextButton({ social.decideHeld(false) }) { Text("Don't") } },
            containerColor = Vault.Surface,
        )
    }
    val message by social.message.collectAsState()
    message?.let { text ->
        LaunchedEffect(text) { delay(4500); social.message.value = null }
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Text(text, color = Vault.Text, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.background(Vault.Edge, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

private class Nav(val open: (Route) -> Unit, val back: () -> Unit, val searched: (Route) -> Unit, val social: Social, val pictures: (List<String>, Int) -> Unit = { _, _ -> },
                  val marble: (androidx.compose.ui.geometry.Offset) -> Unit = {}, val home: () -> Unit = {}) {
    fun thread(id: String) { FeedLog.open(id, "thread"); open(Route.Thread(id)) }
    /** Open the post with the reply box ready. */
    fun reply(id: String) { FeedLog.open(id, "replyBox"); open(Route.Thread(id, reply = true)) }
    fun profile(pk: String) { FeedLog.ev("open", "what" to "profile", "author" to pk); open(Route.Profile(pk)) }
    /** A post, someone, or an article (found first; if it can't be, it opens on marble.syb.lol). */
    suspend fun ref(r: cc.aaro.cosign.Ref, context: android.content.Context) = when (r) {
        is cc.aaro.cosign.Ref.Note -> thread(r.id)
        is cc.aaro.cosign.Ref.Profile -> profile(r.pubkey)
        // Something from another app: to that app (Social.openTarget), never its raw content in a thread.
        is cc.aaro.cosign.Ref.Addr -> social.fetchAddr(r).let { ev -> if (ev != null && OtherApps.drawn(ev.kind)) thread(ev.id)
            else cc.aaro.cosign.Tabs.open(context, ev?.let { social.openTarget(it) } ?: ("https://marble.syb.lol/" + r.naddr), false, Vault.dark, Vault.Ground.toArgb(), null) }
    }
    /** Write a post; [link]: with this link under it. */
    fun compose(link: String = "") = open(Route.Compose(link))
    /** A search's results: people, posts and articles (Marble's own search). */
    fun results(q: String) { SearchText.q.value = q; social.interests.noticed(q); searched(Route.PeoplePosts(q)) }
    /** Your engine's results for [q], over Marble's own (Back returns to them). */
    fun web(q: String) { FeedLog.ev("web", "from" to "results"); open(Route.Results(q)) }
    fun search(q: String) { social.search(q); open(Route.Search) }
}

// --- Home ------------------------------------------------------------------------------

/** Where home's search box sits, as a share of the screen's height between the bars: a little above the middle, as a search page's does. */
private const val HOME_BOX_AT = 0.41f

/**
 * Home, calm: the search box at the very middle of the screen, the marble and the name above it, what you kept lately
 * below, and Discover's lip at the bottom. Nothing to refresh here: a pull down only stretches the marble (to a third
 * of the screen at most) and lets it spring back; a swipe up opens Discover.
 */
@Composable
private fun Home(phone: Phone, ui: Ui, social: Social, nav: Nav) {
    val feed by social.feed.collectAsState()
    val people by People.known.collectAsState()
    val notices by social.notices.collectAsState()
    val seen by social.seenAt.collectAsState()
    val laterItems by social.later.items.collectAsState()
    val tomorrow by social.tomorrow.collectAsState()
    val me = ui.member!!.group.groupX
    LaunchedEffect(me) { People.want(listOf(me)) }
    val muted by social.muted.collectAsState()
    val dismissed by social.dismissed.collectAsState()
    val posts = remember(feed.posts, muted, dismissed) { feed.posts.filter { it.e.id !in dismissed && it.e.pubkey !in muted } }
    LaunchedEffect(Unit) { Startup.mark("home composed", once = false) }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; Startup.mark("home painted", once = false); social.painted.complete(Unit) }
    val d = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
        androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Bottom))) {
        // At rest (home-rules.md): the mark (a still picture), the box, and nothing else. Your own things come when you tap the box.
        androidx.compose.ui.layout.Layout(content = {
            Swirl(64f)
            Row(Modifier.padding(horizontal = 16.dp).widthIn(max = 600.dp).fillMaxWidth().height(52.dp)
                .background(Vault.Surface, RoundedCornerShape(26.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(26.dp))
                .clickable(onClickLabel = "Search") { social.clearSearch(); nav.open(Route.Search) }.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = Vault.Muted)
                Spacer(Modifier.width(12.dp))
                Text("Search", color = Vault.Muted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                // Enter searches (the box opens for typing).
                GoChip(null)
            }
        }, modifier = Modifier.fillMaxSize()) { parts, c ->
            val loose = c.copy(minWidth = 0, minHeight = 0)
            val (top, box) = parts.map { it.measure(loose) }
            // The box at 41% of the height (as the shared design), the mark just above it.
            val gap = 20.dp.roundToPx()
            val boxY = maxOf((c.maxHeight * HOME_BOX_AT).toInt() - box.height / 2, top.height + gap)
            layout(c.maxWidth, c.maxHeight) {
                top.place((c.maxWidth - top.width) / 2, boxY - gap - top.height)
                box.place((c.maxWidth - box.width) / 2, boxY)
            }
        }
        // The one thing that can interrupt home: what waits on you (an approval, an Undo, recovery). And a passing error.
        Column(Modifier.align(Alignment.TopCenter).padding(top = 4.dp).widthIn(max = 600.dp).fillMaxWidth()) {
            NeedsYou(phone, ui, social) { nav.open(Route.Approvals) }
            ui.error?.let { e -> Text(e, color = Vault.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clickable { phone.clearError() }.padding(horizontal = 20.dp, vertical = 6.dp)) }
        }
    }
}

/** A row's source as it reads now: a person's name once their profile has come (it was stored at load). */
private fun liveSource(i: Social.ReaderItem, people: Map<String, Person>): String =
    i.pubkey?.takeIf { i.source.startsWith("people:") || i.source == "writer:$it" }?.let { people[it]?.shown } ?: i.sourceName

/** Your picture's sheet (home-rules.md "Picture"): rare things and account-shaped ones, as named rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PictureSheet(person: Person?, me: String, nav: Nav, onClose: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.ModalBottomSheet(onClose, containerColor = Vault.Surface) {
        Row(Modifier.fillMaxWidth().clickable { onClose(); nav.profile(me) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(person, me, 40)
            Column(Modifier.padding(start = 14.dp)) {
                Text(person?.shown ?: shortNpub(me), color = Vault.Text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("Your profile", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(color = Vault.Edge)
        for ((label, go) in listOf<Pair<String, () -> Unit>>(
            "Notes and searches" to { LaterTab.value = "saved"; nav.open(Route.Later) },
            // Replies, mentions and likes (the bell's list, now that the bell is gone).
            "Replies and mentions" to { nav.open(Route.Bell) },
            "Approvals" to { nav.open(Route.Approvals) },
            "Devices and backup" to { nav.open(Route.Account) },
            "Your marbles" to { nav.open(Route.Shelf) },
            "About Marble" to { cc.aaro.cosign.Tabs.open(context, "https://marble.syb.lol/about", false, Vault.dark, Vault.Ground.toArgb(), null) },
        )) Text(label, color = Vault.Text, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onClose(); go() }.padding(horizontal = 20.dp, vertical = 13.dp))
        // Switch account: each other account here, one tap away.
        val accounts = CosignApp.instance.accounts
        for (p in accounts.all.filter { it.isMember && it.ui.value.member?.group?.groupX != me }) Text("Switch to ${p.label}", color = Vault.Text, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onClose(); accounts.show(p.slot) }.padding(horizontal = 20.dp, vertical = 13.dp))
        Spacer(Modifier.height(24.dp))
    }
}

/** Following's place, kept above the screens: its chip, and the posts it had shown at the top (the rest are "new"). */
private class FollowingPlace {
    val chip = mutableStateOf("all")
    var known: Set<String>? = null
}

/** Which list Notes opens on: "saved" (your notes) or "done". */
private val LaterTab = kotlinx.coroutines.flow.MutableStateFlow("saved")

/** The button under home's box: Notes. */
@Composable
private fun HomePill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) = Row(
    Modifier.clip(RoundedCornerShape(18.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(icon, null, tint = Vault.Muted, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(7.dp))
    Text(label, color = Vault.Text, style = MaterialTheme.typography.labelLarge)
}

/** Which pane is which. */
private const val PANE_FOLLOWING = 0
private const val PANE_SEARCH = 1
private const val PANE_MESSAGES = 2

/**
 * Messages: conversations with anyone, once your account can open them (it comes with Cosign's messages). Nothing
 * yet; the pencil shows what starting one will offer (someone, a group, a code to scan), not working yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesPane() = Box(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
    androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Bottom)), contentAlignment = Alignment.Center) {
    var starting by remember { mutableStateOf(false) }
    Text("Nothing yet.", color = Vault.Muted)
    Box(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(52.dp).clip(RoundedCornerShape(18.dp)).background(Vault.AccentGround)
        .clickable(onClickLabel = "New message") { starting = true }, contentAlignment = Alignment.Center) {
        Icon(Glyphs.pencil, null, tint = Vault.Accent, modifier = Modifier.size(22.dp))
    }
    if (starting) androidx.compose.material3.ModalBottomSheet({ starting = false }, containerColor = Vault.Surface) {
        for ((icon, label) in listOf(Icons.Filled.Person to "Message someone", Glyphs.site to "New group", Icons.Filled.Search to "Scan a code")) {
            Row(Modifier.fillMaxWidth().clickable { starting = false }.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Vault.Muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(18.dp))
                Text(label, color = Vault.Muted, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The panes: Following (your people and the news, and the one place to post), Search (home: yours) and Messages.
 * Three quiet words; swipe between them, or tap one. Your picture is on every pane.
 */
@Composable
private fun Panes(phone: Phone, ui: Ui, social: Social, nav: Nav, pager: androidx.compose.foundation.pager.PagerState, feedState: LazyListState, place: FollowingPlace,
                  covered: Boolean = false, readerState: LazyListState = rememberLazyListState()) {
    val people by People.known.collectAsState()
    val me = ui.member!!.group.groupX
    val scope = rememberCoroutineScope()
    // The feed log (when on): which pane you settle on, for time per pane.
    LaunchedEffect(pager.settledPage, covered) { if (!covered) cc.aaro.cosign.FeedLog.ev("pane", "to" to when (pager.settledPage) { PANE_FOLLOWING -> "following"; PANE_MESSAGES -> "messages"; else -> "search" }) }
    Column(Modifier.fillMaxSize()
        .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))) {
        // Home's one place word top left (a tap, or a swipe, goes there); on it, the mark goes home. Your picture top right.
        var sheet by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            // The place word: Reader (Following only while the older feed is chosen in Advanced).
            if (pager.currentPage == PANE_SEARCH) Text(if (social.feedVersion.collectAsState().value == 3) "Following" else "Reader", color = Vault.Text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { scope.launch { pager.animateScrollToPage(PANE_FOLLOWING) } }.padding(horizontal = 12.dp, vertical = 12.dp))
            else Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp).clip(CircleShape).semantics { contentDescription = "Search" }
                .clickable(onClickLabel = "Go to Search", role = androidx.compose.ui.semantics.Role.Button) { scope.launch { pager.animateScrollToPage(PANE_SEARCH) } }.padding(10.dp)) { Swirl(28f) }
            // 48 dp to tap, a button that opens your menu.
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(48.dp).clip(CircleShape)
                .clickable(onClickLabel = "Open your menu", role = androidx.compose.ui.semantics.Role.Button) { sheet = true }, contentAlignment = Alignment.Center) {
                Avatar(people[me], me, 32, Modifier, "Your picture")
            }
        }
        if (sheet) PictureSheet(people[me], me, nav) { sheet = false }
        val wanted by PaneWanted.collectAsState()
        LaunchedEffect(wanted) { wanted?.let { pager.animateScrollToPage(it); PaneWanted.value = null } }
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            when (page) {
                PANE_FOLLOWING -> if (social.feedVersion.collectAsState().value == 4) ReaderPane(social, nav, current = pager.settledPage == PANE_FOLLOWING && !covered, state = readerState)
                    else FeedScreen(social, nav, pane = true, current = pager.settledPage == PANE_FOLLOWING && !covered, listState = feedState, place = place)
                PANE_MESSAGES -> MessagesPane()
                else -> Home(phone, ui, social, nav)
            }
        }
    }
}

/** The bottom of the Search pane: your notes, a swipe up (or a tap) away. */
@Composable
private fun NotesLip(modifier: Modifier, onOpen: () -> Unit) {
    var dragged by remember { mutableStateOf(0f) }
    Column(modifier.fillMaxWidth().clickable(onClickLabel = "Open your notes", onClick = onOpen)
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (dragged < -24.dp.toPx()) onOpen() }) { c, dy -> c.consume(); dragged += dy }
        }.padding(top = 10.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Vault.Line))

    }
}

/** The bottom of home: Discover's lip. Tap it, or swipe it up. */
@Composable
private fun DiscoverLip(posts: Int, special: Int, modifier: Modifier, onOpen: () -> Unit) {
    var dragged by remember { mutableStateOf(0f) }
    Column(modifier.fillMaxWidth().background(Vault.Ground).clickable(onClickLabel = "Open Discover", onClick = onOpen)
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (dragged < -24.dp.toPx()) onOpen() }) { c, dy -> c.consume(); dragged += dy }
        }.padding(top = 10.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Vault.Line))
        Spacer(Modifier.height(8.dp))
        Text(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = Vault.Text, fontWeight = FontWeight.SemiBold)) { append("Discover") }
            append(when {
                posts == 0 -> " · what people liked this week"
                special > 0 -> " · $posts posts, $special from your ★ people"
                else -> " · $posts popular posts"
            })
        }, color = Vault.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** On home, above Notes: the latest things you kept and haven't ticked done, in your own hand (as before the panes). */
@Composable
private fun KeptLately(items: List<cc.aaro.cosign.LaterItem>, social: Social, nav: Nav, max: Int) {
    val open = items.filter { it.deleted == null && it.done == null }
    if (open.isEmpty()) return
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 600.dp).fillMaxWidth()) {
        LaunchedEffect(open.take(max).map { it.id }) { open.take(max).forEach { cc.aaro.cosign.NotesTrial.shown(it.id, "home") } }
        for (item in open.take(max)) Row(Modifier.fillMaxWidth().clickable { cc.aaro.cosign.NotesTrial.opened(item.id, "home"); FeedLog.open("l:" + item.id, "kept"); item.url?.let { openInBrowser(context, it, "l:" + item.id) } ?: nav.results(item.text) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).border(1.5.dp, Vault.Line, CircleShape).clickable(onClickLabel = "Done") { social.later.done(item, true) })
            Spacer(Modifier.width(12.dp))
            item.url?.let { SiteIcon(it, 16); Spacer(Modifier.width(8.dp)) }
            Text(item.text, color = Vault.Text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (item.url == null) androidx.compose.ui.text.font.FontFamily.Monospace else null),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Discover: today's news in one row you open, then what people liked this week (fiatjaf's Pyramid), newest first,
 * with your Special Follow people (★) woven in near the top. Where it comes from is said at the end.
 */
@Composable
private fun FeedScreen(social: Social, nav: Nav, pane: Boolean, current: Boolean = true, listState: LazyListState? = null,
                       place: FollowingPlace = remember { FollowingPlace() }) = Box(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().then(
    if (pane) Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
        androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Bottom)) else Modifier.safeDrawingPadding())) {
    val feed by social.feed.collectAsState()
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    val saved by social.saved.collectAsState()
    val previews by social.previews.collectAsState()
    val muted by social.muted.collectAsState()
    val dismissed by social.dismissed.collectAsState()
    val special by social.special.collectAsState()
    val all by social.allStories.collectAsState()
    // Put-away posts stay where they were (a slim row, with Undo) until the list itself is next refreshed.
    val posts = remember(feed.posts, muted) { social.dismissed.value.let { gone -> feed.posts.filter { it.e.id !in gone && it.e.pubkey !in muted } } }
    val justDismissed by social.justDismissed.collectAsState()
    val state = listState ?: androidx.compose.foundation.lazy.rememberLazyListState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { social.loadFeed() }
    // What's been on screen stops being new: only while this list is what you're looking at.
    if (current) SeenWatcher(state, social) { listOf(it) }
    // All, or only people (those you follow, and your Special Follow list), the news, or the topics you follow.
    var chip by place.chip
    val follows by social.follows.collectAsState()
    val followedTopics by social.followedTopics.collectAsState()
    val shown = when (chip) {
        "people" -> posts.filter { it.reason.startsWith("★") || it.e.pubkey in (follows ?: emptySet()) || it.e.pubkey in special }
        "all" -> posts
        else -> emptyList()
    }
    val stories = remember(all, chip, followedTopics) {
        if (chip != "news" && chip != "topics") emptyList()
        else cc.aaro.cosign.Calm.stories(all, social.interests, cc.aaro.cosign.core.Clock.now(), window = Long.MAX_VALUE)
            .filter { chip == "news" || it.topic in followedTopics }.take(60)
    }
    val savedLinks by social.saved.collectAsState()
    // What's kept in Notes, by link: the bookmark under a post or story in Following is filled for these.
    val laterItems by social.later.items.collectAsState()
    val keptUrls = remember(laterItems) { laterItems.filter { it.deleted == null }.mapNotNull { it.url }.toSet() }
    // Following: your people's posts with the news in among them, a story after every third post (the news topic only,
    // newest first; seen ones stay put until the list is next put together, so nothing vanishes under you).
    // (Feed 3 has no news between posts: not worked out there at all.)
    val newsWanted = pane && chip == "all" && social.feedVersion.collectAsState().value != 3
    val news = remember(all, newsWanted) { if (!newsWanted) emptyList() else social.dismissed.value.let { gone -> cc.aaro.cosign.Calm.stories(all, social.interests, cc.aaro.cosign.core.Clock.now(), window = Long.MAX_VALUE)
        .filter { it.topic == "news" && "t:" + it.id !in gone }.take(40) } }
    // Feed 3 · Edition: what's new from your people (Special Follow first), Today's news, three from beyond, the rest of your people, an end.
    val version by social.feedVersion.collectAsState()
    val edition = pane && version == 3 && chip == "all"
    val beyond by social.beyond.collectAsState()
    val mark by social.editionMark.collectAsState()
    val unfolded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val mixed: List<Any> = remember(shown, news, pane, edition) {
        if (!pane || edition) shown else buildList { var n = 0; shown.forEachIndexed { i, p -> add(p); if (i % 3 == 2 && n < news.size) add(news[n++]) }; if (shown.isEmpty()) addAll(news.take(10)) }
    }
    // The feed log (when on): each list by its pane and chip, so they can be told apart, and only while this pane is
    // the one on screen (the pager keeps it composed beside Search).
    val logIds = remember(chip, mixed, stories, edition, beyond, feed.stories) {
        if (chip == "news" || chip == "topics") stories.map { "t:" + it.id }
        else if (edition) shown.map { it.e.id } + feed.stories.take(4).map { "t:" + it.id } + beyond.map { it.e.id }  // (the order on screen is set below)
        else mixed.map { if (it is cc.aaro.cosign.TopicCard) "t:" + it.id else (it as cc.aaro.cosign.Pick).e.id }
    }
    if (current) androidx.compose.runtime.key(chip) { LogWatcher(state, if (pane) "following/$chip" else "discover", logIds) }
    LaunchedEffect(chip, stories, current) { if (current && stories.isNotEmpty()) social.logStoryList("following/$chip", stories) }
    if (!pane) Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.Filled.KeyboardArrowDown, "Back home", tint = Vault.Text) }
        // The name: refresh, as pulling down did.
        Text("Discover", style = MaterialTheme.typography.headlineSmall, color = Vault.Text, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).clickable(onClickLabel = "Reload") { social.loadFeed(force = true) })
        IconButton({ social.clearSearch(); nav.open(Route.Search) }) { Icon(Icons.Filled.Search, "Search", tint = Vault.Text) }
    }
    // Pull down to refresh (home has nothing to refresh now; this is where it went).
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(feed.loading) { if (!feed.loading) pulled = false }
    @OptIn(ExperimentalMaterial3Api::class)
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(pulled && feed.loading, { pulled = true; social.loadFeed(force = true) }, Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (chip == "news" || chip == "topics") {
            if (stories.isEmpty()) item(key = "nostories") { Text(if (chip == "topics") "Follow a topic from a story's ⋯ to see it here." else "No stories just now.",
                color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
            items(stories, key = { "t:" + it.id }) { c -> StoryRow(c, social, c.url in savedLinks, nav); HorizontalDivider(color = Vault.Edge) }
        }
        if (chip == "all" && !pane) item(key = "news") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Vault.Surface)
                .border(1.dp, Vault.Edge, RoundedCornerShape(14.dp)).clickable { nav.open(Route.Stories) }.padding(horizontal = 16.dp, vertical = 13.dp)) {
                Text("Today's news", color = Vault.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (all.isEmpty()) "›" else "${all.size} stories ›", color = Vault.Muted)
            }
        }
        if (feed.failed) item(key = "failed") {
            Column(Modifier.padding(24.dp)) {
                Text("Discover isn't answering right now.", color = Vault.Text)
                TextButton({ social.loadFeed(force = true) }, contentPadding = PaddingValues(0.dp)) { Text("Try again", color = Vault.Accent) }
            }
        }
        if (chip != "news" && chip != "topics" && posts.isEmpty() && !feed.failed) item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
        }
        if (shown.isNotEmpty() && !pane) item(key = "from") {
            Text(if (special.isEmpty()) "Popular this week" else "Popular this week · ★ your Special Follow people", style = MaterialTheme.typography.bodyMedium, color = Vault.Muted,
                modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 10.dp))
        }
        if (edition) {
            // Feed 3 · Edition (the user's design, 28 Oct): three short sections, each a few compact rows of one fixed height
            // (pictures can't push the page down) and a More that opens the section's whole list, full-size. Special Follow,
            // then Today's news, then your people (newest first) with beyond your people at its end. Nothing is reordered.
            val starred = shown.filter { it.reason.startsWith("★") }
            val others = shown.filter { !it.reason.startsWith("★") }
            // (Beyond your people is off: strangers belong in Search, not in your chosen sources.)
            val outside = emptyList<Pick>()
            // Relays, a tap away (the sections below are as they were).
            item(key = "s:relays") {
                Text("Relays ›", color = Vault.Accent, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.fillMaxWidth().clickable { nav.open(Route.RelayList) }.padding(horizontal = 18.dp, vertical = 6.dp))
            }
            if (starred.isNotEmpty()) item(key = "s:special") {
                val fresh = starred.count { mark > 0 && it.e.createdAt > mark }
                SectionCard("Special Follow", if (fresh > 0) "$fresh new" else null) {
                    starred.take(3).forEach { p -> CompactPost(p, people, nav) }
                    if (starred.size > 3) SectionMore("More from Special Follow (${starred.size - 3})") { nav.open(Route.Section("special")) }
                }
            }
            if (feed.stories.isNotEmpty()) item(key = "s:today") { TodayBlock(feed.stories.take(3), social, nav, justDismissed) }
            if (others.isNotEmpty() || outside.isNotEmpty()) item(key = "s:people") {
                SectionCard("Your people", "newest first") {
                    others.take(3).forEach { p -> CompactPost(p, people, nav) }
                    if (others.size > 3) SectionMore("More from your people (${others.size - 3})") { nav.open(Route.Section("people")) }
                    if (outside.isNotEmpty()) {
                        Text("BEYOND YOUR PEOPLE", color = Vault.Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 0.08.em,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
                        outside.take(2).forEach { p -> CompactPost(p, people, nav, why = p.reason) }
                        if (outside.size > 2) SectionMore("More beyond (${outside.size - 2})") { nav.open(Route.Section("beyond")) }
                    }
                }
            }
            if (shown.isEmpty() && !feed.loading) item(key = "s:none") { Text("Nothing from your people in the last two days.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
        }
        if (!edition) items(mixed, key = { if (it is cc.aaro.cosign.TopicCard) "t:" + it.id else (it as cc.aaro.cosign.Pick).e.id }) { x ->
            HorizontalDivider(color = Vault.Edge)
            val key = if (x is cc.aaro.cosign.TopicCard) "t:" + x.id else (x as cc.aaro.cosign.Pick).e.id
            // Put away just now: a slim row where it was, with Undo (nothing moves up into its place).
            justDismissed[key]?.let { d -> return@items PutAway(d.label) { social.undoDismiss(key) } }
            if (key in dismissed) return@items
            if (x is cc.aaro.cosign.TopicCard) return@items NewsCard(x, social, nav)
            val p = x as cc.aaro.cosign.Pick
            NoteCard(p.e, people, p.e.id in liked, nav, onLike = { social.like(p.e) }, maxLines = 7, pick = p, previews = previews,
                saved = if (pane) social.postLink(p.e) in keptUrls else p.e.id in saved,
                onSave = { if (pane) social.keepPost(p.e, people[p.e.pubkey]?.shown ?: "A post") else social.save(p.e.id, isLink = false) },
                onShare = if (!pane) null else ({ FeedLog.act(p.e.id, "share"); share(context, p.e.content.trim().take(280) + "\n\n" + social.postLink(p.e), null) })) { PostMenu(p, people[p.e.pubkey]?.shown ?: shortNpub(p.e.pubkey), social) }
        }
        if (shown.isNotEmpty() && !feed.loading && !pane) item(key = "end") {
            HorizontalDivider(color = Vault.Edge)
            Text("That's everything for now. New posts show up through the day.", color = Vault.Field, style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp))
        }
        // Where it comes from, plainly (Discover only; Following is just your people and the news).
        if (!pane) item(key = "source") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("Posts come from Popular, by fiatjaf's Pyramid: what its members liked or replied to this week.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                if (special.isNotEmpty()) Text("Woven in: your Special Follow list (${special.size} people), the list Jumble keeps on your account.", color = Vault.Muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item(key = "room") { Spacer(Modifier.height(72.dp)) }
    }
    // New posts above you: counted, never jumped to. Seen at the top, they stop being new.
    val ids = shown.map { it.e.id }
    if (place.known == null && ids.isNotEmpty()) place.known = ids.toSet()
    val atTop by remember(state) { androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(atTop, ids) { if (atTop) { place.known = ids.toSet(); if (edition) shown.maxOfOrNull { it.e.createdAt }?.let(social::editionSeen) } }
    val firstShown by remember(state) { androidx.compose.runtime.derivedStateOf { state.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.let { k -> cc.aaro.qrst.core.isHex32(k) } == true }?.key as? String } }
    val fresh = remember(ids, firstShown, atTop) {
        val i = ids.indexOf(firstShown)
        if (atTop || i <= 0) 0 else ids.take(i).count { it !in (place.known ?: emptySet()) }
    }
    val scope = rememberCoroutineScope()
    if (fresh > 0 && (chip == "all" || chip == "people")) Text("$fresh new ↑", color = Vault.Ground, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(Vault.Accent)
            .clickable(onClickLabel = "Go up to the new posts") { scope.launch { state.animateScrollToItem(0) } }.padding(horizontal = 14.dp, vertical = 7.dp))
    }
    }
    // The one place to post: public, and the composer says so.
    if (pane) Box(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
        .padding(18.dp).size(52.dp).clip(RoundedCornerShape(18.dp)).background(Vault.AccentGround).clickable(onClickLabel = "New public post") { nav.compose() }, contentAlignment = Alignment.Center) {
        Icon(Glyphs.pencil, null, tint = Vault.Accent, modifier = Modifier.size(22.dp))
    }
}

/** A story's ⋯: more or less like it, its topic, its source; share or keep it (as Google Discover's). Each is learned and logged. */
@Composable
private fun StoryMenu(c: cc.aaro.cosign.TopicCard, social: Social, onClose: () -> Unit) {
    val context = LocalContext.current

    val topic = remember(c.id) { social.storyTopic(c) }
    val topicName = TopicFeeds.name(topic)
    val followed = topic in social.followedTopics.collectAsState().value
    val kept = social.later.items.collectAsState().value.any { it.url == c.url && it.deleted == null }
    DropdownMenu(true, onClose, containerColor = Vault.Surface) {
        DropdownMenuItem({ Text("More like this", color = Vault.Text) }, { onClose(); social.moreLike(topic, key = "t:" + c.id) })
        DropdownMenuItem({ Text("Not interested", color = Vault.Text) }, { onClose(); social.notInterested(c) })
        HorizontalDivider(color = Vault.Edge)
        DropdownMenuItem({ Text(if (followed) "Stop following $topicName" else "Follow $topicName", color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            { onClose(); social.followTopic(topic, !followed) })
        DropdownMenuItem({ Text("Fewer stories about $topicName", color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { onClose(); social.fewerAbout(topic, "t:" + c.id) })
        HorizontalDivider(color = Vault.Edge)
        DropdownMenuItem({ Text("Hide ${c.sourceName}", color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { onClose(); social.hideSource(c) })
        HorizontalDivider(color = Vault.Edge)
        DropdownMenuItem({ Text("Share", color = Vault.Text) }, { onClose(); FeedLog.act("t:" + c.id, "share"); share(context, c.url, c.title) })
        // Save: into your notes (private), as the bookmark on a page does.
        DropdownMenuItem({ Text(if (kept) "Saved" else "Save", color = Vault.Text) }, { onClose(); if (!kept) social.keepStory(c) })
    }
}

/**
 * Feed 3's Today: about four headlines from your topics in one block, compact rows (headline left, small square picture
 * right). Opened ones go grey; once you've read them all the block is one row. Held: folded; ⋯: the story's menu.
 */
@Composable
private fun TodayBlock(stories: List<cc.aaro.cosign.TopicCard>, social: Social, nav: Nav, justDismissed: Map<String, cc.aaro.cosign.Social.Dismissal>) {
    if (stories.isEmpty()) return
    val read = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    fun isRead(c: cc.aaro.cosign.TopicCard) = read[c.id] ?: social.storyRead(c)
    var open by remember { mutableStateOf(false) }
    val allRead = stories.all(::isRead)
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Vault.Surface)
        .border(1.dp, Vault.Edge, RoundedCornerShape(16.dp))) {
        if (allRead && !open) {
            Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Today", color = Vault.Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("Read · show", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
            }
            return@Column
        }
        Text("Today", color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 14.dp, top = 11.dp, bottom = 6.dp))
        stories.forEachIndexed { i, c ->
            HorizontalDivider(color = Vault.Edge)
            justDismissed["t:" + c.id]?.let { d -> PutAway(d.label) { social.undoDismiss("t:" + c.id) } }
                ?: TodayRow(c, social, nav, isRead(c)) { read[c.id] = true }
        }
        SectionMore("More stories") { nav.open(Route.Stories) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TodayRow(c: cc.aaro.cosign.TopicCard, social: Social, nav: Nav, read: Boolean, onRead: () -> Unit) {
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val folded = c.id in cc.aaro.cosign.FoldedStories.ids.collectAsState().value
    val fold = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); cc.aaro.cosign.FoldedStories.toggle(c.id) }
    val open = { onRead(); social.opened(c); openInBrowser(context, c.url, "t:" + c.id) }
    if (folded) return Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = fold, onClick = open).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(c.title, color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    val topic = remember(c.id) { cc.aaro.cosign.TopicFeeds.name(social.storyTopic(c)) }
    Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = fold, onClick = open).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(SpanStyle(color = Vault.Accent)) { append(topic) }
                append(" · " + c.sourceName + if (c.publishedAt > 0) " · " + since(c.publishedAt) else "")
            }, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.title, color = if (read) Vault.Muted else Vault.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(10.dp))
        if (c.image != null) NetImage(c.image, Modifier.padding(top = 4.dp).size(56.dp).clip(RoundedCornerShape(10.dp)), 168, ground = Vault.Edge)
        Box {
            Icon(Icons.Filled.MoreVert, "More", tint = Vault.Muted, modifier = Modifier.size(32.dp).clip(CircleShape).clickable { menu = true }.padding(6.dp))
            if (menu) StoryMenu(c, social) { menu = false }
        }
    }
}


/** One of Feed 3's sections: a title (a quiet note on the right), its rows, its More. Plain (only Today's news is boxed). */
@Composable
private fun SectionCard(title: String, note: String?, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(Modifier.padding(top = 10.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            note?.let { Text(it, color = Vault.Muted, style = MaterialTheme.typography.labelMedium) }
        }
        content()
    }

/** A section's More: its whole list. */
@Composable
private fun SectionMore(label: String, onClick: () -> Unit) {
    HorizontalDivider(color = Vault.Edge)
    Text("$label ›", color = Vault.Accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp))
}

/**
 * A post as one compact row of a fixed height: picture, name and time, two lines of its words, a small square of its
 * first picture. A tap opens it whole. [why]: beyond your people, why it's here.
 */
@Composable
private fun CompactPost(p: cc.aaro.cosign.Pick, people: Map<String, Person>, nav: Nav, why: String? = null) {
    val n = p.e
    val body = remember(n.id) { Body.of(n) }
    // Its words as the post shows them (@names, #tags, short links, "↪ quoted"), pictures left out.
    val words = remember(n.id, people.size) { body.parts.joinToString("") { body.label(it, people) ?: "" }.ifBlank { cc.aaro.cosign.readable(n.content) { people[it]?.shown } } }
    val picture = body.images.firstOrNull()
    val spam = nav.social.spam.collectAsState().value[n.id]
    HorizontalDivider(color = Vault.Edge)
    Row(Modifier.fillMaxWidth().clickable { nav.thread(n.id) }.padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 10.dp)) {
        Avatar(people[n.pubkey], n.pubkey, 28, Modifier.padding(top = if (why != null) 16.dp else 2.dp).clickable { nav.profile(n.pubkey) })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            why?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Vault.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(androidx.compose.ui.text.buildAnnotatedString {
                if (p.reason.startsWith("★")) append("★ ")
                withStyle(SpanStyle(color = Vault.Text, fontWeight = FontWeight.SemiBold)) { append(people[n.pubkey]?.shown ?: shortNpub(n.pubkey)) }
                append(" · " + since(n.createdAt))
                if (spam != null) withStyle(SpanStyle(color = Vault.AmberText)) { append(" · Looks like spam") }
            }, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(words.trim().replace(Regex("\\s+"), " "), color = Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp))
        }
        if (picture != null) {
            Spacer(Modifier.width(10.dp))
            NetImage(picture.url, Modifier.padding(top = 2.dp).size(48.dp).clip(RoundedCornerShape(9.dp)), 144, ground = Vault.Edge)
        }
    }
}

/** A Feed 3 section's whole list, newest first, full-size posts (what its More opens). */
@Composable
private fun SectionScreen(which: String, social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar(when (which) { "special" -> "Special Follow"; "beyond" -> "Beyond your people"; else -> "Your people" }, nav)
    val feed by social.feed.collectAsState()
    val beyond by social.beyond.collectAsState()
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    val previews by social.previews.collectAsState()
    val muted by social.muted.collectAsState()
    val dismissed by social.dismissed.collectAsState()
    val keptUrls = social.later.items.collectAsState().value.filter { it.deleted == null }.mapNotNull { it.url }.toSet()
    val context = LocalContext.current
    val list = when (which) {
        "special" -> feed.posts.filter { it.reason.startsWith("★") }
        "beyond" -> beyond
        else -> feed.posts.filter { !it.reason.startsWith("★") }
    }.filter { it.e.pubkey !in muted && it.e.id !in dismissed }
    val state = rememberLazyListState()
    LogWatcher(state, "following/$which", list.map { it.e.id })
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (list.isEmpty()) item(key = "none") { Text("Nothing here just now.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
        items(list, key = { it.e.id }) { p ->
            HorizontalDivider(color = Vault.Edge)
            NoteCard(p.e, people, p.e.id in liked, nav, onLike = { social.like(p.e) }, maxLines = 12, pick = p, previews = previews, wide = true,
                saved = social.postLink(p.e) in keptUrls, onSave = { social.keepPost(p.e, people[p.e.pubkey]?.shown ?: "A post") },
                onShare = { FeedLog.act(p.e.id, "share"); share(context, p.e.content.trim().take(280) + "\n\n" + social.postLink(p.e), null) }) {
                PostMenu(p, people[p.e.pubkey]?.shown ?: shortNpub(p.e.pubkey), social)
            }
        }
    }
}

/** A quiet label between Feed 3's parts. */
@Composable
private fun SectionLine(label: String) = Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
    HorizontalDivider(Modifier.weight(1f), color = Vault.Edge)
    Text(label, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp))
    HorizontalDivider(Modifier.weight(1f), color = Vault.Edge)
}

/** A thin line where you'd got to last time. */
@Composable
private fun EarlierLine() = Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    HorizontalDivider(Modifier.weight(1f), color = Vault.Edge)
    Text("Earlier", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp))
    HorizontalDivider(Modifier.weight(1f), color = Vault.Edge)
}

/** Something put away in Following: what was done, quietly, and Undo. */
@Composable
private fun PutAway(label: String, onUndo: () -> Unit) = Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    TextButton(onUndo) { Text("Undo", color = Vault.Accent, style = MaterialTheme.typography.labelLarge) }
}

/** The phone's share sheet, with [text] (and [subject]). When Messages works, your contacts come first ("Send to…"). */
private fun share(context: android.content.Context, text: String, subject: String?) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    subject?.let { send.putExtra(android.content.Intent.EXTRA_SUBJECT, it) }
    try { context.startActivity(android.content.Intent.createChooser(send, null)) } catch (_: Exception) {}
}

/**
 * A news story among your people's posts: its picture, where it's from and when, and the headline. Under it, a heart
 * (a tap: the six reactions; sent as a reaction to the page) and a comment (a public post with the story's link).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NewsCard(c: cc.aaro.cosign.TopicCard, social: Social, nav: Nav) {
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val mine = social.pageReactions.collectAsState().value[c.url]
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Held: folded into one quiet row ("seen it, keep it"); a tap on the row opens the story, holding it again unfolds it.
    val folded = c.id in cc.aaro.cosign.FoldedStories.ids.collectAsState().value
    val fold = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); cc.aaro.cosign.FoldedStories.toggle(c.id) }
    val open = { social.opened(c); openInBrowser(context, c.url, "t:" + c.id) }
    if (folded) return Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = fold, onClick = open).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NetImage(c.image, Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)), 96, ground = Vault.Surface)
        Spacer(Modifier.width(12.dp))
        Text(c.title, color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(c.sourceName, color = Vault.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
    Column(Modifier.fillMaxWidth().combinedClickable(onLongClick = fold, onClick = open).padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        if (c.image != null) NetImage(c.image, Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)), 900, ground = Vault.Surface)
        Row(Modifier.padding(top = if (c.image != null) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.sourceName + if (c.publishedAt > 0) " · " + since(c.publishedAt) else "", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            // ⋯: more or less like this, or nothing more from this source (each learned, and logged).
            Box {
                Icon(Icons.Filled.MoreVert, "More", tint = Vault.Muted, modifier = Modifier.size(32.dp).clip(CircleShape).clickable { menu = true }.padding(6.dp))
                if (menu) StoryMenu(c, social) { menu = false }
            }
        }
        Text(c.title, color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.fillMaxWidth().offset(x = (-9).dp).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "React") { picking = !picking }, contentAlignment = Alignment.Center) {
                when (mine) {
                    null -> Icon(Glyphs.heart, "React", tint = Vault.Muted, modifier = Modifier.size(20.dp))
                    "❤️" -> Icon(Glyphs.heartOn, "You reacted ❤️", tint = Vault.Accent, modifier = Modifier.size(20.dp))
                    else -> Text(mine, fontSize = 18.sp)
                }
            }
            if (picking) Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(20.dp)).padding(horizontal = 4.dp)) {
                for (e in social.topEmojis().filter { it.url == null }) Box(Modifier.size(36.dp).clip(CircleShape).clickable { picking = false; social.reactToPage(c.url, cc.aaro.cosign.ReactionRules.wire(e.emoji)) }, contentAlignment = Alignment.Center) { Text(e.emoji, fontSize = 20.sp) }
            } else {
                Icon(Glyphs.reply, "Comment", tint = Vault.Muted, modifier = Modifier.size(40.dp).clip(CircleShape).clickable { nav.compose(c.url) }.padding(10.dp))
                // Repost a story: a public post that quotes its link (there's no plain repost of a page). Held, the same, with a tick.
                Icon(Glyphs.repost, "Quote it", tint = Vault.Muted, modifier = Modifier.size(40.dp).clip(CircleShape).combinedClickable(onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    FeedLog.act("t:" + c.id, "quote"); nav.compose(c.url)
                }) { FeedLog.act("t:" + c.id, "quote"); nav.compose(c.url) }.padding(10.dp))
            }
            // Share it with someone; keep it in Notes (filled once it's there; again, not).
            Spacer(Modifier.weight(1f))
            Icon(Glyphs.share, "Share", tint = Vault.Muted, modifier = Modifier.offset(x = 18.dp).size(40.dp).clip(CircleShape)
                .clickable { FeedLog.act("t:" + c.id, "share"); share(context, c.url, c.title) }.padding(11.dp))
            val kept = social.later.items.collectAsState().value.any { it.url == c.url && it.deleted == null }
            Icon(if (kept) Glyphs.saveOn else Glyphs.save, if (kept) "Kept in your notes" else "Keep in your notes", tint = if (kept) Vault.Accent else Vault.Muted,
                modifier = Modifier.offset(x = 18.dp).size(40.dp).clip(CircleShape).clickable { social.keepStory(c) }.padding(10.dp))
        }
    }
}

/**
 * What's been on screen properly (most of it, or most of the screen, for a second and a half) doesn't
 * come back. [keys]: what a list item stands for (a post's id; the stories box, its stories).
 */
@Composable
private fun SeenWatcher(state: LazyListState, social: Social, keys: (String) -> List<String>) = LaunchedEffect(state) {
    val since = HashMap<String, Long>()
    while (true) {
        delay(250)
        val info = state.layoutInfo
        val top = info.viewportStartOffset; val bottom = info.viewportEndOffset
        val now = android.os.SystemClock.uptimeMillis()
        val showing = info.visibleItemsInfo.mapNotNull { v ->
            val k = (v.key as? String)?.takeIf { it == "stories" || it.startsWith("t:") || cc.aaro.qrst.core.isHex32(it) } ?: return@mapNotNull null
            val shown = minOf(v.offset + v.size, bottom) - maxOf(v.offset, top)
            k.takeIf { v.size > 0 && shown >= 0.6f * minOf(v.size, bottom - top) }
        }.toSet()
        since.keys.retainAll(showing)
        for (k in showing) if (now - since.getOrPut(k) { now } >= 1500) keys(k).forEach(social::saw)
    }
}

/**
 * The feed log (when it's on): each post or story that was half on screen or more, for how long and at what place
 * in [ids] (the list's order), and how you scrolled: where the top was, how fast, how far down you've been. Only
 * while Marble is in front.
 */
@Composable
private fun LogWatcher(state: LazyListState, screen: String, ids: List<String>) {
    if (!FeedLog.on.collectAsState().value) return
    val ranks by androidx.compose.runtime.rememberUpdatedState(remember(ids) { ids.withIndex().associate { it.value to it.index } })
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        class Showing(val since: Long, var most: Float, val rank: Int)
        val showing = HashMap<String, Showing>()
        val began = android.os.SystemClock.uptimeMillis()
        var deepest = -1; var lastPos = -1f; var lastAt = 0L; var moving = false
        fun close(k: String, now: Long) = showing.remove(k)?.let { s ->
            if (now - s.since >= 300) FeedLog.ev("imp", "id" to k, "screen" to screen, "rank" to s.rank, "ms" to now - s.since, "most" to s.most)
        }
        FeedLog.ev("view", "screen" to screen, "on" to true)
        try {
            while (true) {
                delay(250)
                val info = state.layoutInfo
                val top = info.viewportStartOffset; val bottom = info.viewportEndOffset
                val now = android.os.SystemClock.uptimeMillis()
                val r = ranks
                val visible = HashMap<String, Float>()
                for (v in info.visibleItemsInfo) {
                    val k = v.key as? String ?: continue
                    if (k !in r || v.size <= 0) continue
                    // Half of it, or (a post taller than the screen) half the screen.
                    val f = (minOf(v.offset + v.size, bottom) - maxOf(v.offset, top)).toFloat() / minOf(v.size, bottom - top)
                    if (f >= 0.5f) visible[k] = f.coerceAtMost(1f)
                }
                for (k in showing.keys.toList()) if (k !in visible) close(k, now)
                for ((k, f) in visible) { val s = showing.getOrPut(k) { Showing(now, f, r[k]!!) }; if (f > s.most) s.most = f }
                // Scrolling: once a second while it moves, and where it stopped.
                val first = visible.keys.minOfOrNull { r[it]!! } ?: continue
                val item = info.visibleItemsInfo.firstOrNull()
                val pos = state.firstVisibleItemIndex + (item?.let { state.firstVisibleItemScrollOffset.toFloat() / it.size.coerceAtLeast(1) } ?: 0f)
                deepest = maxOf(deepest, visible.keys.maxOf { r[it]!! })
                if (lastPos < 0) { lastPos = pos; lastAt = now }
                if (state.isScrollInProgress && now - lastAt >= 1000) {
                    FeedLog.ev("scroll", "screen" to screen, "top" to first, "deep" to deepest, "speed" to (pos - lastPos) * 1000f / (now - lastAt))
                    lastPos = pos; lastAt = now; moving = true
                } else if (!state.isScrollInProgress && (moving || kotlin.math.abs(pos - lastPos) > 0.5f)) {
                    FeedLog.ev("scroll", "screen" to screen, "top" to first, "deep" to deepest, "speed" to (pos - lastPos) * 1000f / (now - lastAt).coerceAtLeast(1), "stop" to true)
                    lastPos = pos; lastAt = now; moving = false
                }
            }
        } finally {
            val now = android.os.SystemClock.uptimeMillis()
            for (k in showing.keys.toList()) close(k, now)
            FeedLog.ev("view", "screen" to screen, "on" to false, "ms" to now - began, "deep" to deepest)
        }
    } }
}

/** Today's stories: a few headlines at the top, each with its topic, site and age. "More stories" for the rest. */
@Composable
private fun StoryBox(stories: List<TopicCard>, social: Social, saved: Set<String>, nav: Nav, onMore: () -> Unit) = Column(
    Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(18.dp)).padding(vertical = 4.dp),
) {
    Text("Today's stories", color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
    stories.forEachIndexed { i, s ->
        if (i > 0) HorizontalDivider(color = Vault.Edge)
        StoryRow(s, social, s.url in saved, nav)
    }
    HorizontalDivider(color = Vault.Edge)
    Text("More stories ›", color = Vault.Muted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(horizontal = 16.dp, vertical = 12.dp))
}

/** One story: topic · site · age, the headline, its picture; tap to read it on the site. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StoryRow(c: TopicCard, social: Social, saved: Boolean, nav: Nav) {
    val context = LocalContext.current
    // A long press: its menu, as the ⋯ opens it.
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { social.opened(c); openInBrowser(context, c.url, "t:" + c.id) }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = Vault.Accent)) { append(TopicFeeds.name(c.topic)) }
                append(" · " + c.sourceName + if (c.publishedAt > 0) " · " + since(c.publishedAt) else "")
            }, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.title, color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Discussions(c.discussions, "t:" + c.id) { site -> social.openedComments(c, site) }
        }
        c.image?.let { NetImage(it, Modifier.padding(start = 12.dp).size(64.dp).clip(RoundedCornerShape(10.dp)).background(Vault.Edge), 256) }
        StoryMenu(c, social, saved, menu, { menu = it }) { nav.compose(c.url) }
    }
}

/**
 * Where a story is being talked about, each a tap away from the article: "💬 312 comments on Hacker News",
 * or, when it's in several places, "Discussed on Hacker News · Stacker News".
 */
@Composable
private fun Discussions(list: List<cc.aaro.cosign.Discussion>, item: String? = null, onOpen: (String) -> Unit = {}) {
    if (list.isEmpty()) return
    val context = LocalContext.current
    val style = MaterialTheme.typography.labelMedium
    if (list.size == 1) {
        val d = list[0]
        Text("💬 " + (d.comments?.let { "$it comment${if (it == 1L) "" else "s"} on " } ?: "Comments on ") + d.site, color = Vault.Muted, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable { onOpen(d.site); openInBrowser(context, d.url, item) }.padding(vertical = 2.dp))
        return
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("💬 Discussed on ", color = Vault.Muted, style = style, maxLines = 1)
        list.forEachIndexed { i, d ->
            if (i > 0) Text(" · ", color = Vault.Muted, style = style)
            Text(d.site + (d.comments?.let { " ($it)" } ?: ""), color = Vault.Accent, style = style, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpen(d.site); openInBrowser(context, d.url, item) }.padding(vertical = 2.dp))
        }
    }
}

/** "⋯" on a story: follow its topic, more or less like this, hide the site, save it. */
@Composable
private fun StoryMenu(c: TopicCard, social: Social, saved: Boolean, open: Boolean, setOpen: (Boolean) -> Unit, onPost: () -> Unit) = Box {
    val followed = c.topic in social.followedTopics.collectAsState().value
    val name = TopicFeeds.name(c.topic)
    Icon(Icons.Filled.MoreVert, "More", tint = Vault.Muted, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { setOpen(true) }.padding(5.dp))
    if (open) DropdownMenu(true, { setOpen(false) }, containerColor = Vault.Surface) {
        DropdownMenuItem({ Text("Post with a comment", color = Vault.Accent, fontWeight = FontWeight.SemiBold) }, { setOpen(false); onPost() },
            leadingIcon = { Icon(Glyphs.pencil, null, tint = Vault.Accent, modifier = Modifier.size(20.dp)) })
        DropdownMenuItem({ Text(if (followed) "Stop following “$name”" else "Follow “$name”", color = Vault.Text) }, { setOpen(false); social.followTopic(c.topic, !followed) })
        DropdownMenuItem({ Text("More like this", color = Vault.Text) }, { setOpen(false); social.moreLike(c.topic, key = "t:" + c.id) })
        DropdownMenuItem({ Text("Less like this", color = Vault.Text) }, { setOpen(false); social.lessLike(c.topic, "t:" + c.id) })
        DropdownMenuItem({ Text("Hide stories from ${c.sourceName}", color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { setOpen(false); social.hideSource(c) })
        DropdownMenuItem({ Text(if (saved) "Saved" else "Save", color = Vault.Text) }, { setOpen(false); social.save(c.url, isLink = true) })
    }
}

/** "⋯" on a post: follow its topic, more or less like this, hide the person; report. */
@Composable
private fun PostMenu(p: Pick, name: String, social: Social) = Box {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val followed = p.topic in social.followedTopics.collectAsState().value
    val topic = TopicFeeds.name(p.topic)
    Icon(Icons.Filled.MoreVert, "More", tint = Vault.Muted, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { open = true }.padding(5.dp))
    if (open) DropdownMenu(true, { open = false }, containerColor = Vault.Surface) {
        // Its topic, when it has one (a post Marble couldn't place has none, and so no "Follow" without a name).
        if (p.topic.isNotBlank() && topic.isNotBlank()) DropdownMenuItem({ Text(if (followed) "Stop following “$topic”" else "Follow “$topic”", color = Vault.Text) }, { open = false; social.followTopic(p.topic, !followed) })
        DropdownMenuItem({ Text("More like this", color = Vault.Text) }, { open = false; social.moreLike(p.topic, p.e) })
        DropdownMenuItem({ Text("Less like this", color = Vault.Text) }, { open = false; social.lessLike(p.topic, p.e.id, p.e) })
        DropdownMenuItem({ Text("Hide posts from $name", color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { open = false; social.hidePerson(p.e.pubkey, p.e.id) })
        DropdownMenuItem({ Text("Copy link", color = Vault.Text) }, { open = false; FeedLog.act(p.e.id, "copyLink"); copy(context, social, webLink(social, p.e), "Link copied") })
        DropdownMenuItem({ Text("Report…", color = Vault.Danger) }, { open = false; reporting = true })
    }
    if (reporting) ReportDialog(name, onClose = { reporting = false }) { reason -> reporting = false; social.report(p.e, reason) }
}

/** All of today's stories, the topics you follow first; ones you've seen aren't here. Back goes to the feed where you were. */
@Composable
private fun StoriesScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar("Today's stories", nav)
    // Which stories this list showed, kept while you read one, so coming back finds the same list in the same place.
    var ids by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<List<String>?>(null) }
    var all by remember { mutableStateOf(ids?.let { known -> social.allStories.value.associateBy { it.id }.let { byId -> known.mapNotNull { byId[it] } } }?.ifEmpty { null }) }
    LaunchedEffect(Unit) {
        if (all != null) return@LaunchedEffect
        val known = ids
        all = if (known == null) social.unseenStories().also { l -> ids = l.map { it.id } }
            else social.storiesById(known).ifEmpty { social.unseenStories() }
    }
    val dismissed by social.dismissed.collectAsState()
    val saved by social.saved.collectAsState()
    val list = remember(all, dismissed) { all?.filter { "t:" + it.id !in dismissed } ?: emptyList() }
    val state = rememberLazyListState()
    SeenWatcher(state, social) { listOf(it) }
    LogWatcher(state, "stories", list.map { "t:" + it.id })
    if (all == null) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
    else if (list.isEmpty()) Text("You've seen all of today's stories. New ones come through the day.", color = Vault.Muted, modifier = Modifier.padding(20.dp))
    val stale by TopicFeeds.stale.collectAsState()
    if (all != null && stale) Text("Couldn't get the newest stories just now; these are from earlier.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { "t:" + it.id }) { s ->
            StoryRow(s, social, s.url in saved, nav)
            HorizontalDivider(color = Vault.Edge)
        }
    }
}

/** Which marble is big: today's (null), or one you kept, with its day. */
private data class BigMarble(val from: androidx.compose.ui.geometry.Offset, val marble: Marble? = null, val date: java.time.LocalDate? = null)

/**
 * The marble, full screen and still: large on the app's ground (the light or dark render), grown from the
 * header where it was tapped, leaning a little as the phone tilts. Pinch to peek closer (as far as the render has
 * detail); let go and it springs back. A tap or back closes it.
 * With animations off on the phone (Remove animations), it simply appears, and doesn't move.
 */
@Composable
private fun MarbleView(big: BigMarble, social: Social, onShelf: () -> Unit, onClose: () -> Unit) {
    val from = big.from
    val context = LocalContext.current
    val still = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val shown = remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (!still) shown.animateTo(1f, androidx.compose.animation.core.tween(340, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val close: () -> Unit = { scope.launch { if (!still) shown.animateTo(0f, androidx.compose.animation.core.tween(220)); onClose() } }
    BackHandler(onBack = close)
    // Tilt: how far the phone has leaned since the view opened, smoothed, a few dp at most. From gravity, which
    // stays steady however the phone is held (angles from the rotation vector jump about when it's upright,
    // which pushed the marble off centre).
    var lean by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    if (!still) androidx.compose.runtime.DisposableEffect(Unit) {
        val sm = context.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var base: FloatArray? = null
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val g = e.values
                val b = base ?: g.copyOf().also { base = it }
                val tx = ((b[0] - g[0]) / 9.81f / 0.35f).coerceIn(-1f, 1f)      // left and right
                val ty = ((g[2] - b[2]) / 9.81f / 0.35f).coerceIn(-1f, 1f)      // towards and away
                lean = lean + (androidx.compose.ui.geometry.Offset(tx, ty) - lean) * 0.12f
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }
    var centre by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // A peek, while two fingers pinch: how much closer (1: not), and where the fingers took it (from the centre, in pixels).
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val d = androidx.compose.ui.platform.LocalDensity.current
    val today = Marbles.current()
    // No marble today (and none of yours asked for): nothing to show.
    val marble = big.marble ?: today ?: run { LaunchedEffect(Unit) { onClose() }; return }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Vault.Ground)
        .onGloballyPositioned { centre = it.boundsInRoot().center }, contentAlignment = Alignment.Center) {
        // The picture has room round the marble (its shadow): the marble itself ends up about four fifths of the width.
        // As big as the screen allows, but no bigger than the render has detail for (the marble is 0.556 of the
        // picture; shown up to 1.3 times its size in the render, no more).
        val size = minOf(minOf(maxWidth, maxHeight) * 1.45f, with(d) { (marble.detail * 2 * 1.3f / 0.556f).toDp() })
        val sizePx = with(d) { size.toPx() }
        val start = with(d) { (72f * 1.8f).dp.toPx() } / sizePx       // the header's marble, the same picture
        val leanPx = with(d) { 8.dp.toPx() }
        // Closer, up to twice the render's own detail (the view already shows it at up to 1.3 times; at 1:1 there'd be no peek at all).
        val mid = with(d) { androidx.compose.ui.geometry.Offset(maxWidth.toPx() / 2f, maxHeight.toPx() / 2f) }
        val most = ((marble.detail * 2 * 2f / 0.556f) / sizePx).coerceAtLeast(1f)
        // Let go: back to where it was, with a little bounce (or at once, with animations off).
        fun back() = scope.launch {
            val z0 = zoom; val p0 = pan
            if (still) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero; return@launch }
            androidx.compose.animation.core.animate(0f, 1f, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 380f)) { t, _ ->
                zoom = z0 + (1f - z0) * t; pan = p0 * (1f - t)
            }
        }
        Box(Modifier.fillMaxSize()
            // A tap closes.
            .pointerInput(Unit) { detectTapGestures(onTap = { close() }) }
            // Two fingers: closer, about the point between them, following them; let go and it springs back.
            .pointerInput(most) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var peeked = false
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) {
                            peeked = true
                            val z = (zoom * ev.calculateZoom()).coerceIn(1f, most)
                            val c = ev.calculateCentroid(useCurrent = true).let { if (it.isSpecified) it - mid else androidx.compose.ui.geometry.Offset.Zero }
                            pan = c - (c - pan) * (z / zoom) + ev.calculatePan()
                            zoom = z
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (peeked) back()
                }
            })
        MarbleArt(marble, marble.name,
            Modifier.requiredSize(size).graphicsLayer {
                val p = shown.value
                val sc = (start + (1f - start) * p) * zoom
                scaleX = sc; scaleY = sc
                translationX = (from.x - centre.x) * (1f - p) + lean.x * leanPx * p + pan.x
                translationY = (from.y - centre.y) * (1f - p) + lean.y * leanPx * p + pan.y
            })
        // Its name, in a small chip under it. Opening today's (on its UTC day) claims it, privately: the first time, the
        // chip springs in and a little gold flares off its star, with a tick you feel, and the count beside it goes up.
        // No button, no word. One of yours opens just to look at, with a trash can to take it off your account.
        val all by social.kept.all.collectAsState()
        val day = remember { Daily.utcDate() }
        val todays = big.marble == null && Marbles.shift == 0L
        val first = remember { todays && social.kept.keep(day) }
        // Claimed (now or before): its award from the issuer, quietly.
        // (New collecting stopped, 2026-11-04: nothing is claimed.)
        var removing by remember { mutableStateOf(false) }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).graphicsLayer { alpha = shown.value }, horizontalAlignment = Alignment.CenterHorizontally) {
            NameChip(marble.name, big.date ?: java.time.LocalDate.parse(day), flourish = first && !still, count = all.values.count { it.here }.takeIf { it > 0 }, onClick = onShelf, modifier = Modifier)
            if (!todays && big.date != null && all["marble-${big.date}"]?.here == true) {
                Spacer(Modifier.height(10.dp))
                IconButton({ removing = true }) { Icon(androidx.compose.material.icons.Icons.Outlined.Delete, "Remove from your marbles", tint = Vault.Muted, modifier = Modifier.size(22.dp)) }
            }
        }
        if (removing) AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${marble.name}?") },
            confirmButton = { TextButton({ removing = false; social.removeMarble("marble-${big.date}"); close() }) { Text("Remove", color = Vault.Danger) } },
            dismissButton = { TextButton({ removing = false }) { Text("Keep") } },
            containerColor = Vault.Surface,
        )
    }
}

/** "✦ Ruby Rift 27 Oct": the day's marble's name. [flourish]: it springs in, and a few gold flecks fly off the star. */
@Composable
private fun NameChip(name: String, date: java.time.LocalDate, flourish: Boolean, count: Int? = null, onClick: (() -> Unit)? = null, modifier: Modifier) {
    val view = androidx.compose.ui.platform.LocalView.current
    val pop = remember { androidx.compose.animation.core.Animatable(if (flourish) 0f else 1f) }
    val flecks = remember { androidx.compose.animation.core.Animatable(if (flourish) 0f else 1f) }
    val glint = remember { androidx.compose.animation.core.Animatable(if (flourish) 0f else 1f) }
    if (flourish) LaunchedEffect(Unit) {
        delay(250)   // once the marble has grown in
        launch { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 420f)) }
        delay(230)   // landing
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        launch { flecks.animateTo(1f, androidx.compose.animation.core.tween(480, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
        delay(80)
        glint.animateTo(1f, androidx.compose.animation.core.tween(600))
    }
    val amber = Vault.Amber
    Box(modifier) {
        Row(Modifier.graphicsLayer { val p = pop.value; scaleX = 0.5f + 0.5f * p; scaleY = 0.5f + 0.5f * p; alpha = p.coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(18.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Your marbles", onClick = onClick) else Modifier)
            .drawWithContent {
                drawContent()
                // The glint: a soft light running across once.
                val g = glint.value
                if (g in 0.001f..0.999f) {
                    val x = -0.6f * size.width + 2.0f * size.width * g
                    drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent), x, x + 0.4f * size.width))
                }
            }
            .padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✦", color = amber, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(6.dp))
            Text(name, color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(6.dp))
            Text(date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")), color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
            // How many you have (only that): a tap opens them.
            count?.let {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(1.dp).height(16.dp).background(Vault.Edge))
                Spacer(Modifier.width(8.dp))
                Text(it.toString(), color = Vault.Muted, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
            }
        }
        // The flecks, from the star (outside the chip, so they aren't clipped).
        if (flourish) androidx.compose.foundation.Canvas(Modifier.align(Alignment.CenterStart).offset(x = 20.dp).size(1.dp)) {
            val f = flecks.value
            if (f <= 0f || f >= 1f) return@Canvas
            for (i in 0 until 7) {
                val a = Math.toRadians((-150 + i * 50).toDouble())
                val r = (6f + (22f + (i % 3) * 7f - 6f) * f) * density
                val c = androidx.compose.ui.geometry.Offset(center.x + (kotlin.math.sin(a) * r).toFloat(), center.y - (kotlin.math.cos(a) * r).toFloat())
                val alpha = (if (f < 0.3f) f / 0.3f else (1f - f) / 0.7f).coerceIn(0f, 1f)
                val s = (if (i % 2 == 0) 3.2f else 1.8f) * density * (0.6f + 0.4f * f)
                if (i % 2 == 0) {
                    // A tiny four-pointed star.
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x, c.y - s); quadraticTo(c.x, c.y, c.x + s, c.y); quadraticTo(c.x, c.y, c.x, c.y + s); quadraticTo(c.x, c.y, c.x - s, c.y); quadraticTo(c.x, c.y, c.x, c.y - s); close()
                    }
                    drawPath(p, amber.copy(alpha = alpha))
                } else drawCircle(amber.copy(alpha = alpha), s * 0.6f, c)
            }
        }
    }
}

/**
 * A marble's picture. The collection's have their shadow in them; the night's own is just the glass, so the same
 * shadow is drawn under it here, and it takes the same place (0.556 of the width, centred).
 */
@Composable
private fun MarbleArt(marble: Marble, description: String?, modifier: Modifier) {
    val file = marble.file
    if (file == null && marble.url == null) return androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(marble.res), description, modifier)
    val bitmap = file?.let { f -> remember(f) { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() } }
    if (bitmap == null && marble.url == null) return androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(Marbles.today().res), description, modifier)
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val r = size.width * 0.278f
            val c = androidx.compose.ui.geometry.Offset(center.x, center.y + r * 1.07f)
            withTransform({ scale(1f, 0.12f / 0.73f, c) }) {
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent), c, r * 0.9f), r * 0.9f, c)
            }
        }
        if (bitmap != null) androidx.compose.foundation.Image(bitmap, description, Modifier.fillMaxSize(0.556f))
        else NetImage(marble.url, Modifier.fillMaxSize(0.556f), 768, androidx.compose.ui.layout.ContentScale.Fit, description)
    }
}

/**
 * Your marbles: the ones you claimed, newest first, a month at a time, each with its name and date. Only what you
 * have: no gaps, no counts of days missed. A tap opens one big.
 */
@Composable
private fun ShelfScreen(social: Social, nav: Nav, onOpen: (androidx.compose.ui.geometry.Offset, Marble, java.time.LocalDate) -> Unit) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Daily.loadNights(context) }
    val kept by social.kept.all.collectAsState()
    val nights by Daily.nights.collectAsState()
    val days = remember(kept) { kept.filterValues { it.here }.keys.mapNotNull { runCatching { java.time.LocalDate.parse(cc.aaro.cosign.Kept.dateOf(it)) }.getOrNull() }.sortedDescending() }
    TopBar(null, nav)
    Text("Your marbles", style = MaterialTheme.typography.headlineSmall, color = Vault.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
    Text(if (days.size == 1) "1 marble" else "${days.size} marbles", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
    if (days.isEmpty()) return@Column Text("Nothing yet.", color = Vault.Muted, modifier = Modifier.padding(20.dp))
    val months = remember(days) { days.groupBy { it.withDayOfMonth(1) } }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(104.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        for ((month, inMonth) in months) {
            item(key = "m$month", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")).uppercase(), color = Vault.Muted, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp))
            }
            items(inMonth.size, key = { "d" + inMonth[it] }) { i ->
                val d = inMonth[i]
                val m = remember(d, nights) { Daily.marbleOf(d) }
                var at by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Look at ${m.name}") { onOpen(at, m, d) }.padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    MarbleArt(m, m.name, Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 2.dp).onGloballyPositioned { at = it.boundsInRoot().center })
                    Text(m.name, color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.offset(y = (-10).dp))
                    Text(d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")), color = Vault.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.offset(y = (-10).dp))
                }
            }
        }
    }
}

/** The pipeline's owner only: tomorrow's marble, a day early, with a veto. */
@Composable
private fun TomorrowCard(t: Social.Tomorrow, social: Social) {
    var asking by remember { mutableStateOf(false) }
    val picture = remember(t.picture) { android.graphics.BitmapFactory.decodeFile(t.picture.path)?.asImageBitmap() }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(Vault.Surface, RoundedCornerShape(14.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        picture?.let { androidx.compose.foundation.Image(it, null, Modifier.size(56.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Tomorrow's marble · only you see this", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = Vault.Amber)) { append("✦ ") }; append(t.name)
            }, color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text("Goes live at 00:00 UTC", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
        }
        if (t.vetoed) Text("Vetoed", color = Vault.Muted, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.background(Vault.Edge, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
        else OutlinedButton({ asking = true }, border = BorderStroke(1.dp, Vault.Line)) { Text("Veto", color = Vault.Danger) }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Veto ${t.name}?") },
        text = { Text("It won't go live. Tomorrow shows a marble from the collection instead.") },
        confirmButton = { TextButton({ asking = false; social.veto(t) }) { Text("Veto", color = Vault.Danger) } },
        dismissButton = { TextButton({ asking = false }) { Text("Keep it") } },
        containerColor = Vault.Surface,
    )
}

/** Marble of the day: one from the collection ([MARBLES]), by the UTC date: it changes at 00:00 UTC, as the nightly pipeline makes them, and is the same everywhere. */
object Marbles {
    /** Debug builds only: days ahead (or back) to look at (--ei marble_day n). */
    @Volatile var shift = 0L
    /**
     * Today's: the night's own when the issuer made one ([Daily]); none, nothing (new marbles stopped, 2026-11-04: no
     * collection's marble stands in, nothing to claim). A debug build looking days ahead or back: the collection's.
     */
    @Composable
    fun current(): Marble? {
        val live by Daily.today.collectAsState()
        return if (shift != 0L) today() else live
    }
    fun today(): Marble = MARBLES[Math.floorMod(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay() + shift, MARBLES.size.toLong()).toInt()]
}

/** How far the home header has folded away, in pixels (0: open, [range]: today's slim bar). */
private class TopCollapse {
    var px by androidx.compose.runtime.mutableFloatStateOf(0f)
    var range = 1f
    /** Pulled down past the top, in pixels: the header stretches (the marble grows) by this much, never past [stretchMax]. */
    var stretch by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** The most the stretch can be (the marble at a third of the screen, and never wider than it), and where letting go refreshes. */
    var stretchMax = 1f
    var refreshAt = 1f
    /** Let go past [refreshAt]. */
    var onRefresh: () -> Unit = {}
    private fun band(pull: Float) = Stretch.band(pull, refreshAt, stretchMax)
    private fun unband(s: Float) = Stretch.unband(s, refreshAt, stretchMax)
    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        // Scrolling down the feed: give back the stretch first, then fold the header.
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (available.y >= 0) return androidx.compose.ui.geometry.Offset.Zero
            if (stretch > 0f) {
                val before = stretch
                stretch = (stretch + available.y).coerceAtLeast(0f)
                return androidx.compose.ui.geometry.Offset(0f, stretch - before)
            }
            val before = px
            px = (px - available.y).coerceAtMost(range)
            return androidx.compose.ui.geometry.Offset(0f, before - px)
        }
        // Back at the top of the feed and still pulling: open it again, then stretch it, stiffer near the most it goes.
        override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (available.y <= 0) return androidx.compose.ui.geometry.Offset.Zero
            val before = px
            px = (px - available.y).coerceAtLeast(0f)
            val left = available.y - (before - px)
            if (left > 0f && px == 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput)
                stretch = band(unband(stretch) + left * Stretch.GIVE)
            return available
        }
        // Let go: springs back; past the line, a refresh.
        override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
            if (stretch <= 0f) return androidx.compose.ui.unit.Velocity.Zero
            if (stretch >= refreshAt) onRefresh()
            androidx.compose.animation.core.animate(stretch, 0f, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 300f)) { v, _ -> stretch = v }
            return available
        }
    }
}

/**
 * The pull's rubber band. The finger's travel times [GIVE] is the pull; the stretch follows it one to one
 * up to a knee (past the refresh line, so a refresh takes the same pull as ever), then eases towards [max]
 * and never reaches it, however far you pull.
 */
internal object Stretch {
    const val GIVE = 0.6f
    private fun knee(refreshAt: Float, max: Float) = maxOf(refreshAt * 1.2f, max * 0.5f).coerceAtMost(max * 0.9f)
    fun band(pull: Float, refreshAt: Float, max: Float): Float {
        if (pull <= 0f) return 0f
        val k = knee(refreshAt, max)
        if (pull <= k) return pull
        val room = max - k
        return k + room * (1f - kotlin.math.exp(-(pull - k) / room))
    }
    /** The pull that stretches this far (the inverse of [band]). */
    fun unband(s: Float, refreshAt: Float, max: Float): Float {
        if (s <= 0f) return 0f
        val k = knee(refreshAt, max)
        if (s <= k) return s
        val room = max - k
        return k - room * kotlin.math.ln((1f - (s - k) / room).coerceAtLeast(1e-6f))
    }
}

// The header's two shapes, in dp. Open: the marble and the name centred, the search box below,
// like a search engine's front page. Folded: today's slim bar (the name at the left, the search box under it).
private const val OPEN_H = 256f
private const val SLIM_H = 132f

/**
 * The top of the home screen. Everything moves and scales with how far it's folded; nothing fades
 * or changes colour. [actions]: the bell and your picture, top right in both shapes.
 */
@Composable
private fun HomeTop(top: TopCollapse, onName: () -> Unit, onSearch: () -> Unit, onMarble: (androidx.compose.ui.geometry.Offset) -> Unit = {},
                    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    top.range = with(d) { (OPEN_H - SLIM_H).dp.toPx() }
    val f = (top.px / top.range).coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * f
    // Pulled down past the top: everything moves down by as much as the marble grows (to a third of the screen's height).
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
    val grow = with(d) { top.stretch.toDp().value }
    top.refreshAt = with(d) { 90.dp.toPx() }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height((mix(OPEN_H, SLIM_H) + grow).dp)) {
        val w = maxWidth.value
        // The most it grows: the marble a third of the screen's height, and 24dp clear of each side.
        top.stretchMax = with(d) { (minOf(screenH / 3f, w - 48f) - 72f).coerceAtLeast(130f).dp.toPx() }
        // The marble of the day: 72dp, centred under the bell; shrinking to nothing just left of the name; growing with a pull.
        val m = mix(72f, 0f) + grow
        val mx = mix(w / 2, 30f); val my = mix(92f, 32f) + grow / 2
        var centre by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        // Marble of the day; changes at 00:00 UTC, the same moment everywhere (looked at again whenever Discover comes back).
        val marble = Marbles.current()
        if (marble != null) MarbleArt(marble, "The marble",
            // Unbounded, so a marble wider than the header is still placed by its own centre, not pushed aside.
            Modifier.offset((mx - m * 0.9f).dp, (my - m * 0.9f).dp).wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize((m * 1.8f).dp)
                .onGloballyPositioned { centre = it.boundsInRoot().center }
                // Tap: the marble, full screen.
                .clip(CircleShape).clickable(enabled = m > 8f, onClickLabel = "Look at the marble") { onMarble(centre) })
        // Folded: the flat swirl (the themed icon's glyph) in its little disc, growing in as the marble goes.
        val g = 28f * f * f
        if (g > 1f) Swirl(g, Modifier.offset((30f - g / 2).dp, (32f - g / 2).dp), onName)
        // The name: 32sp centred under the marble, scaling to the bar's 22sp at the left.
        var nameW by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val scale = mix(1f, 22f / 32f)
        val nameWdp = with(d) { nameW.toDp().value }
        Text(appName, style = MaterialTheme.typography.titleLarge.copy(fontSize = 32.sp, lineHeight = 40.sp), color = Vault.Text, maxLines = 1,
            modifier = Modifier.offset(mix((w - nameWdp) / 2, 52f).dp, (mix(132f, 18f) + grow).dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
                .onSizeChanged { nameW = it.width }.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Back to the top", onClick = onName))
        Row(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        // The search box: under the name, rising to just under the bar.
        Row(
            Modifier.offset(y = (mix(192f, 68f) + grow).dp).padding(horizontal = 16.dp).fillMaxWidth().height(52.dp)
                .background(Vault.Surface, RoundedCornerShape(26.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(26.dp))
                .clickable(onClick = onSearch).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = Vault.Muted)
            Spacer(Modifier.width(12.dp))
            Text("Search, or keep a thought", color = Vault.Muted, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Open a page in a Custom Tab, in Marble's colours, with Save (filled when the page is already in Later): see [cc.aaro.cosign.Tabs]. */
private fun openInBrowser(context: android.content.Context, url: String, item: String? = null) {
    // A post, article or someone (a kept post's marble.syb.lol link, say): in Marble, never a browser.
    cc.aaro.cosign.inAppRef(url)?.let { OpenPost.ref.value = it; return }
    val saved = Social.of(CosignApp.instance.phone).later.items.value.any { it.url == url && it.deleted == null }
    cc.aaro.cosign.Tabs.open(context, url, saved, Vault.dark, Vault.Ground.toArgb(), item)
}

/** At most one card: the thing that needs you most. The rest are behind the bell. */
@Composable
private fun NeedsYou(phone: Phone, ui: Ui, social: Social, onMore: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // (No ticking seconds on home: the card goes when it's answered or runs out.)
    val counting = ui.asks.isNotEmpty() || ui.batchAsks.isNotEmpty() || ui.deleting.isNotEmpty()
    if (counting) LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    // Home is interrupted only by approvals, an Undo and recovery (home-rules.md "Interrupt"); the rest live in Approvals.
    val cards = needs(phone, ui).filter { it !is Need.Paper && it !is Need.Future }
    // Another key of yours with something waiting.
    val others = CosignApp.instance.accounts.all.filter { it !== phone }.map { it to it.ui.collectAsState().value }
        .filter { (_, u) -> u.asks.isNotEmpty() || u.batchAsks.isNotEmpty() }
    if (cards.isEmpty() && others.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        cards.firstOrNull()?.let { NeedCard(it, phone, now) }
        if (cards.size > 1) TextButton(onMore, contentPadding = PaddingValues(0.dp)) { Text("More waiting for you", color = Vault.AmberText) }
        for ((p, _) in others) TextButton({ CosignApp.instance.accounts.show(p.slot) }, contentPadding = PaddingValues(0.dp)) {
            Text("Something's waiting for ${p.label}. Switch", color = Vault.AmberText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** What needs you, most pressing first. */
private sealed class Need {
    data class Approve(val ask: cc.aaro.cosign.Ask) : Need()
    data class Batch(val b: cc.aaro.cosign.BatchAsk) : Need()
    data class Deleting(val d: cc.aaro.cosign.Deleting) : Need()
    data class Recovery(val name: String, val until: Long) : Need()
    data class Future(val f: cc.aaro.cosign.Future) : Need()
    data object Paper : Need()
}

private fun needs(phone: Phone, ui: Ui): List<Need> = buildList {
    ui.asks.forEach { add(Need.Approve(it)) }
    ui.batchAsks.forEach { add(Need.Batch(it)) }
    ui.deleting.forEach { add(Need.Deleting(it)) }
    ui.recovery?.let { if (!RecoveryOk.has(it.second)) add(Need.Recovery(it.first, it.second)) }
    ui.future.forEach { add(Need.Future(it)) }
    if (phone.needsPaper) add(Need.Paper)
}

@Composable
private fun NeedCard(n: Need, phone: Phone, now: Long) = when (n) {
    is Need.Approve -> AskCard(n.ask, now, quiet = true) { approve -> phone.decide(n.ask.id, approve) }
    is Need.Batch -> BatchCard(n.b, now, quiet = true) { approve -> phone.decideBatch(n.b.batch, approve) }
    is Need.Deleting -> DeletingCard(n.d, now, quiet = true) { phone.cancelDeletion(n.d.batch) }
    is Need.Recovery -> RecoveryCard(n.name, n.until, phone)
    is Need.Future -> FutureCard(n.f) { phone.removeFuture(n.f) }
    Need.Paper -> PaperCard(phone)
}

// --- Notes -------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(n: Event, people: Map<String, Person>, liked: Boolean, nav: Nav, onLike: () -> Unit, maxLines: Int = Int.MAX_VALUE, indent: Int = 0, lit: Boolean = false,
                     onReply: (() -> Unit)? = null, pick: Pick? = null, previews: Map<String, Preview> = emptyMap(), saved: Boolean = false, onSave: (() -> Unit)? = null,
                     onShare: (() -> Unit)? = null, wide: Boolean = false, menu: (@Composable () -> Unit)? = null) {
    // A repost: the post it shares, under a line saying who shared it.
    if (n.kind == 6) {
        // What it reposts: carried in it, or (only pointed at) fetched once it's on screen.
        var inner by remember(n.id) { mutableStateOf(nav.social.reposted(n)) }
        if (inner == null) { n.firstTag("e")?.let { id -> LaunchedEffect(n.id) { inner = nav.social.fetchNote(id) } }; return }
        val shared = inner!!
        val liked2 by nav.social.liked.collectAsState()
        Column {
            Row(Modifier.padding(start = 68.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.repost, null, tint = Vault.Muted, modifier = Modifier.size(14.dp))
                Text("  ${people[n.pubkey]?.shown ?: shortNpub(n.pubkey)} reposted", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            NoteCard(shared, people, shared.id in liked2, nav, onLike = { nav.social.like(shared) }, maxLines = maxLines)
        }
        return
    }
    val author = people[n.pubkey]
    // Translated by your LibreTranslate, if it was in another language; the original a tap away.
    val translations by nav.social.translations.collectAsState()
    val tr = translations[n.id]?.takeIf { it.from != null }
    val offer = nav.social.offers.collectAsState().value[n.id]
    var original by remember(n.id) { mutableStateOf(false) }
    // A link with a preview shows as a card instead of in the text; an article as a card of its own.
    val link = remember(n.id) { if (pick != null) Calm.link(n) else if (n.kind == 1) Body.of(n).previewLink?.url else null }
    if (link != null && pick == null) LaunchedEffect(link) { nav.social.wantPreview(link) }
    val card = if (n.kind == 30023) remember(n.id) { Preview(n.firstTag("title") ?: "Article", n.firstTag("image"), n.firstTag("summary") ?: "", "Article") } else link?.let { previews[it] }
    val body = remember(n.id, tr, original, card != null) {
        val text = if (tr != null && !original) tr.text else if (n.kind == 30023) "" else n.content
        Body.of(n, if (card != null && link != null) text.replace(link, "").trim() else text)
    }
    // Why it's here: its topic, then who vouches for it.
    pick?.reason?.takeIf { it.isNotEmpty() }?.let { r ->
        Text(androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = Vault.Accent)) { append(r.substringBefore(" · ")) }
            if (" · " in r) append(" · " + r.substringAfter(" · "))
        }, color = Vault.Field, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable { nav.thread(n.id) }.padding(start = if (wide) 16.dp else 68.dp, end = 16.dp, top = 12.dp))
    }
    Row(
        Modifier.fillMaxWidth().background(if (lit) Vault.AccentGround else Color.Transparent)
            .clickable { nav.thread(n.id) }.padding(start = 16.dp + (indent * 14).dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        // Wide (Feed 3): the picture in the name row, and the post full width under it.
        if (!wide) {
            Avatar(author, n.pubkey, if (indent > 0) 32 else 40, Modifier.clickable { nav.profile(n.pubkey) })
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(end = 32.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    if (wide) { Avatar(author, n.pubkey, 36, Modifier.clickable { nav.profile(n.pubkey) }); Spacer(Modifier.width(10.dp)) }
                    Text(author?.shown ?: shortNpub(n.pubkey), color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).clickable { nav.profile(n.pubkey) })
                    Spacer(Modifier.width(4.dp))
                    PostTime(n, nav.social)
                }
                if (n.pubkey != nav.social.me) Box(Modifier.align(Alignment.CenterEnd)) { menu?.invoke() ?: NoteMenu(n, author?.shown ?: shortNpub(n.pubkey), nav.social) }
                else if (n.kind == 1) Box(Modifier.align(Alignment.CenterEnd)) { OwnNoteMenu(n, nav.social) }
            }
            val to = remember(n.id) { n.tags.lastOrNull { it.size > 1 && it[0] == "p" }?.get(1) }
            LaunchedEffect(n.id) { People.want(body.mentions + listOfNotNull(to)) }
            if (indent == 0 && parentOf(n) != null) {
                Text("Replying to ${to?.let { people[it]?.shown ?: shortNpub(it) } ?: "a note"}", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            }
            // Likely spam (manufactured likes, or an author who posts like it): said plainly, the reason a tap away.
            nav.social.spam.collectAsState().value[n.id]?.let { SpamCue(it) { nav.social.notSpam(n) } }
            // A content warning (NIP-36): the text, card and pictures wait for a tap.
            val warning = remember(n.id) { Body.warning(n) }
            var unveiled by remember(n.id) { mutableStateOf(false) }
            val veiled = warning != null && !unveiled
            if (veiled) Text((warning!!.ifEmpty { "Sensitive" }) + ": show", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(Vault.Surface).clickable { unveiled = true }.padding(horizontal = 12.dp, vertical = 8.dp))
            val text = remember(body, people.size) { body.annotated(people, nav) }
            val emoji = remember(body) { body.emojiContent() }
            // Translated (on the phone), at the top where a long post starts: the original a tap away, in place.
            if (!veiled && n.kind == 1) LaunchedEffect(n.id) { nav.social.lookAt(n) }
            tr?.let { t ->
                Text("Translated from ${OnDevice.name(t.from ?: "")} · " + if (original) "Show translation" else "Show original",
                    color = Vault.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp).clickable { original = !original })
            } ?: offer?.let { o -> TranslateOffer(n, o, nav.social) }
            if (!veiled && text.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(text, color = Vault.Text, style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis, inlineContent = emoji)
            }
            // What it refers to first, under it (a post, an article, something from another app); any later one is a short link.
            // A closing run of mentions: one line of small chips (+N opens the rest).
            if (!veiled && body.trailing.isNotEmpty()) MentionChips(body.trailing, people, nav)
            if (!veiled) when (val c = body.card) {
                is Body.Part.Quote -> QuoteBox(c.id, people, nav)
                is Body.Part.QuoteAddr -> AddrBox(c.a, people, nav)
                else -> {}
            }
            if (!veiled) body.article?.let { AddrBox(it.a, people, nav) }
            // In a list, one picture, no taller than it is wide (and how many more); a thread shows them all, whole.
            val full = maxLines == Int.MAX_VALUE
            // A tap on a picture opens it full screen (and not the thread); a video plays where it is.
            val urls = remember(body) { body.images.map { it.url } }
            val context = LocalContext.current
            if (!veiled && n.kind == 30023) ArticleCard(n, people, nav, author = false)
            else if (!veiled) card?.let { c -> LinkCard(c, link) { if (link == null) nav.thread(n.id) else cc.aaro.cosign.marblePost(link)?.let { nav.thread(it) } ?: run { nav.social.openedLink(n); openInBrowser(context, link, n.id) } } }
            if (card == null && !veiled) body.media.take(if (full) Int.MAX_VALUE else 1).forEach { m ->
                Spacer(Modifier.height(8.dp))
                when (m) {
                    // Animated if it's a GIF or WebP; a still of it, or a quiet "Open", if it can't be shown.
                    is Body.Img -> PostImage(m.url, m.preview, Modifier.fillMaxWidth().aspectRatio(m.ratio.coerceIn(if (full) 0.6f else 1f, 2.2f)).clip(RoundedCornerShape(12.dp))) {
                        FeedLog.open(n.id, "image"); nav.pictures(urls, urls.indexOf(m.url).coerceAtLeast(0))
                    }
                    is Body.Vid -> VideoCard(m.v)
                }
            }
            if (n.kind == cc.aaro.cosign.Polls.POLL) cc.aaro.cosign.Polls.of(n)?.let { PollCard(it, nav.social) }
            if (pick != null && pick.more.isNotEmpty()) Text("+ ${pick.more.size} more about this from ${pick.more.joinToString(", ")}", color = Vault.Field, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            if (card == null && !veiled && !full && body.media.size > 1) Text("+${body.media.size - 1} more", color = Vault.Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).clickable { if (body.media.drop(1).all { it is Body.Img }) { FeedLog.open(n.id, "image"); nav.pictures(urls, 1) } else nav.thread(n.id) })
            PostActions(n, liked, nav, onLike, full, author, onReply, saved, onSave, onShare, reader = pick != null)
        }
    }
}


/** Under a post (and at an article's end): like, reply, repost; then share and keep. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PostActions(n: Event, liked: Boolean, nav: Nav, onLike: () -> Unit, full: Boolean, author: Person?, onReply: (() -> Unit)? = null,
                        saved: Boolean = false, onSave: (() -> Unit)? = null, onShare: (() -> Unit)? = null, reader: Boolean = false) {
    val context = LocalContext.current
    // Like and reply, as plain icons (an IconButton each costs a visible frame on a slow phone).
    Row(Modifier.offset(x = (-9).dp), verticalAlignment = Alignment.CenterVertically) {
        // A tap likes; a long press picks an emoji (see Heart). Then what others reacted with, once counted.
        // Feed 2: no counts in a list (the post itself has them, a tap in).
        // A row in Following (Reader): no reacting, replying or reposting there; an opened post and Search have them.
        if (!reader) {
        // (No counts under a post in a list: an opened post has them, under it.)
        Heart(n, liked, nav.social, onLike)
        Icon(Glyphs.reply, "Reply", tint = Vault.Muted,
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onReply?.invoke() ?: nav.reply(n.id) }.padding(10.dp))
        // Repost: public, so never one tap; a small menu says so first. Held: quote it, in the composer (public too).
        val reposted = n.id in nav.social.reposted.collectAsState().value
        var asking by remember { mutableStateOf(false) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        Box {
            Icon(Glyphs.repost, if (reposted) "Reposted" else "Repost", tint = if (reposted) Vault.Accent else Vault.Muted,
                modifier = Modifier.size(40.dp).clip(CircleShape).combinedClickable(onLongClickLabel = "Quote it", onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    FeedLog.act(n.id, "quote"); nav.compose(cc.aaro.cosign.marbleLink(n))
                }) { if (!reposted) asking = true }.padding(10.dp))
            if (asking) DropdownMenu(true, { asking = false }, containerColor = Vault.Surface) {
                DropdownMenuItem({ Column {
                    Text("Repost", color = Vault.Text, fontWeight = FontWeight.SemiBold)
                    Text("Everyone who follows you sees it", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                } }, { asking = false; nav.social.repost(n) })
            }
        }
        }
        // Share and keep, on every post (in a list, an open post, its replies): unless the list says otherwise, keep is
        // into your notes and share is the phone's share sheet with the post's marble.syb.lol link.
        Spacer(Modifier.weight(1f))
        val kept = if (onSave != null) saved else cc.aaro.cosign.marbleLink(n).let { link -> nav.social.later.items.collectAsState().value.any { it.url == link && it.deleted == null } }
        val keep = onSave ?: { nav.social.keepPost(n, author?.shown ?: "A post") }
        val shareIt = onShare ?: { FeedLog.act(n.id, "share"); share(context, n.content.trim().take(280) + "\n\n" + cc.aaro.cosign.marbleLink(n), null) }
        // Share: to someone (the phone's share sheet until Messages; then your contacts first).
        Icon(Glyphs.share, "Share", tint = Vault.Muted, modifier = Modifier.offset(x = 18.dp).size(40.dp).clip(CircleShape).clickable(onClick = shareIt).padding(11.dp))
        Icon(if (kept) Glyphs.saveOn else Glyphs.save, if (kept) "Kept" else "Keep", tint = if (kept) Vault.Accent else Vault.Muted,
            modifier = Modifier.offset(x = 18.dp).size(40.dp).clip(CircleShape).clickable(onClick = keep).padding(10.dp))
    }
}

/** "Looks like spam", on a post that does: quiet, honest, and why on a tap. It's never hidden from you once you're here. */
@Composable
private fun SpamCue(why: cc.aaro.cosign.spam.Spam.Why, notSpam: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Why") { open = true }.padding(vertical = 2.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyphs.warn, null, tint = Vault.AmberText, modifier = Modifier.size(14.dp))
        Text("Looks like spam · ${why.short}", color = Vault.AmberText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 5.dp))
    }
    if (open) AlertDialog({ open = false }, confirmButton = { TextButton({ open = false }) { Text("OK") } },
        // Yours to overrule, on this phone.
        dismissButton = { TextButton({ open = false; notSpam() }) { Text("Not spam", color = Vault.Muted) } },
        title = { Text("Looks like spam") },
        text = { Text(why.long + "\n\nMarble leaves posts like this out of search and Following. People you follow are never hidden, only marked.", color = Vault.Muted) },
        containerColor = Vault.Surface)
}

/** A post quoted in another: its author and first words in a small box; a tap shows it whole, another opens it. */
@Composable
private fun QuoteBox(id: String, people: Map<String, Person>, nav: Nav) {
    var q by remember(id) { mutableStateOf<Event?>(null) }
    var looked by remember(id) { mutableStateOf(false) }
    var open by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { q = nav.social.fetchQuoted(id); looked = true; q?.let { People.want(listOf(it.pubkey)) } }
    val e = q ?: return if (looked) NotFound("https://marble.syb.lol/" + cc.aaro.qrst.core.Bech32.encode("note", id.hexToBytes())) else Unit
    if (e.kind != 1) return OtherBox(e, e.pubkey, people, nav)
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(12.dp))
        .clickable(onClickLabel = if (open) "Open it" else "Show it whole") { if (open) nav.thread(e.id) else open = true }.padding(horizontal = 12.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(people[e.pubkey], e.pubkey, 18)
            Text(people[e.pubkey]?.shown ?: shortNpub(e.pubkey), color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
            Text(" · " + since(e.createdAt), color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Text(cc.aaro.cosign.readable(e.content.trim()) { people[it]?.shown }, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** A quoted post that couldn't be found (yet): a quiet line, to its marble.syb.lol page. */
@Composable
private fun NotFound(page: String) {
    val context = LocalContext.current
    Text("Couldn't load this post ›", color = Vault.Muted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { cc.aaro.cosign.Tabs.open(context, page, false, Vault.dark, Vault.Ground.toArgb(), null) }.padding(vertical = 4.dp))
}

/**
 * A quoted addressable event (naddr) under a post: who made it, and what it is in words (its text, its title or name,
 * or its NIP-31 "alt" for a kind Marble doesn't draw, such as a 3D object). A tap opens a post or article in Marble,
 * anything else on its marble.syb.lol page.
 */
@Composable
private fun AddrBox(a: Ref.Addr, people: Map<String, Person>, nav: Nav) {
    var e by remember(a) { mutableStateOf<Event?>(null) }
    var looked by remember(a) { mutableStateOf(false) }
    LaunchedEffect(a) { e = nav.social.fetchAddr(a); looked = true; People.want(listOf(a.pubkey)) }
    if (e == null) { if (looked) NotFound("https://marble.syb.lol/" + a.naddr); return }
    OtherBox(e, a.pubkey, people, nav)
}

/**
 * A quoted post, article, or something from another app ([e]; null: not found, opened on [page]). A post: its words;
 * an article: its card; anything else: its NIP-31 alt (or "A post from another app" and the app's name) and "Open",
 * to the app that made it when we know it (Social.openTarget). Never its raw content.
 */
@Composable
private fun OtherBox(e: Event?, author: String, people: Map<String, Person>, nav: Nav, page: String? = null) {
    if (e?.kind == 30023) return ArticleCard(e, people, nav)
    val context = LocalContext.current
    val inMarble = e != null && OtherApps.drawn(e.kind)
    var target by remember(e?.id) { mutableStateOf(page) }
    if (e != null && !inMarble) LaunchedEffect(e.id) { target = nav.social.openTarget(e) }
    val host = target?.let { android.net.Uri.parse(it).host?.removePrefix("www.") }
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(12.dp))
        .clickable(onClickLabel = "Open it") {
            // (Outside Marble, straight to the app that made it: a marble.syb.lol link would come back in here.)
            if (inMarble) nav.thread(e!!.id) else target?.let { cc.aaro.cosign.Tabs.open(context, it, false, Vault.dark, Vault.Ground.toArgb(), null) }
        }
        .padding(horizontal = 12.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(people[author], author, 18)
            Text(people[author]?.shown ?: shortNpub(author), color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
            e?.let { Text(" · " + since(it.createdAt), color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        }
        when {
            // An article: its card (picture, title, summary), as an article shows in the feed.

            else -> Text(if (e == null) "Couldn't load this post" else if (e.kind == 1) cc.aaro.cosign.readable(e.content.trim()) { people[it]?.shown } else OtherApps.what(e),
                color = if (inMarble) Vault.Text else Vault.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (!inMarble && host != null) Text(if (host == "marble.syb.lol") "Open ›" else "Open on $host ›", color = Vault.Accent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Under a post: "12 replies · 3 reposts · 68 likes" (what the relays counted, or found, whichever is more). */
@Composable
private fun Totals(e: Social.Engagement) {
    fun n(v: Long, one: String, many: String) = "$v ${if (v == 1L) one else many}"
    // (No zaps: showing payments is put off.)
    // (Reactions are the chips above it.)
    val parts = listOfNotNull(n(e.replyTotal, "reply", "replies"), e.reposts.takeIf { it > 0 }?.let { n(it.toLong(), "repost", "reposts") })
    Text(parts.joinToString(" · "), color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 10.dp))
}

/**
 * A pasted link to a post: the post itself, big, then what points at it ("Quoted by", "Replies" with See all,
 * "Mentioned in"). To a person: their card, then "Posts mentioning them". The same sections as marble.syb.lol.
 */
@Composable
private fun LinkResults(ref: Ref, social: Social, nav: Nav) {
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    val eng by social.engagement.collectAsState()
    when (ref) {
        is Ref.Note -> {
            var post by remember(ref) { mutableStateOf(social.note(ref.id)) }
            var found by remember(ref) { mutableStateOf<Social.Lookup?>(null) }
            LaunchedEffect(ref) {
                if (post == null) post = social.fetchNote(ref.id)
                social.lookupNote(ref.id, ref.relays) { found = it }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "post") {
                    val p = post
                    if (p == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
                    else { NoteCard(p, people, p.id in liked, nav, onLike = { social.like(p) }); eng[p.id]?.let { Totals(it) } }
                    HorizontalDivider(color = Vault.Edge)
                }
                val f = found
                if (f == null) item(key = "looking") { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Vault.Accent, trackColor = Color.Transparent) }
                else {
                    if (f.quotedBy.isNotEmpty()) { item(key = "qh") { Heading("Quoted by") }; items(f.quotedBy.take(10), key = { "q" + it.id }) { n -> NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 8); HorizontalDivider(color = Vault.Edge) } }
                    if (f.replies.isNotEmpty()) {
                        item(key = "rh") { Heading("Replies") }
                        items(f.replies.take(3), key = { "r" + it.id }) { n -> NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 8); HorizontalDivider(color = Vault.Edge) }
                        if (f.replies.size > 3 || (eng[ref.id]?.replyTotal ?: 0) > 3) item(key = "all") {
                            TextButton({ nav.thread(ref.id) }, Modifier.padding(start = 8.dp)) { Text("See all ${maxOf(f.replies.size.toLong(), eng[ref.id]?.replyTotal ?: 0)}", color = Vault.Accent) }
                        }
                    }
                    if (f.mentionedIn.isNotEmpty()) { item(key = "mh") { Heading("Mentioned in") }; items(f.mentionedIn.take(10), key = { "m" + it.id }) { n -> NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 8); HorizontalDivider(color = Vault.Edge) } }
                    if (f.quotedBy.isEmpty() && f.replies.isEmpty() && f.mentionedIn.isEmpty()) item(key = "none") { Text("No replies, quotes or mentions found yet.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
                }
            }
        }
        // A pasted address of something (an article, a 3D object…): its box, which opens it.
        is Ref.Addr -> Box(Modifier.padding(16.dp)) { AddrBox(ref, people, nav) }
        is Ref.Profile -> {
            var posts by remember(ref) { mutableStateOf<List<Event>?>(null) }
            LaunchedEffect(ref) { People.want(listOf(ref.pubkey)); social.profileNow(ref.pubkey); posts = social.mentionsOf(ref.pubkey, ref.relays) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "who") {
                    val p = people[ref.pubkey]
                    Column(Modifier.fillMaxWidth().clickable { nav.profile(ref.pubkey) }.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p, ref.pubkey, 64)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p?.shown ?: shortNpub(ref.pubkey), color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                p?.nip05?.let { Text(it, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                        p?.about?.takeIf { it.isNotBlank() }?.let { Text(it, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
                        Text("Open profile ›", color = Vault.Accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                    }
                    HorizontalDivider(color = Vault.Edge)
                }
                item(key = "mh") { Heading("Posts mentioning them") }
                val l = posts
                if (l == null) item(key = "looking") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) } }
                else if (l.isEmpty()) item(key = "none") { Text("No posts mentioning them found.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
                else items(l, key = { it.id }) { n -> NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 8); HorizontalDivider(color = Vault.Edge) }
            }
        }
    }
}

/** A link's preview: the page's picture, its site, title and a line of summary. */
@Composable
private fun LinkCard(c: Preview, url: String? = null, onOpen: () -> Unit) = Column(
    Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(14.dp)).background(Vault.Surface).clickable(onClick = onOpen),
) {
    c.image?.let { NetImage(it, Modifier.fillMaxWidth().heightIn(max = 190.dp).aspectRatio(1.91f).background(Vault.Edge), 1080) }
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
        if (c.site.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            url?.let { SiteIcon(it, 14); Spacer(Modifier.width(6.dp)) }
            Text(c.site, color = Vault.Field, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(c.title, color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        if (c.summary.isNotEmpty()) Text(c.summary, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

/**
 * A new post: what you write, and the story (or link) it's about under it, as it'll show. Back leaves it.
 */
@Composable
private fun ComposeScreen(link: String, social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    // Drafts would hook in here: backing out with something written keeps it, to come back to (not yet).
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The story's own picture and headline; a link from elsewhere, its page's.
    val story = remember(link) { social.allStories.value.firstOrNull { it.url == link } }
    val preview by produceState(story?.let { Preview(it.title, it.image, it.summary, it.sourceName) } ?: social.previews.value[link], link) {
        if (value == null && link.isNotEmpty() && cc.aaro.cosign.marblePost(link) == null) value = Preview.fetch(link)
    }
    // Public, and plainly so: your picture, "Public · everyone can see this", and Post publicly.
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.Filled.Close, "Close", tint = Vault.Text) }
        social.me?.let { me -> Avatar(People.known.collectAsState().value[me], me, 32); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text("Public", style = MaterialTheme.typography.titleMedium, color = Vault.Text, fontWeight = FontWeight.SemiBold)
            Text("Everyone can see this", style = MaterialTheme.typography.labelMedium, color = Vault.Muted)
        }
        Button({
            sending = true
            scope.launch { val sent = social.post(text, link); sending = false; if (sent != null) { social.message.value = "Posted."; nav.back() } }
        }, enabled = !sending && (text.isNotBlank() || link.isNotEmpty()), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text(if (sending) "Posting…" else "Post publicly") }
    }
    TextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 4.dp),
        placeholder = { Text(if (link.isEmpty()) "What's on your mind?" else "Add a comment…", color = Vault.Muted) },
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Vault.Text, unfocusedTextColor = Vault.Text, cursorColor = Vault.Accent))
    // A post being quoted: small under what you write, as quotes show in the feed; a story or a page: its card.
    val quoted = remember(link) { cc.aaro.cosign.marblePost(link) }
    if (quoted != null) Box(Modifier.padding(horizontal = 16.dp)) { QuoteBox(quoted, People.known.collectAsState().value, nav) }
    else preview?.let { p -> Box(Modifier.padding(horizontal = 16.dp)) { LinkCard(p) {} } }
}

/** "⋯" on your own post: copy its link, or pin it to your profile (or unpin it). */
@Composable
private fun OwnNoteMenu(n: Event, social: Social) = Box {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val pins by social.myPins.collectAsState()
    Icon(Icons.Filled.MoreVert, "More", tint = Vault.Muted, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { open = true }.padding(5.dp))
    if (open) DropdownMenu(true, { open = false }, containerColor = Vault.Surface) {
        DropdownMenuItem({ Text("Copy link", color = Vault.Text) }, { open = false; FeedLog.act(n.id, "copyLink"); copy(context, social, webLink(social, n), "Link copied") })
        val on = n.id in pins
        DropdownMenuItem({ Text(if (on) "📌 Unpin from profile" else "📌 Pin to profile", color = Vault.Text) }, { open = false; social.pin(n, !on) })
    }
}

/** "⋯": not interested in this note, or mute its author (on this phone and in your mute list). */
@Composable
private fun NoteMenu(n: Event, name: String, social: Social) = Box {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    // A plain icon on every card (an IconButton each costs a visible frame on a slow phone); the menu only once it's open.
    Icon(Icons.Filled.MoreVert, "More", tint = Vault.Muted, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { open = true }.padding(5.dp))
    if (open) DropdownMenu(true, { open = false }, containerColor = Vault.Surface) {
        DropdownMenuItem({ Text("Not interested", color = Vault.Text) }, { open = false; social.notInterested(n) })
        DropdownMenuItem({ Text("Mute $name", color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, {
            open = false; social.mute(n.pubkey); social.message.value = "You won't see $name any more."
        })
        DropdownMenuItem({ Text("Copy link", color = Vault.Text) }, { open = false; FeedLog.act(n.id, "copyLink"); copy(context, social, webLink(social, n), "Link copied") })
        DropdownMenuItem({ Text("Report…", color = Vault.Danger) }, { open = false; reporting = true })
    }
    if (reporting) ReportDialog(name, onClose = { reporting = false }) { reason -> reporting = false; social.report(n, reason) }
}

/** Why you're reporting it: one tap sends it, and the post goes away. */
@Composable
private fun ReportDialog(name: String, onClose: () -> Unit, onReport: (String) -> Unit) = AlertDialog(
    onDismissRequest = onClose,
    title = { Text("Report this post?") },
    text = {
        Column {
            Text("It's hidden for you at once. What's wrong with it?", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            for ((reason, label) in listOf("spam" to "Spam", "nudity" to "Nudity or sexual content", "profanity" to "Hateful or abusive",
                "illegal" to "Illegal", "impersonation" to "Pretending to be someone they're not", "other" to "Something else")) {
                Text(label, color = Vault.Text, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onReport(reason) }.padding(vertical = 10.dp, horizontal = 4.dp))
            }
        }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClose) { Text("Cancel") } },
    containerColor = Vault.Surface,
)

/**
 * A note's text, cut up: pictures apart, mentions and #tags made tappable. The rules are shared with Marble Web and
 * Astral (marble-content-fixtures: content-rules.md); ContentFixturesTest runs its fixtures against [tokens].
 */
internal class Body(val parts: List<Part>, val media: List<Media>, val mentions: List<String>, private val quoted: Part? = null,
                    /** A run of three or more mentions closing the post: shown as one line of small chips, not in the text. */
                    val trailing: List<String> = emptyList()) {
    val images get() = media.filterIsInstance<Img>()
    sealed class Part {
        data class Plain(val s: String) : Part()
        /** [text]: what it says, when it says something other than its address (a Markdown link). */
        data class Link(val url: String, val text: String? = null) : Part()
        data class Mention(val pubkey: String) : Part()
        /** [raw]: the reference as written (note1…, nevent1…), for its short link. */
        data class Quote(val id: String, val raw: String = "") : Part()
        /** A quoted addressable event (naddr): an article, a 3D object, a listing… */
        data class QuoteAddr(val a: Ref.Addr, val raw: String = "") : Part()
        data class Tag(val tag: String) : Part()
        /** A custom emoji (NIP-30): its picture, in the line. */
        data class Emoji(val code: String, val url: String) : Part()
    }

    /**
     * The first post or thing it refers to: a card under the text (any later one is a short link in the text). Nothing
     * in the text: its first q tag's (NIP-18), after the text.
     */
    val card: Part? = parts.firstOrNull { it is Part.Quote || it is Part.QuoteAddr } ?: quoted

    /** An article it refers to (kind 30023), when that isn't [card] already: a card too, as articles always get. */
    val article: Part.QuoteAddr? = parts.firstOrNull { it is Part.QuoteAddr && it.a.kind == 30023 && it !== card } as Part.QuoteAddr?

    /**
     * The link that gets a preview card (one per post, the first): a web link on its own line, or at the post's end.
     * One in the middle of a sentence stays a link.
     */
    val previewLink: Part.Link? = if (media.isNotEmpty()) null else parts.withIndex().firstOrNull { (i, p) ->
        p is Part.Link && p.text == null && !p.url.startsWith("blossom:") && cc.aaro.cosign.inAppRef(p.url) == null && run {
            val before = (parts.getOrNull(i - 1) as? Part.Plain)?.s
            val after = parts.drop(i + 1)
            val ownLine = (i == 0 || before?.trimEnd(' ', '\t')?.let { it.isEmpty() || it.endsWith("\n") } == true) &&
                (after.isEmpty() || (after.first() as? Part.Plain)?.s?.trimStart(' ', '\t')?.let { it.isEmpty() || it.startsWith("\n") } == true)
            ownLine || after.all { it is Part.Plain && it.s.isBlank() }
        }
    }?.value as Part.Link?

    /** What a part shows as, in the text (null: nothing; a card, or an emoji, drawn as its picture). */
    fun label(p: Part, people: Map<String, Person>): String? = when (p) {
        is Part.Plain -> p.s
        is Part.Link -> p.text ?: cc.aaro.cosign.inAppRef(p.url)?.let { r ->
            when (r) { is Ref.Note -> "↪ a post"; is Ref.Profile -> "@" + (people[r.pubkey]?.shown ?: shortNpub(r.pubkey)); is Ref.Addr -> if (r.kind == 30023) "↪ an article" else "↪ quoted" }
        } ?: p.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").let { if (it.length > 40) it.take(38) + "…" else it }
        is Part.Mention -> "@" + (people[p.pubkey]?.shown ?: shortNpub(p.pubkey))
        is Part.Quote -> if (p === card) null else "↪ quoted"
        is Part.QuoteAddr -> if (p === card || p === article) null else if (p.a.kind == 30023) "↪ an article" else "↪ quoted"
        is Part.Tag -> "#" + p.tag
        is Part.Emoji -> null
    }

    /**
     * What's drawn, as the shared fixtures' tokens: in the text, in order (["text", s], ["mention", pubkey],
     * ["hashtag", tag], ["link", url], ["emoji", code], ["shortlink", reference]); and the text you can see outside links.
     */
    fun tokens(people: Map<String, Person> = emptyMap()): Pair<List<Pair<String, String>>, String> {
        val out = ArrayList<Pair<String, String>>(); val seen = StringBuilder()
        for (p in parts) {
            val shown = label(p, people)
            when (p) {
                is Part.Plain -> out += "text" to p.s
                is Part.Link -> out += "link" to linkToken(p.url)
                is Part.Mention -> out += "mention" to p.pubkey
                is Part.Tag -> out += "hashtag" to p.tag
                is Part.Emoji -> out += "emoji" to p.code
                is Part.Quote -> if (p !== card) out += "shortlink" to p.raw
                is Part.QuoteAddr -> if (p !== card && p !== article) out += "shortlink" to p.raw
            }
            if (p !is Part.Link && shown != null) seen.append(shown)
        }
        for (pk in trailing) { out += "mention" to pk; seen.append("@" + (people[pk]?.shown ?: shortNpub(pk))) }
        return out to seen.toString()
    }
    sealed class Media
    /** [preview]: the post's own still of it (imeta "image"), for when it can't be shown itself. */
    data class Img(val url: String, val ratio: Float, val preview: String? = null) : Media()
    data class Vid(val v: VideoRef) : Media()

    /** The custom emoji's pictures, for the text's inline content. */
    fun emojiContent(): Map<String, androidx.compose.foundation.text.InlineTextContent> = parts.filterIsInstance<Part.Emoji>().associate { e ->
        EMOJI + e.url to androidx.compose.foundation.text.InlineTextContent(
            androidx.compose.ui.text.Placeholder(1.3.em, 1.3.em, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter)) {
            NetImage(e.url, Modifier.fillMaxSize(), 96, ContentScale.Fit, description = e.code, ground = Color.Transparent)
        }
    }

    companion object {
        /** A link as the fixtures' token: its address, and "|/<ref>" when it opens here (a viewer's link to a post or person). */
        fun linkToken(url: String): String = if (cc.aaro.cosign.inAppRef(url) == null || url.startsWith("nostr:")) url
            else url + "|/" + url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        const val EMOJI = "emoji:"
        // Links first (anything inside one is the link's), then references (with or without nostr:, not inside a word),
        // #tags (with a letter: #1 is text) and :emoji: (only those the post defines; others stay text).
        private val TOKEN = Regex("(https?://[^\\s<>\"]+)|(?<![\\p{L}\\p{N}_/:])(?:nostr:)?((?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]+)" +
            "|(?<=^|[\\s(])#((?=[\\p{N}_]*\\p{L})[\\p{L}\\p{N}_]{1,80})|:([A-Za-z0-9_+-]{1,60}):" +
            // A Blossom link (BUD-10): a picture or video shows as one; any other file, a short link.
            "|(blossom:[0-9a-fA-F]{64}\\.[A-Za-z0-9]{1,10}(?:\\?[^\\s<>\"]*)?)")
        private const val TRAILING = ".,;:!?)]}'\""

        /**
         * A post as one line of text (content-rules §9; reader.md §5): people as @names, a quoted post or article as
         * "↪ quoted" / "↪ an article", a viewer's link as "a post", other links shortened, custom emoji as :name:,
         * pictures and video left out ("A picture" when that's all), never a raw reference. Cut after this, not before.
         */
        fun line(e: Event, people: Map<String, Person> = emptyMap(), veil: Boolean = true): String {
            if (veil) warning(e)?.let { return it.ifEmpty { "Sensitive" } + ": show" }
            val b = of(e)
            fun name(pk: String) = "@" + (people[pk]?.shown ?: shortNpub(pk))
            fun ref(r: Ref) = when (r) { is Ref.Note -> "↪ quoted"; is Ref.Profile -> name(r.pubkey); is Ref.Addr -> if (r.kind == 30023) "↪ an article" else "↪ quoted" }
            val sb = StringBuilder()
            for (p in b.parts) when (p) {
                is Part.Plain -> sb.append(p.s)
                // A viewer's link (njump, primal, marble.syb.lol…) is "a post"; any other link, shortened.
                is Part.Link -> when (val r = if (p.url.startsWith("blossom:")) null else cc.aaro.cosign.inAppRef(p.url)) {
                    is Ref.Note -> sb.append("a post")
                    is Ref.Profile -> sb.append(name(r.pubkey))
                    is Ref.Addr -> sb.append(if (r.kind == 30023) "an article" else "a post")
                    null -> sb.append(p.text ?: p.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").let { if (it.length > 40) it.take(38) + "…" else it })
                }
                is Part.Mention -> sb.append(name(p.pubkey))
                is Part.Tag -> sb.append("#" + p.tag)
                is Part.Emoji -> sb.append(":" + p.code + ":")
                is Part.Quote -> sb.append("↪ quoted")
                is Part.QuoteAddr -> sb.append(ref(p.a))
            }
            for (pk in b.trailing) sb.append(" " + name(pk))
            val text = sb.toString().replace(Regex("\\s+"), " ").replace(Regex(" ([.,;:!?])(?=\\s|$)"), "$1").trim()
            // Nothing but its pictures or video: says so.
            return text.ifEmpty { if (b.media.any { it is Vid }) "A video" else if (b.media.isNotEmpty()) "A picture" else "A post" }
        }

        /** [s] cut to about [n] characters, at a space where there is one. */
        fun cut(s: String, n: Int): String = if (s.length <= n) s else s.take(n).let { c ->
            // At the last space; one past half-way if there's one, else any; a single run of letters is cut where it must be.
            c.substringBeforeLast(' ').takeIf { it.length > n / 2 } ?: c.substringBeforeLast(' ', "").ifEmpty { c } }.trimEnd() + "…"

        /** A NIP-36 content warning: its reason ("": none given); null: none. */
        fun warning(e: Event): String? = e.tags.firstOrNull { it.isNotEmpty() && it[0] == "content-warning" }?.let { it.getOrNull(1)?.trim() ?: "" }
        private val IMAGE = Regex("\\.(jpe?g|png|gif|webp|avif)(\\?.*)?$", RegexOption.IGNORE_CASE)
        private val VIDEO = Regex("\\.(mp4|webm|mov|m4v|m3u8)(\\?.*)?$", RegexOption.IGNORE_CASE)
        private val BREAKS = Regex("[ \\t]*\\n[ \\t\\n]*\\n[ \\t\\n]*")

        /** [raw]: a piece of a longer text (an article's): as it is, not trimmed or tidied, and no q-tag card. */
        fun of(e: Event, content: String = e.content, raw: Boolean = false): Body {
            // NIP-92 imeta (and NIP-71 video events): each file's shape, type, picture and length.
            val meta = e.tags.filter { it.isNotEmpty() && it[0] == "imeta" }.mapNotNull { t ->
                val f = t.drop(1).associate { it.substringBefore(' ') to it.substringAfter(' ') }
                f["url"]?.let { it to f }
            }.toMap()
            fun ratioOf(url: String) = meta[url]?.get("dim")?.split('x')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 && it[1] > 0 }?.let { it[0] / it[1] }
            fun isVideo(url: String) = VIDEO.containsMatchIn(url) || meta[url]?.get("m")?.startsWith("video/") == true
            fun video(url: String) = Vid(VideoRef(url, (meta[url]?.get("image") ?: meta[url]?.get("thumb"))?.takeIf { it.startsWith("https://") },
                ratioOf(url) ?: (16f / 9f), meta[url]?.get("duration")?.toFloatOrNull()?.toInt()))
            val emojis = e.tags.filter { it.size > 2 && it[0] == "emoji" && it[2].startsWith("https://") }.associate { it[1] to it[2] }
            val parts = ArrayList<Part>(); val images = ArrayList<Media>(); val mentions = ArrayList<String>()
            var at = 0
            val text = if (raw) content else content.trim()
            for (m in TOKEN.findAll(text)) {
                if (m.range.first > at) parts += Part.Plain(text.substring(at, m.range.first))
                val url = m.groups[1]?.value?.trimEnd { it in TRAILING }
                // (What's trimmed off a link's end is text again.)
                at = if (url != null) m.range.first + url.length else m.range.last + 1
                val ref = m.groups[2]?.value
                val tag = m.groups[3]?.value
                val emoji = m.groups[4]?.value
                val blossom = m.groups[5]?.value?.trimEnd { it in TRAILING }?.let { cc.aaro.cosign.Blossom.parse(it) }
                if (blossom != null) at = m.range.first + blossom.raw.length
                when {
                    url != null && isVideo(url) -> images += video(url)
                    url != null && (IMAGE.containsMatchIn(url) || meta[url]?.get("m")?.startsWith("image/") == true) -> images += Img(url, ratioOf(url) ?: 1.5f, (meta[url]?.get("image") ?: meta[url]?.get("thumb"))?.takeIf { it.startsWith("https://") })
                    url != null -> parts += Part.Link(url)
                    ref != null -> when (val r = Ref.parse(ref)) {
                        is Ref.Profile -> { parts += Part.Mention(r.pubkey); mentions += r.pubkey; Social.hint(r.pubkey, r.relays) }
                        is Ref.Note -> parts += Part.Quote(r.id, ref)
                        is Ref.Addr -> parts += Part.QuoteAddr(r, ref)
                        null -> parts += Part.Plain(m.value)
                    }
                    tag != null -> parts += Part.Tag(tag)
                    emoji != null -> parts += emojis[emoji]?.let { Part.Emoji(emoji, it) } ?: Part.Plain(m.value)
                    blossom != null -> when {
                        blossom.isVideo -> images += video(blossom.raw)
                        blossom.isImage -> images += Img(blossom.raw, ratioOf(blossom.raw) ?: 1.5f, null)
                        else -> parts += Part.Link(blossom.raw, "a .${blossom.ext} file")
                    }
                }
            }
            if (at < text.length) parts += Part.Plain(text.substring(at))
            // A video event (NIP-71) names its file only in imeta; so may any note.
            if (!raw) for ((url, f) in meta) if (f["m"]?.startsWith("video/") == true && images.none { it is Vid && it.v.url == url }) images += video(url)
            // Media links are pulled out of the text: tidy the line breaks they leave behind
            // (the text on either side of one is one stretch now).
            val joined = ArrayList<Part>()
            for (p in parts) { val last = joined.lastOrNull(); if (p is Part.Plain && last is Part.Plain) joined[joined.lastIndex] = Part.Plain(last.s + p.s) else joined += p }
            parts.clear(); parts += joined
            if (raw) return Body(parts.toList(), images, mentions)
            val tidy = parts.mapIndexed { i, p ->
                if (p !is Part.Plain) p else Part.Plain(p.s.replace(BREAKS, "\n\n").let { s -> if (i == 0) s.trimStart() else s }.let { s -> if (i == parts.lastIndex) s.trimEnd() else s })
            }
            // Quoted by q tag only (nothing in the text): its first.
            val q = e.tags.firstOrNull { it.size > 1 && it[0] == "q" }?.let { t ->
                val v = t[1].lowercase()
                if (cc.aaro.qrst.core.isHex32(v)) Part.Quote(v)
                else v.split(':', limit = 3).takeIf { it.size == 3 && cc.aaro.qrst.core.isHex32(it[1]) }?.let { (k, pk, d) -> k.toIntOrNull()?.let { Part.QuoteAddr(Ref.Addr(it, pk, t[1].split(':', limit = 3)[2]).apply { relays = listOfNotNull(t.getOrNull(2)?.takeIf { r -> r.startsWith("wss://") }) }) } }
            }
            // A run of 3+ mentions closing the post (only spaces and line breaks between them): out of the text, into chips.
            var cut = tidy.size
            val run = ArrayList<String>()
            while (cut > 0) {
                val p = tidy[cut - 1]
                if (p is Part.Mention) { run.add(0, p.pubkey); cut-- } else if (p is Part.Plain && p.s.isBlank()) cut-- else break
            }
            if (run.size >= 3) {
                val kept = tidy.take(cut).toMutableList()
                (kept.lastOrNull() as? Part.Plain)?.let { kept[kept.lastIndex] = Part.Plain(it.s.trimEnd()) }
                return Body(kept, images, mentions, q, run)
            }
            return Body(tidy, images, mentions, q)
        }
    }
}

/** A Blossom file, opened in the browser once it's found (Marble says so if it isn't). */
private fun openBlossom(raw: String) = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
    val app = CosignApp.instance
    val url = cc.aaro.cosign.Blossom.resolve(raw)
    if (url == null) android.widget.Toast.makeText(app, "Couldn't find that file", android.widget.Toast.LENGTH_SHORT).show()
    else try { app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

/** The text, drawn: links, mentions, #tags and short links tappable; custom emoji as pictures ([Body.emojiContent]). */
private fun Body.annotated(people: Map<String, Person>, nav: Nav): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = Vault.Accent))
    for (p in parts) { val shown = label(p, people); when (p) {
        is Body.Part.Plain -> append(p.s)
        // A marble.syb.lol link to a post: that post, here; any other link, in your browser.
        is Body.Part.Link -> if (p.url.startsWith("blossom:")) withLink(LinkAnnotation.Clickable(p.url, style) { openBlossom(p.url) }) { append(shown!!) }
            else cc.aaro.cosign.inAppRef(p.url)?.let { r -> withLink(LinkAnnotation.Clickable(p.url, style) { OpenPost.ref.value = r }) { append(shown!!) } }
            ?: withLink(LinkAnnotation.Url(p.url, style)) { append(shown!!) }
        is Body.Part.Mention -> withLink(LinkAnnotation.Clickable(p.pubkey, style) { nav.profile(p.pubkey) }) { append(shown!!) }
        // The first is shown in its own box under the text; any more, as a short link (never the raw string).
        is Body.Part.Quote -> if (shown != null) withLink(LinkAnnotation.Clickable(p.id, style) { nav.thread(p.id) }) { append(shown) }
        is Body.Part.QuoteAddr -> if (shown != null) withLink(LinkAnnotation.Clickable(p.a.naddr, style) { OpenPost.ref.value = p.a }) { append(shown) }
        is Body.Part.Tag -> withLink(LinkAnnotation.Clickable(p.tag, style) { nav.search("#" + p.tag) }) { append(shown!!) }
        is Body.Part.Emoji -> appendInlineContent(Body.EMOJI + p.url, ":${p.code}:")
    } }
}.let { if (it.text.isBlank() && parts.none { it is Body.Part.Emoji }) AnnotatedString("") else it }

@Composable
internal fun Avatar(p: Person?, pubkey: String, size: Int, modifier: Modifier = Modifier, description: String? = null) {
    val hue = remember(pubkey) { (pubkey.take(6).toLongOrNull(16) ?: 0L) % 360 }
    NetImage(p?.picture, modifier.size(size.dp).clip(CircleShape), size * 3, description = description ?: p?.shown) {
        Box(Modifier.fillMaxSize().background(Color.hsl(hue.toFloat(), 0.35f, 0.35f)), contentAlignment = Alignment.Center) {
            // No profile (yet): a figure, not a letter of an npub.
            if (p?.name == null) Icon(Icons.Filled.Person, null, tint = Vault.Text, modifier = Modifier.size((size * 0.6).dp))
            else Text(p.shown.take(1).uppercase(), color = Vault.Text, style = if (size > 50) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A post's time ("6h"). Tap it to copy the post's ID (its nevent), to find it in another app; the post
 * itself doesn't open. Pressed, it shows it can be tapped, and nothing more.
 */
@Composable
private fun PostTime(e: Event, social: Social, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) {
    val context = LocalContext.current
    Text(since(e.createdAt), color = Vault.Muted, style = style, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Copy the post's ID") { copy(context, social, social.neventOf(e), "Post ID copied") }.padding(horizontal = 4.dp, vertical = 2.dp))
}

/** Onto the clipboard; said briefly where Android doesn't show it itself (it does from Android 13). */
private fun copy(context: android.content.Context, social: Social, text: String, said: String) {
    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(said, text))
    if (android.os.Build.VERSION.SDK_INT < 33) social.message.value = said
}

/** A link to a post that opens anywhere, for people without Marble. */
private fun webLink(social: Social, e: Event) = cc.aaro.cosign.marbleLink(e)

private fun since(t: Long): String {
    val s = System.currentTimeMillis() / 1000 - t
    return when {
        s < 60 -> "now"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; s < 7 * 86400 -> "${s / 86400}d"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t * 1000))
    }
}

@Composable
private fun TopBar(title: String?, nav: Nav) = Column(Modifier.fillMaxWidth()) {
    // The bar the main page's header folds into, on every screen: back, the swirl and the name (home, like a
    // site's logo), and search.
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vault.Text) }
        Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Home", onClick = nav.home).padding(8.dp)) { Swirl(28f) }
        Spacer(Modifier.weight(1f))
        IconButton({ nav.social.clearSearch(); nav.open(Route.Search) }) { Icon(Icons.Filled.Search, "Search", tint = Vault.Text) }
        nav.social.me?.let { me -> Avatar(People.known.collectAsState().value[me], me, 32, Modifier.clickable { nav.open(Route.Account) }, "Your account") }
    }
    // What this screen is, when the screen itself doesn't say.
    title?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp)) }
}

/** The flat swirl (the themed icon's glyph) in its little disc: [size] dp across. */
@Composable
private fun Swirl(size: Float, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) =
    Box(modifier.size(size.dp).clip(CircleShape).background(if (Vault.dark) Color(0xFF2B2E3D) else Color(0xFFDCE2FA))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
        Icon(androidx.compose.ui.res.painterResource(cc.aaro.cosign.R.drawable.ic_launcher_monochrome), null, tint = if (Vault.dark) Color(0xFFB9C5FF) else Color(0xFF34427A),
            modifier = Modifier.requiredSize((size * 1.5f).dp))
    }

// --- A thread ------------------------------------------------------------------------------

@Composable
// safeDrawingPadding includes the keyboard: the reply box sits right on top of it.
private fun ThreadScreen(id: String, social: Social, nav: Nav, startReply: Boolean = false) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar(null, nav)
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    // At once, from what's here already (the note you tapped, the thread if you've opened it before);
    // the note it answers and the replies come in around it, with a small spinner while they do.
    var thread by remember(id) { mutableStateOf(social.threadNow(id)) }
    var loading by remember(id) { mutableStateOf(true) }
    LaunchedEffect(id) {
        val full = social.thread(id) { some -> if (some.isNotEmpty()) thread = some }
        if (full.isNotEmpty() || thread == null) thread = full
        loading = false
    }
    val eng by social.engagement.collectAsState()
    // Who you're answering: the post you opened, or any other you tap Reply on.
    var target by remember(id) { mutableStateOf(id) }
    val focus = remember { FocusRequester() }
    var focusNow by remember(id) { mutableStateOf(startReply) }
    val shown = thread
    var showAllReplies by remember(id) { mutableStateOf(false) }
    // Read so the replies fold again when the circle is known or changes.
    social.circleAt.collectAsState().value
    val articleBlocks = shown?.firstOrNull { it.second == 0 && it.first.kind == 30023 }?.first?.let { a -> remember(a.id) { a.id to Markdown.parse(a) } }
    Box(Modifier.weight(1f)) {
        when {
            shown == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
            shown.isEmpty() -> Text("Couldn't find this post.", color = Vault.Muted, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                // Under your own post: replies from outside your circle (and what answers them) fold into one line.
                val root = shown.firstOrNull { it.second == 0 }?.first
                val folded = if (root?.pubkey != social.me || showAllReplies) emptySet() else {
                    val out = HashSet<String>(); var under = Int.MAX_VALUE
                    for ((e, depth) in shown) {
                        if (depth <= under) under = Int.MAX_VALUE
                        if (depth > 0 && under == Int.MAX_VALUE && e.id != id && !social.inCircle(e.pubkey)) under = depth
                        if (under != Int.MAX_VALUE) out += e.id
                    }
                    out
                }
                for ((e, depth) in shown) {
                    if (e.id in folded) continue
                    // An article: the reader (its Markdown, set for reading); then its replies, as under any post.
                    if (e.kind == 30023 && depth == 0 && e.id == articleBlocks?.first) {
                        article(e, articleBlocks.second, people, nav, e.id in liked, onLike = { social.like(e) }, onReply = { target = e.id; focusNow = true })
                        item(key = "a:totals") { eng[e.id]?.let { Totals(it) }; HorizontalDivider(color = Vault.Edge) }
                        continue
                    }
                    item(key = e.id) {
                        NoteCard(e, people, e.id in liked, nav, onLike = { social.like(e) }, indent = minOf(depth, 4), lit = e.id == id && shown.size > 1 && depth > 0,
                            onReply = { target = e.id; focusNow = true })
                        if (depth == 0) {
                            ReactionChips(e, social)
                            eng[e.id]?.let { Totals(it) }
                            HorizontalDivider(color = Vault.Edge)
                        }
                    }
                }
                if (folded.isNotEmpty()) item(key = "more-replies") {
                    Text("More replies", color = Vault.Muted, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { showAllReplies = true }.padding(8.dp))
                }
                if (loading) item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = Vault.Accent, strokeWidth = 2.dp) }
                }
            }
        }
    }
    val parent = shown?.firstOrNull { it.first.id == target }?.first ?: social.note(target)
    if (parent != null && social.me != null) {
        ReplyBox(parent, people, social, focus, onSent = { thread = social.threadNow(id) ?: thread }, onClear = if (target != id) ({ target = id }) else null)
        LaunchedEffect(focusNow, target) { if (focusNow) { focus.requestFocus(); focusNow = false } }
    }
}

// --- An article (kind 30023), to read --------------------------------------------------------

/** An article's text in Literata, for reading: larger, with room between the lines. */
private val articleStyle @Composable get() = androidx.compose.ui.text.TextStyle(fontFamily = Literata, fontSize = 17.5.sp, lineHeight = 28.sp, color = Vault.Text)

/**
 * An article, as a reader (the serif mock): its picture, an "Article · 2 min read" label, the title, the summary in italic,
 * who wrote it and when, a short rule; its Markdown (a drop cap to start; headings in small capitals in the app's sans,
 * block quotes as pull quotes, pictures edge to edge); an end mark, then like, reply, repost, share and keep.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.article(e: Event, blocks: List<Markdown.Block>, people: Map<String, Person>, nav: Nav, liked: Boolean,
                                                                    onLike: () -> Unit, onReply: () -> Unit) {
    val title = e.firstTag("title")?.trim()?.ifEmpty { null }
    // (Many start with their title again, as a heading.)
    val body = blocks.dropWhile { b -> b is Markdown.Block.Heading && title != null && plainOf(b.spans).trim().equals(title, ignoreCase = true) }
    val firstPara = body.indexOfFirst { it is Markdown.Block.Para }
    val pictures = body.filterIsInstance<Markdown.Block.Picture>().map { it.url }
    item(key = "a:head") {
        Column {
            e.firstTag("image")?.takeIf { it.startsWith("https://") }?.let { img ->
                NetImage(img, Modifier.fillMaxWidth().aspectRatio(1.9f).clickable { nav.pictures(listOf(img) + pictures, 0) }, 1080)
            }
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(title ?: "Untitled", fontFamily = LiterataTitle, fontWeight = FontWeight.SemiBold, fontSize = 31.sp, lineHeight = 35.sp, color = Vault.Text,
                    modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
                e.firstTag("summary")?.trim()?.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }?.let {
                    Text(it, fontFamily = Literata, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 17.5.sp, lineHeight = 25.sp, color = Vault.Muted,
                        modifier = Modifier.padding(bottom = 16.dp))
                }
                val when_ = (e.firstTag("published_at")?.toLongOrNull() ?: e.createdAt)
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.profile(e.pubkey) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(people[e.pubkey], e.pubkey, 28)
                    Text(people[e.pubkey]?.shown ?: shortNpub(e.pubkey), color = Vault.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                    Text(" · " + java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(when_ * 1000)), color = Vault.Muted, fontSize = 13.sp)
                }
                Text("${Markdown.minutes(blocks)} min read", color = Vault.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 38.dp, top = 2.dp))
                Box(Modifier.padding(vertical = 18.dp).size(36.dp, 2.dp).clip(RoundedCornerShape(1.dp)).background(Vault.Accent))
            }
        }
    }
    body.forEachIndexed { i, b ->
        item(key = "a:$i") {
            val pad = Modifier.padding(horizontal = 24.dp)
            when (b) {
                is Markdown.Block.Para -> {
                    val (text, inline) = articleText(b.spans, people, nav)
                    if (i == firstPara) DropCap(text, inline, pad.padding(bottom = 14.dp))
                    else Text(text, pad.padding(bottom = 14.dp), style = articleStyle, inlineContent = inline)
                }
                is Markdown.Block.Heading -> Text(plainOf(b.spans).uppercase(), pad.padding(top = 22.dp, bottom = 8.dp), color = Vault.Text, fontWeight = FontWeight.SemiBold,
                    fontSize = if (b.level <= 2) 15.sp else 13.5.sp, letterSpacing = 0.06.em)
                is Markdown.Block.Item -> Row(pad.padding(start = 4.dp, bottom = 4.dp)) {
                    Text(b.n?.let { "$it." } ?: "•", Modifier.width(22.dp), color = Vault.Accent, style = articleStyle)
                    val (text, inline) = articleText(b.spans, people, nav)
                    Text(text, style = articleStyle, inlineContent = inline)
                }
                is Markdown.Block.Quote -> Row(pad.padding(vertical = 18.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Vault.Accent))
                    val (text, inline) = articleText(b.spans, people, nav)
                    Text(text, Modifier.padding(start = 16.dp), style = articleStyle.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 22.sp, lineHeight = 30.sp),
                        inlineContent = inline)
                }
                is Markdown.Block.Code -> Text(b.text, pad.padding(bottom = 14.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Vault.Surface)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp), fontFamily = PlexMono, fontSize = 13.5.sp, lineHeight = 20.sp, color = Vault.Text, softWrap = false)
                is Markdown.Block.Picture -> Column(Modifier.padding(top = 4.dp, bottom = 14.dp)) {
                    PostImage(b.url, null, Modifier.fillMaxWidth().aspectRatio(1.5f)) { nav.pictures(pictures, pictures.indexOf(b.url).coerceAtLeast(0)) }
                    if (b.alt.isNotEmpty()) Text(b.alt, Modifier.fillMaxWidth().padding(top = 6.dp, start = 24.dp, end = 24.dp), color = Vault.Muted, fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                is Markdown.Block.Video -> Box(pad.padding(bottom = 14.dp)) { VideoCard(b.v) }
                Markdown.Block.Rule -> EndMark()
                is Markdown.Block.Table -> ArticleTable(b, people, nav, Modifier.padding(start = 16.dp, bottom = 16.dp))
            }
        }
    }
    item(key = "a:end") {
        Column(Modifier.padding(horizontal = 24.dp)) {
            EndMark()
            HorizontalDivider(color = Vault.Edge)
            Box(Modifier.padding(top = 2.dp)) { PostActions(e, liked, nav, onLike, true, people[e.pubkey], onReply) }
        }
    }
}

@Composable
private fun EndMark() = Text("·   ·   ·", Modifier.fillMaxWidth().padding(vertical = 10.dp), color = Vault.Accent, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)

/** A run of spans as plain words (a heading in small capitals; comparing with the title). */
private fun plainOf(spans: List<Markdown.Span>) = spans.joinToString("") { (it.part as? Body.Part.Plain)?.s ?: (it.part as? Body.Part.Link)?.text ?: "" }

/** Spans, drawn: bold, italic and code; links, mentions, references (as short links) and #tags tappable; emoji as pictures. */
@Composable
private fun articleText(spans: List<Markdown.Span>, people: Map<String, Person>, nav: Nav): Pair<AnnotatedString, Map<String, androidx.compose.foundation.text.InlineTextContent>> {
    val inline = remember(spans) { Body(spans.map { it.part }, emptyList(), emptyList()).emojiContent() }
    val text = remember(spans, people.size) {
        // (No card here: every reference is a short link.)
        val none = Body(emptyList(), emptyList(), emptyList())
        val link = TextLinkStyles(SpanStyle(color = Vault.Accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
        buildAnnotatedString {
            for (sp in spans) withStyle(SpanStyle(fontWeight = if (sp.bold) FontWeight.Bold else null, fontStyle = if (sp.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                fontFamily = if (sp.code) PlexMono else null, background = if (sp.code) Vault.Surface else Color.Unspecified)) {
                val p = sp.part
                val shown = none.label(p, people)
                when (p) {
                    is Body.Part.Plain -> append(p.s)
                    is Body.Part.Link -> if (p.url.startsWith("blossom:")) withLink(LinkAnnotation.Clickable(p.url, link) { openBlossom(p.url) }) { append(shown!!) }
                        else cc.aaro.cosign.inAppRef(p.url)?.let { r -> withLink(LinkAnnotation.Clickable(p.url, link) { OpenPost.ref.value = r }) { append(shown!!) } }
                        ?: withLink(LinkAnnotation.Url(p.url, link)) { append(shown!!) }
                    is Body.Part.Mention -> withLink(LinkAnnotation.Clickable(p.pubkey, link) { nav.profile(p.pubkey) }) { append(shown!!) }
                    is Body.Part.Quote -> withLink(LinkAnnotation.Clickable(p.id, link) { nav.thread(p.id) }) { append(shown!!) }
                    is Body.Part.QuoteAddr -> withLink(LinkAnnotation.Clickable(p.a.naddr, link) { OpenPost.ref.value = p.a }) { append(shown!!) }
                    is Body.Part.Tag -> withLink(LinkAnnotation.Clickable(p.tag, link) { nav.search("#" + p.tag) }) { append(shown!!) }
                    is Body.Part.Emoji -> appendInlineContent(Body.EMOJI + p.url, ":${p.code}:")
                }
            }
        }
    }
    return text to inline
}

/**
 * The first paragraph, with a drop cap: its first letter large, in the accent, beside its first two lines (measured,
 * since Compose can't flow text around it). Not when the first word is one letter ("A swirl…"): that reads oddly.
 */
@Composable
private fun DropCap(text: AnnotatedString, inline: Map<String, androidx.compose.foundation.text.InlineTextContent>, modifier: Modifier) {
    val style = articleStyle
    val firstWord = text.text.takeWhile { !it.isWhitespace() }
    if (firstWord.length < 2 || !text.text[0].isLetter()) return Text(text, modifier, style = style, inlineContent = inline)
    BoxWithConstraints(modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val capStyle = style.copy(fontFamily = LiterataTitle, fontWeight = FontWeight.SemiBold, fontSize = 54.sp, lineHeight = 56.sp, color = Vault.Accent)
        val cap = text.text.substring(0, 1)
        val rest = text.subSequence(1, text.length)
        val split = remember(text, constraints.maxWidth) {
            val capW = measurer.measure(cap, capStyle).size.width + with(density) { 8.dp.roundToPx() }
            val laid = measurer.measure(rest, style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = (constraints.maxWidth - capW).coerceAtLeast(1)))
            if (laid.lineCount <= 2) rest.length else laid.getLineEnd(1)
        }
        Column {
            Row {
                Text(cap, Modifier.padding(end = 8.dp), style = capStyle)
                Text(rest.subSequence(0, split), style = style, inlineContent = inline)
            }
            if (split < rest.length) {
                val after = rest.subSequence(split, rest.length)
                Text(after.subSequence(after.text.length - after.text.trimStart().length, after.length), style = style, inlineContent = inline)
            }
        }
    }
}

/** Write a reply: to the post you opened, or the one you tapped Reply on. */
@Composable
private fun ReplyBox(parent: Event, people: Map<String, Person>, social: Social, focus: FocusRequester, onSent: () -> Unit, onClear: (() -> Unit)?) {
    var text by remember(parent.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val name = people[parent.pubkey]?.shown ?: shortNpub(parent.pubkey)
    HorizontalDivider(color = Vault.Edge)
    Column(Modifier.fillMaxWidth().background(Vault.Ground).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Replying to $name", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(start = 4.dp))
            onClear?.let { Icon(Icons.Filled.Close, "Reply to the first post instead", tint = Vault.Muted, modifier = Modifier.padding(start = 4.dp).size(24.dp).clip(CircleShape).clickable(onClick = it).padding(4.dp)) }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            TextField(text, { text = it }, Modifier.weight(1f).focusRequester(focus).heightIn(max = 160.dp), enabled = !sending,
                placeholder = { Text("Write a reply", color = Vault.Muted) },
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = Vault.Surface, unfocusedContainerColor = Vault.Surface, disabledContainerColor = Vault.Surface,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = Vault.Text, unfocusedTextColor = Vault.Text, disabledTextColor = Vault.Muted, cursorColor = Vault.Accent))
            Spacer(Modifier.width(6.dp))
            val ready = text.isNotBlank() && !sending
            Box(Modifier.padding(bottom = 4.dp).size(48.dp).clip(CircleShape).background(if (ready) Vault.Accent else Vault.Surface).clickable(enabled = ready) {
                sending = true
                scope.launch {
                    if (social.reply(parent, text) != null) { text = ""; onSent() }
                    sending = false
                }
            }, contentAlignment = Alignment.Center) {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), color = Vault.Text, strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (ready) Vault.Ground else Vault.Muted, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// --- A profile -----------------------------------------------------------------------------

@Composable
private fun ProfileScreen(pk: String, social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    val people by People.known.collectAsState()
    ProfileTimer(pk) { people[pk]?.name != null }
    val liked by social.liked.collectAsState()
    val follows by social.follows.collectAsState()
    val busy by social.busy.collectAsState()
    val p = people[pk]
    LaunchedEffect(pk) { social.profileNow(pk) }
    var pinned by remember(pk) { mutableStateOf<List<Event>>(emptyList()) }
    LaunchedEffect(pk) { pinned = try { social.pinnedOf(pk) } catch (_: Exception) { emptyList() } }
    // Not found anywhere after a proper look: say so, with another try, rather than an empty profile.
    var tries by remember(pk) { mutableStateOf(0) }
    var missing by remember(pk) { mutableStateOf(false) }
    LaunchedEffect(pk, tries) { missing = false; missing = !social.findProfile(pk) }
    TopBar(null, nav)
    // Their posts, a page at a time: more as you reach the end.
    // Each relay's posts come in as it answers, newest first; "none" only once every relay has.
    // Back from a post: the posts as they were, at once, so the list is where you left it (nothing loads again or moves).
    val before = remember(pk) { social.profilePosts(pk) }
    var notes by remember(pk) { mutableStateOf(before?.notes) }
    var settled by remember(pk) { mutableStateOf(before?.settled ?: false) }
    var more by remember(pk) { mutableStateOf(before?.more ?: true) }
    var paging by remember(pk) { mutableStateOf(false) }
    LaunchedEffect(pk) {
        if (before?.settled == true) return@LaunchedEffect
        social.notesBy(pk) { got -> notes = ((notes ?: emptyList()) + got).distinctBy { it.id }.sortedByDescending { it.createdAt } }
        settled = true
    }
    LaunchedEffect(pk, notes, settled, more) { social.keepProfilePosts(pk, Social.ProfilePosts(notes, settled, more)) }
    LaunchedEffect(pk) { val t0 = android.os.SystemClock.uptimeMillis(); snapshotFlow { notes?.size ?: -1 }.first { it >= 0 }.let { n ->
        if (debugBuild) android.util.Log.i("CosignProfile", "${pk.take(8)} posts ($n) at ${android.os.SystemClock.uptimeMillis() - t0} ms") } }
    val scope = rememberCoroutineScope()
    val nextPage = {
        val have = notes
        if (settled && have != null && have.isNotEmpty() && more && !paging) {
            paging = true
            scope.launch {
                val next = ArrayList<Event>()
                social.notesBy(pk, until = have.minOf { it.createdAt }) { got -> next += got.filter { n -> have.none { it.id == n.id } } }
                if (next.isEmpty()) more = false else notes = have + next.distinctBy { it.id }.sortedByDescending { it.createdAt }
                paging = false
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, pk, 72, Modifier.clickable(enabled = p?.picture != null) { p?.picture?.let { nav.pictures(listOf(it), 0) } })
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p?.shown ?: shortNpub(pk), style = MaterialTheme.typography.titleLarge, color = Vault.Text)
                        p?.nip05?.let { Text(it.removePrefix("_@"), color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val context = LocalContext.current
                Text(shortNpub(pk), fontFamily = PlexMono, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable {
                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Account ID", cc.aaro.qrst.core.Nip19.npub(pk)))
                    social.message.value = "Copied their account ID"
                })
                p?.about?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Vault.Text, style = MaterialTheme.typography.bodyMedium) }
                if (p == null && missing) {
                    Spacer(Modifier.height(10.dp))
                    Text("Couldn't find this profile", color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("It may be someone from another network whose profile hasn't come across, or the places that have it aren't answering.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                    TextButton({ tries++ }, contentPadding = PaddingValues(0.dp)) { Text("Try again", color = Vault.Accent) }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    pk == social.me -> Text("This is you", color = Vault.Muted)
                    pk in busy || follows == null -> OutlinedButton({}, Modifier.height(44.dp), enabled = false, border = BorderStroke(1.dp, Vault.Line)) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Vault.Accent, strokeWidth = 2.dp)
                    }
                    pk in follows!! -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ social.follow(pk, false) }, Modifier.height(44.dp), border = BorderStroke(1.dp, Vault.Line)) { Text("Following · Unfollow", color = Vault.Text) }
                        // ★: on your Special Follow list (first in Following), or not; tapped again, off.
                        val special by social.special.collectAsState()
                        val on = pk in special
                        OutlinedButton({ social.special(pk, !on) }, Modifier.height(44.dp), border = BorderStroke(1.dp, if (on) Vault.Accent else Vault.Line),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (on) Vault.AccentGround else androidx.compose.ui.graphics.Color.Transparent)) {
                            Text(if (on) "★ Special Follow" else "☆ Special Follow", color = if (on) Vault.Accent else Vault.Text)
                        }
                    }
                    else -> Button({ social.follow(pk, true) }, Modifier.height(44.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Follow") }
                }
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color = Vault.Edge)
        }
        when {
            notes.isNullOrEmpty() && !settled -> item(key = "loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) } }
            notes.isNullOrEmpty() -> item(key = "none") { Text("No posts yet.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
            else -> {
                // Pinned first, marked; on your own profile the mark unpins.
                items(pinned, key = { "pin" + it.id }) { n ->
                    val mine = pk == social.me
                    Row(Modifier.padding(start = 16.dp, top = 10.dp).then(if (mine) Modifier.clip(RoundedCornerShape(8.dp)).clickable { social.pin(n, false); pinned = pinned.filter { it.id != n.id } } else Modifier)
                        .padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📌 " + if (mine) "Pinned · Unpin" else "Pinned", color = Vault.Accent, style = MaterialTheme.typography.labelMedium)
                    }
                    NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12)
                    HorizontalDivider(color = Vault.Edge)
                }
                items(notes!!, key = { it.id }) { n ->
                    NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12)
                    HorizontalDivider(color = Vault.Edge)
                }
                item(key = "more") {
                    LaunchedEffect(notes!!.size, settled) { nextPage() }
                    if (paging || !settled) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = Vault.Accent, strokeWidth = 2.dp) }
                }
            }
        }
    }
}

/** A debug build: the timing logs that name what you look at are for these only. */
private val debugBuild by lazy { cc.aaro.cosign.CosignApp.instance.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }

/** Debug builds: a profile to open, asked for by intent. */
/** A search asked for from outside (a launcher's search: marble://search?q=…): Discover opens search with it. */
object OpenSearch { val query = kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }

/** A post to open, from a marble.syb.lol link that came from outside (an app link). */
object OpenPost {
    val id = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** A post, article or someone to open over where you are (back returns there). */
    val ref = kotlinx.coroutines.flow.MutableStateFlow<cc.aaro.cosign.Ref?>(null)
}

/** How long away before Marble opens on the main page again, rather than where you were. */
object Away {
    const val AFTER = 5 * 60_000L
    /** [AFTER], or shorter in a debug build's test (--el away_ms 20000). */
    var after = AFTER
}

object DebugOpen {
    val profile = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** A screen to open (--es open marble|stories|account|rename|compose|later|search:…|results:…|save:a|b), for tests that can't tap reliably; rename: your account, renaming this device. */
    val screen = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    @Volatile var renameThis = false
}

/** Debug builds: how long after opening a profile its name is on screen. */
@Composable
private fun ProfileTimer(pk: String, named: () -> Boolean) {
    val has by androidx.compose.runtime.rememberUpdatedState(named)
    LaunchedEffect(pk) {
        if (!debugBuild) return@LaunchedEffect
        val t0 = android.os.SystemClock.uptimeMillis()
        snapshotFlow { has() }.first { it }
        android.util.Log.i("CosignProfile", "${pk.take(8)} name at ${android.os.SystemClock.uptimeMillis() - t0} ms")
    }
}

// --- Search ----------------------------------------------------------------------------------

/** What's in the search box, kept while you look at results (and given back to the box when you tap it there). */
object SearchText {
    val q = kotlinx.coroutines.flow.MutableStateFlow("")
    /**
     * This search, in Searches (its id): the pages you open are listed under it, and changing it within a couple of
     * minutes is the same search. A new one starts with ×, an emptied box, typing over the whole search, or a fresh box.
     */
    val search = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Where you tapped in the results bar's text (the cursor goes there); null: at the end. */
    val cursor = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
}

/**
 * Search: the box is for typing in. Enter searches the web with your engine; Save (the chip at its end) keeps what
 * you typed in Later. As you type: what Enter will do, "Go to …" for a site, your engine's suggestions, and, only
 * if you follow people, "Search posts". @name finds people; a pasted link to someone or a post shows its page.
 */
@Composable
private fun SearchScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    val found by social.found.collectAsState()
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    val follows by social.follows.collectAsState()
    val engine by cc.aaro.cosign.SearchPrefs.engine.collectAsState()
    val suggestOn by cc.aaro.cosign.SearchPrefs.suggestions.collectAsState()
    val postsPref by cc.aaro.cosign.SearchPrefs.posts.collectAsState()
    val laterItems by social.later.items.collectAsState()
    var q by remember { mutableStateOf(SearchText.q.value.ifEmpty { found?.query ?: "" }) }
    LaunchedEffect(q) { SearchText.q.value = q }
    // Over a results page, with its search in the box: all of it selected, as a browser's address bar does, so typing
    // replaces it and a tap places the cursor to change it.
    var sel by remember { mutableStateOf(androidx.compose.ui.text.TextRange((SearchText.cursor.value ?: q.length).coerceIn(0, q.length)).also { SearchText.cursor.value = null }) }
    // A search that came from outside (the launcher) while this was open: the box shows it too.
    val running = found?.query
    // (Only a search that arrives after the screen opened: what was searched before isn't put back over the box.)
    val runningAtOpen = remember { running }
    LaunchedEffect(running) { if (running != null && running != runningAtOpen && running != q.trim() && "@$running" != q.trim()) { q = running; sel = androidx.compose.ui.text.TextRange(running.length) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val t = q.trim()
    // A pasted link to someone or a post: their page of results, here (see LinkResults).
    val pasted = remember(t) { Ref.parse(t) }
    // An address, or a well-known site's name: "Go to …". Enter opens it only when it's a sure thing.
    val site = remember(t) { cc.aaro.cosign.Sites.match(t) }
    val place = remember(t, laterItems) { cc.aaro.cosign.HomeRules.goTo(t, PLACES, laterItems.filter { it.deleted == null && it.done == null }.map { cc.aaro.cosign.Later.wordsOf(it.text) }) }
    // A handle (alice@example.com, @name.bsky.social, @name): rows for what a quick lookup confirms, once it's whole and
    // you've stopped typing (at once for a paste). "@" is no mode: Enter searches the web all the same.
    val handle = remember(t) { cc.aaro.cosign.Handles.kind(t) }
    val pastedIn = remember { booleanArrayOf(false) }
    val handleRows by produceState(emptyList<cc.aaro.cosign.Handles.Row>(), handle) {
        value = emptyList()
        val k = handle ?: return@produceState
        if (!pastedIn[0]) delay(600)
        value = cc.aaro.cosign.Handles.look(k, social)
        cc.aaro.cosign.People.want(value.mapNotNull { it.pubkey })
    }
    // Instant: people, posts and articles for what you've typed (Marble's own search, through Ditto's relays), a quarter
    // second after you stop. A newer search cancels the one before; your follows' matches come first, at once.
    val bareName = remember(t) { cc.aaro.cosign.Handles.kind(t) is cc.aaro.cosign.Handles.Kind.Name }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val saved = remember(laterItems, t) { social.later.saved(t) }
    // The bookmark keeps what you typed as a note; tapped again straight after, it's taken back.
    var undo by remember { mutableStateOf<cc.aaro.cosign.LaterItem?>(null) }
    val save = {
        if (t.isNotEmpty()) {
            if (saved == null) { undo = social.later.save(t); social.interests.noticed(t) }
            else if (saved.id == undo?.id) { social.later.unsave(saved); undo = null }
        }
    }
    // Your own, first: up to two of your searches and notes with these words (your latest searches, with the box empty).
    val searchList by social.searches.all.collectAsState()
    // The notes trial's choices for an old note held in the results (Next step / Keep in view / Let go).
    var deciding by remember { mutableStateOf<cc.aaro.cosign.LaterItem?>(null) }
    deciding?.let { n -> NoteDecision(n, social) { deciding = null } }
    val own = remember(t, searchList, laterItems) {
        val s = searchList.filter { t.isEmpty() || (it.text.contains(t, ignoreCase = true) && !cc.aaro.cosign.Later.same(it.text, t)) }.map { it.text to null as cc.aaro.cosign.LaterItem? }
        val n = if (t.isEmpty()) emptyList() else laterItems.filter { it.deleted == null && it.done == null && cc.aaro.cosign.Later.wordsOf(it.text).contains(t, ignoreCase = true) && !cc.aaro.cosign.Later.same(it.text, t) }.map { cc.aaro.cosign.Later.wordsOf(it.text) to it }
        (s + n).distinctBy { it.first.lowercase() }.take(2)
    }
    // A search: into Searches (an edit of this one takes its place); its results are already here, so the keyboard goes.
    fun searchFor(x: String) {
        SearchText.search.value = social.searches.searched(x, SearchText.search.value).id; social.interests.noticed(x)
        if (x != t) { q = x; sel = androidx.compose.ui.text.TextRange(x.length) }
        focusManager.clearFocus()
    }
    // A site, from the box: in Searches too, with the site under it.
    fun goTo(s: cc.aaro.cosign.Sites.Go) { social.searches.searched(t, SearchText.search.value).let { social.searches.opened(it.id, s.url, s.address) }; SearchText.search.value = null; openInBrowser(context, s.url) }
    val go = {
        when {
            t.isEmpty() || pasted != null -> {}
            site?.sure == true -> goTo(site)
            else -> searchFor(t)
        }
    }
    LaunchedEffect(t) {
        if (t.length < 2 || pasted != null) { if (t.isEmpty() || pasted != null) social.clearSearch(); return@LaunchedEffect }
        delay(250)
        if (social.found.value?.query != t) social.search(t)
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vault.Text) }
        // Home, as in every screen's bar.
        Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Home", onClick = nav.home).padding(6.dp)) { Swirl(28f) }
        Spacer(Modifier.width(6.dp))
        TextField(
            androidx.compose.ui.text.input.TextFieldValue(q, if (sel.max <= q.length) sel else androidx.compose.ui.text.TextRange(q.length)), { v ->
                pastedIn[0] = v.text.length - q.length > 1
                // Typed over the whole search, or emptied: a new search from here.
                if (v.text != q && (v.text.isEmpty() || (q.isNotEmpty() && sel.min == 0 && sel.max == q.length))) SearchText.search.value = null
                sel = v.selection; q = v.text
            },
            Modifier.weight(1f).focusRequester(focus), singleLine = true,
            placeholder = { Text("Search or jot anything…", color = Vault.Muted) },
            // A web address box, as a browser's: what you type stays exactly as typed (no corrections, no capitals, no space
            // after a full stop), so sites work; the keyboard's suggestions are still there to tap.
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            trailingIcon = { if (q.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                // Clear: the whole search, in one tap.
                Icon(Icons.Filled.Close, "Clear", tint = Vault.Muted, modifier = Modifier.size(36.dp).clip(CircleShape).clickable { q = ""; sel = androidx.compose.ui.text.TextRange(0); SearchText.search.value = null }.padding(8.dp))
                if (t.isNotEmpty() && pasted == null) {
                    Icon(if (saved != null) Glyphs.saveOn else Glyphs.save, if (saved != null) "Kept as a note" else "Keep as a note", tint = Vault.Accent,
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = save).padding(9.dp))
                    GoChip { go() }
                }
            } },
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Vault.Surface, unfocusedContainerColor = Vault.Surface, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Vault.Text, unfocusedTextColor = Vault.Text, cursorColor = Vault.Accent),
        )
    }
    // Your own and the links first, then people, posts and articles as they come; no web search.
    Box(Modifier.fillMaxSize()) {
        // What goes above the results: a typed address's "Go to", what a handle confirms, and two of your own.
        val top: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            // Go to a place by its name (home-rules.md), never above your own note that's exactly what's typed.
            place?.let { (name, below) ->
                if (below) laterItems.firstOrNull { it.deleted == null && it.done == null && cc.aaro.cosign.Later.same(it.text, t) }?.let { n ->
                    item(key = "exact") { OwnRow(n.text, n, t) { if (n.url != null) openInBrowser(context, n.url, "l:" + n.id) else searchFor(n.text) } }
                }
                item(key = "place") { GoToPlace(name) { goToPlace(name, nav, context) } }
            }
            site?.let { s -> item(key = "site") { GoToSite(s) { goTo(s) } } }
            items(handleRows, key = { "h:" + (it.pubkey ?: it.url) }) { r ->
                if (r.pubkey != null) PersonRow(r.pubkey, people[r.pubkey], null, nav)
                else r.url?.let { u -> HandleRow(r) { social.searches.searched(t, SearchText.search.value).let { social.searches.opened(it.id, u, android.net.Uri.parse(u).host ?: u) }; SearchText.search.value = null; openInBrowser(context, u) } }
            }
            items(own, key = { "o:" + it.first }) { (text, note) ->
                note?.let { n -> LaunchedEffect(n.id) { cc.aaro.cosign.NotesTrial.shown(n.id, "search") } }
                OwnRow(text, note, t, onLong = note?.takeIf { cc.aaro.cosign.NotesTrial.on && it.done == null && System.currentTimeMillis() / 1000 - it.created > 30 * 86_400L }
                    ?.let { n -> { deciding = n } }) {
                    note?.let { cc.aaro.cosign.NotesTrial.opened(it.id, "search") }
                    if (note?.url != null) openInBrowser(context, note.url, "l:" + note.id) else searchFor(text)
                }
            }
        }
        when {
            pasted != null -> LinkResults(pasted, social, nav)
            t.length >= 2 -> MarbleResults(found?.takeIf { it.query == t }, people, liked, social, nav, peopleOnly = false, showPeople = !bareName, top = top)
            t.isEmpty() -> FocusList(social, nav, laterItems, searchList, onSearch = { text -> q = text; searchFor(text) }, onDecide = { deciding = it })
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) { top() }
        }
    }
}

/**
 * A search's results: people, posts and articles (Marble's own search, through Ditto's relays); no web search.
 * The box shows the search; its bookmark keeps it as a note.
 * A bare @name shows no people from open search (anyone can call themselves anything); the box's own rows cover it.
 */
@Composable
private fun PeoplePostsScreen(q: String, social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    val found by social.found.collectAsState()
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    val laterItems by social.later.items.collectAsState()
    val saved = remember(laterItems, q) { social.later.saved(q) }
    LaunchedEffect(q) { if (social.found.value?.query != q) social.search(q) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Home", onClick = nav.home).padding(10.dp)) { Swirl(28f) }
        Row(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(26.dp))
            .clickable(onClickLabel = "Change the search") { SearchText.q.value = q; nav.open(Route.Search) }.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Vault.Muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(q, color = Vault.Text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(if (saved != null) Glyphs.saveOn else Glyphs.save, if (saved != null) "Kept as a note" else "Keep as note", tint = Vault.Accent,
                modifier = Modifier.padding(end = 6.dp).size(40.dp).clip(CircleShape).clickable { if (saved == null) social.later.save(q) }.padding(10.dp))
        }
    }
    val bareName = remember(q) { cc.aaro.cosign.Handles.kind(q) is cc.aaro.cosign.Handles.Kind.Name }
    MarbleResults(found?.takeIf { it.query == q }, people, liked, social, nav, peopleOnly = false, showPeople = !bareName)
}

/**
 * Your engine's results, inside Marble: only the engine's own pages open here (its tabs, the next page, its EU
 * consent page). A result, or any other link, opens in your browser, unwrapped from the engine's click-through
 * (ads stay wrapped). The box shows what you searched; Save keeps it in Later.
 */
@Composable
private fun ResultsScreen(q: String, social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    val engine by cc.aaro.cosign.SearchPrefs.engine.collectAsState()
    val laterItems by social.later.items.collectAsState()
    // The search on screen: what you typed, or what you changed it to in the page (a "did you mean", a tab).
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(q) }
    val saved = remember(laterItems, query) { social.later.saved(query) }
    val context = LocalContext.current
    val dark = Vault.dark
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: nav.back() }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Home", onClick = nav.home).padding(10.dp)) { Swirl(28f) }
        Row(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(26.dp))
            .clickable(onClickLabel = "Change the search") { SearchText.q.value = query; nav.open(Route.Search) }.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Vault.Muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            // A tap on the words: the cursor goes there (past them, at the end), as a search box's does.
            var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            Text(query, color = Vault.Text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it },
                modifier = Modifier.weight(1f).pointerInput(query) {
                    detectTapGestures { at -> SearchText.cursor.value = layout?.getOffsetForPosition(at); SearchText.q.value = query; nav.open(Route.Search) }
                })
            // Kept as a note: the bookmark, filled. Opened a result (a visit now): empty again, a tap keeps it.
            Icon(if (saved != null) Glyphs.saveOn else Glyphs.save, if (saved != null) "Kept as a note" else "Keep as note", tint = Vault.Accent,
                modifier = Modifier.padding(end = 6.dp).size(40.dp).clip(CircleShape).clickable { if (saved == null) social.later.save(query) }.padding(10.dp))
        }
    }
    if (loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Vault.Accent, trackColor = Color.Transparent)
    else Spacer(Modifier.height(2.dp))
    androidx.compose.ui.viewinterop.AndroidView({ c ->
        android.webkit.WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // A link elsewhere: the real page in your browser, and a note of it in Later's pages.
            fun outside(u: android.net.Uri) {
                val real = cc.aaro.cosign.Engine.unwrap(u)
                val url = real.toString()
                if (real.scheme == "http" || real.scheme == "https") {
                    // Listed under this search in Searches (one opened from a note, say, starts its own).
                    val id = SearchText.search.value ?: social.searches.searched(query).id.also { SearchText.search.value = it }
                    social.searches.opened(id, url, real.host?.removePrefix("www.") ?: url)
                    social.titlePage(url)
                    // In a Custom Tab: the Keep bookmark is there, Back closes it, and no tab is left behind in your browser.
                    openInBrowser(c, url)
                    return
                }
                try { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, real).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            // The search changed in the page: Marble's box follows, and it's in Searches (changed straight away: the same search).
            fun followed(u: android.net.Uri) {
                val nq = engine.query(u) ?: return
                if (cc.aaro.cosign.Later.same(nq, query)) return
                query = nq; SearchText.q.value = nq
                SearchText.search.value = social.searches.searched(nq, SearchText.search.value).id
            }
            // The engine's own box, hidden: one box, Marble's.
            fun hideOwnBox(v: android.webkit.WebView) = v.evaluateJavascript(
                "(function(){if(document.getElementById('marble-one-box'))return;var s=document.createElement('style');s.id='marble-one-box';" +
                "s.textContent='${engine.ownBox}{display:none!important}';(document.head||document.documentElement).appendChild(s);})()", null)
            webViewClient = object : android.webkit.WebViewClient() {
                override fun doUpdateVisitedHistory(view: android.webkit.WebView, url: String, isReload: Boolean) { followed(android.net.Uri.parse(url)) }
                override fun onPageCommitVisible(view: android.webkit.WebView, url: String) { hideOwnBox(view) }
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val u = request.url
                    if (engine.owns(u)) return false
                    outside(u); return true
                }
                override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?) { loading = true }
                override fun onPageFinished(view: android.webkit.WebView, url: String) { loading = false; hideOwnBox(view) }
            }
            loadUrl(engine.results(q, dark))
            web = this
        }
    }, Modifier.fillMaxSize(), onRelease = { it.destroy() })
}

/** Later: what you saved (your words), what you've done, and the pages you opened from here; with its own filter. */
@Composable
private fun LaterScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    LaunchedEffect(Unit) { social.refreshLater() }
    val items by social.later.items.collectAsState()
    var filter by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(LaterTab.value.takeIf { it == "done" } ?: "saved") }
    val f = filter.trim()
    fun hit(t: String) = f.isEmpty() || t.contains(f, ignoreCase = true)
    val open = items.filter { it.deleted == null && it.done == null && (hit(it.text) || it.quote?.let(::hit) == true) }
    val done = items.filter { it.deleted == null && it.done != null && (hit(it.text) || it.quote?.let(::hit) == true) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vault.Text) }
        Text("Notes and searches", style = MaterialTheme.typography.headlineSmall, color = Vault.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        OnlyYou()
    }
    FilterBox(filter, "Filter your notes") { filter = it }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, label) in listOf("saved" to "Notes", "done" to "Done")) {
            val on = show == k
            Text(label, color = if (on) Vault.Ground else Vault.Text, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Vault.Accent else Vault.Surface).border(1.dp, if (on) Vault.Accent else Vault.Edge, RoundedCornerShape(18.dp))
                    .clickable { show = k }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
    val shelf by cc.aaro.cosign.NotesTrial.shelf.collectAsState()
    val answer by cc.aaro.cosign.NotesTrial.answer.collectAsState()
    val trial = cc.aaro.cosign.NotesTrial.on
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // Day 28 of the notes trial: the one question, asked once.
        if (cc.aaro.cosign.NotesTrial.ask && answer == null) item(key = "ask") { TrialQuestion() }
        // The trial (from day 15): Keep in view at the top (no number), then the rest, the ones you haven't seen lower.
        val inView = if (trial && show != "done") shelf.mapNotNull { id -> open.firstOrNull { it.id == id } } else emptyList()
        if (inView.isNotEmpty()) {
            item(key = "kh") { Text("Keep in view", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 2.dp)) }
            items(inView, key = { "v" + it.id }) { item -> LaterRow(item, f, emptyList(), social, nav) }
            item(key = "kd") { Spacer(Modifier.height(10.dp)) }
        }
        val list = if (show == "done") done else if (trial) cc.aaro.cosign.NotesTrial.order(open.filter { it.id !in shelf }) else open
        items(list, key = { "i" + it.id }) { item -> LaterRow(item, f, emptyList(), social, nav) }
        if (list.isEmpty()) item { Text(if (f.isEmpty()) "Nothing yet." else "Nothing matches “$f”.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
    }
    // Your searches, at the foot.
    HorizontalDivider(color = Vault.Edge)
    Row(Modifier.fillMaxWidth().clickable { nav.open(Route.Searches) }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyphs.clock, null, tint = Vault.Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text("Searches", color = Vault.Text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FilterBox(value: String, hint: String, onChange: (String) -> Unit) = TextField(value, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), singleLine = true,
    placeholder = { Text(hint, color = Vault.Muted) }, leadingIcon = { Icon(Icons.Filled.Search, null, tint = Vault.Muted) },
    shape = RoundedCornerShape(26.dp),
    colors = TextFieldDefaults.colors(focusedContainerColor = Vault.Surface, unfocusedContainerColor = Vault.Surface, focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Vault.Text, unfocusedTextColor = Vault.Text, cursorColor = Vault.Accent))

/**
 * Searches: what you searched on this phone, 30 days, by day; under each, the sites you opened from it (a tap opens
 * one). A tap on a search searches it again; its bookmark keeps the words as a note; hold to take it out.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SearchesScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    val all by social.searches.all.collectAsState()
    val items by social.later.items.collectAsState()
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    val f = filter.trim()
    val list = remember(all, f) { all.filter { e -> f.isEmpty() || e.text.contains(f, ignoreCase = true) || e.pages.any { it.url.contains(f, ignoreCase = true) || it.title.contains(f, ignoreCase = true) } } }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vault.Text) }
        Text("Searches", style = MaterialTheme.typography.headlineSmall, color = Vault.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        OnlyYou()
    }
    FilterBox(filter, "Filter your searches") { filter = it }
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    fun dayOf(at: Long) = java.time.Instant.ofEpochSecond(at).atZone(zone).toLocalDate()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (list.isEmpty()) item { Text(if (f.isEmpty()) "Nothing yet." else "Nothing matches “$f”.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
        for ((day, inDay) in list.groupBy { dayOf(it.at) }) {
            item(key = "d$day") {
                Text(when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")) },
                    color = Vault.Muted, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
            }
            items(inDay, key = { "s" + it.id }) { e ->
                var menu by remember { mutableStateOf(false) }
                val kept = items.any { it.url == null && it.deleted == null && it.done == null && cc.aaro.cosign.Later.same(it.text, e.text) }
                Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { SearchText.q.value = e.text; SearchText.search.value = social.searches.searched(e.text).id; nav.results(e.text) }
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.text, color = Vault.Text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (e.pages.isNotEmpty()) Row(Modifier.padding(top = 2.dp)) {
                            e.pages.distinctBy { android.net.Uri.parse(it.url).host }.take(3).forEachIndexed { i, p ->
                                if (i > 0) Text(" · ", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
                                Text(android.net.Uri.parse(p.url).host?.removePrefix("www.") ?: p.url, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                    modifier = Modifier.clickable(onClickLabel = "Open ${p.title}") { openInBrowser(context, p.url) })
                            }
                        }
                    }
                    Icon(if (kept) Glyphs.saveOn else Glyphs.save, if (kept) "Kept as a note" else "Keep as a note", tint = if (kept) Vault.Accent else Vault.Line,
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable { if (!kept) social.later.save(e.text) }.padding(11.dp))
                    if (menu) DropdownMenu(true, { menu = false }, containerColor = Vault.Surface) {
                        DropdownMenuItem({ Text("Remove from Searches", color = Vault.Danger) }, { menu = false; social.searches.remove(e.id) })
                    }
                }
            }
        }
    }
}

/** A page's own little icon (its favicon, asked of the site itself, kept with the pictures), or its first letter until then. */
@Composable
private fun SiteIcon(url: String, size: Int) {
    val host = android.net.Uri.parse(url).host?.removePrefix("www.") ?: ""
    val scheme = android.net.Uri.parse(url).scheme ?: "https"
    NetImage("$scheme://${android.net.Uri.parse(url).host}/favicon.ico", Modifier.size(size.dp).clip(RoundedCornerShape(5.dp)), px = size * 3,
        scale = androidx.compose.ui.layout.ContentScale.Fit, ground = Vault.AccentGround) {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
            Text(host.take(1).uppercase(), color = Vault.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * One saved thing: a circle to tick it done, then what it is, so the two kinds never look alike. Your own words in a
 * typewriter face ("you typed this"; tap: search them again); a page with its site's icon, its title in the usual face
 * and "site · page" under it (tap: open it). Hold to remove.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LaterRow(item: cc.aaro.cosign.LaterItem, filter: String, page: List<cc.aaro.cosign.Page>, social: Social, nav: Nav) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(item.id) { cc.aaro.cosign.NotesTrial.shown(item.id, "notes") }
    Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { cc.aaro.cosign.NotesTrial.opened(item.id, "notes"); FeedLog.open("l:" + item.id, "note"); item.url?.let { openInBrowser(context, it, "l:" + item.id) } ?: nav.results(item.text) }
        .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
        IconButton({ social.later.done(item, item.done == null) }) {
            if (item.done != null) Icon(Icons.Filled.CheckCircle, "Not done", tint = Vault.Accent)
            else Box(Modifier.size(24.dp).border(1.5.dp, Vault.Line, CircleShape))
        }
        if (item.url != null) Box(Modifier.padding(top = 12.dp, end = 10.dp)) { SiteIcon(item.url, 20) }
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                val i = if (filter.isEmpty()) -1 else item.text.indexOf(filter, ignoreCase = true)
                if (i < 0) append(item.text) else {
                    append(item.text.take(i)); withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(item.text.substring(i, i + filter.length)) }; append(item.text.drop(i + filter.length))
                }
            }, color = if (item.done != null) Vault.Muted else Vault.Text,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (item.url == null) androidx.compose.ui.text.font.FontFamily.Monospace else null),
                textDecoration = if (item.done != null) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            // Kept alongside: what was shared (two lines of it), and where from; a page's site.
            item.quote?.let { Text(cc.aaro.cosign.readable(it) { pk -> People.known.value[pk]?.name }, color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            val site = item.url?.let { android.net.Uri.parse(it).host?.removePrefix("www.") ?: it } ?: item.from
            // (After the notes trial, no ages: only where it's from.)
            // No ages (home-rules.md: no counts, no ages).
            val age: String? = null
            listOfNotNull(site, age).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, color = Vault.Muted, style = MaterialTheme.typography.labelMedium) }
        }
        if (menu) DropdownMenu(true, { menu = false }, containerColor = Vault.Surface) {
            if (cc.aaro.cosign.NotesTrial.on && item.done == null) {
                val inView = item.id in cc.aaro.cosign.NotesTrial.shelf.collectAsState().value
                DropdownMenuItem({ Text(if (inView) "Take out of Keep in view" else "Keep in view", color = Vault.Text) }, {
                    menu = false; cc.aaro.cosign.NotesTrial.keepInView(item.id, !inView); if (!inView) cc.aaro.cosign.NotesTrial.decided(item.id, "view")
                })
            }
            DropdownMenuItem({ Text("Remove from Later", color = Vault.Danger) }, { menu = false; social.later.remove(item) })
        }
    }
    HorizontalDivider(color = Vault.Edge)
}

/** On what's yours alone (Later, a thought you keep): a small lock and [text]. */
@Composable
private fun OnlyYou(text: String = "Only you", modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Filled.Lock, null, tint = Vault.Muted, modifier = Modifier.size(13.dp))
    Spacer(Modifier.width(4.dp))
    Text(text, color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
}

/** "just now", "5 days ago", "3 weeks ago", "2 months ago": [sec] in plain words. */
private fun agoWords(sec: Long): String {
    val d = (cc.aaro.cosign.core.Clock.now() - sec).coerceAtLeast(0)
    fun n(v: Long, unit: String) = "$v $unit${if (v == 1L) "" else "s"} ago"
    return when {
        d < 120 -> "just now"
        d < 3600 -> n(d / 60, "minute")
        d < 86_400 -> n(d / 3600, "hour")
        d < 2 * 86_400 -> "yesterday"
        d < 14 * 86_400 -> n(d / 86_400, "day")
        d < 60 * 86_400 -> n(d / (7 * 86_400), "week")
        else -> n(d / (30 * 86_400), "month")
    }
}

/** "Save" at the end of the box; "✓ Saved" once it's in Later (a tap takes it out again). */
@Composable
private fun SaveChip(saved: Boolean, onClick: () -> Unit) = Row(
    Modifier.padding(end = 6.dp).clip(RoundedCornerShape(18.dp)).background(if (saved) Vault.Accent else Vault.AccentGround).clickable(onClickLabel = if (saved) "In Later" else "Save to Later", onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(if (saved) Icons.Filled.CheckCircle else Glyphs.save, null, tint = if (saved) Vault.Ground else Vault.Accent, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
    Text(if (saved) "Saved" else "Save", color = if (saved) Vault.Ground else Vault.Accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
}

/**
 * The box's Enter, in the Save chip's shape (0.12.18): a return arrow drawn as Marble's other line icons are. It
 * searches (keeping is the bookmark's). [SOLID_ENTER]: filled cobalt instead of the pale chip.
 */
@Composable
private fun GoChip(onClick: (() -> Unit)?) = Box(
    Modifier.padding(end = 6.dp).clip(RoundedCornerShape(18.dp)).background(if (SOLID_ENTER) Vault.Accent else Vault.AccentGround)
        .then(if (onClick != null) Modifier.clickable(onClickLabel = "Search", onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = 7.dp),
    contentAlignment = Alignment.Center,
) {
    Icon(Glyphs.enter, "Enter", tint = if (SOLID_ENTER) Vault.Ground else Vault.Accent, modifier = Modifier.size(20.dp))
}
private const val SOLID_ENTER = false

/** Places you can go by typing their name (home-rules.md "Typed"). */
private val PLACES = listOf("Reader", "Notes", "Searches", "Settings", "Approvals", "Devices", "About")

/** Asks home's pager for a pane (Go to Reader, from the box). */
private val PaneWanted = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

private fun goToPlace(name: String, nav: Nav, context: android.content.Context) = when (name) {
    "Reader" -> { nav.home(); PaneWanted.value = PANE_FOLLOWING }
    "Notes" -> { LaterTab.value = "saved"; nav.open(Route.Later) }
    "Searches" -> nav.open(Route.Searches)
    "Approvals" -> nav.open(Route.Approvals)
    "About" -> cc.aaro.cosign.Tabs.open(context, "https://marble.syb.lol/about", false, Vault.dark, Vault.Ground.toArgb(), null)
    else -> nav.open(Route.Account)
}

/** "Go to Settings": a place, by its name. */
@Composable
private fun GoToPlace(name: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClickLabel = "Go to $name", onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(Glyphs.openIn, null, tint = Vault.Accent, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(16.dp))
    Text("Go to $name", color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
}

/**
 * The focused, empty box (home-rules.md, at most 7 rows): Keep in view, one older note (both from the notes trial's
 * day 15), your 2 latest searches or notes, then Notes and searches. No dates, no counts; your own words in typewriter type.
 */
@Composable
private fun FocusList(social: Social, nav: Nav, laterItems: List<cc.aaro.cosign.LaterItem>, searchList: List<cc.aaro.cosign.SearchEntry>,
                      onSearch: (String) -> Unit, onDecide: (cc.aaro.cosign.LaterItem) -> Unit) {
    val context = LocalContext.current
    val shelf by cc.aaro.cosign.NotesTrial.shelf.collectAsState()
    val trial = cc.aaro.cosign.NotesTrial.on
    val open = remember(laterItems) { laterItems.filter { it.deleted == null && it.done == null } }
    val byId = remember(open) { open.associateBy { it.id } }
    // This account's (the accounts on a phone share these prefs).
    val prefs = remember { CosignApp.instance.getSharedPreferences("marble", android.content.Context.MODE_PRIVATE) }
    val offerKey = "olderOffered:" + social.me
    val rows = remember(open, searchList, shelf, trial) {
        // "id offeredAt seenAtOffer": the older note offered today, and how long unseen it was then, so being shown in
        // the box (which counts as seeing it) doesn't make it stop being the day's older note.
        val held = prefs.getString(offerKey, null)?.split(' ')?.takeIf { it.size == 3 }
        val offered = held?.let { it[0] to (it[1].toLongOrNull() ?: 0) }
        val seen = open.associate { it.id to maxOf(cc.aaro.cosign.NotesTrial.seen(it.id), it.updated) }.toMutableMap()
        // Today only: tomorrow, being shown counts as seen, and another note's turn comes.
        val today = System.currentTimeMillis() / 1000 / 86_400L
        held?.let { h -> if (h[0] in seen && (h[1].toLongOrNull() ?: 0) / 86_400L == today) seen[h[0]] = maxOf(h[2].toLongOrNull() ?: 0, byId[h[0]]?.updated ?: 0) }
        cc.aaro.cosign.HomeRules.focus(if (trial) shelf else emptyList(), open.map { cc.aaro.cosign.HomeRules.Note(it.id, cc.aaro.cosign.Later.wordsOf(it.text), it.created) },
            searchList.map { cc.aaro.cosign.HomeRules.Search(it.text, it.at) }, System.currentTimeMillis() / 1000, seen, offered, older = trial)
    }
    LaunchedEffect(rows) { rows.firstOrNull { it.kind == "older" }?.let { r -> if (prefs.getString(offerKey, "")?.substringBefore(' ') != r.id)
        prefs.edit().putString(offerKey, "${r.id} ${System.currentTimeMillis() / 1000} ${maxOf(cc.aaro.cosign.NotesTrial.seen(r.id), byId[r.id]?.updated ?: 0)}").apply() } }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.toString() }) { r ->
            val note = byId[r.id]
            when (r.kind) {
                "all" -> Row(Modifier.fillMaxWidth().clickable { LaterTab.value = "saved"; nav.open(Route.Later) }.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(38.dp))
                    Text("Notes and searches", color = Vault.Text, style = MaterialTheme.typography.bodyLarge)
                }
                "search" -> FocusRow(Glyphs.clock, "Search", r.id, Vault.Muted) { onSearch(r.id) }
                else -> if (note != null) {
                    if (r.kind != "note") LaunchedEffect(note.id) { cc.aaro.cosign.NotesTrial.shown(note.id, "box") }
                    FocusRow(if (r.kind == "inView") Glyphs.saveOn else Glyphs.save, when (r.kind) { "inView" -> "Kept in view"; "older" -> "An older note"; else -> "Note" }, cc.aaro.cosign.Later.wordsOf(note.text),
                        if (r.kind == "older") Vault.Field else Vault.Muted, more = if (r.kind == "older") { { onDecide(note) } } else null) {
                        cc.aaro.cosign.NotesTrial.opened(note.id, "box")
                        if (note.url != null) openInBrowser(context, note.url, "l:" + note.id) else onSearch(cc.aaro.cosign.Later.wordsOf(note.text))
                    }
                }
            }
        }
    }
}

/** One row of the focused box: its icon (with a spoken name), your words in typewriter type, and an optional ⋯. */
@Composable
private fun FocusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, what: String, text: String, tint: Color, more: (() -> Unit)? = null, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    // The older note: a dashed bookmark (home-rules.md).
    if (more != null) androidx.compose.foundation.Canvas(Modifier.size(22.dp).semantics { contentDescription = what }) {
        val path = androidx.compose.ui.graphics.vector.PathParser().parsePathString(Glyphs.savePath).toPath()
        val k = size.width / 24f
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.5f, 2f))))
        }
    } else Icon(icon, what, tint = tint, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(16.dp))
    Text(text, color = Vault.Text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    if (more != null) IconButton(more, Modifier.semantics { contentDescription = "Choices for this note" }) { Text("⋯", color = Vault.Muted, style = MaterialTheme.typography.titleLarge) }
}

/** "Go to google.com": the top row of search, for an address or a well-known site's name. Opens the browser. */
@Composable
private fun GoToSite(s: cc.aaro.cosign.Sites.Go, onGo: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClickLabel = "Open in your browser", onClick = onGo).padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(40.dp).background(Vault.Surface, CircleShape).border(1.dp, Vault.Edge, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Glyphs.site, null, tint = Vault.Accent, modifier = Modifier.size(22.dp))
    }
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
        Text("Go to ${s.address}", color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Opens in your browser", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** A handle's page elsewhere ("Open on Mastodon", "Open on Bluesky"): their picture, then who. */
@Composable
private fun HandleRow(r: cc.aaro.cosign.Handles.Row, onOpen: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
) {
    NetImage(r.picture, Modifier.size(40.dp).clip(CircleShape), 120) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Glyphs.site, null, tint = Vault.Accent, modifier = Modifier.size(22.dp)) }
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Text(r.title, color = Vault.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (r.sub.isNotEmpty()) Text(r.sub, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One of your own in the box's list: a past search (the clock) or a note (the bookmark). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun OwnRow(text: String, note: cc.aaro.cosign.LaterItem?, typed: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().combinedClickable(onLongClick = onLong, onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(if (note != null) Glyphs.save else Glyphs.clock, null, tint = Vault.Muted, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(16.dp))
    Text(androidx.compose.ui.text.buildAnnotatedString {
        val i = if (typed.isEmpty()) -1 else text.indexOf(typed, ignoreCase = true)
        if (i < 0) append(text) else { append(text.take(i)); withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i, i + typed.length)) }; append(text.drop(i + typed.length)) }
    }, color = Vault.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
}

/** A row of the dropdown: a search (what's typed in plain, the rest in bold), and ↖ to put it in the box. */
@Composable
private fun DropRow(text: String, sub: String?, typed: String = "", strong: Boolean = false, onFill: (() -> Unit)?, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().background(if (strong) Vault.AccentGround else Color.Transparent).clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (strong) Box(Modifier.size(36.dp).background(Vault.Accent, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Search, null, tint = Vault.Ground, modifier = Modifier.size(20.dp)) }
    else Icon(Icons.Filled.Search, null, tint = Vault.Muted, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
        Text(androidx.compose.ui.text.buildAnnotatedString {
            if (!strong && typed.isNotEmpty() && text.startsWith(typed, ignoreCase = true)) {
                append(text.take(typed.length)); withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(text.drop(typed.length)) }
            } else withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)) { append(text) }
        }, color = Vault.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, color = Vault.Muted, style = MaterialTheme.typography.labelMedium) }
    }
    if (onFill != null) IconButton(onFill) { Icon(Glyphs.out, "Put in the box", tint = Vault.Muted, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = -90f }) }
}

/** "Later · 5 ›": what you saved, a tap away. */
@Composable
private fun LaterLink(n: Int, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Icon(Glyphs.save, null, tint = Vault.Accent, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(14.dp))
    Text("Later · $n", color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Text("›", color = Vault.Muted, style = MaterialTheme.typography.titleMedium)
}

/** People (and posts) found by Marble's own search: after @name, or "Search posts". */
@Composable
private fun MarbleResults(f: cc.aaro.cosign.Found?, people: Map<String, Person>, liked: Set<String>, social: Social, nav: Nav, peopleOnly: Boolean,
                          showPeople: Boolean = true, top: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null) {
    if (f == null && top == null) return Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
    var showSpam by remember(f?.query) { mutableStateOf(false) }
    var showFurther by remember(f?.query) { mutableStateOf(false) }
    // Read so the lists below filter again when the circle is known or changes.
    social.circleAt.collectAsState().value
    val marked by social.spam.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        top?.invoke(this)
        if (f == null) { item(key = "wait") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) } }; return@LazyColumn }
        // Still looking: a spinner while there's nothing yet, a thin bar once results are showing.
        if (f.loading) item(key = "loading") {
            if (f.people.isEmpty() && f.notes.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
            else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Vault.Accent, trackColor = Color.Transparent)
        }
        // Someone you follow, named in the search: "Anarko · thailand", their posts (and anyone else it could mean). Their
        // name: first. A part of a name (it could be wrong): after the first few of the usual posts, never instead of them.
        val authorSection: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = { f.split?.takeIf { !peopleOnly }?.let { sp ->
            item(key = "ah") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(people[sp.author], sp.author, 22, Modifier.clickable { nav.profile(sp.author) })
                    Text(listOf(people[sp.author]?.shown ?: shortNpub(sp.author), sp.rest).joinToString(" · "), color = Vault.Text, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
                if (sp.others.isNotEmpty()) Row(Modifier.padding(start = 16.dp, bottom = 4.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                    for (pk in sp.others) Text(people[pk]?.shown ?: shortNpub(pk), color = Vault.Accent, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Vault.AccentEdge, RoundedCornerShape(14.dp))
                            .clickable { social.search(f.query, author = pk) }.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            items(f.byAuthor, key = { "a" + it.id }) { n ->
                NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12)
                HorizontalDivider(color = Vault.Edge)
            }
            if (!f.loading && f.byAuthor.isEmpty()) item(key = "anone") {
                Text("Nothing from ${people[sp.author]?.shown ?: "them"} about “${sp.rest}”.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))
            }
        } }
        val loose = f.split?.loose == true && !peopleOnly && f.notes.isNotEmpty()
        if (!loose) authorSection()
        if (showPeople && f.people.isNotEmpty()) {
            item(key = "ph") { Heading("People") }
            // Identical look-alikes (a bridge's copies, say) fold into one quiet row under the best of them.
            val rows = LookAlikes.fold(f.people, people, f.hits).take(10)
            items(rows, key = { "p" + it.pubkey }) { r ->
                var open by remember(r.pubkey) { mutableStateOf(false) }
                PersonRow(r.pubkey, people[r.pubkey], f.hits[r.pubkey], nav)
                if (r.alike.isNotEmpty()) {
                    Text(if (open) "Hide the ${r.alike.size} like this" else "${r.alike.size} more like this", color = Vault.Muted, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 72.dp).clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 6.dp))
                    if (open) r.alike.forEach { pk -> PersonRow(pk, people[pk], f.hits[pk], nav) }
                }
            }
        }
        // Likely spam: out of the ranking, at the end behind a quiet line (and marked, if shown).
        val (spammy, all) = f.notes.partition { it.id in f.spam && it.id in marked }
        // Your circle's posts first; the world's after them, folded (Circle).
        val (clean, further) = all.partition { social.inCircle(it.pubkey) }
        if (!peopleOnly && clean.isNotEmpty()) {
            item(key = "nh") { Heading("Posts") }
            val lead = if (loose) 3 else 0
            items(clean.take(lead), key = { it.id }) { n ->
                NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12)
                HorizontalDivider(color = Vault.Edge)
            }
            if (loose) authorSection()
            items(clean.drop(lead), key = { it.id }) { n ->
                NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12)
                HorizontalDivider(color = Vault.Edge)
            }
        }
        if (!peopleOnly && further.isNotEmpty()) {
            item(key = "fh") {
                if (clean.isEmpty()) Heading("Posts")
                if (!showFurther) Text("More posts", color = Vault.Muted, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { showFurther = true }.padding(8.dp))
            }
            if (showFurther) items(further, key = { "f" + it.id }) { n ->
                NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12)
                HorizontalDivider(color = Vault.Edge)
            }
        }
        if (!peopleOnly && spammy.isNotEmpty()) {
            item(key = "sh") {
                Text(if (showSpam) "Hide the ${spammy.size} that look like spam" else "${spammy.size} more that look like spam · Show", color = Vault.Muted,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { showSpam = !showSpam }.padding(8.dp))
            }
            if (showSpam) items(spammy, key = { "s" + it.id }) { n ->
                NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12)
                HorizontalDivider(color = Vault.Edge)
            }
        }
        if (!f.loading && (f.people.isEmpty() || !showPeople) && (peopleOnly || f.notes.isEmpty()) && f.byAuthor.isEmpty()) item(key = "none") {
            Text("Nothing found for “${f.query}”.", color = Vault.Muted, modifier = Modifier.padding(20.dp))
            // Only here: one row to try your engine instead.
            val engine by cc.aaro.cosign.SearchPrefs.engine.collectAsState()
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Vault.Surface)
                .border(1.dp, Vault.Edge, RoundedCornerShape(14.dp)).clickable { nav.web(f.query) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Vault.AccentGround), contentAlignment = Alignment.Center) {
                    Text(engine.label.take(1), color = Vault.Accent, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Search the web", color = Vault.Text, style = MaterialTheme.typography.titleSmall)
                    Text("with ${engine.label}", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Someone in search results: picture, name, a check when their address checks out, "Following", then their address and followers. */
@Composable
private fun PersonRow(pk: String, p: Person?, h: Hit?, nav: Nav) =
    Row(Modifier.fillMaxWidth().clickable { nav.profile(pk) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p, pk, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p?.shown ?: h?.name ?: shortNpub(pk), color = Vault.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (h?.nip05 == Nip05.Check.OK) Icon(Icons.Filled.CheckCircle, "Checked", tint = Vault.Accent, modifier = Modifier.padding(start = 5.dp).size(15.dp))
                if (h?.follow == true) Text("Following", color = Vault.Accent, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 6.dp).border(1.dp, Vault.AccentEdge, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            // Their address (unless it named someone else), and how many follow them.
            val line = listOfNotNull(p?.nip05?.removePrefix("_@")?.takeIf { h?.nip05 != Nip05.Check.WRONG && !it.startsWith("npub1") }, h?.followers?.takeIf { it > 0 }?.let { followers(it) })
                .joinToString(" · ").ifEmpty { p?.about?.lineSequence()?.firstOrNull() ?: "" }
            if (line.isNotEmpty()) Text(line, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

private fun followers(n: Long) = when {
    n >= 1_000_000 -> "%.1fM followers".format(n / 1e6).replace(".0M", "M")
    n >= 10_000 -> "${n / 1000}K followers"
    n >= 1_000 -> "%.1fK followers".format(n / 1e3).replace(".0K", "K")
    n == 1L -> "1 follower"
    else -> "$n followers"
}

@Composable private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = Vault.Muted, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))

// --- The bell ----------------------------------------------------------------------------------

@Composable
private fun BellScreen(phone: Phone, ui: Ui, social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar("Notifications", nav)
    val notices by social.notices.collectAsState()
    val people by People.known.collectAsState()
    // What was new when you opened this stays marked while you look.
    val seenBefore = remember { social.seenAt.value }
    LaunchedEffect(Unit) { social.loadNotices(force = true) }
    LaunchedEffect(notices.at) { if (notices.at != 0L) social.markSeen() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (ui.asks.isNotEmpty() || ui.batchAsks.isNotEmpty() || ui.deleting.isNotEmpty()) LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    // Home is interrupted only by approvals, an Undo and recovery (home-rules.md "Interrupt"); the rest live in Approvals.
    val cards = needs(phone, ui).filter { it !is Need.Paper && it !is Need.Future }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // What's waiting for your key has its own screen now (Approvals): one line to it here.
        if (cards.isNotEmpty()) item(key = "ch") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(14.dp))
                .clickable { nav.open(Route.Approvals) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (cards.size == 1) "Something is waiting for your key" else "${cards.size} things are waiting for your key", color = Vault.Text, style = MaterialTheme.typography.titleSmall)
                    Text("Open Approvals", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = Vault.Muted, fontSize = 20.sp)
            }
        }
        item(key = "nh") { Heading("Replies, mentions and likes") }
        if (notices.list.isEmpty()) item(key = "empty") {
            if (notices.loading) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
            else Text("Nothing yet. When someone replies to you, mentions you or likes your notes, it shows up here.", color = Vault.Muted,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        items(notices.list, key = { it.event.id }) { n -> NoticeRow(n, people, n.event.createdAt > seenBefore, social, nav) }
    }
}

@Composable
private fun NoticeRow(n: Notice, people: Map<String, Person>, fresh: Boolean, social: Social, nav: Nav) {
    val who = people[n.event.pubkey]
    val what = when (n.type) {
        Notice.Type.REPLY -> "replied"
        Notice.Type.MENTION -> "mentioned you"
        Notice.Type.REPOST -> "reposted your note"
        Notice.Type.REACTION -> n.event.content.let { if (it == "+" || it.isEmpty()) "liked your note" else if (it.startsWith(":")) "reacted to your note" else "reacted $it" }
    }
    val text = when (n.type) {
        Notice.Type.REPLY, Notice.Type.MENTION -> n.event.content
        else -> n.about?.let { social.note(it)?.content } ?: ""
    }
    Row(
        Modifier.fillMaxWidth().background(if (fresh) Vault.AccentGround else Color.Transparent)
            .clickable { n.about?.let(nav::thread) }.padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Avatar(who, n.event.pubkey, 36, Modifier.clickable { nav.profile(n.event.pubkey) })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(who?.shown ?: shortNpub(n.event.pubkey), color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(" $what · ", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                PostTime(n.event, social, MaterialTheme.typography.bodyMedium)
            }
            if (text.isNotBlank()) Text(text, color = if (n.type == Notice.Type.REPLY || n.type == Notice.Type.MENTION) Vault.Text else Vault.Muted,
                style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Account › Special Follow: who's on your list (first in Following), each with × to take them off. Added from a profile (★). */
@Composable
private fun SpecialListScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar("Special Follow", nav)
    val special by social.special.collectAsState()
    val people by People.known.collectAsState()
    val busy by social.busy.collectAsState()
    LaunchedEffect(special) { People.want(special.toList()) }
    val list = special.sortedBy { (people[it]?.shown ?: "~" + it).lowercase() }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Their posts come first in Following. Add someone from their profile (★).", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        items(list, key = { it }) { pk ->
            val p = people[pk]
            Row(Modifier.fillMaxWidth().clickable { nav.profile(pk) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p, pk, 40)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p?.shown ?: "Someone", color = Vault.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    p?.nip05?.let { Text(it.removePrefix("_@"), color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (pk in busy) CircularProgressIndicator(Modifier.size(18.dp), color = Vault.Accent, strokeWidth = 2.dp)
                else IconButton({ social.special(pk, false) }) { Icon(Icons.Filled.Close, "Take off Special Follow", tint = Vault.Muted) }
            }
            HorizontalDivider(color = Vault.Edge, modifier = Modifier.padding(horizontal = 20.dp))
        }
        item { Text(if (list.isEmpty()) "No one yet." else "${list.size} ${if (list.size == 1) "person" else "people"}", color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(20.dp)) }
    }
}

/**
 * Account › News sources: your own feeds (a site or its RSS address; Marble finds the feed and asks its topic), and
 * Marble's sources, each with Remove (and Add back). Synced with your account.
 */
@Composable
private fun NewsSourcesScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar("News sources", nav)
    val own by cc.aaro.cosign.NewsSources.own.collectAsState()
    val off by cc.aaro.cosign.NewsSources.off.collectAsState()
    val marble by cc.aaro.cosign.NewsSources.marble.collectAsState()
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var finding by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<cc.aaro.cosign.OwnFeed?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (marble.isEmpty()) cc.aaro.cosign.TopicFeeds.load(context, cachedOnly = true) }
    fun topicsOf(ids: Set<String>) = ids.joinToString(" · ") { cc.aaro.cosign.TopicFeeds.name(it) }
    @Composable fun SourceRow(name: String, sub: String, gone: Boolean, action: String, onAction: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(if (gone) Vault.Edge else Vault.AccentGround), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = if (gone) Vault.Muted else Vault.Accent, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = if (gone) Vault.Muted else Vault.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onAction) { Text(action, color = if (gone) Vault.Muted else Vault.AmberText) }
        }
        HorizontalDivider(color = Vault.Edge, modifier = Modifier.padding(horizontal = 20.dp))
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Heading("Your feeds") }
        items(own.filter { !it.gone }, key = { "own" + it.url }) { f ->
            SourceRow(f.name, "${f.site} · ${cc.aaro.cosign.TopicFeeds.name(f.topic)}", false, "Remove") { cc.aaro.cosign.NewsSources.remove(f.url) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(typed, { typed = it; problem = null; found = null }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Add a feed: a site or its RSS address", color = Vault.Muted) }, shape = RoundedCornerShape(24.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                Spacer(Modifier.width(8.dp))
                Button({
                    finding = true; problem = null
                    scope.launch { found = cc.aaro.cosign.NewsSources.find(typed); finding = false; if (found == null) problem = "Couldn't find a feed there." }
                }, enabled = typed.isNotBlank() && !finding, colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) {
                    if (finding) CircularProgressIndicator(Modifier.size(16.dp), color = Vault.Ground, strokeWidth = 2.dp) else Text("Add")
                }
            }
            problem?.let { Text(it, color = Vault.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
            // Found: its name, and which topic it belongs to.
            found?.let { f ->
                Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text("${f.name} (${f.site}). Which topic?", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in cc.aaro.cosign.TopicFeeds.topics.ifEmpty { listOf(cc.aaro.cosign.Topic("news", "General news")) }) {
                            Text(cc.aaro.cosign.TopicFeeds.name(t.id), color = Vault.Text, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(16.dp))
                                    .clickable { cc.aaro.cosign.NewsSources.add(f.copy(topic = t.id, at = System.currentTimeMillis() / 1000)); found = null; typed = "" }
                                    .padding(horizontal = 10.dp, vertical = 7.dp))
                        }
                    }
                }
            }
            Text("Marble finds the site's feed, shows its name, and asks which topic it belongs to. Fetched on this phone; synced with your account.",
                color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        item { Heading("Marble's sources") }
        // Ones you took out stay listed, at the end, with Add back.
        val all = (marble.keys + off.filterValues { it.first }.keys).distinct()
            .sortedWith(compareBy<String> { off[it]?.first == true }.thenBy { (marble[it]?.first ?: it).lowercase() })
        items(all, key = { "m$it" }) { id ->
            val gone = off[id]?.first == true
            val name = marble[id]?.first?.ifEmpty { null } ?: id
            SourceRow(name, if (gone) "Removed" else topicsOf(marble[id]?.second ?: emptySet()), gone, if (gone) "Add back" else "Remove") {
                cc.aaro.cosign.NewsSources.setOff(id, !gone)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * A poll (NIP-88): before you vote, choose (one, or several when it says) and Vote; after, or once it's ended, each
 * option's share, yours ticked, the most chosen in bold. Under it: one or several, when it ends, how many voted.
 */
@Composable
private fun PollCard(p: cc.aaro.cosign.Polls.Poll, social: Social) {
    val tallies by social.polls.collectAsState()
    LaunchedEffect(p.id) { social.pollVotes(p) }
    val t = tallies[p.id]
    val me = social.me
    val now = System.currentTimeMillis() / 1000
    val mine = t?.choices?.get(me).orEmpty()
    val showResults = mine.isNotEmpty() || p.ended(now) || me == null
    var picked by remember(p.id) { mutableStateOf(emptySet<String>()) }
    val top = t?.counts?.values?.maxOrNull() ?: 0
    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        for ((id, label) in p.options) {
            val share = t?.share(id) ?: 0f
            val chosen = id in picked
            Box(Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .border(1.dp, if (chosen) Vault.Accent else if (id in mine) Vault.AccentEdge else Vault.Line, RoundedCornerShape(10.dp))
                .clickable(enabled = !showResults) { picked = if (p.multiple) (if (chosen) picked - id else picked + id) else setOf(id) }) {
                if (showResults) Box(Modifier.matchParentSize().fillMaxWidth(share.coerceIn(0f, 1f)).background(Vault.AccentGround))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val mark = when { showResults && id in mine -> "✓ "; showResults -> ""; p.multiple -> if (chosen) "☑ " else "☐ "; else -> if (chosen) "● " else "○ " }
                    Text(mark + label, color = Vault.Text, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (showResults && top > 0 && (t?.counts?.get(id) ?: 0) == top) FontWeight.SemiBold else null, modifier = Modifier.weight(1f))
                    if (showResults) Text("${(share * 100).toInt()}%", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val ends = p.endsAt
            Text(listOfNotNull(if (p.multiple) "Pick any" else "Pick one", when { ends == null -> null; p.ended(now) -> "ended"; else -> "ends ${untilWords(ends - now)}" }).joinToString(" · ").replaceFirstChar { it.uppercase() },
                color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            t?.let { Text("${it.voters} ${if (it.voters == 1) "vote" else "votes"}", color = Vault.Muted, style = MaterialTheme.typography.bodySmall) }
        }
        if (!showResults) Button({ social.vote(p, picked.toList()) }, Modifier.padding(top = 8.dp).fillMaxWidth(), enabled = picked.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Vote") }
    }
}

/** "in 2 days", "in 3 hours", "in 20 minutes". */
private fun untilWords(s: Long): String = when {
    s >= 2 * 86_400 -> "in ${s / 86_400} days"; s >= 86_400 -> "tomorrow"; s >= 2 * 3600 -> "in ${s / 3600} hours"; s >= 3600 -> "in an hour"
    else -> "in ${maxOf(1, s / 60)} minutes"
}

/**
 * Not in a language you read, not translated yet: "Translate from Spanish" (one tap, downloading Spanish once), or,
 * when the language isn't clear, "Translate from…" with the likeliest few and Other; the pick is remembered for
 * this person.
 */
@Composable
private fun TranslateOffer(n: Event, o: Social.Offer, social: Social) = Box {
    var open by remember { mutableStateOf(false) }
    var other by remember { mutableStateOf(false) }
    val from = o.from
    Text(if (from != null) "Translate from ${OnDevice.name(from)}" else "Translate from…", color = Vault.Accent, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 2.dp).clickable { if (from != null) social.translateFrom(n, from, remember = false) else open = true })
    if (open) DropdownMenu(true, { open = false; other = false }, containerColor = Vault.Surface) {
        for (l in if (other) OnDevice.all() else o.likeliest) DropdownMenuItem({ Text(OnDevice.name(l), color = Vault.Text) }, { open = false; other = false; social.translateFrom(n, l, remember = true) })
        if (!other) DropdownMenuItem({ Text("Other…", color = Vault.Muted) }, { other = true })
    }
}

/**
 * A table in an article: wider than the screen, it scrolls sideways in its own box, its first column staying put.
 * Bold, links and emoji in cells work as in the text.
 */
@Composable
private fun ArticleTable(t: Markdown.Block.Table, people: Map<String, Person>, nav: Nav, modifier: Modifier = Modifier) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    val cell = articleStyle.copy(fontSize = 14.sp, lineHeight = 19.sp, fontFamily = null)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(10.dp))) {
        (listOf(t.header) + t.rows).forEachIndexed { r, row ->
            val head = r == 0
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).background(if (head) Vault.Surface else Color.Transparent)) {
                @Composable fun Cell(spans: List<Markdown.Span>, w: androidx.compose.ui.unit.Dp) {
                    val (text, inline) = articleText(spans, people, nav)
                    Text(text, Modifier.width(w).fillMaxHeight().padding(horizontal = 10.dp, vertical = 8.dp), style = if (head) cell.copy(fontWeight = FontWeight.SemiBold) else cell, inlineContent = inline)
                }
                // The first column stays put (all rows share one sideways scroll for the rest).
                Cell(row.firstOrNull() ?: emptyList(), 112.dp)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Vault.Edge))
                Row(Modifier.horizontalScroll(scroll)) { row.drop(1).forEach { Cell(it, 210.dp) } }
            }
            if (r < t.rows.size) HorizontalDivider(color = Vault.Edge)
        }
    }
}

/**
 * An article (kind 30023), as a card: its picture if it has one (16:9, from the top; none, no box), its title, the
 * line under it (Markdown.line), and who wrote it, how long it takes to read, and when if it's older than a week.
 * A tap opens the reader.
 */
@Composable
private fun ArticleCard(e: Event, people: Map<String, Person>, nav: Nav, author: Boolean = true) {
    val line = remember(e.id) { Markdown.line(e) }
    val minutes = remember(e.id) { Markdown.minutes(Markdown.parse(e)) }
    LaunchedEffect(e.pubkey) { People.want(listOf(e.pubkey)) }
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(14.dp)).background(Vault.Surface)
        .clickable(onClickLabel = "Read it") { nav.thread(e.id) }) {
        e.firstTag("image")?.trim()?.takeIf { it.startsWith("https://") }?.let {
            NetImage(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Vault.Edge), 1080, ContentScale.Crop, alignment = Alignment.TopCenter)
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Text(e.firstTag("title")?.trim()?.ifEmpty { null } ?: "Untitled", color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (author) {
                    Avatar(people[e.pubkey], e.pubkey, 18)
                    Text(people[e.pubkey]?.shown ?: shortNpub(e.pubkey), color = Vault.Text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
                    Text(" · ", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
                }
                val at = e.firstTag("published_at")?.toLongOrNull() ?: e.createdAt
                val old = System.currentTimeMillis() / 1000 - at > 7 * 86_400L
                Text("$minutes min read" + if (old) " · " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(at * 1000)) else "",
                    color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/** A closing run of mentions: small muted chips on one wrapped line, the first four and "+N" (a tap shows the rest). */
@Composable
private fun MentionChips(who: List<String>, people: Map<String, Person>, nav: Nav) {
    var all by remember(who) { mutableStateOf(false) }
    LaunchedEffect(who) { People.want(who) }
    val shown = if (all) who else who.take(4)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        @Composable fun Chip(text: String, onClick: () -> Unit) = Text(text, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Vault.Surface).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp))
        for (pk in shown) Chip("@" + (people[pk]?.shown ?: shortNpub(pk))) { nav.profile(pk) }
        if (!all && who.size > 4) Chip("+${who.size - 4}") { all = true }
    }
}

// --- Relays as feeds (RelayFeeds) ---------------------------------------------------------------------------

@Composable
private fun RelayRow(r: cc.aaro.cosign.RelayFeeds.Relay, sub: String, social: Social, nav: Nav, star: Boolean? = null, onStar: (() -> Unit)? = null) {
    val info by produceState<Social.RelayInfo?>(null, r.url) { value = social.relayInfo(r) }
    Row(Modifier.fillMaxWidth().clickable { nav.open(Route.Relay(r.address)) }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RelayIcon(info, 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info?.name ?: r.address, color = Vault.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (star != null && onStar != null) Text(if (star) "★" else "☆", color = if (star) Vault.Accent else Vault.Muted, fontSize = 20.sp,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onStar).padding(8.dp))
    }
    HorizontalDivider(color = Vault.Edge, modifier = Modifier.padding(horizontal = 18.dp))
}

/** A relay's icon (NIP-11), else a plain mark. */
@Composable
private fun RelayIcon(info: Social.RelayInfo?, size: Int) = Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(Vault.AccentGround), contentAlignment = Alignment.Center) {
    info?.icon?.let { NetImage(it, Modifier.fillMaxSize(), size * 3) } ?: Text("◎", color = Vault.Accent, fontSize = (size / 2).sp)
}

/** Following › Relays: open one by its address; your favourites and sets; relays your people write to (☆ keeps one). */
@Composable
private fun RelayListScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar("Relays", nav)
    val favs by social.favRelays.collectAsState()
    val sets by social.relaySets.collectAsState()
    val people by People.known.collectAsState()
    var theirs by remember { mutableStateOf<List<Pair<cc.aaro.cosign.RelayFeeds.Relay, List<String>>>?>(null) }
    LaunchedEffect(Unit) { social.knowRelayLists(); theirs = try { social.relaysPeopleUse() } catch (_: Exception) { emptyList() } }
    var typed by remember { mutableStateOf("") }
    val typedRelay = cc.aaro.cosign.RelayFeeds.of(typed)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            androidx.compose.material3.OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), singleLine = true,
                placeholder = { Text("Open a relay: wss://… or its address", color = Vault.Muted) }, shape = RoundedCornerShape(24.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { typedRelay?.let { nav.open(Route.Relay(it.address)) } }))
            typedRelay?.let { r -> Text("Open ${r.address} ›", color = Vault.Accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 18.dp).clip(RoundedCornerShape(8.dp)).clickable { nav.open(Route.Relay(r.address)) }.padding(6.dp)) }
        }
        if (favs.isNotEmpty()) {
            item { Heading("Your favourites") }
            items(favs, key = { "f" + it.url }) { r -> RelayRow(r, r.address, social, nav) }
        }
        if (sets.isNotEmpty()) {
            item { Heading("Your sets") }
            items(sets, key = { "s" + it.d }) { set ->
                Row(Modifier.fillMaxWidth().clickable { nav.open(Route.RelaySet(set.d)) }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Vault.AccentGround), contentAlignment = Alignment.Center) { Text("▦", color = Vault.Accent, fontSize = 18.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column { Text(set.title, color = Vault.Text, style = MaterialTheme.typography.titleSmall); Text("${set.relays.size} ${if (set.relays.size == 1) "relay" else "relays"}", color = Vault.Muted, style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider(color = Vault.Edge, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
        item { Heading("Your people post to") }
        when (val t = theirs) {
            null -> item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = Vault.Accent, strokeWidth = 2.dp) } }
            else -> if (t.isEmpty()) item { Text("Nothing beyond the usual relays yet.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
            else items(t, key = { "p" + it.first.url }) { (r, who) ->
                val names = who.mapNotNull { people[it]?.name?.takeIf { n -> n.isNotBlank() } }.take(2)
                val sub = when { names.isEmpty() -> "People you follow"; who.size > names.size -> names.joinToString(", ") + " and others you follow"; else -> names.joinToString(" and ") }
                RelayRow(r, sub, social, nav, star = favs.any { it.url == r.url }) { social.favourite(r, favs.none { it.url == r.url }) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** A relay, read like a channel: what it says about itself, ★ Favourite, Share, Add to a set; then its posts, newest first. */
@Composable
private fun RelayScreen(address: String, social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar(null, nav)
    val r = remember(address) { cc.aaro.cosign.RelayFeeds.of(address) } ?: return@Column Text("That doesn't look like a relay's address.", color = Vault.Muted, modifier = Modifier.padding(20.dp))
    val context = LocalContext.current
    val info by produceState<Social.RelayInfo?>(null, r.url) { value = social.relayInfo(r) }
    val favs by social.favRelays.collectAsState()
    val sets by social.relaySets.collectAsState()
    val people by People.known.collectAsState()
    LaunchedEffect(Unit) { social.knowRelayLists() }
    LaunchedEffect(info?.pubkey) { info?.pubkey?.let { People.want(listOf(it)) } }
    var adding by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    RelayFeed(listOf(r), social, nav) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RelayIcon(info, 52)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(info?.name ?: r.address, color = Vault.Text, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Relay · ${r.url}", color = Vault.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            info?.description?.let { Text(it, color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
            info?.pubkey?.let { pk -> Text("Run by " + (people[pk]?.shown ?: shortNpub(pk)), color = Vault.Accent, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp).clickable { nav.profile(pk) }) }
            val fav = favs.any { it.url == r.url }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ social.favourite(r, !fav) }, border = BorderStroke(1.dp, if (fav) Vault.AccentEdge else Vault.Line),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (fav) Vault.AccentGround else Color.Transparent)) { Text(if (fav) "★ Favourite" else "☆ Favourite", color = if (fav) Vault.Accent else Vault.Text) }
                OutlinedButton({ share(context, (info?.name ?: r.address) + "\n" + cc.aaro.cosign.RelayFeeds.link(r), null) }, border = BorderStroke(1.dp, Vault.Line)) { Text("Share", color = Vault.Text) }
                Box {
                    OutlinedButton({ adding = true }, border = BorderStroke(1.dp, Vault.Line)) { Text("Add to a set", color = Vault.Text) }
                    if (adding) DropdownMenu(true, { adding = false }, containerColor = Vault.Surface) {
                        for (set in sets) {
                            val inIt = set.relays.any { it.url == r.url }
                            DropdownMenuItem({ Text((if (inIt) "✓ " else "") + set.title, color = Vault.Text) }, { adding = false; social.inSet(set.d, set.title, r, !inIt) })
                        }
                        DropdownMenuItem({ Text("New set…", color = Vault.Accent) }, { adding = false; naming = true })
                    }
                }
            }
        }
    }
    if (naming) NewSetDialog({ naming = false }) { title -> naming = false; social.inSet(null, title, r, true) }
}

@Composable
private fun NewSetDialog(onClose: () -> Unit, onMake: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(onDismissRequest = onClose, containerColor = Vault.Surface,
        title = { Text("A new set", color = Vault.Text) },
        text = { androidx.compose.material3.OutlinedTextField(title, { title = it }, singleLine = true, placeholder = { Text("Its name", color = Vault.Muted) }) },
        confirmButton = { TextButton({ onMake(title) }, enabled = title.isNotBlank()) { Text("Make it", color = Vault.Accent) } },
        dismissButton = { TextButton(onClose) { Text("Cancel", color = Vault.Muted) } })
}

/** One of your relay sets: its relays, Edit (each one's ×), Share; their posts together, newest first. */
@Composable
private fun RelaySetScreen(d: String, social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar(null, nav)
    val sets by social.relaySets.collectAsState()
    LaunchedEffect(Unit) { if (sets.isEmpty()) social.knowRelayLists() }
    val set = sets.firstOrNull { it.d == d } ?: return@Column Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    RelayFeed(set.relays, social, nav) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text(set.title, color = Vault.Text, style = MaterialTheme.typography.titleLarge)
            Text("Relay set · yours", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in set.relays) Text(r.address + if (editing) "  ×" else "", color = Vault.Text, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(14.dp))
                        .clickable { if (editing) social.inSet(set.d, set.title, r, false) else nav.open(Route.Relay(r.address)) }.padding(horizontal = 9.dp, vertical = 4.dp))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ editing = !editing }, border = BorderStroke(1.dp, Vault.Line)) { Text(if (editing) "Done" else "Edit", color = Vault.Text) }
                OutlinedButton({ share(context, set.title + "\n" + set.relays.joinToString("\n") { cc.aaro.cosign.RelayFeeds.link(it) }, null) }, border = BorderStroke(1.dp, Vault.Line)) { Text("Share", color = Vault.Text) }
            }
        }
    }
}

/** Posts from [relays] alone, newest first, a page at a time, under [head]. */
@Composable
private fun RelayFeed(relays: List<cc.aaro.cosign.RelayFeeds.Relay>, social: Social, nav: Nav, head: @Composable () -> Unit) {
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    val key = relays.joinToString(",") { it.url }
    var posts by remember(key) { mutableStateOf<List<Event>?>(null) }
    var more by remember(key) { mutableStateOf(true) }
    var paging by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key) { posts = try { social.relayPosts(relays) } catch (_: Exception) { emptyList() }; if (posts.isNullOrEmpty()) more = false }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "head") { head(); HorizontalDivider(color = Vault.Edge) }
        when (val p = posts) {
            null -> item(key = "loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) } }
            else -> {
                if (p.isEmpty()) item(key = "none") { Text("No posts to show here. Some relays share them only with their members.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
                items(p, key = { it.id }) { n -> NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12); HorizontalDivider(color = Vault.Edge) }
                if (more && p.isNotEmpty()) item(key = "more") {
                    LaunchedEffect(p.size) {
                        if (paging) return@LaunchedEffect
                        paging = true
                        scope.launch {
                            val next = try { social.relayPosts(relays, until = p.minOf { it.createdAt } - 1) } catch (_: Exception) { emptyList() }.filter { n -> p.none { it.id == n.id } }
                            if (next.isEmpty()) more = false else posts = p + next
                            paging = false
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = Vault.Accent, strokeWidth = 2.dp) }
                }
            }
        }
    }
}

/** The notes trial, held on an old note in search: write one next step into it, keep it in view, or let it go (done). */
@Composable
private fun NoteDecision(n: cc.aaro.cosign.LaterItem, social: Social, onClose: () -> Unit) {
    var step by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(onDismissRequest = onClose, containerColor = Vault.Surface,
        title = { Text(n.text, color = Vault.Text, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (step == null) {
                    for ((label, act) in listOf<Pair<String, () -> Unit>>(
                        "Next step" to { step = "" },
                        "Keep in view" to { cc.aaro.cosign.NotesTrial.keepInView(n.id, true); cc.aaro.cosign.NotesTrial.decided(n.id, "view"); onClose() },
                        "Let go" to { cc.aaro.cosign.NotesTrial.decided(n.id, "go"); social.later.done(n, true); onClose() })) {
                        Text(label, color = Vault.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable(onClick = act).padding(vertical = 12.dp))
                    }
                } else {
                    androidx.compose.material3.OutlinedTextField(step!!, { step = it }, Modifier.fillMaxWidth(), placeholder = { Text("One next step", color = Vault.Muted) })
                }
            }
        },
        confirmButton = { if (step != null) TextButton({
            cc.aaro.cosign.NotesTrial.decided(n.id, "step"); social.later.setText(n, n.text + "\n→ " + step!!.trim()); onClose()
        }, enabled = !step.isNullOrBlank()) { Text("Add it", color = Vault.Accent) } },
        dismissButton = { TextButton(onClose) { Text("Cancel", color = Vault.Muted) } })
}

/** Day 28 of the notes trial: the one question, asked once. */
@Composable
private fun TrialQuestion() = Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Vault.Surface).padding(14.dp)) {
    Text("These last weeks, has Notes felt calmer, the same, or worse?", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, label) in listOf("calmer" to "Calmer", "same" to "The same", "worse" to "Worse"))
            OutlinedButton({ cc.aaro.cosign.NotesTrial.answered(k) }, border = BorderStroke(1.dp, Vault.Line)) { Text(label, color = Vault.Text) }
    }
}

/**
 * Approvals: everything waiting for your key (requests to sign, a deletion you can still undo, recovery, a device
 * joining, the paper backup), each with what it needs from you. Reached from home's "more waiting" and from the bell.
 */
@Composable
private fun ApprovalsScreen(phone: Phone, ui: Ui, social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar("Waiting for you", nav)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    // Home is interrupted only by approvals, an Undo and recovery (home-rules.md "Interrupt"); the rest live in Approvals.
    val cards = needs(phone, ui).filter { it !is Need.Paper && it !is Need.Future }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (cards.isEmpty()) item { Text("Nothing is waiting for you.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
        items(cards) { c -> Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { NeedCard(c, phone, now) } }
    }
}

// --- Reader (marble-2009-spec §4, reader.md): Google Reader's list view, a row a story, a tap to read it in place ---

private val READER_FOLDERS = listOf("first" to "Read first", "people" to "Your people", "news" to "News", "writers" to "Writers", "relays" to "Relays")

/** "9:41" today, else "Nov 3"; more than a year old, "Nov 3, 2025". */
private fun shortDate(t: Long): String {
    val d = java.util.Date(t * 1000); val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
    return when {
        day.format(d) == day.format(java.util.Date()) -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(d)
        System.currentTimeMillis() / 1000 - t > 365 * 86_400L -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(d)
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(d)
    }
}
/** "today", else "Nov 3": a day's name, for a digest. */
private fun dayWords(t: Long): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).let { f ->
    if (f.format(java.util.Date(t * 1000)) == f.format(java.util.Date())) "today" else java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(t * 1000)) }
/** "Nov 4, 9:12": the by-line's date. */
private fun fullDate(t: Long): String = java.text.SimpleDateFormat("MMM d, ", java.util.Locale.getDefault()).format(java.util.Date(t * 1000)) +
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t * 1000))

/**
 * The message line's Undo (Mark all as read, a star taken off, a note kept, a post about to go). [commit] is what
 * happens if it isn't undone: when it runs out, and also when another takes its place or Reader closes (never dropped).
 */
private class ReaderUndo(val key: String, val text: String, val undo: () -> Unit, val commit: (() -> Unit)? = null)

/**
 * Reader, as Google Reader (marble-reader-fidelity.md): chips along the top (bold for what arrived since you last
 * opened one, never a number or a dot), the stream head as text, a row a story, one opened at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPane(social: Social, nav: Nav, current: Boolean, state: LazyListState) {
    val items by social.readerItems.collectAsState()
    val list by social.readerList.collectAsState()
    social.readerReadAt.collectAsState().value
    val laterItems by social.later.items.collectAsState()
    val kept = remember(laterItems) { laterItems.filter { it.deleted == null }.mapNotNull { it.url }.toSet() }
    // "all", "starred", a folder's id, or "src:<source id>" (one source's items).
    var folder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("all") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun refresh() { loading = true; scope.launch { try { social.pullReader(); social.loadReader() } finally { loading = false } } }
    // The first time it's shown, then on a pull: Back from a post finds Reader as it was, nothing redrawn under you.
    LaunchedEffect(current) { if (current && items == null && !loading) refresh() }
    val all = items.orEmpty()
    val people by People.known.collectAsState()
    val custom = list.folders.filter { !it.gone && READER_FOLDERS.none { b -> b.first == it.id } && it.id != "all" && it.id != "starred" }.map { it.id to it.name }
    val folders = (READER_FOLDERS + custom).filter { (id, _) -> all.any { it.folder == id } }
    // Muted or unsubscribed just now: gone at once; the list loaded again for what changed.
    val gone = remember(list) { list.muted.toSet() + list.sources.filter { it.gone }.map { it.id } }
    fun inFolder(id: String) = when {
        id == "all" -> all
        id == "starred" -> all.filter { social.starred(it, kept) }
        id.startsWith("src:") -> all.filter { it.source == id.removePrefix("src:") }
        else -> all.filter { it.folder == id }
    }.filter { it.source !in gone }
    val shape = remember(list) { Triple(list.live.map { it.id to it.folder }, list.muted.toSet(), social.peopleOn()) }
    var lastShape by remember { mutableStateOf(shape) }
    // A change while a load is running waits for it, then loads again; the rows on screen stay.
    // (Launched apart from the effect, which `loading` keys: setting it mustn't cancel the load.)
    LaunchedEffect(shape, loading) { if (shape != lastShape && items != null && !loading) { lastShape = shape; loading = true
        scope.launch { try { social.loadReader(keep = items.orEmpty().map { it.key }.toSet()) } finally { loading = false } } } }
    fun unread(i: Social.ReaderItem) = !social.itemRead(i)
    // Opening a folder clears its bold (what arrived since it was last opened).
    LaunchedEffect(folder) { social.openedFolder(folder) }
    val head = when {
        folder == "all" -> "All items"; folder == "starred" -> "Starred"
        folder.startsWith("src:") -> all.firstOrNull { it.source == folder.removePrefix("src:") }?.let { liveSource(it, people) } ?: "Source"
        else -> (READER_FOLDERS + custom).firstOrNull { it.first == folder }?.second ?: folder
    }
    // All, Starred and a source's view keep their List/Expanded on this phone; a folder's is in the synced list.
    val pseudo = folder == "all" || folder == "starred" || folder.startsWith("src:")
    var localView by remember(folder) { mutableStateOf(if (pseudo) social.localView(folder) else "list") }
    val view = if (pseudo) localView else list.folders.firstOrNull { it.id == folder }?.view ?: "list"
    val expanded = view == "river"
    // The order is fixed when the folder loads: unread first, the "Earlier" line, then read. Reading only changes looks.
    val order = remember(items, folder, gone) { inFolder(folder).let { l -> l.filter(::unread) + l.filterNot(::unread) } }
    val earlier = remember(order) { order.indexOfFirst { !unread(it) }.takeIf { it > 0 } }
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // Undo is the account's (one per item, never dropped; cancelled on a switch of account), shown here.
    val pendingShown by social.pending.shown.collectAsState()
    fun offer(u: ReaderUndo) { social.pending.offer(u.key, u.text, u.undo, u.commit) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // Chips: All · Starred · the folders · Add. Bold: something arrived since you last opened it (never All or Starred).
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, name) in listOf("all" to "All", "starred" to "Starred") + folders) {
                val on = folder == id
                val fresh = id != "all" && id != "starred" && !on && social.arrivedSince(id, inFolder(id))
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Vault.AccentGround else Color.Transparent).border(1.dp, if (on) Vault.AccentEdge else Vault.Edge, RoundedCornerShape(14.dp))
                    .clickable { folder = id; open = null }.padding(horizontal = 11.dp, vertical = 5.dp)) {
                    Text(name, color = if (on) Vault.Accent else Vault.Text, style = MaterialTheme.typography.labelLarge, fontWeight = if (fresh) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Box(Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(14.dp)).clickable { nav.open(Route.ReaderSources) }.padding(horizontal = 11.dp, vertical = 5.dp)) {
                Text("Add", color = Vault.Muted, style = MaterialTheme.typography.labelLarge)
            }
        }
        // The stream head, as text: List · Expanded, and Mark all as read (always there; Undo for a few seconds).
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(head, color = Vault.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            @Composable fun Link(label: String, on: Boolean = true, onClick: () -> Unit) = Text(label, color = if (on) Vault.Accent else Vault.Muted, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = on, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp))
            fun setView(v: String) = if (pseudo) { social.setLocalView(folder, v); localView = v }
                else social.changeReader { l -> l.withFolder(cc.aaro.cosign.reader.ReaderList.Folder(folder, head, l.folders.firstOrNull { it.id == folder }?.order ?: 0, v, cc.aaro.cosign.core.Clock.now(), false)) }
            Link("List", on = expanded) { setView("list") }
            Text("·", color = Vault.Muted)
            Link("Expanded", on = !expanded) { setView("river"); open = null }
            Spacer(Modifier.width(10.dp))
            Link("Mark all as read") {
                val shown = inFolder(folder)
                if (folder == "starred") {
                    // Starred: only these items (their sources' other items stay as they are).
                    val were = shown.filter(::unread)
                    were.forEach { social.markItemRead(it, opened = true) }
                    offer(ReaderUndo("markall:$folder", "Marked all as read", { were.forEach { social.keepUnread(it.key) } }))
                } else {
                    // Its Undo puts back only what this changed, not what a sync brought in meanwhile.
                    val change = social.markAllRead(social.uptoKeys(shown), shown.flatMap { social.itemKeys(it) })
                    offer(ReaderUndo("markall:$folder", "Marked all as read", { social.undoRead(change) }))
                }
            }
        }
        if (folder == "people" || folder.startsWith("src:")) Text("Source settings…", color = Vault.Accent, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(6.dp)).clickable { nav.open(Route.ReaderSources) }.padding(horizontal = 4.dp, vertical = 4.dp))
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(loading && items != null, ::refresh, Modifier.fillMaxSize()) {
            // Expanded view: an item is read once its top has gone above the pane's top and a second has passed, not while flinging.
            if (expanded) LaunchedEffect(state, order) {
                val since = HashMap<String, Long>()
                while (true) {
                    delay(250)
                    val now = System.currentTimeMillis()
                    val info = state.layoutInfo
                    val past = if (state.isScrollInProgress) emptySet() else info.visibleItemsInfo.filter { it.offset < info.viewportStartOffset }.mapNotNull { it.key as? String }.toSet()
                    since.keys.retainAll(past)
                    for (k in past) {
                        val t = since.getOrPut(k) { now }
                        // Kept unread stays so: scrolling never marks it.
                        if (now - t >= 1000) order.firstOrNull { it.key == k }?.let { if (!social.itemRead(it) && !social.keptUnread(it)) social.markItemRead(it) }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (items == null) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) } }
                else if (order.isEmpty()) item { Text(if (all.isEmpty()) "Add a site or a person with Add." else "Nothing here.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
                order.forEachIndexed { n, i ->
                    if (n == earlier) item(key = "earlier") { Text("Earlier", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
                    item(key = i.key) {
                        ReaderRow(i, unread(i), social.starred(i, kept), expanded || open == i.key, expanded, social, nav,
                            onToggle = { if (open == i.key) open = null else { open = i.key; social.markItemRead(i, opened = true) } },
                            onSource = { folder = "src:$it"; open = null }, onUndo = { offer(it) })
                    }
                }
                // Expanded view: room after the last item, so the last ones can reach the top and be read.
                if (expanded && order.isNotEmpty()) item(key = "room") { Spacer(Modifier.fillParentMaxHeight()) }
            }
        }
    }
    // The message line: what just happened, and Undo.
    pendingShown?.let { u -> Row(Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(10.dp))
        .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(u.text, color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
        TextButton({ social.pending.undo(u) }) { Text("Undo", color = Vault.Accent) }
    } }
    }
}

/** Reader's quiet text links in a row ("Star · Share with a note · …"). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReaderLinks(links: List<Pair<String, () -> Unit>>, after: List<Pair<String, () -> Unit>> = emptyList(),
                        middle: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit = {}) =
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp).background(Vault.ReadTint, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Center) {
        @Composable fun word(n: Int, label: String, act: () -> Unit) {
            if (n > 0) Text(" · ", color = Vault.Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
            Text(label, color = Vault.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(4.dp)).clickable(onClick = act).padding(horizontal = 2.dp, vertical = 10.dp))
        }
        links.forEachIndexed { n, (label, act) -> word(n, label, act) }
        middle()
        after.forEach { (label, act) -> word(1, label, act) }
    }

/**
 * One item. Closed: two lines (star · source · date; the title, bold until read, and the snippet), a faint tint once
 * read. Open (or in Expanded view): the title as the way out, the by-line, the body in full, and the action bar.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ReaderRow(i: Social.ReaderItem, unread: Boolean, starred: Boolean, open: Boolean, expanded: Boolean, social: Social, nav: Nav,
                      onToggle: () -> Unit, onSource: (String) -> Unit, onUndo: (ReaderUndo) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val people by People.known.collectAsState()
    fun star() { social.star(i)?.let { was -> onUndo(ReaderUndo("star:${i.key}", "Unstarred", { social.later.bringBack(was) })) } }
    // The open row is one entry with its body: a thin accent edge down the left of both.
    val accent = Vault.Accent
    val edge = Modifier.drawBehind { if (open && !expanded) drawRect(accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
    Column(edge) {
    if (!expanded) Box {
        Column(Modifier.fillMaxWidth().background(if (!unread && !open) Vault.ReadTint else Color.Transparent)
            .combinedClickable(onLongClick = { menu = true }, onClick = onToggle).padding(start = 4.dp, end = 14.dp, top = 2.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (starred) Glyphs.starOn else Glyphs.star, if (starred) "Starred" else "Star", tint = if (starred) Vault.Amber else Vault.Field,
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { star() }.padding(9.dp))
                Text(liveSource(i, people), color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(shortDate(i.at), color = Vault.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
            }
            // Drawn again as names arrive (the line has @names in it). A day's posts are named by the day, not counted.
            val (title, snippet) = remember(i, people.size) { cc.aaro.cosign.ReaderRows.text(i, people) }
            val shownTitle = if (i.posts.isNotEmpty()) cc.aaro.cosign.ReaderRows.dayTitle(i.at, System.currentTimeMillis() / 1000) else title
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = Vault.Text, fontWeight = if (unread && !open) FontWeight.Bold else FontWeight.Normal)) { append(shownTitle) }
                if (snippet.isNotBlank()) withStyle(androidx.compose.ui.text.SpanStyle(color = Vault.Muted)) { append(" — " + snippet) }
            }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 9.dp))
        }
        if (menu) DropdownMenu(true, { menu = false }, containerColor = Vault.Surface) {
            DropdownMenuItem({ Text("Keep unread", color = Vault.Text) }, { menu = false; social.keepUnread(i.key) })
            DropdownMenuItem({ Text("Share with a note", color = Vault.Text) }, { menu = false; if (!open) onToggle(); sharing = true })
            DropdownMenuItem({ Text("Share", color = Vault.Text) }, { menu = false; share(context, social.shareTitle(i) + "\n" + (social.readerUrl(i) ?: ""), null) })
            DropdownMenuItem({ Text("Mute ${liveSource(i, people)}", color = Vault.Text) }, { menu = false; social.changeReader { it.withMute(i.source, true, cc.aaro.cosign.core.Clock.now()) } })
        }
    }
    if (open) ReaderOpened(i, starred, unread, expanded, social, nav, people, sharing, { sharing = it }, ::star, onToggle, onSource, onUndo)
    }
    HorizontalDivider(color = Vault.Edge)
}

/** The opened item: the title (the way out), the by-line, the body in full, flat, and the action bar in words. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReaderOpened(i: Social.ReaderItem, starred: Boolean, unread: Boolean, expanded: Boolean, social: Social, nav: Nav, people: Map<String, Person>,
                         sharing: Boolean, setSharing: (Boolean) -> Unit, star: () -> Unit, onToggle: () -> Unit, onSource: (String) -> Unit, onUndo: (ReaderUndo) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = liveSource(i, people)
    val post = cc.aaro.cosign.ReaderRows.openTarget(i)
    val here = i.link?.let { cc.aaro.cosign.inAppRef(it) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
        // The title: a story's headline (its site), an article's title (Marble's reader), a post's name and time (the post),
        // a day's name and day (the person's page).
        val (title, go) = when {
            i.posts.size > 1 -> "$name · ${dayWords(i.at)}" to { i.pubkey?.let { nav.profile(it) }; Unit }
            i.event?.kind == 30023 -> (i.event.firstTag("title")?.trim()?.ifEmpty { null } ?: i.title) to { nav.thread(i.event.id) }
            post != null -> "$name · ${shortDate(post.createdAt)}" to { nav.thread(post.id) }
            here != null -> i.title to { scope.launch { nav.ref(here, context) }; Unit }
            else -> i.title to { i.link?.let { openInBrowser(context, it, i.key) }; Unit }
        }
        Row(verticalAlignment = Alignment.Top) {
            if (expanded) Icon(if (starred) Glyphs.starOn else Glyphs.star, if (starred) "Starred" else "Star", tint = if (starred) Vault.Amber else Vault.Field,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = star).padding(7.dp))
            Text(title, color = Vault.Accent, style = MaterialTheme.typography.titleMedium, fontWeight = if (expanded && unread) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).clickable { social.markItemRead(i, opened = true); go() }.padding(vertical = 4.dp))
        }
        // The by-line: from Source (its items alone) · the date.
        Text(androidx.compose.ui.text.buildAnnotatedString {
            append("from ")
            withLink(androidx.compose.ui.text.LinkAnnotation.Clickable("source", androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = Vault.Accent))) { onSource(i.source) }) { append(name) }
            append(" · " + fullDate(i.at))
        }, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        // The body, flat and whole: no card, no avatar, no tap on it (only its links, names, pictures and quotes are live).
        when {
            i.posts.isNotEmpty() -> i.posts.sortedBy { it.createdAt }.forEachIndexed { n, p ->
                if (i.posts.size > 1) {
                    // Each post's name and time is its link (held: Open in… for it).
                    var menu by remember(p.id) { mutableStateOf(false) }
                    Box {
                        Text("${liveSource(i, people)} · ${shortDate(p.createdAt)}", color = Vault.Accent, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = if (n > 0) 10.dp else 0.dp).clip(RoundedCornerShape(4.dp))
                                .combinedClickable(onLongClick = { menu = true }, onLongClickLabel = "Open in…") { social.markItemRead(i, opened = true); nav.thread(p.id) }.padding(vertical = 4.dp))
                        if (menu) OpenInMenu(i, p, null, social, nav) { menu = false }
                    }
                }
                ReaderPostBody(p, people, nav)
            }
            i.event?.kind == 30023 -> Veiled(i.event) {
                i.event.firstTag("image")?.trim()?.takeIf { it.startsWith("https://") }?.let { NetImage(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)), 1080) }
                Text(i.event.firstTag("summary")?.trim()?.ifEmpty { null } ?: Markdown.line(i.event), color = Vault.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            i.event != null -> ReaderPostBody(i.event, people, nav)
            else -> {
                i.image?.let { NetImage(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)), 1080) }
                if (i.snippet.isNotBlank()) Text(i.snippet, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                // A very long one: its site, in words.
                if (i.snippet.length > 1500) i.link?.let { u -> Text("Read on ${try { java.net.URI(u).host.removePrefix("www.") } catch (_: Exception) { "the site" }}", color = Vault.Accent,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp).clickable { openInBrowser(context, u, i.key) }) }
            }
        }
        // The action bar, in Reader's order, as words: Star · Share with a note · Share · Keep unread · Open in… · Discussion.
        var opener by remember { mutableStateOf(false) }
        Box {
            ReaderLinks(listOf(
                (if (starred) "★ Starred" else "☆ Star") to star,
                "Share with a note" to { setSharing(!sharing) },
                "Share" to { share(context, social.shareTitle(i) + "\n" + (social.readerUrl(i) ?: ""), null) },
            ), after = buildList {
                if (post != null) add("Open in…" to { opener = true })
                i.discussion?.takeIf { it.isNotEmpty() }?.let { d -> add("Discussion" to { openInBrowser(context, d, i.key) }) }
            }) {
                // Keep unread: a real toggle; unticked, read again.
                val keptNow = social.keptUnread(i)
                Text(" · ", color = Vault.Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
                Row(Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(4.dp)).clickable(role = androidx.compose.ui.semantics.Role.Checkbox) {
                    if (keptNow) social.markItemRead(i, opened = true) else social.keepUnread(i.key) }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(keptNow, null, Modifier.size(32.dp))
                    Text("Keep unread", color = Vault.Accent, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (opener && post != null) OpenInMenu(i, post, i.link?.takeIf { here == null }, social, nav) { opener = false }
        }
        if (sharing) ShareWithNote(i, social, onUndo) { setSharing(false) }
    }
}

/**
 * Open in… (reader.md §5): a post or article in your Nostr app, Marble or njump.me, and the page it links to. In the
 * opened item's action bar only, for Nostr items; never on the row.
 */
@Composable
private fun OpenInMenu(i: Social.ReaderItem, post: Event, page: String?, social: Social, nav: Nav, onClose: () -> Unit) {
    val context = LocalContext.current
    DropdownMenu(true, onClose, containerColor = Vault.Surface) {
        val ref = remember(post.id) { if (post.kind in 30000..39999) cc.aaro.cosign.naddr(post.firstTag("d") ?: "", post.pubkey, post.kind) else cc.aaro.cosign.nevent(post.id, post.pubkey, post.kind, emptyList()) }
        val (app, marble, njump) = cc.aaro.cosign.reader.ReaderRules.OPEN_IN
        DropdownMenuItem({ Text(app, color = Vault.Text) }, {
            onClose()
            social.markItemRead(i, opened = true)
            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("nostr:$ref")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: android.content.ActivityNotFoundException) { social.message.value = "No Nostr app on this phone." }
        })
        DropdownMenuItem({ Text(marble, color = Vault.Text) }, { onClose(); social.markItemRead(i, opened = true); nav.thread(post.id) })
        DropdownMenuItem({ Text(njump, color = Vault.Text) }, { onClose(); social.markItemRead(i, opened = true); openInBrowser(context, "https://njump.me/$ref", i.key) })
        // A post about a page: the page too.
        page?.let { u -> DropdownMenuItem({ Text(try { java.net.URI(u).host.removePrefix("www.") } catch (_: Exception) { u }, color = Vault.Text) }, { onClose(); social.markItemRead(i, opened = true); openInBrowser(context, u, i.key) }) }
    }
}

/** A content warning's veil (NIP-36) over [content]: "<reason>: show", until tapped. */
@Composable
private fun Veiled(e: Event, content: @Composable () -> Unit) {
    val warning = remember(e.id) { Body.warning(e) }
    var unveiled by remember(e.id) { mutableStateOf(false) }
    if (warning != null && !unveiled) Text(warning.ifEmpty { "Sensitive" } + ": show", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Vault.Ground).clickable { unveiled = true }.padding(horizontal = 12.dp, vertical = 8.dp))
    else Column { content() }
}

/** A post's body in Reader, by the shared renderer (Body), flat: its text whole, its card, its first picture or video. */
@Composable
private fun ReaderPostBody(n: Event, people: Map<String, Person>, nav: Nav) {
    val body = remember(n.id) { Body.of(n) }
    LaunchedEffect(n.id) { People.want(body.mentions + n.pubkey) }
    Veiled(n) {
        val text = remember(body, people.size) { body.annotated(people, nav) }
        if (text.isNotEmpty()) Text(text, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, inlineContent = remember(body) { body.emojiContent() }, modifier = Modifier.padding(top = 2.dp))
        if (body.trailing.isNotEmpty()) MentionChips(body.trailing, people, nav)
        when (val c = body.card) {
            is Body.Part.Quote -> QuoteBox(c.id, people, nav)
            is Body.Part.QuoteAddr -> AddrBox(c.a, people, nav)
            else -> {}
        }
        body.article?.let { AddrBox(it.a, people, nav) }
        val urls = remember(body) { body.images.map { it.url } }
        body.media.firstOrNull()?.let { m ->
            Spacer(Modifier.height(6.dp))
            when (m) {
                is Body.Img -> PostImage(m.url, m.preview, Modifier.fillMaxWidth().aspectRatio(m.ratio.coerceIn(1f, 2.2f)).clip(RoundedCornerShape(10.dp))) { nav.pictures(urls, 0) }
                is Body.Vid -> VideoCard(m.v)
            }
        }
        if (body.media.size > 1) Text("+${body.media.size - 1} more", color = Vault.Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp).clickable { if (body.media.drop(1).all { it is Body.Img }) nav.pictures(urls, if (body.media[0] is Body.Img) 1 else 0) else nav.thread(n.id) })
    }
}

/**
 * Share with a note (marble-2009-spec §5), inline under the item, with its guards: Post publicly is off every time it
 * opens; the button says Keep (to Notes, which stars it) or Post publicly; Undo for a few seconds before anything goes.
 */
@Composable
private fun ShareWithNote(i: Social.ReaderItem, social: Social, onUndo: (ReaderUndo) -> Unit, onClose: () -> Unit) {
    var public by remember { mutableStateOf(false) }
    var keepNote by remember { mutableStateOf("") }
    var postNote by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Vault.ReadTint, RoundedCornerShape(8.dp)).padding(10.dp)) {
        if (!public) androidx.compose.material3.OutlinedTextField(keepNote, { keepNote = it }, Modifier.fillMaxWidth(), placeholder = { Text("A note (only you)", color = Vault.Muted) })
        else androidx.compose.material3.OutlinedTextField(postNote, { postNote = it }, Modifier.fillMaxWidth(), placeholder = { Text("Say something (everyone sees this)", color = Vault.Muted) })
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable { public = !public }, verticalAlignment = Alignment.CenterVertically) {
            Text("Post publicly", color = Vault.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(public, { public = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClose) { Text("Cancel", color = Vault.Muted) }
            TextButton({
                if (public) {
                    val text = postNote
                    // Goes when the Undo runs out, or is replaced, or Reader closes; from Social, which nothing here cancels.
                    onUndo(ReaderUndo("post:${i.key}", "Posting publicly", {}) { social.postSoon(i, text) })
                } else social.readerUrl(i)?.let { url ->
                    // Undo: the note there was before (its words), or none, if this made it.
                    val was = social.later.forUrl(url)
                    social.later.keep(keepNote.ifBlank { social.shareTitle(i) }, url, i.snippet.take(500).ifEmpty { null }, i.sourceName)?.let { n ->
                        onUndo(ReaderUndo("keep:${i.key}", "Kept in Notes", { if (was != null) social.later.putBack(was) else social.later.remove(n) })) }
                }
                onClose()
            }) { Text(if (public) "Post publicly" else "Keep", color = Vault.Accent) }
        }
    }
}

/** Reader's folders and sources: add a site or a person; the people toggle; each source's Mute and Unsubscribe. */
@Composable
private fun ReaderSourcesScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    TopBar("Reader", nav)
    val list by social.readerList.collectAsState()
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            androidx.compose.material3.OutlinedTextField(typed, { typed = it; said = null }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
                placeholder = { Text("A site or a person", color = Vault.Muted) }, shape = RoundedCornerShape(24.dp),
                leadingIcon = { if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Vault.Accent, strokeWidth = 2.dp) else Text("＋", color = Vault.Muted) },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    busy = true
                    scope.launch { val got = social.addToReader(typed); busy = false; said = got?.let { "Added $it." } ?: "Couldn't find anything to follow there."; if (got != null) { typed = ""; social.loadReader() } }
                }))
            said?.let { Text(it, color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        item { Heading("Folders") }
        item {
            Row(Modifier.fillMaxWidth().clickable { social.changeReader { it.withPeople(!social.peopleOn(), cc.aaro.cosign.core.Clock.now()) } }.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your people", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
                    Text("Posts from people you follow", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.material3.Switch(social.peopleOn(), { on -> social.changeReader { it.withPeople(on, cc.aaro.cosign.core.Clock.now()) } })
            }
        }
        item { Text("Read first is your Special Follow list.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        val sources = list.live
        if (sources.isNotEmpty()) item { Heading("Sources") }
        items(sources, key = { it.id }) { src ->
            var menu by remember { mutableStateOf(false) }
            val isMuted = src.id in list.muted
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(src.name.ifEmpty { src.value }, color = if (isMuted) Vault.Muted else Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(READER_FOLDERS.firstOrNull { it.first == src.folder }?.second ?: src.folder, color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Vault.Muted) }
                    if (menu) DropdownMenu(true, { menu = false }, containerColor = Vault.Surface) {
                        DropdownMenuItem({ Text(if (isMuted) "Unmute" else "Mute", color = Vault.Text) }, { menu = false; social.changeReader { it.withMute(src.id, !isMuted, cc.aaro.cosign.core.Clock.now()) } })
                        DropdownMenuItem({ Text("Unsubscribe", color = Vault.Danger) }, { menu = false; social.changeReader { it.withSource(src.copy(gone = true, at = cc.aaro.cosign.core.Clock.now())) } })
                    }
                }
            }
            HorizontalDivider(color = Vault.Edge, modifier = Modifier.padding(horizontal = 20.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
