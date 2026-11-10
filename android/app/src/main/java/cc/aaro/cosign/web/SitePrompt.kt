package cc.aaro.cosign.web

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.aaro.cosign.ui.Vault

/** A tap that comes sooner than this after the prompt appears is ignored: it was meant for the page under it. */
const val PROMPT_GUARD_MS = 500L

/**
 * Over the web pane: a site asking for something, named by the WebView's verified origin (never by what the page
 * calls itself). Taps in its first [PROMPT_GUARD_MS] are ignored, so a page can't time a button under your finger.
 */
@Composable
fun SitePrompt(title: String, body: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit, note: String? = null) {
    val shownAt = remember { android.os.SystemClock.uptimeMillis() }
    fun guarded(f: () -> Unit): () -> Unit = { if (android.os.SystemClock.uptimeMillis() - shownAt >= PROMPT_GUARD_MS) f() }
    BackHandler { onNo() }
    // Every touch is the prompt's: none reaches the page under it.
    Box(Modifier.fillMaxSize().background(Vault.Scrim).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
            .background(Vault.Surface, RoundedCornerShape(20.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(20.dp)).padding(22.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Vault.Text)
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
            note?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = Vault.Muted) }
            Spacer(Modifier.height(20.dp))
            // Declining is never the weaker control.
            OutlinedButton(guarded(onNo), Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Vault.Line)) { Text(no, color = Vault.Text) }
            if (yes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Button(guarded(onYes), Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) {
                    Text(yes, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
