package cc.aaro.cosign

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.aaro.cosign.Nip55Request.Stage
import cc.aaro.cosign.ui.CosignTheme
import cc.aaro.cosign.ui.Vault

/**
 * Android signer, when you need to say something (NIP-55's Intents): an app that isn't
 * connected yet, something destructive, or the first time an app wants your messages.
 * It opens over the app, asks once, answers, and closes. Everything else an app asks
 * goes to Nip55Provider and never opens this. What it asks and how it answers is the
 * Cosign library's [Nip55Request]; this draws it.
 */
class Nip55Activity : ComponentActivity() {
    private val request = Nip55Request(this)
    private val phone get() = request.phone
    private val label get() = request.label

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CosignTheme { Sheet(request.stage.collectAsState().value) } }
        request.handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        request.handle(intent)
    }

    private fun reject() = request.reject()

    /** With several keys on this phone: which one this is about. */
    private fun whichKey() = if (request.several) ", as ${phone.label}" else ""

    @Composable
    private fun Sheet(s: Stage) {
        BackHandler { reject() }
        Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                    .background(Vault.Surface, RoundedCornerShape(20.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(20.dp)).padding(22.dp),
            ) {
                when (s) {
                    Stage.Connect -> Ask(
                        "Let $label use your account?" + (if (request.several) " (${phone.label})" else ""),
                        "It can post, like and follow as you. Anything destructive, like unfollowing everyone or deleting posts, comes to this phone first.",
                        "Remove it under Your account in $appName any time.",
                        no = "Don't connect", yes = "Use it with $label",
                    ) { request.connect() }
                    Stage.Dms -> Ask(
                        "Let $label read and send your messages?",
                        "It asked to encrypt or decrypt a message. Allow it once and it can do that from now on, until you remove it.",
                        null, no = "Not now", yes = "Allow",
                    ) { request.allowDms() }
                    is Stage.Approve -> Ask(
                        cc.aaro.cosign.ui.question(s.what), "$label asked${whichKey()}.", "It doesn't happen unless you say yes.",
                        no = "Don't", yes = "Yes", amber = true,
                    ) { request.approve() }
                    is Stage.Working -> {
                        CircularProgressIndicator(color = Vault.Teal, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(14.dp))
                        Text(s.text, color = Vault.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    is Stage.Failed -> {
                        Text("Couldn't do that", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
                        Spacer(Modifier.height(10.dp))
                        Text(s.text, style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton({ reject() }, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Vault.Line)) { Text("Close", color = Vault.Text) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Ask(title: String, body: String, small: String?, no: String, yes: String, amber: Boolean = false, onYes: () -> Unit) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = if (amber) Vault.Amber else Vault.Text)
        Spacer(Modifier.height(10.dp))
        Text(body, style = if (amber) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, color = if (amber) Vault.Text else Vault.Muted)
        small?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = Vault.Muted) }
        Spacer(Modifier.height(20.dp))
        // Declining is never the weaker control; the affirmative one says what it does.
        OutlinedButton({ reject() }, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Vault.Line)) { Text(no, color = Vault.Text) }
        Spacer(Modifier.height(10.dp))
        Button(onYes, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(if (amber) Vault.Amber else Vault.Teal, Vault.Ground)) {
            Text(yes, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
