package cc.aaro.cosign.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
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
import cc.aaro.cosign.Ref
import cc.aaro.cosign.Social
import cc.aaro.cosign.Startup
import cc.aaro.cosign.Translate
import cc.aaro.cosign.Ui
import cc.aaro.cosign.appName
import cc.aaro.cosign.core.Event
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
    /** The marbles you kept. */
    data object Shelf : Route()
    /** A new post; [link]: a story to post with a comment. */
    data class Compose(val link: String = "") : Route()

    /** As text, so the stack of screens survives Android recreating Marble. */
    fun save(): String = when (this) {
        Account -> "account"; Bell -> "bell"; Search -> "search"; Stories -> "stories"; Feed -> "discover"
        is Thread -> "thread:$id:$reply"; is Profile -> "profile:$pubkey"; is Compose -> "compose:$link"; is Results -> "results:$q"; Later -> "later"; Shelf -> "marbles"
    }
    companion object {
        fun of(s: String): Route? = when {
            s == "account" -> Account; s == "bell" -> Bell; s == "search" -> Search; s == "stories" -> Stories; s == "discover" -> Feed
            s.startsWith("thread:") -> s.split(':').let { Thread(it[1], it.getOrNull(2) == "true") }
            s.startsWith("profile:") -> Profile(s.removePrefix("profile:"))
            s.startsWith("compose:") -> Compose(s.removePrefix("compose:"))
            s.startsWith("results:") -> Results(s.removePrefix("results:"))
            s == "later" -> Later
            s == "marbles" -> Shelf
            else -> null
        }
    }
}

@Composable
fun Discover(phone: Phone, ui: Ui, onScan: () -> Unit) {
    val social = remember(phone) { Social.of(phone) }
    val appContext = LocalContext.current
    remember { cc.aaro.cosign.SearchPrefs.load(appContext) }
    // Which screens are open, kept when Android recreates Marble (after the browser, say), so you come back to the same one.
    val stack = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Route>, String>(
        save = { l -> l.map { it.save() } }, restore = { l -> mutableStateListOf<Route>().apply { addAll(l.mapNotNull { Route.of(it) }) } })) { mutableStateListOf() }
    val feedState = rememberLazyListState()
    // How far the header had folded, kept with the list's place when Android recreates Marble.
    val header = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.Saver<TopCollapse, Float>({ it.px }, { TopCollapse().apply { px = it } })) { TopCollapse() }
    // Each screen on the stack keeps its own state (where it was scrolled to) while another is on top of it;
    // a screen that's closed forgets it.
    val kept = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun keyOf(i: Int) = "$i:${stack[i]}"
    val open: (Route) -> Unit = { stack.add(it) }
    val back: () -> Unit = { kept.removeState(keyOf(stack.lastIndex)); stack.removeAt(stack.lastIndex) }
    if (stack.isNotEmpty()) BackHandler(onBack = back)
    // A note's pictures, full screen, over everything else.
    var viewing by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    // A marble, full screen, grown from where it was tapped: today's, or one you kept.
    var bigMarble by remember { mutableStateOf<BigMarble?>(null) }
    // Away a while ([Away.after]): back on the main page, at the top, its search box showing (no keyboard), the
    // feed as it was. Away a moment (copying a link, answering a text): just where you were. Kept through Android
    // recreating Marble, as the screens are.
    var awaySince by androidx.compose.runtime.saveable.rememberSaveable { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { awaySince = android.os.SystemClock.elapsedRealtime() }
    // Read on open and when you come back to it; nothing in between.
    // Home: the main page, at the top, its search box showing, the feed as it was (the swirl in any screen's bar, or back after a while).
    val home: () -> Unit = {
        stack.indices.forEach { kept.removeState(keyOf(it)) }; stack.clear()
        viewing = null; bigMarble = null
        header.px = 0f
        scope.launch { feedState.scrollToItem(0) }
    }
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val gone = if (awaySince > 0) android.os.SystemClock.elapsedRealtime() - awaySince else 0L
        if (debugBuild && awaySince > 0) android.util.Log.i("CosignAway", "back after ${gone / 1000} s (home from ${Away.after / 1000} s)")
        val away = awaySince > 0 && gone >= Away.after
        awaySince = 0
        if (away) { home(); social.returned(fetchAfter = Away.after * 2) } else social.loadFeed()
        social.loadNotices()
        social.refreshLater()
        // The night's marble (and where it's claimed), then today's award if it's kept and not yet claimed.
        Daily.refresh(context).invokeOnCompletion { social.claimToday() }
    }
    // And every 30 minutes while it's open: new things come in below what you're reading.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(30 * 60_000L); social.loadFeed() } } }

    val nav = Nav(open = open, back = back, social = social, pictures = { urls, i -> viewing = urls to i }, marble = { bigMarble = BigMarble(it) }, home = home)
    // Debug builds: a profile asked for by intent (MainActivity's test hook).
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
    val inSearch = stack.any { it == Route.Search || it is Route.Results }
    LaunchedEffect(inSearch) { if (!inSearch) { SearchText.q.value = ""; social.clearSearch() } }
    val top = stack.lastOrNull()
    if (top == null) Home(phone, ui, social, feedState, header, nav)
    else kept.SaveableStateProvider(keyOf(stack.lastIndex)) {
        when (top) {
            Route.Account -> AccountScreen(phone, ui, onScan, onProfile = { ui.member?.group?.groupX?.let { nav.profile(it) } }, onBack = back)
            Route.Bell -> BellScreen(phone, ui, social, nav)
            Route.Search -> SearchScreen(social, nav)
            Route.Stories -> StoriesScreen(social, nav)
            Route.Feed -> FeedScreen(social, nav)
            is Route.Thread -> ThreadScreen(top.id, social, nav, top.reply)
            is Route.Profile -> ProfileScreen(top.pubkey, social, nav)
            is Route.Compose -> ComposeScreen(top.link, social, nav)
            is Route.Results -> ResultsScreen(top.q, social, nav)
            Route.Later -> LaterScreen(social, nav)
            Route.Shelf -> ShelfScreen(social, nav) { from, m, d -> bigMarble = BigMarble(from, m, d) }
        }
    }

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

private class Nav(val open: (Route) -> Unit, val back: () -> Unit, val social: Social, val pictures: (List<String>, Int) -> Unit = { _, _ -> },
                  val marble: (androidx.compose.ui.geometry.Offset) -> Unit = {}, val home: () -> Unit = {}) {
    fun thread(id: String) = open(Route.Thread(id))
    /** Open the post with the reply box ready. */
    fun reply(id: String) = open(Route.Thread(id, reply = true))
    fun profile(pk: String) = open(Route.Profile(pk))
    /** Write a post; [link]: with this link under it. */
    fun compose(link: String = "") = open(Route.Compose(link))
    /** Search the web for [q] with your engine (its results open inside Marble). */
    fun results(q: String) { SearchText.q.value = q; open(Route.Results(q)) }
    fun search(q: String) { social.search(q); open(Route.Search) }
}

// --- Home ------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(phone: Phone, ui: Ui, social: Social, state: LazyListState, top: TopCollapse, nav: Nav) {
    val feed by social.feed.collectAsState()
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    val saved by social.saved.collectAsState()
    val previews by social.previews.collectAsState()
    val notices by social.notices.collectAsState()
    val seen by social.seenAt.collectAsState()
    val laterItems by social.later.items.collectAsState()
    val tomorrow by social.tomorrow.collectAsState()
    val me = ui.member!!.group.groupX
    LaunchedEffect(me) { People.want(listOf(me)) }
    val muted by social.muted.collectAsState()
    val dismissed by social.dismissed.collectAsState()
    val posts = remember(feed.posts, muted, dismissed) { feed.posts.filter { it.e.id !in dismissed && it.e.pubkey !in muted } }
    val stories = remember(feed.stories, dismissed) { feed.stories.filter { "t:" + it.id !in dismissed } }
    // How far down you've looked, in posts: what's above it stays put when the order changes.
    val postsNow by androidx.compose.runtime.rememberUpdatedState(posts)
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.key }.collect { key ->
            val i = postsNow.indexOfFirst { it.e.id == key }
            if (i > social.lookedAt) social.lookedAt = i
        }
    }
    val storiesNow by androidx.compose.runtime.rememberUpdatedState(stories)
    SeenWatcher(state, social) { k -> if (k == "stories") storiesNow.map { "t:" + it.id } else listOf(k) }
    LaunchedEffect(Unit) { Startup.mark("home composed", once = false) }
    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; Startup.mark("home painted", once = false); social.painted.complete(Unit) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // The name: back to the top of the feed (opening the header up again); already there, refresh it (as pulling down does).
        val scope = rememberCoroutineScope()
        val toTop: () -> Unit = {
            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 && top.px == 0f) { social.loadFeed(force = true); social.loadNotices(force = true) }
            else scope.launch {
                launch { androidx.compose.animation.core.animate(top.px, 0f) { v, _ -> top.px = v } }
                // From far down, jump most of the way first, so the smooth part is short.
                if (state.firstVisibleItemIndex > 6) state.scrollToItem(6)
                state.animateScrollToItem(0)
            }
        }
        val waiting = ui.asks.size + ui.batchAsks.size
        val fresh = notices.list.count { it.event.createdAt > seen }
        HomeTop(top, toTop, onSearch = { social.clearSearch(); nav.open(Route.Search) }, onMarble = nav.marble) {
            IconButton({ nav.compose() }) { Icon(Glyphs.pencil, "New post", tint = Vault.Text, modifier = Modifier.size(22.dp)) }
            IconButton({ nav.open(Route.Bell) }) {
                Box {
                    Icon(Icons.Outlined.Notifications, "Notifications", tint = Vault.Text)
                    if (waiting + fresh > 0) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(9.dp)
                        .background(if (waiting > 0) Vault.Amber else Vault.Accent, CircleShape))
                }
            }
            Spacer(Modifier.width(4.dp))
            Avatar(people[me], me, 34, Modifier.clickable { nav.open(Route.Account) }, "Your account")
        }

        // Pulled far enough: a refresh, shown as a thin line under the header (never over the marble). A refresh
        // in the background (it can take a while) stays out of sight.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(feed.loading) { if (!feed.loading) pulled = false }
        top.onRefresh = { pulled = true; social.loadFeed(force = true); social.loadNotices(force = true) }
        if (pulled && feed.loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Vault.Accent, trackColor = Color.Transparent)
        Box(Modifier.fillMaxSize()) {
            // The header folds away before the feed scrolls, and opens again once you're back at the top (before pull to refresh).
            LazyColumn(Modifier.fillMaxSize().nestedScroll(top.connection), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "needs") { NeedsYou(phone, ui, social) { nav.open(Route.Bell) } }
                // What you kept lately (your words), and the rest of Later a tap away.
                item(key = "kept") { KeptLately(laterItems, social, nav) }
                tomorrow?.let { t -> item(key = "tomorrow") { TomorrowCard(t, social) } }
                // Tap it away (it also goes by itself after a while).
                ui.error?.let { e -> item(key = "error") { Text(e, color = Vault.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clickable { phone.clearError() }.padding(horizontal = 20.dp, vertical = 6.dp)) } }
                item(key = "room") { Spacer(Modifier.height(96.dp)) }
            }
            // Discover, below: a lip saying what's there. Swipe it up, or tap it.
            DiscoverLip(posts.size, posts.count { it.reason.startsWith("★") }, Modifier.align(Alignment.BottomCenter)) { nav.open(Route.Feed) }
        }
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

/** On home: the last two things you kept, in your own hand, and Later. */
@Composable
private fun KeptLately(items: List<cc.aaro.cosign.LaterItem>, social: Social, nav: Nav) {
    val open = items.filter { it.deleted == null && it.done == null }
    if (open.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text("KEPT LATELY", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
        for (item in open.take(2)) Row(Modifier.fillMaxWidth().clickable { item.url?.let { nav.open(Route.Later) } ?: nav.results(item.text) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).border(1.5.dp, Vault.Line, CircleShape).clickable { social.later.done(item, true) })
            Spacer(Modifier.width(12.dp))
            Text(item.text, color = Vault.Text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (item.url == null) androidx.compose.ui.text.font.FontFamily.Monospace else null),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(agoWords(item.created), color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 10.dp))
        }
        Text("Later · ${open.size} ›", color = Vault.Accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { nav.open(Route.Later) }.padding(vertical = 6.dp))
    }
}

/**
 * Discover: today's news in one row you open, then what people liked this week (fiatjaf's Pyramid), newest first,
 * with your Special Follow people (★) woven in near the top. Where it comes from is said at the end.
 */
@Composable
private fun FeedScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    val feed by social.feed.collectAsState()
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    val saved by social.saved.collectAsState()
    val previews by social.previews.collectAsState()
    val muted by social.muted.collectAsState()
    val dismissed by social.dismissed.collectAsState()
    val special by social.special.collectAsState()
    val all by social.allStories.collectAsState()
    val posts = remember(feed.posts, muted, dismissed) { feed.posts.filter { it.e.id !in dismissed && it.e.pubkey !in muted } }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(Unit) { social.loadFeed() }
    SeenWatcher(state, social) { listOf(it) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.Filled.KeyboardArrowDown, "Back home", tint = Vault.Text) }
        // The name: refresh, as pulling down did.
        Text("Discover", style = MaterialTheme.typography.headlineSmall, color = Vault.Text, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).clickable(onClickLabel = "Reload") { social.loadFeed(force = true) })
        IconButton({ social.clearSearch(); nav.open(Route.Search) }) { Icon(Icons.Filled.Search, "Search", tint = Vault.Text) }
    }
    if (feed.loading && posts.isNotEmpty()) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Vault.Accent, trackColor = Color.Transparent)
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "news") {
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
        if (posts.isEmpty() && !feed.failed) item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
        }
        if (posts.isNotEmpty()) item(key = "from") {
            Text(if (special.isEmpty()) "Popular this week" else "Popular this week · ★ your Special Follow people", style = MaterialTheme.typography.bodyMedium, color = Vault.Muted,
                modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 10.dp))
        }
        items(posts, key = { it.e.id }) { p ->
            HorizontalDivider(color = Vault.Edge)
            NoteCard(p.e, people, p.e.id in liked, nav, onLike = { social.like(p.e) }, maxLines = 7, pick = p, previews = previews,
                saved = p.e.id in saved, onSave = { social.save(p.e.id, isLink = false) }) { PostMenu(p, people[p.e.pubkey]?.shown ?: shortNpub(p.e.pubkey), social) }
        }
        if (posts.isNotEmpty() && !feed.loading) item(key = "end") {
            HorizontalDivider(color = Vault.Edge)
            Text("That's everything for now. New posts show up through the day.", color = Vault.Field, style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp))
        }
        // Where it comes from, plainly.
        item(key = "source") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("Posts come from Popular, by fiatjaf's Pyramid: what its members liked or replied to this week.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                if (special.isNotEmpty()) Text("Woven in: your Special Follow list (${special.size} people), the list Jumble keeps on your account.", color = Vault.Muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
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
            val k = (v.key as? String)?.takeIf { it == "stories" || it.startsWith("t:") || cc.aaro.cosign.core.isHex32(it) } ?: return@mapNotNull null
            val shown = minOf(v.offset + v.size, bottom) - maxOf(v.offset, top)
            k.takeIf { v.size > 0 && shown >= 0.6f * minOf(v.size, bottom - top) }
        }.toSet()
        since.keys.retainAll(showing)
        for (k in showing) if (now - since.getOrPut(k) { now } >= 1500) keys(k).forEach(social::saw)
    }
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
    Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { social.opened(c); openInBrowser(context, c.url) }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = Vault.Accent)) { append(TopicFeeds.name(c.topic)) }
                append(" · " + c.sourceName + if (c.publishedAt > 0) " · " + since(c.publishedAt) else "")
            }, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.title, color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Discussions(c.discussions)
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
private fun Discussions(list: List<cc.aaro.cosign.Discussion>) {
    if (list.isEmpty()) return
    val context = LocalContext.current
    val style = MaterialTheme.typography.labelMedium
    if (list.size == 1) {
        val d = list[0]
        Text("💬 " + (d.comments?.let { "$it comment${if (it == 1L) "" else "s"} on " } ?: "Comments on ") + d.site, color = Vault.Muted, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable { openInBrowser(context, d.url) }.padding(vertical = 2.dp))
        return
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("💬 Discussed on ", color = Vault.Muted, style = style, maxLines = 1)
        list.forEachIndexed { i, d ->
            if (i > 0) Text(" · ", color = Vault.Muted, style = style)
            Text(d.site + (d.comments?.let { " ($it)" } ?: ""), color = Vault.Accent, style = style, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { openInBrowser(context, d.url) }.padding(vertical = 2.dp))
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
        DropdownMenuItem({ Text("More like this", color = Vault.Text) }, { setOpen(false); social.moreLike(c.topic) })
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
        DropdownMenuItem({ Text(if (followed) "Stop following “$topic”" else "Follow “$topic”", color = Vault.Text) }, { open = false; social.followTopic(p.topic, !followed) })
        DropdownMenuItem({ Text("More like this", color = Vault.Text) }, { open = false; social.moreLike(p.topic, p.e) })
        DropdownMenuItem({ Text("Less like this", color = Vault.Text) }, { open = false; social.lessLike(p.topic, p.e.id, p.e) })
        DropdownMenuItem({ Text("Hide posts from $name", color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { open = false; social.hidePerson(p.e.pubkey) })
        DropdownMenuItem({ Text("Copy link", color = Vault.Text) }, { open = false; copy(context, social, webLink(social, p.e), "Link copied") })
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
    val marble = big.marble ?: today
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
        // Its name, in a small chip under it. The first look of the (UTC) day collects it: the chip springs in and a
        // little gold flares off its star, with a tick you feel. After that, or with animations off, just the chip.
        // Only today's is kept, and only by looking at it big (no button, no word); one you kept opens just to look at.
        val first = remember { big.marble == null && Marbles.shift == 0L && social.kept.keep(Daily.utcDate()) }
        // Kept today (now or before): its award, quietly.
        LaunchedEffect(Unit) { if (big.marble == null) social.claimToday() }
        val count = social.kept.all.collectAsState().value.size
        NameChip(marble.name, big.date ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC), flourish = first && !still, count = count.takeIf { it > 0 },
            onClick = onShelf, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp).graphicsLayer { alpha = shown.value })
        // On your profile: this one marble, only if you say (other apps show it too). Off unless you tap it.
        val day = (big.date ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC)).toString()
        val award = social.kept.all.collectAsState().value["marble-$day"]?.award
        val shownOn by social.onProfile.collectAsState()
        LaunchedEffect(Unit) { social.readOnProfile() }
        award?.let { a ->
            val id = remember(a) { org.json.JSONObject(a).optString("id") }
            val on = id in shownOn
            Text(if (on) "On your profile · Remove" else "Show on profile", color = if (on) Vault.Muted else Vault.Accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).graphicsLayer { alpha = shown.value }
                    .clip(RoundedCornerShape(12.dp)).clickable { social.showOnProfile(a, !on) }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
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
 * Your marbles: the ones you kept, newest first, a month at a time, each with its name and date. Only what you
 * have: no gaps, no counts of days missed. A tap opens one big.
 */
@Composable
private fun ShelfScreen(social: Social, nav: Nav, onOpen: (androidx.compose.ui.geometry.Offset, Marble, java.time.LocalDate) -> Unit) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Daily.loadNights(context) }
    val kept by social.kept.all.collectAsState()
    val nights by Daily.nights.collectAsState()
    val days = remember(kept) { kept.keys.mapNotNull { runCatching { java.time.LocalDate.parse(cc.aaro.cosign.Kept.dateOf(it)) }.getOrNull() }.sortedDescending() }
    TopBar(null, nav)
    Text("Your marbles", style = MaterialTheme.typography.headlineSmall, color = Vault.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
    Text(if (days.size == 1) "1 marble" else "${days.size} marbles", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
    if (days.isEmpty()) return@Column Text("Tap today's marble to look at it big, and it's yours.", color = Vault.Muted, modifier = Modifier.padding(20.dp))
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
    /** Today's: the night's own when it's here ([Daily]), else one from the collection. */
    @Composable
    fun current(): Marble {
        val live by Daily.today.collectAsState()
        return live?.takeIf { shift == 0L } ?: today()
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
        MarbleArt(marble, "The marble",
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

/**
 * Open a page in a Custom Tab, inside this app, when the browser has them, in Marble's colours, with one
 * button: Save, which puts the page on screen in Later ([cc.aaro.cosign.SavePageReceiver]). These extras are
 * what androidx.browser's CustomTabsIntent sets; any other browser just opens the page.
 */
private fun openInBrowser(context: android.content.Context, url: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
    i.putExtras(android.os.Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
    i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", Vault.Ground.toArgb())
    i.putExtra("androidx.browser.customtabs.extra.COLOR_SCHEME", if (Vault.dark) 2 else 1)
    // Mutable: the browser fills in the address of the page on screen when Save is tapped.
    val save = android.app.PendingIntent.getBroadcast(context, 0,
        android.content.Intent(context, cc.aaro.cosign.SavePageReceiver::class.java).putExtra("slot", CosignApp.instance.phone.slot),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
    context.getDrawable(cc.aaro.cosign.R.drawable.ic_save_page)?.let { icon ->
        i.putExtra("android.support.customtabs.extra.ACTION_BUTTON_BUNDLE", android.os.Bundle().apply {
            putParcelable("android.support.customtabs.customaction.ICON", icon.toBitmap())
            putString("android.support.customtabs.customaction.DESCRIPTION", "Save to Later")
            putParcelable("android.support.customtabs.customaction.PENDING_INTENT", save)
        })
        i.putExtra("android.support.customtabs.extra.TINT_ACTION_BUTTON", true)
    }
    try { context.startActivity(i) } catch (_: android.content.ActivityNotFoundException) {}
}

/** At most one card: the thing that needs you most. The rest are behind the bell. */
@Composable
private fun NeedsYou(phone: Phone, ui: Ui, social: Social, onMore: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val counting = ui.asks.isNotEmpty() || ui.batchAsks.isNotEmpty() || ui.deleting.isNotEmpty()
    if (counting) LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val cards = needs(phone, ui)
    // Another key of yours with something waiting.
    val others = CosignApp.instance.accounts.all.filter { it !== phone }.map { it to it.ui.collectAsState().value }
        .filter { (_, u) -> u.asks.isNotEmpty() || u.batchAsks.isNotEmpty() }
    if (cards.isEmpty() && others.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        cards.firstOrNull()?.let { NeedCard(it, phone, now) }
        if (cards.size > 1) TextButton(onMore, contentPadding = PaddingValues(0.dp)) { Text("${cards.size - 1} more waiting for you", color = Vault.AmberText) }
        for ((p, u) in others) TextButton({ CosignApp.instance.accounts.show(p.slot) }, contentPadding = PaddingValues(0.dp)) {
            Text("${p.label}: ${u.asks.size + u.batchAsks.size} waiting for you. Switch", color = Vault.AmberText, style = MaterialTheme.typography.bodySmall)
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
    is Need.Approve -> AskCard(n.ask, now) { approve -> phone.decide(n.ask.id, approve) }
    is Need.Batch -> BatchCard(n.b, now) { approve -> phone.decideBatch(n.b.batch, approve) }
    is Need.Deleting -> DeletingCard(n.d, now) { phone.cancelDeletion(n.d.batch) }
    is Need.Recovery -> RecoveryCard(n.name, n.until, phone)
    is Need.Future -> FutureCard(n.f) { phone.removeFuture(n.f) }
    Need.Paper -> PaperCard(phone)
}

// --- Notes -------------------------------------------------------------------------------

@Composable
private fun NoteCard(n: Event, people: Map<String, Person>, liked: Boolean, nav: Nav, onLike: () -> Unit, maxLines: Int = Int.MAX_VALUE, indent: Int = 0, lit: Boolean = false,
                     onReply: (() -> Unit)? = null, pick: Pick? = null, previews: Map<String, Preview> = emptyMap(), saved: Boolean = false, onSave: (() -> Unit)? = null,
                     menu: (@Composable () -> Unit)? = null) {
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
    var original by remember(n.id) { mutableStateOf(false) }
    // A link with a preview shows as a card instead of in the text; an article as a card of its own.
    val link = remember(n.id) { if (pick != null) Calm.link(n) else null }
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
            modifier = Modifier.fillMaxWidth().clickable { nav.thread(n.id) }.padding(start = 68.dp, end = 16.dp, top = 12.dp))
    }
    Row(
        Modifier.fillMaxWidth().background(if (lit) Vault.AccentGround else Color.Transparent)
            .clickable { nav.thread(n.id) }.padding(start = 16.dp + (indent * 14).dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Avatar(author, n.pubkey, if (indent > 0) 32 else 40, Modifier.clickable { nav.profile(n.pubkey) })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(end = 32.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    Text(author?.shown ?: shortNpub(n.pubkey), color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).clickable { nav.profile(n.pubkey) })
                    Spacer(Modifier.width(4.dp))
                    PostTime(n, nav.social)
                }
                if (n.pubkey != nav.social.me) Box(Modifier.align(Alignment.CenterEnd)) { menu?.invoke() ?: NoteMenu(n, author?.shown ?: shortNpub(n.pubkey), nav.social) }
            }
            val to = remember(n.id) { n.tags.lastOrNull { it.size > 1 && it[0] == "p" }?.get(1) }
            LaunchedEffect(n.id) { People.want(body.mentions + listOfNotNull(to)) }
            if (indent == 0 && parentOf(n) != null) {
                Text("Replying to ${to?.let { people[it]?.shown ?: shortNpub(it) } ?: "a note"}", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            }
            val text = remember(body, people.size) { body.annotated(people, nav) }
            if (text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(text, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            }
            tr?.let { t ->
                Text("Translated from ${Translate.name(t.from) ?: "another language"} · " + if (original) "Show translation" else "Show original",
                    color = Vault.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp).clickable { original = !original })
            }
            // In a list, one picture, no taller than it is wide; a thread shows them all, whole.
            val full = maxLines == Int.MAX_VALUE
            // A tap on a picture opens it full screen (and not the thread); a video plays where it is.
            val urls = remember(body) { body.images.map { it.url } }
            val context = LocalContext.current
            card?.let { c -> LinkCard(c) { if (n.kind == 30023 || link == null) nav.thread(n.id) else { nav.social.openedLink(n); openInBrowser(context, link) } } }
            if (card == null) body.media.take(if (full) 4 else 1).forEach { m ->
                Spacer(Modifier.height(8.dp))
                when (m) {
                    is Body.Img -> NetImage(m.url, Modifier.fillMaxWidth().aspectRatio(m.ratio.coerceIn(if (full) 0.6f else 1f, 2.2f)).clip(RoundedCornerShape(12.dp))
                        .clickable { nav.pictures(urls, urls.indexOf(m.url).coerceAtLeast(0)) }, 1080, description = "Picture")
                    is Body.Vid -> VideoCard(m.v)
                }
            }
            if (pick != null && pick.more.isNotEmpty()) Text("+ ${pick.more.size} more about this from ${pick.more.joinToString(", ")}", color = Vault.Field, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            if (card == null && !full && body.media.size > 1) Text("+${body.media.size - 1} more", color = Vault.Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).clickable { if (body.media.drop(1).all { it is Body.Img }) nav.pictures(urls, 1) else nav.thread(n.id) })
            // Like and reply, as plain icons (an IconButton each costs a visible frame on a slow phone).
            Row(Modifier.offset(x = (-9).dp), verticalAlignment = Alignment.CenterVertically) {
                // A tap likes; a long press picks an emoji (see Heart). Then what others reacted with, once counted.
                Heart(n, liked, nav.social, onLike)
                Icon(Glyphs.reply, "Reply", tint = Vault.Muted,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onReply?.invoke() ?: nav.reply(n.id) }.padding(10.dp))
                // Save: into your bookmarks.
                if (onSave != null) {
                    Spacer(Modifier.weight(1f))
                    Icon(if (saved) Glyphs.saveOn else Glyphs.save, if (saved) "Saved" else "Save", tint = if (saved) Vault.Accent else Vault.Muted,
                        modifier = Modifier.offset(x = 18.dp).size(40.dp).clip(CircleShape).clickable(onClick = onSave).padding(10.dp))
                }
            }
        }
    }
}

/** Under a post: "12 replies · 3 reposts · 68 likes" (what the relays counted, or found, whichever is more). */
@Composable
private fun Totals(e: Social.Engagement) {
    fun n(v: Long, one: String, many: String) = "$v ${if (v == 1L) one else many}"
    val parts = listOfNotNull(n(e.replyTotal, "reply", "replies"), e.reposts.takeIf { it > 0 }?.let { n(it.toLong(), "repost", "reposts") }, n(e.likeTotal, "like", "likes"),
        e.zaps.takeIf { it > 0 }?.let { n(it.toLong(), "zap", "zaps") })
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

/** A link's preview: the page's picture, its site, title and a line of summary. */
@Composable
private fun LinkCard(c: Preview, onOpen: () -> Unit) = Column(
    Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(14.dp)).background(Vault.Surface).clickable(onClick = onOpen),
) {
    c.image?.let { NetImage(it, Modifier.fillMaxWidth().heightIn(max = 190.dp).aspectRatio(1.91f).background(Vault.Edge), 1080) }
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
        if (c.site.isNotEmpty()) Text(c.site, color = Vault.Field, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        if (value == null && link.isNotEmpty()) value = Preview.fetch(link)
    }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.Filled.Close, "Close", tint = Vault.Text) }
        Text("New post", style = MaterialTheme.typography.titleMedium, color = Vault.Text, modifier = Modifier.weight(1f))
        Button({
            sending = true
            scope.launch { val sent = social.post(text, link); sending = false; if (sent != null) { social.message.value = "Posted."; nav.back() } }
        }, enabled = !sending && (text.isNotBlank() || link.isNotEmpty()), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text(if (sending) "Posting…" else "Post") }
    }
    TextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus).padding(horizontal = 4.dp),
        placeholder = { Text(if (link.isEmpty()) "What's on your mind?" else "Add a comment…", color = Vault.Muted) },
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Vault.Text, unfocusedTextColor = Vault.Text, cursorColor = Vault.Accent))
    preview?.let { p -> Box(Modifier.padding(horizontal = 16.dp)) { LinkCard(p) {} } }
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
        DropdownMenuItem({ Text("Copy link", color = Vault.Text) }, { open = false; copy(context, social, webLink(social, n), "Link copied") })
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

/** A note's text, cut up: pictures apart, mentions and #tags made tappable. */
private class Body(val parts: List<Part>, val media: List<Media>, val mentions: List<String>) {
    val images get() = media.filterIsInstance<Img>()
    sealed class Part {
        data class Plain(val s: String) : Part()
        data class Link(val url: String) : Part()
        data class Mention(val pubkey: String) : Part()
        data class Quote(val id: String) : Part()
        data class Tag(val tag: String) : Part()
    }
    sealed class Media
    data class Img(val url: String, val ratio: Float) : Media()
    data class Vid(val v: VideoRef) : Media()

    fun annotated(people: Map<String, Person>, nav: Nav): AnnotatedString = buildAnnotatedString {
        val style = TextLinkStyles(SpanStyle(color = Vault.Accent))
        for (p in parts) when (p) {
            is Part.Plain -> append(p.s)
            is Part.Link -> withLink(LinkAnnotation.Url(p.url, style)) { append(p.url.removePrefix("https://").let { if (it.length > 40) it.take(38) + "…" else it }) }
            is Part.Mention -> withLink(LinkAnnotation.Clickable(p.pubkey, style) { nav.profile(p.pubkey) }) { append("@" + (people[p.pubkey]?.shown ?: shortNpub(p.pubkey))) }
            is Part.Quote -> withLink(LinkAnnotation.Clickable(p.id, style) { nav.thread(p.id) }) { append("↪ quoted note") }
            is Part.Tag -> withLink(LinkAnnotation.Clickable(p.tag, style) { nav.search("#" + p.tag) }) { append("#" + p.tag) }
        }
    }.let { if (it.text.isBlank()) AnnotatedString("") else it }

    companion object {
        private val TOKEN = Regex("(https?://[^\\s<>\"]+)|nostr:((?:npub|nprofile|note|nevent)1[02-9ac-hj-np-z]+)|(?<=^|\\s)#([\\p{L}\\p{N}_]{1,40})")
        private val IMAGE = Regex("\\.(jpe?g|png|gif|webp|avif)(\\?.*)?$", RegexOption.IGNORE_CASE)
        private val VIDEO = Regex("\\.(mp4|webm|mov|m4v|m3u8)(\\?.*)?$", RegexOption.IGNORE_CASE)
        private val BREAKS = Regex("[ \\t]*\\n[ \\t\\n]*\\n[ \\t\\n]*")

        fun of(e: Event, content: String = e.content): Body {
            // NIP-92 imeta (and NIP-71 video events): each file's shape, type, picture and length.
            val meta = e.tags.filter { it.isNotEmpty() && it[0] == "imeta" }.mapNotNull { t ->
                val f = t.drop(1).associate { it.substringBefore(' ') to it.substringAfter(' ') }
                f["url"]?.let { it to f }
            }.toMap()
            fun ratioOf(url: String) = meta[url]?.get("dim")?.split('x')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 && it[1] > 0 }?.let { it[0] / it[1] }
            fun isVideo(url: String) = VIDEO.containsMatchIn(url) || meta[url]?.get("m")?.startsWith("video/") == true
            fun video(url: String) = Vid(VideoRef(url, (meta[url]?.get("image") ?: meta[url]?.get("thumb"))?.takeIf { it.startsWith("https://") },
                ratioOf(url) ?: (16f / 9f), meta[url]?.get("duration")?.toFloatOrNull()?.toInt()))
            val parts = ArrayList<Part>(); val images = ArrayList<Media>(); val mentions = ArrayList<String>()
            var at = 0
            val text = content.trim()
            for (m in TOKEN.findAll(text)) {
                if (m.range.first > at) parts += Part.Plain(text.substring(at, m.range.first))
                at = m.range.last + 1
                val url = m.groups[1]?.value?.trimEnd('.', ',', ')', '!', '?')
                val ref = m.groups[2]?.value
                val tag = m.groups[3]?.value
                when {
                    url != null && isVideo(url) -> images += video(url)
                    url != null && IMAGE.containsMatchIn(url) -> images += Img(url, ratioOf(url) ?: 1.5f)
                    url != null -> parts += Part.Link(url)
                    ref != null -> when (val r = Ref.parse(ref)) {
                        is Ref.Profile -> { parts += Part.Mention(r.pubkey); mentions += r.pubkey; Social.hint(r.pubkey, r.relays) }
                        is Ref.Note -> parts += Part.Quote(r.id)
                        null -> parts += Part.Plain(m.value)
                    }
                    tag != null -> parts += Part.Tag(tag)
                }
            }
            if (at < text.length) parts += Part.Plain(text.substring(at))
            // A video event (NIP-71) names its file only in imeta; so may any note.
            for ((url, f) in meta) if (f["m"]?.startsWith("video/") == true && images.none { it is Vid && it.v.url == url }) images += video(url)
            // Media links are pulled out of the text: tidy the line breaks they leave behind
            // (the text on either side of one is one stretch now).
            val joined = ArrayList<Part>()
            for (p in parts) { val last = joined.lastOrNull(); if (p is Part.Plain && last is Part.Plain) joined[joined.lastIndex] = Part.Plain(last.s + p.s) else joined += p }
            parts.clear(); parts += joined
            val tidy = parts.mapIndexed { i, p ->
                if (p !is Part.Plain) p else Part.Plain(p.s.replace(BREAKS, "\n\n").let { s -> if (i == 0) s.trimStart() else s }.let { s -> if (i == parts.lastIndex) s.trimEnd() else s })
            }
            return Body(tidy, images.take(4), mentions)
        }
    }
}

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
private fun webLink(social: Social, e: Event) = "https://njump.me/" + social.neventOf(e)

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
        IconButton({ nav.compose() }) { Icon(Glyphs.pencil, "New post", tint = Vault.Text, modifier = Modifier.size(22.dp)) }
        IconButton({ nav.open(Route.Bell) }) { Icon(Icons.Outlined.Notifications, "Notifications", tint = Vault.Text) }
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
    Box(Modifier.weight(1f)) {
        when {
            shown == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
            shown.isEmpty() -> Text("Couldn't find this post.", color = Vault.Muted, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(shown, key = { it.first.id }) { (e, depth) ->
                    NoteCard(e, people, e.id in liked, nav, onLike = { social.like(e) }, indent = minOf(depth, 4), lit = e.id == id && shown.size > 1 && depth > 0,
                        onReply = { target = e.id; focusNow = true })
                    if (depth == 0) {
                        eng[e.id]?.let { Totals(it) }
                        HorizontalDivider(color = Vault.Edge)
                    }
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
                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Account ID", cc.aaro.cosign.core.Nip19.npub(pk)))
                    social.message.value = "Copied their account ID"
                })
                p?.about?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Vault.Text, style = MaterialTheme.typography.bodyMedium) }
                // The marbles they chose to show (their profile badges from Marble's issuer): a slim row, if any.
                val shownMarbles by produceState(emptyList<Pair<String, String?>>(), pk) { value = social.marblesShownBy(pk) }
                if (shownMarbles.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    shownMarbles.take(8).forEach { (name, img) -> NetImage(img, Modifier.size(30.dp), 96, androidx.compose.ui.layout.ContentScale.Fit, name) }
                    if (shownMarbles.size > 8) Text("+${shownMarbles.size - 8}", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                }
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
                    pk in follows!! -> OutlinedButton({ social.follow(pk, false) }, Modifier.height(44.dp), border = BorderStroke(1.dp, Vault.Line)) { Text("Following · Unfollow", color = Vault.Text) }
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
object SearchText { val q = kotlinx.coroutines.flow.MutableStateFlow("") }

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
    // A search that came from outside (the launcher) while this was open: the box shows it too.
    val running = found?.query
    // (Only a search that arrives after the screen opened: what was searched before isn't put back over the box.)
    val runningAtOpen = remember { running }
    LaunchedEffect(running) { if (running != null && running != runningAtOpen && running != q.trim() && "@$running" != q.trim()) q = running }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val t = q.trim()
    // A pasted link to someone or a post: their page of results, here (see LinkResults).
    val pasted = remember(t) { Ref.parse(t) }
    // An address, or a well-known site's name: "Go to …". Enter opens it only when it's a sure thing.
    val site = remember(t) { cc.aaro.cosign.Sites.match(t) }
    val atPeople = t.startsWith("@") && t.length > 1
    // "Search posts" for what you typed: only for accounts that follow people, unless set otherwise.
    var posts by remember { mutableStateOf(false) }
    LaunchedEffect(t) { if (!atPeople) posts = false }
    val showPostsRow = when (postsPref) { "always" -> true; "never" -> false; else -> !follows.isNullOrEmpty() }
    val saved = laterItems.firstOrNull { it.deleted == null && it.text.equals(t, ignoreCase = true) }
    var undo by remember { mutableStateOf<cc.aaro.cosign.LaterItem?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(4000); undo = null } }
    val save = { if (t.isNotEmpty()) { if (saved == null) undo = social.later.save(t) } }
    val go = {
        when {
            t.isEmpty() || pasted != null -> {}
            atPeople -> social.search(t.drop(1))
            site?.sure == true -> { social.later.opened(site.url, site.address, t); openInBrowser(context, site.url) }
            Nip05.isAddress(t) -> scope.launch { Nip05.lookup(t)?.takeIf { it.length == 64 }?.let { nav.profile(it) } ?: nav.results(t) }
            else -> nav.results(t)
        }
    }
    // People as you type after @; posts only once asked.
    LaunchedEffect(t, posts) {
        if (atPeople) { delay(300); if (social.found.value?.query != t.drop(1)) social.search(t.drop(1)) }
        else if (posts && t.length >= 2) { if (social.found.value?.query != t) social.search(t) }
        else if (pasted != null || t.isEmpty()) social.clearSearch()
    }
    // Your engine's suggestions: asked straight from the phone, after a short pause.
    val suggestions by produceState(emptyList<String>(), t, engine, suggestOn) {
        value = emptyList()
        if (!suggestOn || t.length < 2 || pasted != null || atPeople) return@produceState
        delay(150)
        value = engine.suggest(t)
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vault.Text) }
        // Home, as in every screen's bar.
        Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Home", onClick = nav.home).padding(6.dp)) { Swirl(28f) }
        Spacer(Modifier.width(6.dp))
        TextField(
            q, { q = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
            placeholder = { Text("Search, or save for later", color = Vault.Muted) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }),
            trailingIcon = { if (t.isNotEmpty() && pasted == null && !atPeople) SaveChip(saved != null) { if (saved == null) save() else { social.later.remove(saved) } } },
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Vault.Surface, unfocusedContainerColor = Vault.Surface, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Vault.Text, unfocusedTextColor = Vault.Text, cursorColor = Vault.Accent),
        )
    }
    Box(Modifier.fillMaxSize()) {
        when {
            pasted != null -> LinkResults(pasted, social, nav)
            atPeople || posts -> MarbleResults(found, people, liked, social, nav, peopleOnly = atPeople)
            t.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                val n = laterItems.count { it.deleted == null && it.done == null }
                if (n > 0) item { LaterLink(n) { nav.open(Route.Later) } }
                item { Text("Enter searches ${engine.label}. Save keeps what you typed in Later. @name finds people; paste a link to someone or a post to see it here.",
                    color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp)) }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                // What Enter does, first.
                item(key = "enter") { DropRow(t, if (site?.sure == true) "Enter · open ${site.address}" else "Enter · search ${engine.label}", strong = true, onFill = null) { go() } }
                site?.takeIf { !it.sure }?.let { s -> item(key = "site") { GoToSite(s) { social.later.opened(s.url, s.address, t); openInBrowser(context, s.url) } } }
                site?.takeIf { it.sure }?.let { s -> item(key = "search") { DropRow(t, "Search ${engine.label} instead", onFill = null) { nav.results(t) } } }
                items(suggestions, key = { "s:$it" }) { sug -> DropRow(sug, null, typed = t, onFill = { q = "$sug " }) { nav.results(sug) } }
                if (suggestions.isNotEmpty()) item(key = "from") { Text("Suggestions from ${engine.label} · turn off in Settings", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
                if (showPostsRow && t.length >= 2) item(key = "posts") {
                    HorizontalDivider(color = Vault.Edge)
                    Row(Modifier.fillMaxWidth().clickable { posts = true }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyphs.site, null, tint = Vault.Muted, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Search posts for “$t”", color = Vault.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (postsPref == "auto") Text("only shown because you follow people · Settings", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        undo?.let { u ->
            Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().background(Vault.Text, RoundedCornerShape(14.dp)).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("✓  Saved to Later", color = Vault.Ground, modifier = Modifier.weight(1f))
                TextButton({ social.later.unsave(u); undo = null }) { Text("Undo", color = Vault.AccentEdge, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
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
    val saved = laterItems.firstOrNull { it.deleted == null && it.text.equals(q.trim(), ignoreCase = true) }
    val context = LocalContext.current
    val dark = Vault.dark
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: nav.back() }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Home", onClick = nav.home).padding(10.dp)) { Swirl(28f) }
        Row(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(26.dp))
            .clickable(onClickLabel = "Change the search") { SearchText.q.value = q; nav.back() }.padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Vault.Muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(q, color = Vault.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SaveChip(saved != null) { if (saved == null) social.later.save(q) else social.later.remove(saved) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🔒 ${engine.home} · links open in your browser", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
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
                    social.later.opened(url, real.host ?: url, q)
                    social.titlePage(url)
                }
                try { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, real).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val u = request.url
                    if (engine.owns(u)) return false
                    outside(u); return true
                }
                override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?) { loading = true }
                override fun onPageFinished(view: android.webkit.WebView, url: String) { loading = false }
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
    val pages by social.later.pages.collectAsState()
    var filter by remember { mutableStateOf("") }
    var show by remember { mutableStateOf("saved") }
    val context = LocalContext.current
    val f = filter.trim()
    fun hit(t: String) = f.isEmpty() || t.contains(f, ignoreCase = true)
    val open = items.filter { it.deleted == null && it.done == null && (hit(it.text) || it.quote?.let(::hit) == true) }
    val done = items.filter { it.deleted == null && it.done != null && (hit(it.text) || it.quote?.let(::hit) == true) }
    val pg = pages.filter { hit(it.title) || hit(it.url) || hit(it.from) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vault.Text) }
        Text("Later", style = MaterialTheme.typography.headlineSmall, color = Vault.Text, fontWeight = FontWeight.SemiBold)
    }
    TextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), singleLine = true,
        placeholder = { Text("Filter Later", color = Vault.Muted) }, leadingIcon = { Icon(Icons.Filled.Search, null, tint = Vault.Muted) },
        shape = RoundedCornerShape(26.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = Vault.Surface, unfocusedContainerColor = Vault.Surface, focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Vault.Text, unfocusedTextColor = Vault.Text, cursorColor = Vault.Accent))
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, label) in listOf("saved" to "Saved · ${open.size}", "pages" to "Pages · ${pg.size}", "done" to "Done")) {
            val on = show == k
            Text(label, color = if (on) Vault.Ground else Vault.Text, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Vault.Accent else Vault.Surface).border(1.dp, if (on) Vault.Accent else Vault.Edge, RoundedCornerShape(18.dp))
                    .clickable { show = k }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        val list = if (show == "done") done else open
        if (show != "pages") {
            items(list, key = { "i" + it.id }) { item -> LaterRow(item, f, pages.filter { it.from.equals(item.text, true) }.take(1), social, nav) }
            if (list.isEmpty()) item { Text(if (f.isEmpty()) (if (show == "done") "Nothing done yet." else "Nothing saved yet. Type in the search box and tap Save.") else "Nothing in Later matches “$f”.",
                color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
        }
        if (show == "pages" || (show == "saved" && pg.isNotEmpty())) {
            item { Text("PAGES YOU OPENED", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)) }
            items(if (show == "pages") pg else pg.take(5), key = { "p" + it.url }) { p ->
                Row(Modifier.fillMaxWidth().clickable { openInBrowser(context, p.url) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val host = android.net.Uri.parse(p.url).host?.removePrefix("www.") ?: ""
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Vault.AccentGround), contentAlignment = Alignment.Center) {
                        Text(host.take(1).uppercase(), color = Vault.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, color = Vault.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$host · ${agoWords(p.at)}" + (if (p.from.isNotEmpty()) " · opened from “${p.from}”" else ""), color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { Text("Pages open in your browser. Marble keeps the ones you opened from here, so you never need to leave a tab open “just in case”. Nothing here ever pings you.",
            color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(20.dp)) }
    }
}

/** One saved thing: a circle to tick it done, your words (tap: search them again; a saved page opens), when, and a page you opened for it. Hold to remove. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LaterRow(item: cc.aaro.cosign.LaterItem, filter: String, page: List<cc.aaro.cosign.Page>, social: Social, nav: Nav) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { item.url?.let { openInBrowser(context, it) } ?: nav.results(item.text) }
        .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
        IconButton({ social.later.done(item, item.done == null) }) {
            if (item.done != null) Icon(Icons.Filled.CheckCircle, "Not done", tint = Vault.Accent)
            else Box(Modifier.size(24.dp).border(1.5.dp, Vault.Line, CircleShape))
        }
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                val i = if (filter.isEmpty()) -1 else item.text.indexOf(filter, ignoreCase = true)
                if (i < 0) append(item.text) else {
                    append(item.text.take(i)); withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(item.text.substring(i, i + filter.length)) }; append(item.text.drop(i + filter.length))
                }
            }, color = if (item.done != null) Vault.Muted else Vault.Text, style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.done != null) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            // Kept alongside: what was shared (two lines of it), and where from; a page's site.
            item.quote?.let { Text(it, color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            val site = (item.url?.let { android.net.Uri.parse(it).host?.removePrefix("www.") } ?: item.from?.let { "from $it" })?.let { "$it · " } ?: ""
            Text(site + if (item.done != null) "Done · ${agoWords(item.done)}" else "Saved ${agoWords(item.created)}", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
            page.firstOrNull()?.let { p -> Text("${p.title} — ${android.net.Uri.parse(p.url).host?.removePrefix("www.")}", color = Vault.Accent, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).clickable { openInBrowser(context, p.url) }) }
        }
        if (menu) DropdownMenu(true, { menu = false }, containerColor = Vault.Surface) {
            DropdownMenuItem({ Text("Remove from Later", color = Vault.Danger) }, { menu = false; social.later.remove(item) })
        }
    }
    HorizontalDivider(color = Vault.Edge)
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
    Modifier.padding(end = 6.dp).clip(RoundedCornerShape(18.dp)).background(if (saved) Vault.Accent else Vault.AccentGround).clickable(onClickLabel = if (saved) "Remove from Later" else "Save to Later", onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(if (saved) Icons.Filled.CheckCircle else Glyphs.save, null, tint = if (saved) Vault.Ground else Vault.Accent, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
    Text(if (saved) "Saved" else "Save", color = if (saved) Vault.Ground else Vault.Accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
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
private fun MarbleResults(f: cc.aaro.cosign.Found?, people: Map<String, Person>, liked: Set<String>, social: Social, nav: Nav, peopleOnly: Boolean) {
    val f = f ?: return Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // Still looking: a spinner while there's nothing yet, a thin bar once results are showing.
        if (f.loading) item(key = "loading") {
            if (f.people.isEmpty() && f.notes.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
            else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Vault.Accent, trackColor = Color.Transparent)
        }
        if (f.people.isNotEmpty()) {
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
        if (!peopleOnly && f.notes.isNotEmpty()) {
            item(key = "nh") { Heading("Posts") }
            items(f.notes, key = { it.id }) { n ->
                NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12)
                HorizontalDivider(color = Vault.Edge)
            }
        }
        if (!f.loading && f.people.isEmpty() && (peopleOnly || f.notes.isEmpty())) item(key = "none") { Text("Nothing found for “${f.query}”.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
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
    val cards = needs(phone, ui)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (cards.isNotEmpty()) {
            item(key = "ch") { Heading("For your key") }
            items(cards) { c -> Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { NeedCard(c, phone, now) } }
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
