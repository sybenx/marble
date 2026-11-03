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

// Reactions under a post, as Jumble's: ☺+ (or your own emoji), and how many reacted. A tap opens the picker (your
// recent ones, then 👍 ❤️ 😂 🥲 👀 🫡 🫂, then search and every emoji); a long press opens the quick row above it:
// slide to one and let go. A new pick changes your reaction. An opened post shows each emoji people used, and how many.

private val ITEM = 36.dp
private val LIFT = 60.dp
private val LEFT = (-10).dp

@Composable
fun Heart(n: Event, liked: Boolean, social: cc.aaro.cosign.Social, onLike: () -> Unit, showCounts: Boolean = true) {
    val mine by social.reactions.collectAsState()
    val counts by social.engagement.collectAsState()
    val haptic = LocalHapticFeedback.current
    val px = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(-1) }
    var choices by remember { mutableStateOf(emptyList<String>()) }
    // Where the row starts, from the heart's left edge: above it, but kept on screen however near the edge the heart is.
    var heartX by remember { mutableStateOf(0f) }
    val screenW = with(px) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    fun rowLeft(n: Int): Float = with(px) {
        val w = (n + 1) * ITEM.toPx()
        (heartX + LEFT.toPx()).coerceAtMost(screenW - w - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()) - heartX
    }
    fun pick(e: String) { open = false; hover = -1; social.react(n, e) }
    val chosen = mine[n.id]
    Box {
        Box(Modifier.size(40.dp).clip(CircleShape)
            .onGloballyPositioned { heartX = it.boundsInWindow().left }
            .semantics { contentDescription = if (liked) "Liked" else "Like"; onLongClick("React with an emoji") { choices = social.topEmojis(); open = true; true } }
            .pointerInput(n.id) {
                val item = ITEM.toPx(); val top = -LIFT.toPx()
                fun at(x: Float, y: Float): Int { val left = rowLeft(choices.size); return if (y < top - item * 0.6f || y > 20.dp.toPx()) -1 else ((x - left) / item).toInt().takeIf { x >= left && it <= choices.size } ?: -1 }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var up = false
                    // The tap is the heart's: consumed, so the post under it doesn't open too.
                    val quick = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { up = waitForUpOrCancellation()?.also { it.consume() } != null; true } != null
                    // A tap: the picker.
                    if (quick) { if (up) all = true; return@awaitEachGesture }
                    // Held: the row opens, and follows the finger.
                    choices = social.topEmojis(); open = true; hover = -1
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
                                else -> hover = -1   // let go elsewhere: the row stays, for a tap
                            }
                            break
                        }
                    }
                }
            }, contentAlignment = Alignment.Center) {
            if (chosen != null) Text(chosen, fontSize = 19.sp)
            else if (liked) Icon(Glyphs.heartOn, null, tint = Vault.Amber, modifier = Modifier.padding(10.dp))
            else Box { Icon(Glyphs.smile, null, tint = Vault.Muted, modifier = Modifier.padding(10.dp)); Text("+", color = Vault.Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 5.dp)) }
        }
        if (open) Popup(offset = with(px) { IntOffset(rowLeft(choices.size).toInt(), -LIFT.roundToPx()) }, onDismissRequest = { open = false; hover = -1 },
            properties = PopupProperties(focusable = true)) {
            Row(Modifier.clip(RoundedCornerShape(26.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(26.dp)), verticalAlignment = Alignment.CenterVertically) {
                choices.forEachIndexed { i, e ->
                    Box(Modifier.size(ITEM).clickable { pick(e) }, contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 22.sp, modifier = Modifier.graphicsLayer {
                            val s = if (hover == i) 1.45f else 1f; scaleX = s; scaleY = s; translationY = if (hover == i) -10.dp.toPx() else 0f
                        })
                    }
                }
                Box(Modifier.size(ITEM).clickable { open = false; all = true }, contentAlignment = Alignment.Center) {
                    Text("+", color = if (hover == choices.size) Vault.Accent else Vault.Muted, fontSize = 22.sp)
                }
            }
        }
    }
    // How many reacted, beside it (counted for posts in a list as they come on screen).
    LaunchedEffect(n.id) { social.wantCounts(n.id) }
    if (showCounts) counts[n.id]?.likeTotal?.takeIf { it > 0 }?.let { total ->
        Text(total.toString(), color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.offset(x = (-6).dp))
    }
    if (all) Dialog({ all = false }) { Picker(social.topEmojis()) { e -> all = false; social.react(n, e) } }
}

/** The picker: your recent ones and Jumble's usual set, a search (by the emoji's name), then every emoji. */
@Composable
private fun Picker(top: List<String>, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(q) {
        if (q.isBlank()) EMOJIS else EMOJIS.filter { e -> nameOf(e).contains(q.trim(), ignoreCase = true) }
    }
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Vault.Surface).padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (e in top) Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Vault.Ground).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
        }
        androidx.compose.material3.OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), singleLine = true,
            placeholder = { Text("Search emoji", color = Vault.Muted) }, shape = RoundedCornerShape(20.dp))
        LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(shown) { e -> Box(Modifier.size(44.dp).clip(CircleShape).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) } }
        }
    }
}

/** An emoji's Unicode name ("smiling face with heart-eyes"), for the search. */
private fun nameOf(e: String): String = try {
    e.codePoints().toArray().filter { it > 0x2000 && it != 0xFE0F && it != 0x200D }.joinToString(" ") { Character.getName(it) ?: "" }
} catch (_: Exception) { "" }

/**
 * Under an opened post: each emoji people reacted with, and how many, most used first (yours marked). A tap adds
 * the same reaction.
 */
@Composable
fun ReactionChips(n: Event, social: cc.aaro.cosign.Social) {
    val counts by social.engagement.collectAsState()
    val mine by social.reactions.collectAsState()
    val liked by social.liked.collectAsState()
    val list = counts[n.id]?.emojis?.filter { (e, _) -> e != "-" && !e.startsWith(":") && e.length <= 8 }?.map { (e, c) -> (if (e == "+") "❤️" else e) to c }
        ?.groupBy({ it.first }, { it.second })?.map { it.key to it.value.sum() }?.sortedByDescending { it.second } ?: return
    if (list.isEmpty()) return
    val my = mine[n.id] ?: if (n.id in liked) "❤️" else null
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 68.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((e, c) in list.take(12)) {
            val me = e == my
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(if (me) Vault.AccentGround else Vault.Surface).border(1.dp, if (me) Vault.AccentEdge else Vault.Edge, RoundedCornerShape(14.dp))
                .clickable(enabled = !me) { social.react(n, e) }.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e, fontSize = 15.sp)
                Spacer(Modifier.width(5.dp))
                Text(c.toString(), color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** All the emojis + offers: faces, hands and hearts first, then the rest people react with most. */
private val EMOJIS = ("❤️ 😂 🤣 😍 🥰 😘 😊 🙂 😉 😎 🤩 🥳 😮 😲 🤯 😳 🥺 😢 😭 😤 😡 🤬 😱 😨 😅 😆 😁 😀 🙃 🤔 🤨 😐 😑 🙄 😏 😬 🤐 😴 🤤 🤢 🤮 🤧 🥴 😵 🤠 🤓 🧐 😇 🤗 🤭 🫡 🫠 🫢 😈 💀 👻 👽 🤖 💩 🙈 🙉 🙊 " +
    "👍 👎 👏 🙌 🙏 🤝 💪 👊 ✊ 🤙 🤘 ✌️ 🤞 👌 🤌 👀 🫶 👋 ☝️ 👆 👇 👉 👈 " +
    "🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💖 💯 🔥 ✨ ⭐ 🌟 ⚡ 💥 🎉 🎊 🏆 🥇 🚀 💡 💰 💸 🧡 ₿ 🫂 " +
    "☕ 🍺 🍻 🥂 🍷 🍕 🍔 🌮 🍿 🍩 🍪 🎂 🍓 🍑 🍆 🥑 🌶️ " +
    "🐶 🐱 🐸 🐵 🦄 🐝 🦋 🐙 🦀 🐳 🦩 🐐 🦉 🌸 🌹 🌻 🌈 ☀️ 🌙 🌊 ❄️ 🌍 " +
    "⚽ 🏀 🎮 🎯 🎵 🎶 🎸 📷 📚 ✅ ❌ ❓ ❗ ⚠️ 🔒 🔑 🧠 🫀 🤑 🥶 🥵 🤡 🫣 🤥").split(' ').filter { it.isNotBlank() }.distinct()
