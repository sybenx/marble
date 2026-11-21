package cc.aaro.cosign

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.aaro.cosign.ui.AskCard
import cc.aaro.cosign.ui.CosignTheme
import cc.aaro.cosign.ui.Vault
import kotlinx.coroutines.delay

/**
 * One question that waits for you, opened from its notification (Keykin SPEC 11.6, The tap). A level 2 question is
 * answered here, never in the notification: Android shows the fingerprint prompt only over a screen of this app, so
 * this stays up after your Yes until the signing it started has ended (the fingerprint, then the box). Signer process.
 */
class AskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Proc.signerOnly("AskActivity")
        val s = intent.getStringExtra("slot")?.let { CosignApp.instance.signers.signer(it) } ?: return finish()
        val id = intent.getStringExtra("id") ?: return finish()
        setContent {
            CosignTheme {
                val ui by s.ui.collectAsState()
                val answering by s.answering.collectAsState()
                var said by remember { mutableStateOf(false) }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                val ask = ui.asks.firstOrNull { it.id == id }
                BackHandler { if (ask != null && !said) s.decide(id, false); finish() }
                when {
                    !said && ask != null -> Sheet { AskCard(ask, now) { yes -> said = true; s.decide(id, yes); if (!yes) finish() } }
                    // Your Yes is being carried out: the fingerprint prompt comes up over this.
                    said && (ask != null || id in answering) -> Sheet {
                        CircularProgressIndicator(color = Vault.Accent, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(14.dp))
                        Text("Signing…", color = Vault.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    // Answered, run out, or done: nothing more to show.
                    else -> LaunchedEffect(Unit) { finish() }
                }
            }
        }
    }

    @Composable
    private fun Sheet(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
        Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                    .background(Vault.Surface, RoundedCornerShape(20.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(20.dp)).padding(10.dp),
                content = content,
            )
        }

    companion object {
        /** Opens the question [id] of the account in [slot]. */
        fun intent(context: Context, slot: String, id: String): Intent = Intent(context, AskActivity::class.java)
            .putExtra("slot", slot).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
