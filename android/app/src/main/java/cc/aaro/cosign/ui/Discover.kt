package cc.aaro.cosign.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import cc.aaro.cosign.Nip05
import cc.aaro.cosign.LookAlikes
import cc.aaro.cosign.Hit
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
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
    data class Thread(val id: String, val reply: Boolean = false) : Route()
    data class Profile(val pubkey: String) : Route()

    /** As text, so the stack of screens survives Android recreating Marble. */
    fun save(): String = when (this) {
        Account -> "account"; Bell -> "bell"; Search -> "search"; Stories -> "stories"
        is Thread -> "thread:$id:$reply"; is Profile -> "profile:$pubkey"
    }
    companion object {
        fun of(s: String): Route? = when {
            s == "account" -> Account; s == "bell" -> Bell; s == "search" -> Search; s == "stories" -> Stories
            s.startsWith("thread:") -> s.split(':').let { Thread(it[1], it.getOrNull(2) == "true") }
            s.startsWith("profile:") -> Profile(s.removePrefix("profile:"))
            else -> null
        }
    }
}

@Composable
fun Discover(phone: Phone, ui: Ui, onScan: () -> Unit) {
    val social = remember(phone) { Social.of(phone) }
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
    // The marble, full screen, grown from where it was tapped.
    var marbleFrom by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
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
        viewing = null; marbleFrom = null
        header.px = 0f
        scope.launch { feedState.scrollToItem(0) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (awaySince > 0 && android.os.SystemClock.elapsedRealtime() - awaySince >= Away.after) home()
        awaySince = 0
        social.loadFeed(); social.loadNotices()
    }
    // And every 30 minutes while it's open: new things come in below what you're reading.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(30 * 60_000L); social.loadFeed() } } }

    val nav = Nav(open = open, back = back, social = social, pictures = { urls, i -> viewing = urls to i }, marble = { marbleFrom = it }, home = home)
    // Debug builds: a profile asked for by intent (MainActivity's test hook).
    val searchAsked by OpenSearch.query.collectAsState()
    LaunchedEffect(searchAsked) { searchAsked?.let { q -> stack.indices.forEach { kept.removeState(keyOf(it)) }; stack.clear(); social.search(q); stack.add(Route.Search); OpenSearch.query.value = null } }
    val asked by DebugOpen.profile.collectAsState()
    LaunchedEffect(asked) { asked?.let { stack.indices.forEach { kept.removeState(keyOf(it)) }; stack.clear(); nav.profile(it); DebugOpen.profile.value = null } }
    val top = stack.lastOrNull()
    if (top == null) Home(phone, ui, social, feedState, header, nav)
    else kept.SaveableStateProvider(keyOf(stack.lastIndex)) {
        when (top) {
            Route.Account -> AccountScreen(phone, ui, onScan, onProfile = { ui.member?.group?.groupX?.let { nav.profile(it) } }, onBack = back)
            Route.Bell -> BellScreen(phone, ui, social, nav)
            Route.Search -> SearchScreen(social, nav)
            Route.Stories -> StoriesScreen(social, nav)
            is Route.Thread -> ThreadScreen(top.id, social, nav, top.reply)
            is Route.Profile -> ProfileScreen(top.pubkey, social, nav)
        }
    }

    viewing?.let { (urls, i) -> ImageViewer(urls, i) { viewing = null } }
    marbleFrom?.let { MarbleView(it) { marbleFrom = null } }
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
    if (posts.isNotEmpty() || stories.isNotEmpty()) LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; Startup.mark("first cards painted", once = false); social.painted.complete(Unit) }

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
        val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        HomeTop(top, toTop, onSearch = { social.clearSearch(); nav.open(Route.Search) }, pull = { pull.distanceFraction }, onMarble = nav.marble) {
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

        // The spinner only when you pulled: a refresh in the background (it can take a while) stays out of sight.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(feed.loading) { if (!feed.loading) pulled = false }
        PullToRefreshBox(pulled && feed.loading, { pulled = true; social.loadFeed(force = true); social.loadNotices(force = true) }, Modifier.fillMaxSize(), state = pull) {
            // The header folds away before the feed scrolls, and opens again once you're back at the top (before pull to refresh).
            LazyColumn(Modifier.fillMaxSize().nestedScroll(top.connection), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "needs") { NeedsYou(phone, ui, social) { nav.open(Route.Bell) } }
                // Tap it away (it also goes by itself after a while).
                ui.error?.let { e -> item(key = "error") { Text(e, color = Vault.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clickable { phone.clearError() }.padding(horizontal = 20.dp, vertical = 6.dp)) } }
                if (feed.failed) item(key = "failed") {
                    Column(Modifier.padding(24.dp)) {
                        Text("Discover isn't answering right now.", color = Vault.Text)
                        TextButton({ social.loadFeed(force = true) }, contentPadding = PaddingValues(0.dp)) { Text("Try again", color = Vault.Accent) }
                    }
                }
                // Today's stories, kept together at the top; never between the posts.
                if (stories.isNotEmpty()) item(key = "stories") { StoryBox(stories, social, saved) { nav.open(Route.Stories) } }
                if (posts.isEmpty() && (feed.loading || feed.at == 0L && !feed.failed)) item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Vault.Accent) }
                }
                if (posts.isNotEmpty()) item(key = "from people") {
                    Text("From people", style = MaterialTheme.typography.bodyMedium, color = Vault.Muted, modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 10.dp))
                }
                items(posts, key = { it.e.id }) { p ->
                    HorizontalDivider(color = Vault.Edge)
                    NoteCard(p.e, people, p.e.id in liked, nav, onLike = { social.like(p.e) }, maxLines = 7, pick = p, previews = previews,
                        saved = p.e.id in saved, onSave = { social.save(p.e.id, isLink = false) }) { PostMenu(p, people[p.e.pubkey]?.shown ?: shortNpub(p.e.pubkey), social) }
                }
                // It ends.
                if (posts.isNotEmpty() && !feed.loading) item(key = "end") {
                    HorizontalDivider(color = Vault.Edge)
                    Text("That's everything for now. New posts show up through the day.", color = Vault.Field, style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 28.dp))
                }
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
private fun StoryBox(stories: List<TopicCard>, social: Social, saved: Set<String>, onMore: () -> Unit) = Column(
    Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(18.dp)).padding(vertical = 4.dp),
) {
    Text("Today's stories", color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
    stories.forEachIndexed { i, s ->
        if (i > 0) HorizontalDivider(color = Vault.Edge)
        StoryRow(s, social, s.url in saved)
    }
    HorizontalDivider(color = Vault.Edge)
    Text("More stories ›", color = Vault.Muted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onMore).padding(horizontal = 16.dp, vertical = 12.dp))
}

/** One story: topic · site · age, the headline, its picture; tap to read it on the site. */
@Composable
private fun StoryRow(c: TopicCard, social: Social, saved: Boolean) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable { social.opened(c); openInBrowser(context, c.url) }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = Vault.Accent)) { append(TopicFeeds.name(c.topic)) }
                append(" · " + c.sourceName + if (c.publishedAt > 0) " · " + since(c.publishedAt) else "")
            }, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.title, color = Vault.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Discussions(c.discussions)
        }
        c.image?.let { NetImage(it, Modifier.padding(start = 12.dp).size(64.dp).clip(RoundedCornerShape(10.dp)).background(Vault.Edge), 256) }
        StoryMenu(c, social, saved)
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
private fun StoryMenu(c: TopicCard, social: Social, saved: Boolean) = Box {
    var open by remember { mutableStateOf(false) }
    val followed = c.topic in social.followedTopics.collectAsState().value
    val name = TopicFeeds.name(c.topic)
    Icon(Icons.Filled.MoreVert, "More", tint = Vault.Muted, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { open = true }.padding(5.dp))
    if (open) DropdownMenu(true, { open = false }, containerColor = Vault.Surface) {
        DropdownMenuItem({ Text(if (followed) "Stop following “$name”" else "Follow “$name”", color = Vault.Text) }, { open = false; social.followTopic(c.topic, !followed) })
        DropdownMenuItem({ Text("More like this", color = Vault.Text) }, { open = false; social.moreLike(c.topic) })
        DropdownMenuItem({ Text("Less like this", color = Vault.Text) }, { open = false; social.lessLike(c.topic, "t:" + c.id) })
        DropdownMenuItem({ Text("Hide stories from ${c.sourceName}", color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { open = false; social.hideSource(c) })
        DropdownMenuItem({ Text(if (saved) "Saved" else "Save", color = Vault.Text) }, { open = false; social.save(c.url, isLink = true) })
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
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { "t:" + it.id }) { s ->
            StoryRow(s, social, s.url in saved)
            HorizontalDivider(color = Vault.Edge)
        }
    }
}

/**
 * The marble, full screen and still: large on the app's ground (the light or dark render), grown from the
 * header where it was tapped, leaning a little as the phone tilts. A tap or back closes it. With
 * animations off on the phone (Remove animations), it simply appears, and doesn't move.
 */
@Composable
private fun MarbleView(from: androidx.compose.ui.geometry.Offset, onClose: () -> Unit) {
    val context = LocalContext.current
    val still = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val shown = remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (!still) shown.animateTo(1f, androidx.compose.animation.core.tween(340, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val close: () -> Unit = { scope.launch { if (!still) shown.animateTo(0f, androidx.compose.animation.core.tween(220)); onClose() } }
    BackHandler(onBack = close)
    // Tilt: how far the phone has turned since the view opened, smoothed, a few degrees at most.
    var lean by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    if (!still) androidx.compose.runtime.DisposableEffect(Unit) {
        val sm = context.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR)
        var base: FloatArray? = null
        val m = FloatArray(9); val o = FloatArray(3)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(m, e.values); android.hardware.SensorManager.getOrientation(m, o)
                val b = base ?: o.copyOf().also { base = it }
                val tx = ((o[2] - b[2]).coerceIn(-0.35f, 0.35f)) / 0.35f      // roll: left and right
                val ty = ((o[1] - b[1]).coerceIn(-0.35f, 0.35f)) / 0.35f      // pitch: towards and away
                lean = lean + (androidx.compose.ui.geometry.Offset(tx, ty) - lean) * 0.12f
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }
    var centre by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Vault.Ground)
            .onGloballyPositioned { centre = it.boundsInRoot().center }
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.Center,
    ) {
        // The picture has room round the marble (its shadow): the marble itself ends up about four fifths of the width.
        val size = minOf(maxWidth, maxHeight) * 1.45f
        val d = androidx.compose.ui.platform.LocalDensity.current
        val sizePx = with(d) { size.toPx() }
        val start = with(d) { (72f * 1.8f).dp.toPx() } / sizePx       // the header's marble, the same picture
        val leanPx = with(d) { 12.dp.toPx() }
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(cc.aaro.cosign.R.drawable.marble), "The marble",
            Modifier.requiredSize(size).graphicsLayer {
                val p = shown.value
                val sc = start + (1f - start) * p
                scaleX = sc; scaleY = sc
                translationX = (from.x - centre.x) * (1f - p) + lean.x * leanPx * p
                translationY = (from.y - centre.y) * (1f - p) + lean.y * leanPx * p
            })
    }
}

/** How far the home header has folded away, in pixels (0: open, [range]: today's slim bar). */
private class TopCollapse {
    var px by androidx.compose.runtime.mutableFloatStateOf(0f)
    var range = 1f
    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        // Scrolling down the feed: fold the header first.
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (available.y >= 0) return androidx.compose.ui.geometry.Offset.Zero
            val before = px
            px = (px - available.y).coerceAtMost(range)
            return androidx.compose.ui.geometry.Offset(0f, before - px)
        }
        // Back at the top of the feed and still pulling: open it again.
        override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (available.y <= 0) return androidx.compose.ui.geometry.Offset.Zero
            val before = px
            px = (px - available.y).coerceAtLeast(0f)
            return androidx.compose.ui.geometry.Offset(0f, before - px)
        }
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
private fun HomeTop(top: TopCollapse, onName: () -> Unit, onSearch: () -> Unit, pull: () -> Float = { 0f }, onMarble: (androidx.compose.ui.geometry.Offset) -> Unit = {},
                    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    top.range = with(d) { (OPEN_H - SLIM_H).dp.toPx() }
    val f = (top.px / top.range).coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * f
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(mix(OPEN_H, SLIM_H).dp)) {
        val w = maxWidth.value
        // The marble (the swirl render, light or dark): 72dp, centred under the bell; shrinking to nothing just left of the name.
        val m = mix(72f, 0f)
        val mx = mix(w / 2, 30f); val my = mix(92f, 32f)
        // Pulling down past the top: it grows a little with the pull (refresh comes at the same point as ever), and springs back.
        val grow by androidx.compose.animation.core.animateFloatAsState(1f + 0.3f * pull().coerceIn(0f, 1.2f),
            androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "marble pull")
        var centre by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(cc.aaro.cosign.R.drawable.marble), "The marble",
            Modifier.offset((mx - m * 0.9f).dp, (my - m * 0.9f).dp).size((m * 1.8f).dp).graphicsLayer { scaleX = grow; scaleY = grow }
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
            modifier = Modifier.offset(mix((w - nameWdp) / 2, 52f).dp, mix(132f, 18f).dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
                .onSizeChanged { nameW = it.width }.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Back to the top", onClick = onName))
        Row(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        // The search box: under the name, rising to just under the bar.
        Row(
            Modifier.offset(y = mix(192f, 68f).dp).padding(horizontal = 16.dp).fillMaxWidth().height(52.dp)
                .background(Vault.Surface, RoundedCornerShape(26.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(26.dp))
                .clickable(onClick = onSearch).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = Vault.Muted)
            Spacer(Modifier.width(12.dp))
            Text("Search people, posts, #tags", color = Vault.Muted, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Open a page in a Custom Tab, inside this app, when the browser has them. These two extras
 * are what androidx.browser's CustomTabsIntent sets; any other browser just opens the page.
 */
private fun openInBrowser(context: android.content.Context, url: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
    i.putExtras(android.os.Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
    i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", android.graphics.Color.rgb(0x13, 0x11, 0x0F))
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
                Icon(if (liked) Glyphs.heartOn else Glyphs.heart, if (liked) "Liked" else "Like", tint = if (liked) Vault.Amber else Vault.Muted,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onLike).padding(10.dp))
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
                        is Ref.Profile -> { parts += Part.Mention(r.pubkey); mentions += r.pubkey }
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
        Row(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClickLabel = "Home", onClick = nav.home).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Swirl(28f)
            Spacer(Modifier.width(10.dp))
            Text(appName, style = MaterialTheme.typography.titleLarge, color = Vault.Text, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        IconButton({ nav.social.clearSearch(); nav.open(Route.Search) }) { Icon(Icons.Filled.Search, "Search", tint = Vault.Text) }
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
        val full = social.thread(id)
        if (full.isNotEmpty() || thread == null) thread = full
        loading = false
    }
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
                    if (depth == 0) HorizontalDivider(color = Vault.Edge)
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

object DebugOpen { val profile = kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }

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

@Composable
private fun SearchScreen(social: Social, nav: Nav) = Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    val found by social.found.collectAsState()
    val people by People.known.collectAsState()
    val liked by social.liked.collectAsState()
    var q by remember { mutableStateOf(found?.query ?: "") }
    // A search that came from outside (the launcher) while this was open: the box shows it too.
    val running = found?.query
    LaunchedEffect(running) { if (running != null && running != q.trim()) q = running }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (found == null) focus.requestFocus() }
    val scope = rememberCoroutineScope()
    // A pasted link to someone or a post opens it; name@domain opens whoever it names.
    val open: (Ref) -> Unit = { r -> when (r) { is Ref.Profile -> nav.profile(r.pubkey); is Ref.Note -> nav.thread(r.id) } }
    val go = {
        val t = q.trim()
        Ref.parse(t)?.let(open) ?: run {
            social.search(t)
            if (Nip05.isAddress(t)) scope.launch { Nip05.lookup(t)?.takeIf { it.length == 64 }?.let { nav.profile(it) } }
        }
    }
    // As you type: a pasted link at once, words once you pause.
    LaunchedEffect(q) {
        val t = q.trim()
        if (t.isEmpty()) { social.clearSearch(); return@LaunchedEffect }
        Ref.parse(t)?.let { open(it); return@LaunchedEffect }
        if (t.length < 2) return@LaunchedEffect
        // Back from a result: these are its results already; searching again would reload them and lose your place.
        if (social.found.value?.query == t) return@LaunchedEffect
        delay(350)
        social.search(t)
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vault.Text) }
        // Home, as in every screen's bar.
        Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Home", onClick = nav.home).padding(6.dp)) { Swirl(28f) }
        Spacer(Modifier.width(6.dp))
        TextField(
            q, { q = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
            placeholder = { Text("People, posts, #tags…", color = Vault.Muted) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }),
            trailingIcon = { if (q.isNotEmpty()) IconButton({ q = ""; social.clearSearch(); focus.requestFocus() }) { Icon(Icons.Filled.Close, "Clear", tint = Vault.Muted) } },
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Vault.Surface, unfocusedContainerColor = Vault.Surface, focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Vault.Text, unfocusedTextColor = Vault.Text, cursorColor = Vault.Accent),
        )
    }
    val f = found ?: return@Column Text("Search names, words or #hashtags. Paste a link to someone or a post to open it.",
        color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
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
        if (f.notes.isNotEmpty()) {
            item(key = "nh") { Heading("Posts") }
            items(f.notes, key = { it.id }) { n ->
                NoteCard(n, people, n.id in liked, nav, onLike = { social.like(n) }, maxLines = 12)
                HorizontalDivider(color = Vault.Edge)
            }
        }
        if (!f.loading && f.people.isEmpty() && f.notes.isEmpty()) item(key = "none") { Text("Nothing found for “${f.query}”.", color = Vault.Muted, modifier = Modifier.padding(20.dp)) }
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
