package cc.aaro.cosign.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cc.aaro.qrst.core.Event
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.style.TextOverflow
import cc.aaro.cosign.ReactionRules
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height


// Reactions (~/igloo/marble-reactions.md: Facebook's tray, Wisp's recents, Jumble's picker). The button: ☺+, or your
// reaction. A tap opens the tray (seven: your recent ones, then ❤️ 👍 😂 😢 😡 🤔, then +), or, once you've reacted,
// takes your reaction back. Press and slide: the tray under your finger; let go on one to react (or change yours).
// A long press: the tray. + opens the full picker. No counts here: an opened post has them (ReactionChips).

private val ITEM = 40.dp
private val LIFT = 62.dp
private val LEFT = (-10).dp

@Composable
fun Heart(n: Event, liked: Boolean, social: cc.aaro.cosign.Social, onLike: () -> Unit, showCounts: Boolean = false) {
    val mine by social.reactions.collectAsState()
    val recents by social.recents.collectAsState()
    val haptic = LocalHapticFeedback.current
    val px = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(-1) }
    var choices by remember { mutableStateOf(emptyList<ReactionRules.Shown>()) }
    var heartX by remember { mutableStateOf(0f) }
    val screenW = with(px) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    fun rowLeft(k: Int): Float = with(px) {
        val w = (k + 1) * ITEM.toPx()
        (heartX + LEFT.toPx()).coerceAtMost(screenW - w - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()) - heartX
    }
    fun pick(e: ReactionRules.Shown) { open = false; hover = -1; social.react(n, e.emoji, e.url) }
    val chosen = mine[n.id] ?: if (liked) ReactionRules.LIKE else null
    val reacted = chosen != null
    fun tray() { choices = social.topEmojis(); hover = -1; open = true }
    Box {
        Box(Modifier.size(40.dp).clip(CircleShape)
            .onGloballyPositioned { heartX = it.boundsInWindow().left }
            .semantics { contentDescription = if (reacted) "Your reaction: $chosen. Tap to take it back" else "React"; onLongClick("Choose a reaction") { tray(); true } }
            .pointerInput(n.id, reacted) {
                val item = ITEM.toPx(); val top = -LIFT.toPx()
                fun at(x: Float, y: Float): Int { val left = rowLeft(choices.size); return if (y < top - item * 0.6f || y > 20.dp.toPx()) -1 else ((x - left) / item).toInt().takeIf { x >= left && it <= choices.size } ?: -1 }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var up = false
                    val quick = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { up = waitForUpOrCancellation()?.also { it.consume() } != null; true } != null
                    // A tap: the tray; on your reaction, takes it back.
                    if (quick) { if (up) { if (reacted) social.unreact(n) else tray() }; return@awaitEachGesture }
                    // Held: the tray, following the finger.
                    tray()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        val h = at(c.position.x, c.position.y)
                        if (h != hover) { hover = h; if (h >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        if (!c.pressed) {
                            when {
                                hover in choices.indices -> pick(choices[hover])
                                hover == choices.size -> { open = false; hover = -1; all = true }
                                else -> hover = -1
                            }
                            break
                        }
                    }
                }
            }, contentAlignment = Alignment.Center) {
            when {
                chosen == null -> Box {
                    Icon(Glyphs.smile, null, tint = Vault.Muted, modifier = Modifier.padding(10.dp))
                    Text("+", color = Vault.Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 5.dp))
                }
                chosen.startsWith(":") -> recents.firstOrNull { it.emoji == chosen }?.url?.let { NetImage(it, Modifier.size(22.dp), 66, androidx.compose.ui.layout.ContentScale.Fit, ground = androidx.compose.ui.graphics.Color.Transparent) }
                    ?: Text(chosen, color = Vault.Accent, fontSize = 11.sp, maxLines = 1)
                else -> Text(chosen, fontSize = 19.sp)
            }
        }
        if (open) Popup(offset = with(px) { IntOffset(rowLeft(choices.size).toInt(), -LIFT.roundToPx()) }, onDismissRequest = { open = false; hover = -1 },
            properties = PopupProperties(focusable = true)) {
            Row(Modifier.clip(RoundedCornerShape(26.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(26.dp)), verticalAlignment = Alignment.CenterVertically) {
                choices.forEachIndexed { i, e ->
                    Box(Modifier.size(ITEM).clickable { pick(e) }.graphicsLayer {
                        val sc = if (hover == i) 1.45f else 1f; scaleX = sc; scaleY = sc; translationY = if (hover == i) -10.dp.toPx() else 0f
                    }, contentAlignment = Alignment.Center) { EmojiGlyph(e, 24) }
                }
                Box(Modifier.size(ITEM).clickable { open = false; all = true }, contentAlignment = Alignment.Center) {
                    Text("+", color = if (hover == choices.size) Vault.Accent else Vault.Muted, fontSize = 22.sp)
                }
            }
        }
    }
    if (all) Dialog({ all = false }) { Picker(social) { e -> all = false; social.react(n, e.emoji, e.url) } }
}

/** An emoji, or a custom one's picture. */
@Composable
private fun EmojiGlyph(e: ReactionRules.Shown, size: Int) {
    if (e.url != null) NetImage(e.url, Modifier.size(size.dp), size * 3, androidx.compose.ui.layout.ContentScale.Fit, description = e.emoji, ground = androidx.compose.ui.graphics.Color.Transparent)
    else Text(e.emoji, fontSize = (size - 2).sp)
}

/** The full picker, as Jumble's: search; Recent; Your emoji (NIP-30); then every emoji by kind. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Picker(social: cc.aaro.cosign.Social, onPick: (ReactionRules.Shown) -> Unit) {
    val recents by social.recents.collectAsState()
    val mine by social.myEmoji.collectAsState()
    LaunchedEffect(Unit) { try { social.knowRecents(); social.knowMyEmoji() } catch (_: Exception) {} }
    var q by remember { mutableStateOf("") }
    val sections: List<Pair<String, List<ReactionRules.Shown>>> = remember(q, recents, mine) {
        val t = q.trim()
        if (t.isNotEmpty()) listOf("Results" to (mine.filter { it.emoji.contains(t, ignoreCase = true) } + CATEGORIES.flatMap { it.second }.distinct()
            .filter { nameOf(it).contains(t, ignoreCase = true) }.map { ReactionRules.Shown(it) }))
        else listOfNotNull(
            ("Recent" to (recents.ifEmpty { ReactionRules.DEFAULTS.map { ReactionRules.Shown(it) } })),
            mine.takeIf { it.isNotEmpty() }?.let { "Your emoji" to it }) + CATEGORIES.map { (name, l) -> name to l.map { ReactionRules.Shown(it) } }
    }
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Vault.Surface).padding(vertical = 10.dp)) {
        androidx.compose.material3.OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), singleLine = true,
            placeholder = { Text("Search emoji", color = Vault.Muted) }, shape = RoundedCornerShape(20.dp))
        LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            for ((name, list) in sections) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(name, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp))
                }
                items(list) { e -> Box(Modifier.size(44.dp).clip(CircleShape).clickable { onPick(e) }, contentAlignment = Alignment.Center) { EmojiGlyph(e, 26) } }
            }
        }
    }
}

/** An emoji's Unicode name ("smiling face with heart-eyes"), for the search. */
private fun nameOf(e: String): String = try {
    e.codePoints().toArray().filter { it > 0x2000 && it != 0xFE0F && it != 0x200D }.joinToString(" ") { Character.getName(it) ?: "" }
} catch (_: Exception) { "" }

/**
 * Under an opened post: each reaction with how many gave it, most first (the top five, then +N), yours marked.
 * Tapping another's gives the same (a custom one too); tapping the row's end, or holding a chip, shows who reacted.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReactionChips(n: Event, social: cc.aaro.cosign.Social) {
    val all by social.reactors.collectAsState()
    val mine by social.reactions.collectAsState()
    val liked by social.liked.collectAsState()
    val list = all[n.id]?.takeIf { it.isNotEmpty() } ?: return
    val my = mine[n.id] ?: if (n.id in liked) ReactionRules.LIKE else null
    var sheet by remember { mutableStateOf(false) }
    var everyone by remember { mutableStateOf(false) }
    FlowRow(Modifier.padding(start = 68.dp, end = 16.dp, bottom = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((e, who) in if (everyone) list else list.take(5)) {
            val me = e.emoji == my
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(if (me) Vault.AccentGround else Vault.Surface).border(1.dp, if (me) Vault.AccentEdge else Vault.Edge, RoundedCornerShape(14.dp))
                .combinedClickable(onLongClick = { sheet = true }) { if (!me) social.react(n, e.emoji, e.url) }.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiGlyph(e, 17)
                Spacer(Modifier.width(5.dp))
                Text(who.size.toString(), color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!everyone && list.size > 5) Text("+${list.size - 5}", color = Vault.Muted, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { everyone = true }.padding(horizontal = 8.dp, vertical = 4.dp))
        Box(Modifier.weight(1f).height(26.dp).clickable { sheet = true })
    }
    if (sheet) ReactorsSheet(list, social) { sheet = false }
}

/** "Reactions": a tab per emoji (and All), who gave it; a tap opens their profile. */
@Composable
private fun ReactorsSheet(list: List<Pair<ReactionRules.Shown, List<String>>>, social: cc.aaro.cosign.Social, onClose: () -> Unit) {
    val people by cc.aaro.cosign.People.known.collectAsState()
    var tab by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) { cc.aaro.cosign.People.want(list.flatMap { it.second }.distinct()) }
    val rows = if (tab < 0) list.flatMap { (e, who) -> who.map { it to e } } else list[tab].second.map { it to list[tab].first }
    Dialog(onClose) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Vault.Surface).padding(vertical = 12.dp)) {
            Text("Reactions", color = Vault.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable fun Tab(i: Int, content: @Composable () -> Unit) = Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (tab == i) Vault.AccentGround else Vault.Ground)
                    .clickable { tab = i }.padding(horizontal = 10.dp, vertical = 5.dp)) { content() }
                Tab(-1) { Text("All ${list.sumOf { it.second.size }}", color = Vault.Text, style = MaterialTheme.typography.labelMedium) }
                list.forEachIndexed { i, (e, who) -> Tab(i) { Row(verticalAlignment = Alignment.CenterVertically) { EmojiGlyph(e, 16); Text(" ${who.size}", color = Vault.Text, style = MaterialTheme.typography.labelMedium) } } }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                listItems(rows, key = { it.first + it.second.emoji }) { (pk, e) ->
                    Row(Modifier.fillMaxWidth().clickable { onClose(); cc.aaro.cosign.ui.DebugOpen.profile.value = pk }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(people[pk], pk, 32)
                        Text(people[pk]?.shown ?: cc.aaro.cosign.shortNpub(pk), color = Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 12.dp))
                        EmojiGlyph(e, 18)
                    }
                }
            }
        }
    }
}

/** Every emoji the picker offers, by kind. */
private val CATEGORIES = listOf(
    "Smileys" to "😀 😁 😂 🤣 😊 🙂 😉 😍 🥰 😘 😎 🤩 🥳 😮 😲 🤯 😳 🥺 😢 😭 😤 😡 🤬 😱 😨 😅 😆 🙃 🤔 🤨 😐 😑 🙄 😏 😬 🤐 😴 🤤 🤢 🤮 🤧 🥴 😵 🤠 🤓 🧐 😇 🤗 🤭 🫡 🫠 🫢 🫣 🤥 🤑 🥶 🥵 🤡 😈 💀 👻 👽 🤖 💩 🙈 🙉 🙊",
    "Hands" to "👍 👎 👏 🙌 🙏 🤝 💪 👊 ✊ 🤙 🤘 ✌️ 🤞 👌 🤌 🫶 👋 ☝️ 👆 👇 👉 👈 👀 🧠 🫀 🫂",
    "Hearts and symbols" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💖 💯 🔥 ✨ ⭐ 🌟 ⚡ 💥 🎉 🎊 🏆 🥇 🚀 💡 💰 💸 ₿ ✅ ❌ ❓ ❗ ⚠️ 🔒 🔑",
    "Food and drink" to "☕ 🍺 🍻 🥂 🍷 🍕 🍔 🌮 🍿 🍩 🍪 🎂 🍓 🍑 🍆 🥑 🌶️",
    "Animals and nature" to "🐶 🐱 🐸 🐵 🦄 🐝 🦋 🐙 🦀 🐳 🦩 🐐 🦉 🌸 🌹 🌻 🌈 ☀️ 🌙 🌊 ❄️ 🌍",
    "Activities" to "⚽ 🏀 🎮 🎯 🎵 🎶 🎸 📷 📚",
).map { (n, s) -> n to s.split(' ').filter { it.isNotBlank() } }
