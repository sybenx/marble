package cc.aaro.cosign.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import cc.aaro.cosign.core.Event
import kotlinx.coroutines.withTimeoutOrNull

// The heart under a post, as on Marble Web. A tap likes it. A long press opens a row of emojis above it (your
// most-used, then the usual six): slide to one and let go, or let go and tap one; + opens them all. On a post
// you've reacted to, a tap opens the row too, and a new pick changes your reaction. The heart then shows your
// emoji (a ❤️ shows as the filled heart), and beside it, what others chose.

private val ITEM = 36.dp
private val LIFT = 60.dp
private val LEFT = (-10).dp

@Composable
fun Heart(n: Event, liked: Boolean, social: cc.aaro.cosign.Social, onLike: () -> Unit) {
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
                    val quick = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { up = waitForUpOrCancellation() != null; true } != null
                    // A tap: a like; on a post you've reacted to, the row, to change it.
                    if (quick) { if (up) { if (liked) { choices = social.topEmojis(); hover = -1; open = true } else onLike() }; return@awaitEachGesture }
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
            if (chosen != null && chosen != "❤️") Text(chosen, fontSize = 19.sp)
            else Icon(if (liked) Glyphs.heartOn else Glyphs.heart, null, tint = if (liked) Vault.Amber else Vault.Muted, modifier = Modifier.padding(10.dp))
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
    // What others reacted with: the top few emojis, and how many liked it in all.
    counts[n.id]?.let { e ->
        val top = e.emojis.map { it.first }.filter { it != "+" && !it.startsWith(":") && it.length <= 8 }.take(3)
        val total = e.likeTotal
        if (total > 0) Text((if (top.isEmpty()) "" else top.joinToString("") + " ") + total, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.offset(x = (-6).dp))
    }
    if (all) Dialog({ all = false }) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Vault.Surface).padding(vertical = 12.dp)) {
            Text("React with", color = Vault.Text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(EMOJIS) { e -> Box(Modifier.size(48.dp).clip(CircleShape).clickable { all = false; social.react(n, e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 26.sp) } }
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
