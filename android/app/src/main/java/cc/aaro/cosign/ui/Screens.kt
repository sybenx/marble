package cc.aaro.cosign.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.aaro.cosign.Ask
import cc.aaro.cosign.Change
import cc.aaro.cosign.BatchAsk
import cc.aaro.cosign.Connect
import cc.aaro.cosign.CosignApp
import cc.aaro.cosign.Deleted
import cc.aaro.cosign.Deleting
import cc.aaro.cosign.SiteSummary
import cc.aaro.cosign.Future
import cc.aaro.cosign.Line
import cc.aaro.cosign.Offer
import cc.aaro.cosign.Phone
import cc.aaro.cosign.Site
import cc.aaro.cosign.Ui
import cc.aaro.cosign.appName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun App(phone: Phone, ui: Ui) {
    var scanning by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Vault.Ground)) {
        when {
            scanning -> {
                BackHandler { scanning = false }
                val context = androidx.compose.ui.platform.LocalContext.current
                Box(Modifier.fillMaxSize()) {
                    QrScanner(Modifier.fillMaxSize()) { scanning = false; phone.scanned(it) }
                    // A code copied instead of shown (the box's, a device's, nostrconnect://, a sign-in link). A site's is then checked by comparing digits.
                    Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp)) {
                        TextButton({
                            val t = pasted(context).trim()
                            if (t.isEmpty()) phone.showError("Copy the code first, then tap Paste a code.") else { scanning = false; phone.pasted(t) }
                        }) { Text("Paste a code", color = Vault.Accent) }
                        Spacer(Modifier.width(16.dp))
                        TextButton({ scanning = false }) { Text("Cancel", color = Vault.Text) }
                    }
                }
            }
            ui.offer != null -> JoinPrompt(ui.offer!!, phone::accept, phone::decline)
            ui.joining != null -> Joining(ui.joining!!, phone::decline)
            ui.asking != null && ui.member == null -> AskScreen(ui.asking!!, phone)
            ui.joinCode != null && ui.member == null -> JoinCode(ui.joinCode!!, ui.error, onScan = { phone.clearError(); scanning = true }, onPaste = phone::pasted, onCancel = phone::cancelJoin)
            ui.member == null -> Column(Modifier.fillMaxSize()) {
                // "I already have an account": its nsec first (straight to the signer), then the box's code, as for a new one.
                var importing by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) { Welcome(ui.error, onCreate = phone::createLocal, onOther = { phone.clearError(); phone.receiveKey() }, onHave = { phone.clearError(); importing = true }) }
                if (importing) ImportSheet({ importing = false }) { chars -> importing = false; phone.importKey(chars) }
                // A place for another key: the ones you have are a tap away.
                val others = CosignApp.instance.accounts.all.filter { it !== phone && it.isMember }
                if (others.isNotEmpty()) TextButton({ CosignApp.instance.accounts.forget(phone.slot); CosignApp.instance.accounts.show(others.first().slot) },
                    Modifier.align(Alignment.CenterHorizontally).safeDrawingPadding().padding(bottom = 12.dp)) { Text("Back to ${accountName(others.first())}", color = Vault.Muted) }
            }
            else -> Discover(phone, ui) { phone.clearError(); scanning = true }
        }
        // With several keys, every question says which one it's about.
        val which = if (CosignApp.instance.accounts.all.count { it.isMember } > 1) phone.label else null
        ui.connect?.takeUnless(cc.aaro.cosign.web.WebSignIn::hides)?.let { ConnectSheet(it, which, { pick -> phone.confirmConnect(pick) }, phone::digits, phone::closeConnect) }
        ui.joinText?.let { JoinTextSheet(it, phone::closeJoinText) }
        ui.loginAsk?.let { LoginSheet(it, { phone.confirmConnect() }, phone::closeConnect) }
        ui.move?.let { KeyMoveScreen(it, phone) }
        ui.summary?.let { UndoSiteSheet(it, phone::confirmUndoSite, phone::closeSummary) }
        ui.adding?.let { AddDeviceSheet(it, phone) }
        ui.reporting?.let { ReportLostSheet(it, phone) }
        ui.removedBy?.let { RemovedSheet(it, phone) }
        // A bunker link and the whole key never come to this process: the signer shows them (SignerActivity).
        ui.bunker?.let { androidx.compose.runtime.LaunchedEffect(Unit) { phone.reveal() } }
        ui.working?.let { WorkingSheet(it) }
        ui.tap?.let { TapSheet(it, phone::tap) }
        ui.takeOut?.let { if (it.stage == cc.aaro.cosign.TakeOut.Stage.SHOWING) androidx.compose.runtime.LaunchedEffect(Unit) { phone.reveal() } else TakeOutSheet(it, phone::closeTakeOut) }
    }
}

// --- Forming the whole key ------------------------------------------------------------------

/** Under Advanced: form the whole key (nsec) on this phone and show it, after a fingerprint. */
@Composable
private fun TakeOutEntry(phone: Phone, onScan: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 10.dp)) {
        TextButton({ open = !open }, contentPadding = PaddingValues(0.dp)) { Text("Take my account out of $appName", color = Vault.Accent) }
        if (open) {
            Muted("This forms your whole secret key (nsec) on this phone and shows it, so you can use it elsewhere or keep it as a backup. It needs your fingerprint, and your box hands over its piece for it. This is the only time a whole key exists.")
            Spacer(Modifier.height(8.dp))
            TextButton({ phone.takeOutViaServers() }, contentPadding = PaddingValues(0.dp)) { Text("Show my secret key, with my fingerprint", color = Vault.Danger) }
        }
    }
}

/** Waiting, the key itself (once, no screenshots), or why not. */
@Composable
internal fun TakeOutSheet(t: cc.aaro.cosign.TakeOut, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    if (t.stage == cc.aaro.cosign.TakeOut.Stage.SHOWING) androidx.compose.runtime.DisposableEffect(Unit) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Vault.Surface, RoundedCornerShape(20.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            when (t.stage) {
                cc.aaro.cosign.TakeOut.Stage.WAITING -> {
                    Text("Taking your account out", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
                    Spacer(Modifier.height(10.dp))
                    Text(t.text, style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
                }
                cc.aaro.cosign.TakeOut.Stage.SHOWING -> {
                    Text("Your secret key", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
                    Spacer(Modifier.height(10.dp))
                    Text(t.nsec ?: "", fontFamily = PlexMono, color = Vault.Text, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Account: ${short(t.npub ?: "")}", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(14.dp))
                    var copied by remember { mutableStateOf(false) }
                    OutlinedButton({ copySecret(context, "Nostr key", t.nsec ?: ""); copied = true; clearClipLater(context) }, Modifier.fillMaxWidth().height(48.dp), border = BorderStroke(1.dp, Vault.Line)) {
                        Text(if (copied) "Copied (cleared in a minute)" else "Copy", color = Vault.Text)
                    }
                    Muted("Anyone with this key is you. Keep it in a password manager.")
                }
                cc.aaro.cosign.TakeOut.Stage.FAILED -> {
                    Text("Not taken out", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
                    Spacer(Modifier.height(10.dp))
                    Text(t.text, style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClose, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) {
                Text(if (t.stage == cc.aaro.cosign.TakeOut.Stage.WAITING) "Cancel" else "Done")
            }
        }
    }
}

/** Clear the clipboard a minute after a key was copied, if it still holds it. */
private fun clearClipLater(context: android.content.Context) {
    val cb = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { cb.clearPrimaryClip() } catch (_: Exception) {} }, 60_000)
}


@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp), content = content)

@Composable
private fun Welcome(error: String?, onCreate: () -> Unit, onOther: () -> Unit, onHave: () -> Unit) = Column(Modifier.fillMaxSize().background(Kk.Ground).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
    Spacer(Modifier.weight(1f))
    Text(appName, style = MaterialTheme.typography.headlineMedium, color = Vault.Text)
    Spacer(Modifier.height(12.dp))
    Text("Search and discover what people are posting. With an account you can like and follow, and sign in to sites and apps.",
        style = MaterialTheme.typography.bodyLarge, color = Vault.Muted)
    error?.let { Spacer(Modifier.height(16.dp)); Text(it, color = Vault.Danger, style = MaterialTheme.typography.bodyMedium) }
    Spacer(Modifier.weight(1f))
    Button(onCreate, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Create your account", style = MaterialTheme.typography.labelLarge) }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onOther, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Kk.Line)) { Text("I have an account on another device", color = Vault.Text) }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onHave, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Kk.Line)) { Text("I have my secret key", color = Vault.Text) }
    Spacer(Modifier.height(10.dp))
    Text("Your key stays on this phone, sealed. It is never deleted unless you choose to.", style = MaterialTheme.typography.bodySmall, color = Vault.Muted,
        modifier = Modifier.align(Alignment.CenterHorizontally))
}

/**
 * "I already have an account": paste its nsec. The field is a plain Android one, so what's
 * in it is read as characters (never a String) and wiped; no screenshots while it's open;
 * the clipboard is cleared once it's used.
 */
@Composable
private fun ImportSheet(onClose: () -> Unit, onKey: (CharArray) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(Unit) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var field by remember { mutableStateOf<android.widget.EditText?>(null) }
    val take = {
        field?.let { f ->
            val e = f.text
            val chars = CharArray(e.length).also { e.getChars(0, e.length, it, 0) }
            e.clear()
            context.getSystemService(android.content.ClipboardManager::class.java)?.let { cb -> try { cb.clearPrimaryClip() } catch (_: Exception) {} }
            onKey(chars)
        }
    }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Vault.Surface, RoundedCornerShape(20.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Text("Bring your account", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
            Spacer(Modifier.height(10.dp))
            Text("Paste its secret key (nsec). It is kept on this phone, sealed, and signs from here. It is never deleted unless you choose to.",
                style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
            Spacer(Modifier.height(16.dp))
            androidx.compose.ui.viewinterop.AndroidView({ ctx ->
                android.widget.EditText(ctx).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    hint = "nsec1…"
                    setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.GRAY)
                    isSingleLine = true
                    field = this
                }
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button({ take() }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Bring it into $appName") }
            TextButton(onClose, Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel", color = Vault.Muted) }
        }
    }
}

@Composable
private fun Joining(text: String, onCancel: () -> Unit) = Page {
    Spacer(Modifier.weight(1f))
    CircularProgressIndicator(color = Vault.Accent, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(20.dp))
    Text(text, color = Vault.Text, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.weight(1f))
    TextButton(onCancel, Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel", color = Vault.Muted) }
}

@Composable
private fun JoinPrompt(offer: Offer, onJoin: () -> Unit, onCancel: () -> Unit) = if (offer.create) MakeKeyPrompt(offer.box, offer.bringing, onJoin, onCancel) else Page {
    Spacer(Modifier.height(24.dp))
    Text("Use your account on this phone?", style = MaterialTheme.typography.headlineSmall, color = Vault.Text)
    Spacer(Modifier.height(8.dp))
    Text(short(offer.npub ?: ""), fontFamily = PlexMono, color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(20.dp))
    Text("This phone gets its own piece of your account, and signs together with your box. Nothing can post as you from just one place, this phone included.",
        color = Vault.Text, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    Text("Almost everything happens without asking. Anything destructive, like unfollowing most people, wiping your profile or deleting a pile of posts, waits for you here.",
        color = Vault.Muted, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.weight(1f))
    Button(onJoin, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Use it on this phone", style = MaterialTheme.typography.labelLarge) }
    Spacer(Modifier.height(8.dp))
    TextButton(onCancel, Modifier.fillMaxWidth()) { Text("Not now", color = Vault.Muted) }
}

/** The box's code was read: this phone and the box make the account together. */
@Composable
private fun MakeKeyPrompt(box: String, bringing: Boolean, onJoin: () -> Unit, onCancel: () -> Unit) = if (bringing) Page {
    Spacer(Modifier.height(24.dp))
    Text("Bring your account to this box?", style = MaterialTheme.typography.headlineSmall, color = Vault.Text)
    Spacer(Modifier.height(20.dp))
    Text("This phone splits your key with your box at $box. From then on the two sign together, and each of your other devices gets a piece of its own. Your account and its npub stay the same.",
        color = Vault.Text, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    Text("The whole key stays on this phone too, sealed, as your backup. You can forget it later under Your account.", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.weight(1f))
    Button(onJoin, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Bring it in", style = MaterialTheme.typography.labelLarge) }
    Spacer(Modifier.height(8.dp))
    TextButton(onCancel, Modifier.fillMaxWidth()) { Text("Not now", color = Vault.Muted) }
} else Page {
    Spacer(Modifier.height(24.dp))
    Text("Create your account?", style = MaterialTheme.typography.headlineSmall, color = Vault.Text)
    Spacer(Modifier.height(20.dp))
    Text(
        "This phone and your box at $box make it together. Nobody holds all of it, not even this phone, so a lost phone or a hacked site can't post as you.",
        color = Vault.Text, style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(12.dp))
    Text("Almost everything happens without asking. Anything destructive, like unfollowing most people, wiping your profile or deleting a pile of posts, waits for you here.",
        color = Vault.Muted, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.weight(1f))
    Button(onJoin, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Create my account", style = MaterialTheme.typography.labelLarge) }
    Spacer(Modifier.height(8.dp))
    TextButton(onCancel, Modifier.fillMaxWidth()) { Text("Not now", color = Vault.Muted) }
}


/** Connecting a site: the one-tap consent of QRST at its lowest check, then progress. */
@Composable
private fun ConnectSheet(c: Connect, which: String?, onConnect: (String?) -> Unit, onDigits: (Boolean) -> Unit, onClose: () -> Unit) {
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Vault.Surface, RoundedCornerShape(20.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            when (c.stage) {
                // A site new to this account: a new account of its own is the main choice, the main account the small one.
                Connect.Stage.CONFIRM -> if (c.ask) {
                    Text("Sign in to ${c.origin}?", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
                    which?.let { Text("as $it", fontFamily = PlexMono, color = Vault.Accent, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(10.dp))
                    Text("A new account is made just for this site, empty to start. Nothing from your main account goes with it.",
                        style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
                    Spacer(Modifier.height(6.dp))
                    Text("Anything destructive comes to this phone first. Remove it under Your account any time.",
                        style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClose, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Vault.Line)) { Text("Don't connect", color = Vault.Text) }
                    Spacer(Modifier.height(10.dp))
                    Button({ onConnect("new") }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) {
                        Text("Sign in with a new account", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ onConnect("main") }, Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Use my main account", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Use your account on ${c.origin}?", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
                    // Signed in before with an account of its own: it gets that one again.
                    if (c.own) Text("Its own account, as before", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium)
                    // A Keykin account page: it is about the main account, so there is no other to choose.
                    if (c.accountPage) Text("This is an account page, so it uses your main account.", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium)
                    which?.let { Text("as $it", fontFamily = PlexMono, color = Vault.Accent, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(10.dp))
                    Text("It can post, like and follow as you. Anything destructive, like unfollowing everyone or deleting posts, comes to this phone first.",
                        style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
                    Spacer(Modifier.height(6.dp))
                    Text("Remove it under Your account any time. That stops it from then on; it doesn't undo what it already did.",
                        style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
                    Spacer(Modifier.height(20.dp))
                    // Declining is never the weaker control; the affirmative one names the site.
                    OutlinedButton(onClose, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Vault.Line)) { Text("Don't connect", color = Vault.Text) }
                    Spacer(Modifier.height(10.dp))
                    Button({ onConnect(null) }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) {
                        Text("Use it on ${c.origin}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Connect.Stage.COMPARE -> {
                    Text("Does ${c.origin} show these digits?", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
                    Spacer(Modifier.height(16.dp))
                    Text((c.digits ?: "").chunked(1).joinToString(" "), fontFamily = PlexMono, style = MaterialTheme.typography.displaySmall, color = Vault.Accent, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(16.dp))
                    Text("You pasted this code, so check that the site you mean is the one answering. If the digits are different, nothing is sent.", style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton({ onDigits(false) }, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Vault.Line)) { Text("They're different", color = Vault.Text) }
                    Spacer(Modifier.height(10.dp))
                    Button({ onDigits(true) }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("They're the same") }
                }
                Connect.Stage.WORKING -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Vault.Accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(c.message ?: "Connecting…", color = Vault.Text)
                    }
                    Spacer(Modifier.height(18.dp))
                    OutlinedButton(onClose, Modifier.fillMaxWidth().height(48.dp), border = BorderStroke(1.dp, Vault.Line)) { Text("Cancel", color = Vault.Text) }
                }
                Connect.Stage.DONE, Connect.Stage.FAILED -> {
                    Text(if (c.stage == Connect.Stage.DONE) "Connected" else "Not connected", style = MaterialTheme.typography.titleLarge,
                        color = if (c.stage == Connect.Stage.DONE) Vault.Accent else Vault.Danger)
                    Spacer(Modifier.height(8.dp))
                    Text(c.message ?: "", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                    Button(onClose, Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(Vault.Edge, Vault.Text)) { Text("OK") }
                }
            }
        }
    }
}

/**
 * This app's own grant, as your servers list it: an app on this phone (no site above it) named as this
 * app is, or as it was called before it was Marble (the name a grant was first made with stays on older ones).
 */
internal fun ownGrant(s: Site) = s.parent == null && s.via == "App on this phone" && s.origin in setOf("Marble", "Cosign")

/**
 * Your account, behind your picture on the home screen: your profile, devices, connected
 * apps and sites, keeping it safe, and what changed. How it works (servers, relays) is under Advanced.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AccountScreen(phone: Phone, ui: Ui, onScan: () -> Unit, onProfile: () -> Unit, onBack: () -> Unit,
                           onSpecial: () -> Unit = {}, onNews: () -> Unit = {}) {
    val m = ui.member!!
    var leaving by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val people by cc.aaro.cosign.People.known.collectAsState()
    val me = people[m.group.groupX]
    val social = remember(phone) { cc.aaro.cosign.Social.of(phone) }
    val names by social.deviceNames.collectAsState()
    var renaming by remember { mutableStateOf<cc.aaro.cosign.Member?>(null) }
    LaunchedEffect(Unit) { if (DebugOpen.renameThis) { DebugOpen.renameThis = false; renaming = m.group.members.firstOrNull { it.idx == m.idx } } }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack, Modifier.padding(end = 8.dp).size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vault.Text)
                }
                Text("Your account", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
            }
            Spacer(Modifier.height(16.dp))
            var switching by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClick = onProfile), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(me, m.group.groupX, 52)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(me?.name ?: "Your profile", style = MaterialTheme.typography.titleMedium, color = Vault.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (me?.name == null) Text("No name or picture yet", style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
                        Text(short(m.group.npub), fontFamily = PlexMono, style = MaterialTheme.typography.labelSmall, color = Vault.Muted)
                    }
                }
                TextButton({ switching = true }) { Text("Switch ▾", color = Vault.Accent) }
            }
            if (switching) KeysSheet(phone) { switching = false }
            // Another account of yours with something waiting: say so here too, not only in notifications.
            val waiting = CosignApp.instance.accounts.all.filter { it !== phone }.map { it to it.ui.collectAsState().value }
                .filter { (_, u) -> u.asks.isNotEmpty() || u.batchAsks.isNotEmpty() }
            for ((p, u) in waiting) TextButton({ CosignApp.instance.accounts.show(p.slot) }, contentPadding = PaddingValues(0.dp)) {
                Text("${accountName(p)}: ${u.asks.size + u.batchAsks.size} waiting for you. Switch", color = Vault.AmberText, style = MaterialTheme.typography.bodySmall)
            }
            ui.error?.let { Text(it, color = Vault.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        }
        // The paper backup's nudge is on the home screen; here it's under Keep your account safe.
        items(ui.future, key = { "future:" + it.id }) { f -> FutureCard(f) { phone.removeFuture(f) } }
        items(ui.deleting, key = { "deleting:" + it.batch }) { d -> DeletingCard(d, now) { phone.cancelDeletion(d.batch) } }
        items(ui.batchAsks, key = { "batch:" + it.batch }) { b -> BatchCard(b, now) { approve -> phone.decideBatch(b.batch, approve) } }
        items(ui.asks, key = { "ask:" + it.id }) { ask -> AskCard(ask, now) { approve -> phone.decide(ask.id, approve) } }

        // A quiet note, no banner: write relays that haven't answered in a week (with half or more silent, your
        // notes' and Reader's copies can't be written).
        cc.aaro.cosign.Social.of(phone).silentWriteRelays()?.let { (silent, of) ->
            item { Text("$silent of your $of write relays haven't answered in a week", color = Vault.Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp)) }
        }
        item { Heading("Your devices") }
        items(m.group.members.filter { !it.isBox }, key = { "member:" + it.idx }) { d ->
            // What you've called it (on any of your devices), else the name it joined with. A long press renames it.
            val name = names[cc.aaro.cosign.Social.deviceKey(d)]?.name ?: d.name
            Row(Modifier.combinedClickable(onClick = {}, onLongClickLabel = "Rename", onLongClick = { renaming = d }), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (d.idx == m.idx || ui.online[d.idx] == true) Vault.Accent else Vault.Field, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(if (d.idx == m.idx) "$name (this one)" else if (d.blocked) "$name (blocked)" else name, color = if (d.blocked) Vault.Muted else Vault.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                if (d.idx != m.idx && d.blocked) TextButton({ phone.unblock(d) }) { Text("Unblock", color = Vault.Accent, style = MaterialTheme.typography.bodySmall) }
                else if (d.idx != m.idx) TextButton({ phone.askReportLost(d) }) { Text("Report lost", color = Vault.Danger, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { Muted("Press and hold a device to rename it.") }
        if (ui.needsCycle) item {
            Column {
                Text("A blocked device still holds a piece that fits with the others. Cycle your keys to end that for good. Your account stays the same.", color = Vault.AmberText, style = MaterialTheme.typography.bodySmall)
                TextButton({ phone.cycle() }, contentPadding = PaddingValues(0.dp)) { Text("Cycle my keys, with my fingerprint", color = Vault.Accent) }
            }
        }
        if (!m.chip) item { Muted("This phone has no fingerprint set up, so it can sign but can't change your account (add a device, unblock, cycle keys, show the key).") }
        val tier1 = m.box.isEmpty()
        if (ui.keptKey) item {
            Column {
                TextButton(onScan, contentPadding = PaddingValues(0.dp)) { Text("Send my key to another device", color = Vault.Accent) }
                Muted("On the other device, choose I have an account on another device, then scan the code it shows.")
            }
        }
        if (!tier1) item { TextButton(onScan, contentPadding = PaddingValues(0.dp)) { Text("Add a device", color = Vault.Accent) } }
        if (!tier1) item { TextButton(onScan, contentPadding = PaddingValues(0.dp)) { Text("Log in to your box's page", color = Vault.Accent) } }

        // Your account page (Keykin Login): where it is, and the username and password that can look and block there.
        if (!tier1) item { Heading("Your account page") }
        if (!tier1) item { AccountPageRows(ui, phone) }

        item { Heading("Apps and sites you're signed in to") }
        item {
            Button(onScan, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) {
                Text("Sign in to a site", style = MaterialTheme.typography.labelLarge)
            }
        }
        item { TextButton({ phone.makeBunker() }, contentPadding = PaddingValues(0.dp)) { Text("Get a bunker link to paste into a site", color = Vault.Accent) } }
        // Marble's own way in (its likes, follows and saved interests) isn't one of them: removing it would break Marble.
        val sites = ui.sites.filterNot { ownGrant(it) }
        if (sites.isEmpty()) item { Muted("None yet. To use your account on a website, tap Sign in to a site and scan the code it shows (a nostrconnect code works too).") }
        // One entry per website, with each way it's connected inside.
        items(sites.groupBy { it.origin }.toList(), key = { "site:" + it.first }) { (origin, grants) ->
            SiteGroup(origin, grants, onRemove = phone::removeSite, onUndo = phone::askUndoSite)
        }

        // Following: your Special Follow list and where the news comes from.
        item { Heading("Following") }
        item {
            val special by social.special.collectAsState()
            Column {
                Row(Modifier.fillMaxWidth().clickable(onClick = onSpecial).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Special Follow", color = Vault.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(if (special.isEmpty()) "›" else "${special.size} ›", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                val circle by social.circleOn.collectAsState()
                Row(Modifier.fillMaxWidth().clickable { social.setCircleOn(!circle) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only your circle", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
                        Text("People you follow, and people at least two of them follow. Others stay out of Following, come after them in search, and fold under your posts. Worked out on this phone.",
                            color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(circle, { social.setCircleOn(it) })
                }
                Row(Modifier.fillMaxWidth().clickable(onClick = onNews).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("News sources", color = Vault.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("›", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Translation: posts in a language you don't read are translated on this phone; these you read are left as they are.
        item { Heading("Languages you read") }
        item {
            val added by cc.aaro.cosign.OnDevice.added.collectAsState()
            var adding by remember { mutableStateOf(false) }
            val phoneLangs = remember { val l = android.os.LocaleList.getDefault(); (0 until l.size()).map { l[it].language }.distinct() }
            Column {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (l in phoneLangs) Text(cc.aaro.cosign.OnDevice.name(l), color = Vault.Text, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 7.dp))
                    for (l in added.filter { it !in phoneLangs }) Text(cc.aaro.cosign.OnDevice.name(l) + "  ×", color = Vault.Text, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Vault.Surface).border(1.dp, Vault.Edge, RoundedCornerShape(16.dp))
                            .clickable { cc.aaro.cosign.OnDevice.setAdded(added - l) }.padding(horizontal = 10.dp, vertical = 7.dp))
                    Box {
                        Text("+ Add", color = Vault.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { adding = true }.padding(horizontal = 10.dp, vertical = 7.dp))
                        if (adding) androidx.compose.material3.DropdownMenu(true, { adding = false }, containerColor = Vault.Surface) {
                            for (l in cc.aaro.cosign.OnDevice.all().filter { it !in added && it !in phoneLangs })
                                androidx.compose.material3.DropdownMenuItem({ Text(cc.aaro.cosign.OnDevice.name(l), color = Vault.Text) }, { adding = false; cc.aaro.cosign.OnDevice.setAdded(added + l) })
                        }
                    }
                }
                Text("Posts in other languages are translated on this phone, at the top of the post, with the original a tap away. A language is downloaded once, the first time.",
                    color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { TranslateSetting() }

        // Search: the engine Enter goes to, its suggestions as you type, and when "Search posts" shows. On this phone.
        item { Heading("Search") }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            val engine by cc.aaro.cosign.SearchPrefs.engine.collectAsState()
            val suggest by cc.aaro.cosign.SearchPrefs.suggestions.collectAsState()
            val posts by cc.aaro.cosign.SearchPrefs.posts.collectAsState()
            Column {
                Text("Search with", color = Vault.Muted, style = MaterialTheme.typography.labelMedium)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (e in cc.aaro.cosign.Engine.entries) {
                        val on = e == engine
                        Text(e.label, color = if (on) Vault.Ground else Vault.Text, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Vault.Accent else Vault.Surface).border(1.dp, if (on) Vault.Accent else Vault.Edge, RoundedCornerShape(16.dp))
                                .clickable { cc.aaro.cosign.SearchPrefs.setEngine(context, e) }.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { cc.aaro.cosign.SearchPrefs.setSuggestions(context, !suggest) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Suggestions as you type", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
                        Text("What you type goes to ${engine.label} as you type, as in a browser.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(suggest, { cc.aaro.cosign.SearchPrefs.setSuggestions(context, it) })
                }
                Text("Search posts", color = Vault.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, label) in listOf("auto" to "Automatic", "always" to "Always", "never" to "Never")) {
                        val on = k == posts
                        Text(label, color = if (on) Vault.Ground else Vault.Text, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Vault.Accent else Vault.Surface).border(1.dp, if (on) Vault.Accent else Vault.Edge, RoundedCornerShape(16.dp))
                                .clickable { cc.aaro.cosign.SearchPrefs.setPosts(context, k) }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                Text("Automatic: offered under your search only if you follow people.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        // The paper backup is not decided for Keykin (one paper piece does nothing alone). Until it is: a second device.
        item { Heading("Keep your account safe") }
        if (tier1) item {
            Muted("Your key is on this phone, sealed, and it signs from here. It is never deleted unless you choose to. To keep a copy, send it to another device of yours.")
        }
        if (ui.keptKey && !tier1) item {
            var asking by remember { mutableStateOf(false) }
            Column {
                Text("Backup: the whole key is on this phone", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
                Muted("The whole key is kept here, sealed. It is how the account comes back if the pieces are lost. Signing uses the pieces.")
                TextButton({ asking = true }, contentPadding = PaddingValues(0.dp)) { Text("Forget the whole key on this phone", color = Vault.Danger, style = MaterialTheme.typography.bodySmall) }
            }
            if (asking) AlertDialog(
                onDismissRequest = { asking = false },
                title = { Text("Forget the whole key?") },
                text = { Text("This phone keeps only its piece. Unless you have the key somewhere else, there is then no backup: the account lives in its pieces alone.") },
                confirmButton = { TextButton({ asking = false; phone.forgetKey() }) { Text("Forget it", color = Vault.Danger) } },
                dismissButton = { TextButton({ asking = false }) { Text("Keep it") } },
                containerColor = Vault.Surface,
            )
        }
        if (!ui.keptKey) item {
            val devices = m.group.members.count { !it.isBox && !it.blocked }
            Muted(if (devices <= 1) "Your account is on this phone and your box only. If you lose this phone, you can't get it back: add a second device, or keep your secret key somewhere safe (Advanced, Take my account out)."
                else "Your account is on $devices devices and your box. Any one device with the box can sign, so losing one loses nothing: block it from another.")
        }

        item { Heading("Recent changes") }
        if (ui.changes.isEmpty()) item { Muted("Follows, profile, bookmarks… every change made through this phone shows up here, with Undo.") }
        items(ui.changes.take(12), key = { "change:" + it.id }) { ChangeRow(it) { phone.undo(it) } }

        if (ui.deleted.isNotEmpty()) {
            item { Heading("Deleted, with copies kept") }
            items(ui.deleted, key = { "deleted:" + it.id }) { DeletedRow(it) { phone.repost(it) } }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { advanced = !advanced }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced", style = MaterialTheme.typography.titleSmall, color = Vault.Muted, modifier = Modifier.weight(1f))
                Text(if (advanced) "▴" else "▾", color = Vault.Muted)
            }
        }
        if (advanced) {
            item {
                if (tier1) Muted("Your account is a Nostr key, kept on this phone. A Keykin box is optional: signing in to one splits the key between this phone and the box, so a lost phone can be blocked. This phone keeps the whole key either way.")
                if (tier1) TextButton(onScan, contentPadding = PaddingValues(0.dp)) { Text("Sign in to a Keykin box", color = Vault.Accent) }
                if (!tier1) Muted("Your account is a Nostr key in pieces: each of your devices holds one and your box holds one. Any two pieces sign; one alone can do nothing.")
                Spacer(Modifier.height(10.dp))
                val context = androidx.compose.ui.platform.LocalContext.current
                Text(short(m.group.npub), fontFamily = PlexMono, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable {
                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("npub", m.group.npub))
                })
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mem in m.group.members) Chip(if (mem.idx == m.idx) "This phone" else mem.name, ui.online[mem.idx] == true, Modifier.weight(1f))
                }
                if (ui.relaysUp == 0) Text("Can't reach your box. Retrying…", color = Vault.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            // The notes trial's summary, to send on at its end (from this phone's log; nothing in it is shown in Marble).
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                TextButton({
                    val text = cc.aaro.cosign.NotesTrial.summary(social.later.items.value)
                    context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), null))
                }, contentPadding = PaddingValues(0.dp)) { Text("Share the notes trial summary", color = Vault.Accent) }
            }
            item { TakeOutEntry(phone, onScan) }
            item { Heading("On this phone") }
            items(ui.log.take(20)) { LogLine(it) }
            item {
                Spacer(Modifier.height(12.dp))
                if (!tier1) Muted("Your box: ${m.box} · " + (if (ui.relaysUp == 0) "not reachable" else "reachable") + (if (m.group.relays.isEmpty()) "" else " · sites reach this phone on ${m.group.relays.size} relay${if (m.group.relays.size == 1) "" else "s"}"))
                TextButton({ leaving = true }, contentPadding = PaddingValues(0.dp)) { Text("Remove this phone from your account", color = Vault.Danger, style = MaterialTheme.typography.bodySmall) }
            }
            item { FeedLogSetting() }
        }
    }

    renaming?.let { d ->
        var text by remember(d.idx) { mutableStateOf(names[cc.aaro.cosign.Social.deviceKey(d)]?.name ?: d.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename this device") },
            text = {
                Column {
                    TextField(text, { text = it.take(40) }, singleLine = true)
                    Text("Shown on all your devices.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton({ social.nameDevice(cc.aaro.cosign.Social.deviceKey(d), text); renaming = null }, enabled = text.isNotBlank()) { Text("Save", color = Vault.Accent) } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
            containerColor = Vault.Surface,
        )
    }
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false },
        title = { Text("Remove this phone?") },
        text = { Text("This blocks this phone at your box and deletes its piece of your account. Sites and apps signed in through this phone stop working. Your other devices keep working; cycle your keys from one of them afterwards." +
            if (ui.keptKey) " The whole key kept on this phone as your backup is deleted too: copy it out first (Advanced, Take my account out) if you have it nowhere else." else "") },
        confirmButton = { TextButton({ leaving = false; phone.leave() }) { Text("Remove this phone", color = Vault.Danger) } },
        dismissButton = { TextButton({ leaving = false }) { Text("Keep it") } },
        containerColor = Vault.Surface,
    )
}

/**
 * The version, and under it (once the version's been tapped seven times) the feed log, for tuning Discover by hand:
 * off unless you turn it on, kept on this phone only. See [cc.aaro.cosign.FeedLog].
 */
@Composable
private fun FeedLogSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unlocked by cc.aaro.cosign.FeedLog.unlocked.collectAsState()
    val on by cc.aaro.cosign.FeedLog.on.collectAsState()
    val bytes by cc.aaro.cosign.FeedLog.bytes.collectAsState()
    var taps by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val version = remember { try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { null } ?: "" }
    Column {
        Text("$appName $version", color = Vault.Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                if (!unlocked && ++taps >= 7) cc.aaro.cosign.FeedLog.unlock(context)
            }.padding(vertical = 6.dp))
        if (!unlocked) return@Column
        Row(Modifier.fillMaxWidth().clickable { cc.aaro.cosign.FeedLog.set(context, !on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Record how I use Following", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
                Text("What you open, like and pass over in Following, and how long things stay on screen, for tuning it by hand. " +
                    "Kept on this phone only; nothing is sent. " + if (bytes > 0) "%.1f MB so far.".format(bytes / 1_048_576.0) else "Nothing recorded yet.",
                    color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.Switch(on, { cc.aaro.cosign.FeedLog.set(context, it) })
        }
        // Which feed Following is, to compare them (one tap back, any time): Feed 1 · Original (as on 27 Oct), Feed 2 · Mixed
        // (no counts, news between posts), Feed 3 · Edition (what's new from your people, Special Follow first; Today's news; three from beyond; the rest of your people; an end).
        val social = remember { cc.aaro.cosign.Social.of(cc.aaro.cosign.CosignApp.instance.phone) }
        val feed by social.feedVersion.collectAsState()
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Left pane", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
            Text("Now: " + cc.aaro.cosign.FeedNames.of(feed), color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (v in listOf(5, 4, 3)) Text(cc.aaro.cosign.FeedNames.short(v), color = if (feed == v) Vault.Ground else Vault.Text, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (feed == v) Vault.Accent else Vault.Surface).border(1.dp, if (feed == v) Vault.Accent else Vault.Edge, RoundedCornerShape(16.dp))
                        .clickable { social.setFeedVersion(v) }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({
                scope.launch {
                    val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cc.aaro.cosign.FeedLog.export(context) }
                    if (uri == null) { said = "Nothing to export, or it couldn't be saved."; return@launch }
                    said = "Saved in Downloads/Marble."
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { context.startActivity(android.content.Intent.createChooser(send, "Export the feed log")) } catch (_: Exception) {}
                }
            }, enabled = bytes > 0, contentPadding = PaddingValues(0.dp)) { Text("Export", color = Vault.Accent) }
            TextButton({ deleting = true }, enabled = bytes > 0, contentPadding = PaddingValues(0.dp)) { Text("Delete what's recorded", color = Vault.Danger) }
        }
        said?.let { Muted(it) }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete what's recorded?") },
        text = { Text("Everything the feed log kept on this phone goes. Exports already in Downloads stay.") },
        confirmButton = { TextButton({ deleting = false; cc.aaro.cosign.FeedLog.clear(); said = null }) { Text("Delete", color = Vault.Danger) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Keep it") } },
        containerColor = Vault.Surface,
    )
}

/** Your own LibreTranslate: notes in other languages are translated instead of left out. */
@Composable
private fun TranslateSetting() {
    val url by cc.aaro.cosign.Translate.url.collectAsState()
    val key by cc.aaro.cosign.Translate.key.collectAsState()
    var typedKey by remember(key) { mutableStateOf(key) }
    val status by cc.aaro.cosign.Translate.status.collectAsState()
    var typed by remember(url) { mutableStateOf(url) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.padding(top = 6.dp)) {
        Text("Your own translation server (optional)", style = MaterialTheme.typography.titleSmall, color = Vault.Text)
        Muted(if (url.isEmpty()) "A LibreTranslate address, if you run one: posts are then translated by it, and by this phone whenever it doesn't answer."
            else "Posts are translated by your server, and by this phone whenever it doesn't answer.")
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("https://translate.example.com", color = Vault.Muted) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Vault.Text))
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.OutlinedTextField(typedKey, { typedKey = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("API key (only if your server asks for one)", color = Vault.Muted) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Vault.Text))
        Row {
            TextButton({ cc.aaro.cosign.Translate.setKey(typedKey); cc.aaro.cosign.Translate.setUrl(typed); scope.launch { cc.aaro.cosign.Translate.check() } }, contentPadding = PaddingValues(0.dp)) {
                Text(if (typed.isBlank()) "Turn off" else "Save", color = Vault.Accent)
            }
        }
        status?.let { Muted(it) }
        Muted("Over https. The address and key stay on this phone.")
    }
}

/** An account, as you know it: its name, else its npub, shortened. */
internal fun accountName(p: Phone) = p.ui.value.member?.group?.groupX?.let { cc.aaro.cosign.People.known.value[it]?.name } ?: p.label

@Composable private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = Vault.Muted, modifier = Modifier.padding(top = 10.dp))
@Composable private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Vault.Muted)

@Composable
internal fun FutureCard(f: Future, onRemove: () -> Unit) = Card(Vault.AmberEdge, Vault.AmberGround) {
    Text("Dated in the future", style = MaterialTheme.typography.labelMedium, color = Vault.AmberText)
    Spacer(Modifier.height(4.dp))
    Text(f.text, style = MaterialTheme.typography.bodyMedium, color = Vault.Text)
    Spacer(Modifier.height(14.dp))
    Button(onRemove, Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(Vault.Amber, Vault.OnAmber)) { Text("Remove it", fontWeight = FontWeight.SemiBold) }
}

@Composable
/** [quiet]: on home, no ticking seconds (home-rules.md: no counts). */
internal fun AskCard(ask: Ask, now: Long, quiet: Boolean = false, onDecide: (Boolean) -> Unit) = Card(Vault.AmberEdge, Vault.AmberGround) {
    val left = ((ask.expiresAt - now) / 1000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(question(ask.hold), style = MaterialTheme.typography.titleMedium, color = Vault.Text, modifier = Modifier.weight(1f))
        if (!quiet) Text("${left}s", fontFamily = PlexMono, color = Vault.AmberText, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(6.dp))
    Text("${ask.app} asked. It doesn't happen unless you say yes.", style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
    Spacer(Modifier.height(14.dp))
    YesNo(onDecide)
}

/** Yes or no to something that needs you. No is never the weaker button. */
@Composable
private fun YesNo(onDecide: (Boolean) -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    OutlinedButton({ onDecide(false) }, Modifier.weight(1f).height(48.dp), border = BorderStroke(1.dp, Vault.Line)) { Text("Don't", color = Vault.Text) }
    Button({ onDecide(true) }, Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(Vault.Amber, Vault.OnAmber)) { Text("Yes", fontWeight = FontWeight.SemiBold) }
}

/**
 * What needs you, as a question about what would happen: "Remove 212 of your 230 follows"
 * becomes "Unfollow 212 people?". The words come from the policy (here and on your servers).
 */
internal fun question(what: String): String {
    val s = what.trim().removeSuffix(".").removeSuffix("?")
    fun people(n: String) = if (n == "1") "1 person" else "$n people"
    Regex("^Remove (\\d+) of your \\d+ follows?(?: \\(and add (\\d+)\\))?$").matchEntire(s)?.let {
        val add = it.groupValues[2]
        return "Unfollow ${people(it.groupValues[1])}" + (if (add.isEmpty()) "" else " and follow ${people(add)} instead") + "?"
    }
    Regex("^Empty your follow list \\(it has (\\d+) follows?\\)$").matchEntire(s)?.let { return "Unfollow all ${people(it.groupValues[1])}?" }
    Regex("^Empty your (.+) \\(it has (\\d+) (.+)\\)$").matchEntire(s)?.let { return "Remove all ${it.groupValues[2]} ${it.groupValues[3].replace("persons", "people")} from your ${it.groupValues[1]}?" }
    Regex("^(.+) dated in the future \\((.+)\\)$").matchEntire(s)?.let { return "Allow a ${it.groupValues[1].lowercase()} dated ${it.groupValues[2]}?" }
    Regex("^Delete (\\d+) (?:of your )?events?(?: for .*)?$").matchEntire(s)?.let { val n = it.groupValues[1]; return "Delete $n post${if (n == "1") "" else "s"}?" }
    Regex("^Delete (?:several|some) events?(?: for .*)?$").matchEntire(s)?.let { return "Delete several posts?" }
    Regex("^Wipe your profile: (.+)$").matchEntire(s)?.let { return "Clear your profile (${it.groupValues[1]})?" }
    if (s.startsWith("Ask relays to erase everything")) return "Erase everything you've ever posted?"
    return "$s?"
}

@Composable
private fun SiteGroup(origin: String, grants: List<Site>, onRemove: (Site) -> Unit, onUndo: (Site) -> Unit) = Column(
    Modifier.fillMaxWidth().background(Vault.Surface, RoundedCornerShape(12.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The address opens the site.
        val context = androidx.compose.ui.platform.LocalContext.current
        val web = origin.startsWith("https://") || origin.startsWith("http://")
        Text(origin.removePrefix("https://"), color = if (web) Vault.Accent else Vault.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).clickable(enabled = web, onClickLabel = "Open the site") {
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(origin))) } catch (_: Exception) {}
            }.padding(vertical = 6.dp))
        Spacer(Modifier.weight(1f))
        if (grants.size > 1) TextButton({ grants.forEach(onRemove) }) { Text("Remove all", color = Vault.AmberText) }
    }
    for (g in grants.sortedByDescending { it.lastUsed }) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            // Which account it signed in with, for sites (an app on this phone always has the main one).
            (if (g.via == "App on this phone") "" else if (g.own) "Own account · " else "Main account · ") +
                "${g.via ?: if (g.parent == null) "Connected by code" else "Connected"} · used ${ago(g.lastUsed)}",
            color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        TextButton({ onUndo(g) }) { Text("Undo all", color = Vault.Accent) }
        TextButton({ onRemove(g) }) { Text("Remove", color = Vault.AmberText) }
    }
}

@Composable
internal fun DeletingCard(d: Deleting, now: Long, quiet: Boolean = false, onUndo: () -> Unit) = Card(Vault.Line, Vault.Surface) {
    val left = ((d.until - now) / 1000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (quiet) "Deleting posts for ${d.by.removePrefix("https://")}" else "Deleting ${d.count} post${if (d.count == 1) "" else "s"} for ${d.by.removePrefix("https://")}", style = MaterialTheme.typography.titleSmall, color = Vault.Text)
            Text(if (quiet) "A copy is kept here" else "in ${left}s · a copy is kept here", style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
        }
        Button(onUndo, colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Undo") }
    }
}

@Composable
internal fun BatchCard(b: BatchAsk, now: Long, quiet: Boolean = false, onDecide: (Boolean) -> Unit) = Card(Vault.AmberEdge, Vault.AmberGround) {
    val left = ((b.expiresAt - now) / 1000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(question(b.text), style = MaterialTheme.typography.titleMedium, color = Vault.Text, modifier = Modifier.weight(1f))
        if (!quiet) Text("${left}s", fontFamily = PlexMono, color = Vault.AmberText, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(6.dp))
    Text("${b.by.removePrefix("https://")} asked. A deletion can't be fully taken back; a copy is kept here, so you can post them again.", style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
    Spacer(Modifier.height(14.dp))
    YesNo(onDecide)
}

@Composable
private fun DeletedRow(d: Deleted, onRepost: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(d.text.ifBlank { "(no text)" }, style = MaterialTheme.typography.bodyMedium, color = Vault.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${d.kind} · ${ago(d.at)}", style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
    }
    TextButton(onRepost) { Text("Post again", color = Vault.Accent) }
}

/** Before undoing a site: what it did, and what undoing it will do. */
@Composable
fun UndoSiteSheet(sum: SiteSummary, onUndo: () -> Unit, onClose: () -> Unit) {
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Vault.Surface, RoundedCornerShape(20.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            val origin = sum.origin.removePrefix("https://")
            Text("Undo everything $origin did?", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
            Spacer(Modifier.height(10.dp))
            val did = sum.counts.entries.sortedByDescending { it.value }.joinToString(", ") { (k, n) -> "$n $k${if (n == 1) "" else if (k.endsWith("s")) "" else "s"}" }
            Text(if (sum.total == 0) "It hasn't signed anything yet." else "Since it connected, it signed ${sum.total}: $did.", style = MaterialTheme.typography.bodyMedium, color = Vault.Text)
            Spacer(Modifier.height(8.dp))
            val parts = buildList {
                add("removes its access")
                if (sum.lists.isNotEmpty()) add("puts your ${sum.lists.joinToString(" and ")} back to how ${if (sum.lists.size == 1) "it was" else "they were"} before it changed ${if (sum.lists.size == 1) "it" else "them"}")
                if (sum.deletable > 0) add("deletes the other ${sum.deletable} it posted")
            }
            Text("This ${if (parts.size > 1) parts.dropLast(1).joinToString(", ") + " and " + parts.last() else parts.first()}." + if (sum.deletable > 0) " A deletion can't be fully taken back; $appName keeps copies." else "",
                style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClose, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Vault.Line)) { Text("Cancel", color = Vault.Text) }
            Spacer(Modifier.height(10.dp))
            Button(onUndo, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Vault.Amber, Vault.OnAmber)) { Text("Undo everything", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun ChangeRow(c: Change, onUndo: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(6.dp).background(if (c.held) Vault.Amber else Vault.Accent, CircleShape))
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
        Text(c.text, style = MaterialTheme.typography.bodyMedium, color = if (c.undone) Vault.Muted else Vault.Text)
        Text("${c.by.removePrefix("https://")} · ${ago(c.at)}${if (c.undone) " · undone" else ""}", style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
    }
    if (!c.undone) TextButton(onUndo) { Text("Undo", color = Vault.Accent) }
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier) = Row(
    modifier.border(1.dp, Vault.Edge, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(8.dp).background(if (on) Vault.Accent else Vault.Field, CircleShape))
    Spacer(Modifier.width(8.dp))
    Text(label, style = MaterialTheme.typography.bodySmall, color = if (on) Vault.Text else Vault.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Card(edge: Color, ground: Color = Vault.Surface, content: @Composable () -> Unit) =
    Column(Modifier.fillMaxWidth().background(ground, RoundedCornerShape(14.dp)).border(1.dp, edge, RoundedCornerShape(14.dp)).padding(16.dp)) { content() }

@Composable
private fun LogLine(line: Line) = Row {
    val color = when (line.kind) { "approved" -> Vault.Amber; "denied" -> Vault.Danger; "auto" -> Vault.Accent; else -> Vault.Muted }
    Box(Modifier.padding(top = 7.dp).size(6.dp).background(color, CircleShape))
    Spacer(Modifier.width(10.dp))
    Column {
        Text(line.text, style = MaterialTheme.typography.bodyMedium, color = Vault.Text)
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(line.ts)), style = MaterialTheme.typography.bodySmall, color = Vault.Muted)
    }
}

private fun ago(seconds: Long): String {
    val s = System.currentTimeMillis() / 1000 - seconds
    return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" }
}

private fun short(npub: String) = if (npub.length > 20) npub.take(12) + "…" + npub.takeLast(6) else npub


/** On a new device: its code, for one of your devices to scan; then that device's answer, for this one to scan. */
@Composable
private fun JoinCode(code: String, error: String?, onScan: () -> Unit, onPaste: (String) -> Unit, onCancel: () -> Unit) = Page {
    val context = androidx.compose.ui.platform.LocalContext.current
    Spacer(Modifier.height(24.dp))
    Text("Sign in with another device", style = MaterialTheme.typography.headlineSmall, color = Vault.Text)
    Spacer(Modifier.height(10.dp))
    Text("1. On a device that's already in your account, open $appName, tap your picture, then Add a device, and scan this. It asks for that device's fingerprint.\n2. That device then shows a code of its own. Scan it here.", style = MaterialTheme.typography.bodyMedium, color = Vault.Muted)
    Spacer(Modifier.height(16.dp))
    val qr = remember(code) { qrBitmap(code, 720) }
    androidx.compose.foundation.Image(qr.asImageBitmap(), "Join code", Modifier.fillMaxWidth().then(Modifier.aspectRatio(1f)).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp))
    error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Vault.Danger, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.weight(1f))
    Button(onScan, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Scan the other device's code") }
    Row(Modifier.align(Alignment.CenterHorizontally)) {
        TextButton({ context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Invite", code)) }) { Text("Copy this code", color = Vault.Muted) }
        TextButton({ onPaste(pasted(context)) }) { Text("Paste theirs", color = Vault.Muted) }
        TextButton(onCancel) { Text("Cancel", color = Vault.Muted) }
    }
}

private fun qrBitmap(text: String, size: Int): android.graphics.Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
}

/** Your keys on this phone: switch between them, or add another. */
@Composable
private fun KeysSheet(current: Phone, onClose: () -> Unit) {
    val accounts = CosignApp.instance.accounts
    BackHandler { onClose() }
    androidx.compose.ui.window.Dialog(onClose) {
        Column(Modifier.background(Vault.Surface, RoundedCornerShape(20.dp)).border(1.dp, Vault.Edge, RoundedCornerShape(20.dp)).padding(22.dp)) {
            Text("Your accounts on this phone", style = MaterialTheme.typography.titleLarge, color = Vault.Text)
            Spacer(Modifier.height(12.dp))
            for (p in accounts.all.filter { it.isMember || it === current }) {
                TextButton({ accounts.show(p.slot); onClose() }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    Text((if (p === current) "● " else "○ ") + accountName(p), color = if (p === current) Vault.Accent else Vault.Text, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Each account has its own devices, sites and history; adding one doesn't touch the others.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button({ accounts.add(); onClose() }, Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(Vault.Accent, Vault.Ground)) { Text("Add another account") }
        }
    }
}

/** One device and one box: a card at the top until there is a second device. (The old paper backup's nudge; paper is not decided for Keykin yet.) */
@Composable
internal fun PaperCard(phone: Phone) = Card(Vault.AmberEdge, Vault.AmberGround) {
    Text("Add a second device", style = MaterialTheme.typography.titleMedium, color = Vault.AmberText)
    Spacer(Modifier.height(4.dp))
    Text("Your account is on this phone and your box only. If you lose this phone, you can't get it back. Add another device under Your account.", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
}

/** Copy a secret, marked sensitive so the keyboard and clipboard preview don't show it. */
private fun copySecret(context: android.content.Context, label: String, text: String) {
    val clip = android.content.ClipData.newPlainText(label, text)
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
}

/** What's on the clipboard, as text (the paper backup's code, pasted from a password manager). */
private fun pasted(context: android.content.Context): String =
    context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()

/** Blocking another device: one press, then the keys are cycled with your fingerprint so its piece fits nothing. */
@Composable
private fun ReportLostSheet(d: cc.aaro.cosign.Member, phone: Phone) {
    BackHandler { phone.cancelReportLost() }
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Kk.Ground, RoundedCornerShape(20.dp)).border(1.dp, Kk.Line, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Text("Report ${d.name} lost?", style = MaterialTheme.typography.titleLarge, color = Kk.Text)
            Spacer(Modifier.height(10.dp))
            Text("It is blocked at once: your box stops helping it, so it can't sign or read anything. Then your keys are cycled with your fingerprint, and what it holds stops fitting. Your account stays the same.",
                style = MaterialTheme.typography.bodyMedium, color = Kk.Muted)
            Spacer(Modifier.height(6.dp))
            Text("If you find it before the keys are cycled, you can unblock it. After that, add it again as a new device.", style = MaterialTheme.typography.bodySmall, color = Kk.Muted)
            Spacer(Modifier.height(20.dp))
            OutlinedButton({ phone.cancelReportLost() }, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Kk.Line)) { Text("Cancel", color = Kk.Text) }
            Spacer(Modifier.height(10.dp))
            Button({ phone.confirmReportLost() }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Kk.Danger, Color.White)) { Text("Block it") }
        }
    }
}

/** The box refuses this phone: it was blocked. */
@Composable
private fun RemovedSheet(by: String, phone: Phone) {
    var hidden by remember { mutableStateOf(false) }
    if (hidden) return
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Kk.Ground, RoundedCornerShape(20.dp)).border(1.dp, Kk.Line, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Text("This phone is blocked", style = MaterialTheme.typography.titleLarge, color = Kk.Danger)
            Spacer(Modifier.height(10.dp))
            Text("It was blocked by $by, so it can't sign any more. If that was a mistake, unblock it from another of your devices or from your box's page. If the keys were cycled since, remove this phone here and add it again.",
                style = MaterialTheme.typography.bodyMedium, color = Kk.Muted)
            Spacer(Modifier.height(16.dp))
            OutlinedButton({ phone.refresh() }, Modifier.fillMaxWidth().height(48.dp), border = BorderStroke(1.dp, Kk.Line)) { Text("Check again", color = Kk.Text) }
            TextButton({ hidden = true }, Modifier.align(Alignment.CenterHorizontally)) { Text("Close", color = Kk.Muted) }
        }
    }
}

/** You scanned a new device's code: add it, with your fingerprint. */
@Composable
private fun AddDeviceSheet(a: cc.aaro.cosign.AddingDevice, phone: Phone) {
    BackHandler { phone.cancelAddDevice() }
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Kk.Ground, RoundedCornerShape(20.dp)).border(1.dp, Kk.Line, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Text("Add ${a.name} to your account?", style = MaterialTheme.typography.titleLarge, color = Kk.Text)
            Spacer(Modifier.height(10.dp))
            Text("It gets its own piece of your account and can sign with your box, like this phone. This phone and your box each give half of that piece; nothing ever holds all of your account.",
                style = MaterialTheme.typography.bodyMedium, color = Kk.Muted)
            Spacer(Modifier.height(6.dp))
            Text("$appName asks for your fingerprint, and your box checks it.", style = MaterialTheme.typography.bodySmall, color = Kk.Muted)
            Spacer(Modifier.height(20.dp))
            OutlinedButton({ phone.cancelAddDevice() }, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Kk.Line)) { Text("Don't add", color = Kk.Text) }
            Spacer(Modifier.height(10.dp))
            Button({ phone.confirmAddDevice() }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Add with my fingerprint") }
        }
    }
}

/** Where the account's login page is (a small signed event that marble.syb.lol reads), and its username and password. */
@Composable
private fun AccountPageRows(ui: Ui, phone: Phone) {
    var page by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { page = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ui.loginPage?.removePrefix("https://") ?: "No address yet", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
                Text("Where you manage your account in a browser. $appName on the web reads this address for its account button.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (ui.loginPage == null) "Set" else "Change", color = Vault.Accent, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth().clickable { pass = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ui.username?.let { "Username: $it" } ?: "No username and password", color = Vault.Text, style = MaterialTheme.typography.bodyMedium)
                Text("A password alone can look, block a device, sign things out and log out. Anything more needs a device's fingerprint.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (ui.username == null) "Set" else "Change", color = Vault.Accent, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (page) {
        var text by remember { mutableStateOf(ui.loginPage ?: "https://") }
        AlertDialog(
            onDismissRequest = { page = false },
            title = { Text("Your login page") },
            text = { Column {
                TextField(text, { text = it.trim().take(120) }, singleLine = true)
                Text("Its address, starting with https://. Signed by your account and published, so anyone can see it. Leave it empty to remove it.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton({ phone.setLoginPage(if (text == "https://") "" else text); page = false }) { Text("Save", color = Vault.Accent) } },
            dismissButton = { TextButton({ page = false }) { Text("Cancel") } },
            containerColor = Vault.Surface,
        )
    }
    if (pass) {
        var name by remember { mutableStateOf(ui.username ?: "") }
        var word by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pass = false },
            title = { Text("Username and password") },
            text = { Column {
                TextField(name, { name = it.trim().lowercase().take(32) }, singleLine = true, placeholder = { Text("Username") })
                Spacer(Modifier.height(8.dp))
                TextField(word, { word = it.take(200) }, singleLine = true, placeholder = { Text("Password") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text("Setting it needs your fingerprint. Your box never sees the password.", color = Vault.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton({ phone.setPassword(name, word); pass = false }, enabled = name.length >= 3 && word.length >= 8) { Text("Set, with my fingerprint", color = Vault.Accent) } },
            dismissButton = { Row {
                if (ui.username != null) TextButton({ phone.clearPassword(); pass = false }) { Text("Remove", color = Vault.Danger) }
                TextButton({ pass = false }) { Text("Cancel") }
            } },
            containerColor = Vault.Surface,
        )
    }
}

/** After adding a device: what it needs to find your box, for it to scan (or to paste there). */
@Composable
private fun JoinTextSheet(text: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Kk.Ground, RoundedCornerShape(20.dp)).border(1.dp, Kk.Line, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Text("Now scan this with the new device", style = MaterialTheme.typography.titleLarge, color = Kk.Text)
            Spacer(Modifier.height(10.dp))
            Text("It tells the new device where your box is, so it can collect its piece. It holds no secret.", style = MaterialTheme.typography.bodyMedium, color = Kk.Muted)
            Spacer(Modifier.height(14.dp))
            val qr = remember(text) { qrBitmap(text, 720) }
            androidx.compose.foundation.Image(qr.asImageBitmap(), "Join code", Modifier.fillMaxWidth().then(Modifier.aspectRatio(1f)).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp))
            Spacer(Modifier.height(12.dp))
            var copied by remember { mutableStateOf(false) }
            OutlinedButton({ context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Join code", text)); copied = true },
                Modifier.fillMaxWidth().height(48.dp), border = BorderStroke(1.dp, Kk.Line)) { Text(if (copied) "Copied" else "Copy it instead", color = Kk.Text) }
            Spacer(Modifier.height(8.dp))
            Button(onClose, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Done") }
        }
    }
}

/** A bunker link, shown by the signer process only (SignerActivity): paste it into a site that takes one. */
@Composable
internal fun BunkerSheet(link: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Kk.Ground, RoundedCornerShape(20.dp)).border(1.dp, Kk.Line, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Text("Your bunker link", style = MaterialTheme.typography.titleLarge, color = Kk.Text)
            Spacer(Modifier.height(10.dp))
            Text("Paste it into a site that asks for a bunker or remote signer. It works once, for 15 minutes. The site then signs as you through this phone, until you remove it under Your account.",
                style = MaterialTheme.typography.bodyMedium, color = Kk.Muted)
            Spacer(Modifier.height(12.dp))
            Text(link, fontFamily = PlexMono, color = Kk.Text, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            var copied by remember { mutableStateOf(false) }
            OutlinedButton({ copySecret(context, "Bunker link", link); copied = true }, Modifier.fillMaxWidth().height(48.dp), border = BorderStroke(1.dp, Kk.Line)) {
                Text(if (copied) "Copied" else "Copy", color = Kk.Text)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClose, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Done") }
        }
    }
}

/** Your account page asked for something hard to undo: Yes or No, here. */
@Composable
private fun TapSheet(t: cc.aaro.cosign.Tap, onAnswer: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Kk.Ground, RoundedCornerShape(20.dp)).border(1.dp, Kk.Line, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Text(t.title, style = MaterialTheme.typography.titleLarge, color = Kk.Text)
            Spacer(Modifier.height(10.dp))
            Text(t.text, style = MaterialTheme.typography.bodyMedium, color = Kk.Muted)
            Spacer(Modifier.height(20.dp))
            OutlinedButton({ onAnswer(false) }, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Kk.Line)) { Text("No", color = Kk.Text) }
            Spacer(Modifier.height(10.dp))
            Button({ onAnswer(true) }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Kk.Danger, Color.White)) { Text("Yes, remove them") }
        }
    }
}

/** An account change is under way (it waits on your fingerprint, then on your box). */
@Composable
private fun WorkingSheet(what: String) {
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                .background(Kk.Ground, RoundedCornerShape(20.dp)).border(1.dp, Kk.Line, RoundedCornerShape(20.dp)).padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Kk.Green, strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Text(what, color = Kk.Text)
            }
        }
    }
}

// --- Keykin's own screens, in the approved mockup's look: neutral gray, white cards, one green accent ---

/** The mockup's palette (no tinted surfaces): gray ground, white cards, 1px lines, green on main buttons only. */
internal object Kk {
    private fun pick(night: Long, day: Long) = Color(if (Vault.dark) night else day)
    val Ground get() = pick(0xFF161616, 0xFFF4F4F4)
    val Card get() = pick(0xFF222222, 0xFFFFFFFF)
    val Line get() = pick(0xFF3A3A3A, 0xFFDCDCDC)
    val Text get() = pick(0xFFEDEDED, 0xFF1B1B1B)
    val Muted get() = pick(0xFFA3A3A3, 0xFF5F5F5F)
    val Green get() = Color(0xFF0B7A5C)
    val Danger get() = pick(0xFFF08A80, 0xFFB3261E)
}

@Composable
private fun KkRow(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
    Text(label, color = Kk.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
    Text(value, color = Kk.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
}

/** A browser asks to log in to your account page by the code it shows. Your fingerprint, then it is in. */
@Composable
private fun LoginSheet(l: cc.aaro.cosign.LoginAsk, onLogIn: () -> Unit, onNotMe: () -> Unit) {
    BackHandler { onNotMe() }
    Box(Modifier.fillMaxSize().background(Vault.Scrim), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp).background(Kk.Ground, RoundedCornerShape(20.dp)).padding(20.dp)) {
            Text("A browser wants to sign in", color = Kk.Muted, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text("Log in to ${l.host}?", color = Kk.Text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("That browser will have your account page: your devices, what is signed in, and what you can do there. Signing still goes through this phone.",
                color = Kk.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().background(Kk.Card, RoundedCornerShape(12.dp)).border(1.dp, Kk.Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp)) {
                KkRow("Account", l.account)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Kk.Line))
                KkRow("Box", "Your box, checked")
                Box(Modifier.fillMaxWidth().height(1.dp).background(Kk.Line))
                KkRow("Lasts", l.lasts)
            }
            Spacer(Modifier.height(10.dp))
            Text("Confirm with your fingerprint", color = Kk.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(onLogIn, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Log in") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onNotMe, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Kk.Line)) { Text("Not me", color = Kk.Danger) }
        }
    }
}

/** No setup code: ask a box for an account. Its owner sees your note and approves; then this phone and the box make the key. */
@Composable
private fun AskScreen(a: cc.aaro.cosign.Asking, phone: Phone) {
    var box by remember { mutableStateOf(a.box.removePrefix("https://")) }
    var note by remember { mutableStateOf(a.note) }
    BackHandler { phone.closeAsk() }
    Column(Modifier.fillMaxSize().background(Kk.Ground).safeDrawingPadding().padding(20.dp)) {
        Text("Ask for an account", color = Kk.Text, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("This box is run by someone you know. They see your note and approve it. Then this phone and the box make your key together.",
            color = Kk.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().background(Kk.Card, RoundedCornerShape(12.dp)).border(1.dp, Kk.Line, RoundedCornerShape(12.dp)).padding(14.dp)) {
            Text("Box", color = Kk.Muted, style = MaterialTheme.typography.labelMedium)
            if (a.step == 0) TextField(box, { box = it.trim().take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            else Text(box, color = Kk.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
            Spacer(Modifier.height(10.dp))
            Text("A note so they know it is you", color = Kk.Muted, style = MaterialTheme.typography.labelMedium)
            if (a.step == 0) TextField(note, { note = it.take(200) }, modifier = Modifier.fillMaxWidth())
            else Text(note.ifBlank { "(none)" }, color = Kk.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        for ((n, label) in listOf(1 to "Asked", 2 to "Waiting for approval", 3 to "Make the key with the box")) {
            val done = a.step > n; val now = a.step == n
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(if (done || now) Kk.Green else Kk.Card, CircleShape).border(1.dp, if (done || now) Kk.Green else Kk.Line, CircleShape), contentAlignment = Alignment.Center) {
                    Text(if (done) "✓" else "$n", color = if (done || now) Color.White else Kk.Muted, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label, color = if (done || now) Kk.Text else Kk.Muted, style = MaterialTheme.typography.bodyMedium)
                    if (n == 2 && now) Text("You can close the app. It keeps checking.", color = Kk.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        a.error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Kk.Danger, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.weight(1f))
        if (a.step == 0) Button({ phone.askAccount(box, note) }, Modifier.fillMaxWidth().height(52.dp), enabled = box.isNotBlank(),
            colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Ask") }
        else if (a.step < 3) OutlinedButton({ phone.cancelAsk() }, Modifier.fillMaxWidth().height(52.dp), border = BorderStroke(1.dp, Kk.Line)) { Text("Cancel the request", color = Kk.Danger) }
        TextButton({ phone.cancelAsk() }, Modifier.align(Alignment.CenterHorizontally)) { Text("I have a setup code instead", color = Kk.Text) }
    }
}


/** The whole key moving between your devices by QRST, in QRST Signer's words. */
@Composable
private fun KeyMoveScreen(m: cc.aaro.cosign.KeyMove, phone: Phone) {
    val context = androidx.compose.ui.platform.LocalContext.current
    BackHandler { phone.closeMove() }
    if (m.kind == "receive" && m.phase != "ENDED") androidx.compose.runtime.DisposableEffect(Unit) {
        val a = context as? android.app.Activity
        a?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); onDispose { a?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val big: @Composable (String) -> Unit = { Text(it.chunked(1).joinToString(" "), fontFamily = PlexMono, style = MaterialTheme.typography.displaySmall, color = Kk.Text) }
    Column(Modifier.fillMaxSize().background(Kk.Ground).safeDrawingPadding().padding(20.dp)) {
        when {
            m.kind == "consent" -> {
                var sure by remember { mutableStateOf(false) }
                Text("Key leaves this phone", color = Kk.Text, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("This is not a login. The other device keeps a working copy of your key. Once sent it can't be taken back.", color = Kk.Muted, style = MaterialTheme.typography.bodyMedium)
                if (!m.viaCamera) { Spacer(Modifier.height(8.dp)); Text("Opened as a link, not by this app's scanner. Make sure the other device is yours.", color = Kk.Danger, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().background(Kk.Card, RoundedCornerShape(12.dp)).border(1.dp, Kk.Line, RoundedCornerShape(12.dp)).clickable { sure = !sure }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(sure, { sure = it })
                    Text("I understand this gives that device permanent control of my account", color = Kk.Text, style = MaterialTheme.typography.bodyMedium)
                }
                m.problem?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Kk.Danger, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.weight(1f))
                Button({ phone.sendKey() }, Modifier.fillMaxWidth().height(52.dp), enabled = sure, colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Send my key") }
                TextButton({ phone.closeMove() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel", color = Kk.Text) }
            }
            m.phase == "ENDED" -> {
                val ok = m.outcome == "delivered" || m.kept
                Text(when { m.kind == "receive" && m.kept -> "Your key is on this phone"; ok -> "Sent"; m.outcome == "expired" -> "Ten minutes passed"; else -> "Nothing moved" },
                    color = Kk.Text, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(when {
                    m.kind == "receive" && m.kept -> "It is kept here, sealed, and signs from here."
                    ok -> "The other device confirmed it kept the key."
                    m.outcome == "wrong-code" || m.outcome == "too-many-attempts" -> "Too many wrong digits. Nothing was sent. Start again from a fresh code on the other device."
                    else -> "Nothing was sent or kept."
                }, color = Kk.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Button({ phone.closeMove() }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Done") }
            }
            m.kind == "receive" && m.phase == "ACCEPT" -> {
                Text("Your other device sent a key", color = Kk.Text, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(m.rendering ?: "", fontFamily = PlexMono, color = Kk.Text, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("Keep it, and this phone signs as that account.", color = Kk.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Button({ phone.keepKey() }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Keep it") }
                TextButton({ phone.declineKey() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Don't keep it", color = Kk.Danger) }
            }
            m.code != null && m.kind == "receive" -> {
                Text("Type these on the device that's sending", color = Kk.Text, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(20.dp)); big(m.code ?: ""); Spacer(Modifier.height(12.dp))
                Text("This transfer only. Not a PIN.", color = Kk.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton({ phone.closeMove() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel", color = Kk.Text) }
            }
            m.kind == "send" && m.phase == "RELEASE" && m.check == "COMPARE" -> {
                Text("Does the other device show these digits?", color = Kk.Text, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(20.dp)); big(m.compare ?: ""); Spacer(Modifier.weight(1f))
                Button({ phone.moveDigits("") }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("They match. Send my key") }
                TextButton({ phone.moveDigits(null) }, Modifier.align(Alignment.CenterHorizontally)) { Text("The digits are different", color = Kk.Danger) }
            }
            m.kind == "send" && m.phase == "RELEASE" -> {
                var digits by remember { mutableStateOf("") }
                Text("Digits on your other device", color = Kk.Text, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Type the digits the other device shows. They make sure the key goes to the device in front of you.", color = Kk.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                TextField(digits, { digits = it.filter(Char::isDigit).take(8) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                Spacer(Modifier.weight(1f))
                Button({ phone.moveDigits(digits) }, Modifier.fillMaxWidth().height(52.dp), enabled = digits.length >= 5, colors = ButtonDefaults.buttonColors(Kk.Green, Color.White)) { Text("Send my key") }
                TextButton({ phone.closeMove() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel", color = Kk.Text) }
            }
            m.kind == "receive" && m.uri != null && m.phase == "WAITING" -> {
                Text("Receiving a key", color = Kk.Text, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Scan this with the device that has the key. It sends the key here.", color = Kk.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                val qr = remember(m.uri) { qrBitmap(m.uri ?: "", 720) }
                androidx.compose.foundation.Image(qr.asImageBitmap(), "Pairing code", Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp))
                Spacer(Modifier.height(10.dp))
                Text("Only show this to your own devices. Whoever scans it can answer it.", color = Kk.Muted, style = MaterialTheme.typography.bodySmall)
                if (!m.connected) Text("Not connected to a relay yet. Still trying.", color = Kk.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                TextButton({ context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("QRST pairing link", m.uri ?: "")) },
                    Modifier.align(Alignment.CenterHorizontally)) { Text("Copy the link instead", color = Kk.Text) }
                TextButton({ phone.closeMove(); phone.startJoin() }, Modifier.align(Alignment.CenterHorizontally)) { Text("My account uses a Keykin box: join with an invite instead", color = Kk.Muted) }
                TextButton({ phone.closeMove() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel", color = Kk.Text) }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Kk.Green, strokeWidth = 2.dp); Spacer(Modifier.width(14.dp))
                    Text(if (m.phase == "SENT") "Sent. Waiting for the other device to confirm it kept the key." else if (m.kind == "receive" && m.uri == null) "Testing relays before showing a code" else "Reaching the other device", color = Kk.Text)
                }
                Spacer(Modifier.weight(1f))
                TextButton({ phone.closeMove() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel", color = Kk.Text) }
            }
        }
    }
}
