package cc.aaro.cosign

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.aaro.cosign.ui.CosignTheme
import cc.aaro.cosign.ui.Marbles
import cc.aaro.cosign.ui.Vault
import kotlinx.coroutines.launch

/**
 * Keep in Marble, from another app's share sheet: a sheet over that app with your words (what you'd want to find it
 * by) and what was shared kept alongside: a link, or text with the app it came from. Keep puts it in Later (synced
 * like any saved thing) and you're back where you were. Main process.
 */
class KeepActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.let { Shared.of(it.getStringExtra(Intent.EXTRA_TEXT), it.getStringExtra(Intent.EXTRA_SUBJECT)) }
            ?: return finish()
        // The app it came from, by its name, when Android says (it may not).
        val from = referrer?.takeIf { it.scheme == "android-app" }?.host?.takeIf { it != packageName }?.let { pkg ->
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { null }
        }
        setContent { CosignTheme { KeepSheet(shared, from, onDone = ::finish) } }
    }
}

/** What another app shared, sorted: a link (and its title, if the app gave one), short text (your words already), or a longer quote. */
internal data class Shared(val words: String, val url: String? = null, val title: String? = null, val quote: String? = null) {
    /** What it's kept by when you write nothing: the page's title or site, or the quote's first line. */
    fun fallback(): String = title ?: url?.let { android.net.Uri.parse(it).host?.removePrefix("www.") ?: it }
        ?: quote?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.let { if (it.length > 80) it.take(79).trimEnd() + "…" else it } ?: ""

    companion object {
        private val URL = Regex("https?://[^\\s<>\"]+")
        fun of(text: String?, subject: String?): Shared? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            URL.find(t)?.let { m ->
                val url = m.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':', '\'', '"')
                // Apps often share "Title https://…": the rest is the title, if it's title-sized.
                val rest = (t.removeRange(m.range)).trim().trim('-', '–', '—', '|', ':').trim()
                val title = subject?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: rest.takeIf { it.isNotEmpty() && it.length <= 200 && '\n' !in it }
                return Shared("", url, title)
            }
            // A line or two is your words already; more is kept alongside as a quote.
            return if (t.length <= 140 && t.lines().size <= 2) Shared(t) else Shared("", quote = t.take(2000))
        }
    }
}

@Composable
private fun KeepSheet(shared: Shared, from: String?, onDone: () -> Unit) {
    var words by remember { mutableStateOf(shared.words) }
    var keeping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val focus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    fun keep() {
        if (keeping) return
        keeping = true
        scope.launch {
            val kept = SavePageReceiver.account(null)?.let { p ->
                Social.of(p).later.keep(words.ifBlank { shared.fallback() }, shared.url, shared.quote, from.takeIf { shared.quote != null })
            } != null
            android.widget.Toast.makeText(context.applicationContext, if (kept) "Kept in Marble" else "Couldn't keep this", android.widget.Toast.LENGTH_SHORT).show()
            onDone()
        }
    }
    Box(Modifier.fillMaxSize().background(Vault.Scrim).clickable(MutableInteractionSource(), null, onClick = onDone)) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Vault.Surface)
            .clickable(MutableInteractionSource(), null) {}.navigationBarsPadding().imePadding().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Vault.Line))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(Marbles.today().res), null, Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("Keep in Marble", color = Vault.Text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                // Yours alone: kept in Later, never posted.
                androidx.compose.material3.Icon(Icons.Filled.Lock, null, tint = Vault.Muted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Only you", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
            }
            Label("IN YOUR WORDS")
            TextField(words, { words = it }, Modifier.fillMaxWidth().heightIn(min = 64.dp).focusRequester(focus),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodyLarge.fontSize, color = Vault.Text),
                placeholder = { Text(if (shared.url != null || shared.quote != null) "Why keep it? (optional)" else "Your words", color = Vault.Muted) },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = Vault.Ground, unfocusedContainerColor = Vault.Ground, focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent, cursorColor = Vault.Accent))
            if (shared.url != null || shared.quote != null) {
                Label("KEPT ALONGSIDE")
                shared.url?.let { url ->
                    val site = android.net.Uri.parse(url).host?.removePrefix("www.") ?: url
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Vault.Ground).border(1.dp, Vault.Edge, RoundedCornerShape(14.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Vault.AccentGround), contentAlignment = Alignment.Center) {
                            Text(site.take(1).uppercase(), color = Vault.Accent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            shared.title?.let { Text(it, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Text(site, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                shared.quote?.let { q ->
                    Row(Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                        Box(Modifier.width(3.dp).heightIn(min = 20.dp).background(Vault.Line))
                        Text("“$q”", color = Vault.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
                    }
                    from?.let { Text("from $it", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDone) { Text("Cancel", color = Vault.Muted) }
                Spacer(Modifier.width(8.dp))
                Button(::keep, enabled = !keeping && (words.isNotBlank() || shared.fallback().isNotEmpty()), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Keep") }
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
