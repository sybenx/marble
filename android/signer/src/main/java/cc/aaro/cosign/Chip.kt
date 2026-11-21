// The chip key (Keykin SPEC §4): a P-256 key in the phone's secure hardware that signs only after a
// fingerprint or face check. The box checks its signature on every account change (add a device,
// unblock, cycle the keys, form the whole key, log a browser in to the box's page).
//
// From the Keykin repo's Android app, changed for Marble: one key per account on this phone, and the
// prompt is shown by an activity of the signer process (FingerprintActivity), because the request it
// signs is made there.
package cc.aaro.cosign

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import cc.aaro.cosign.core.Asking
import cc.aaro.keykin.core.Der
import cc.aaro.keykin.core.KeykinException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Chip {
    private fun alias(slot: String) = if (slot == "0") "keykin-chip" else "keykin-chip.$slot"
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun prefs(c: Context) = c.getSharedPreferences("keykin-chip", 0)
    private fun debuggable(c: Context) = (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    fun canFingerprint(c: Context) = BiometricManager.from(c).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Whether the key that exists asks for a fingerprint. False only for the debug stand-in. */
    fun asksFingerprint(c: Context, slot: String) = prefs(c).getBoolean(alias(slot), false)

    /**
     * The chip key's public half, made on first use. With a fingerprint enrolled, the key needs one for
     * every signature. Without one: no chip key at all, except in a debug build, where a key that asks
     * for nothing stands in so the flows can be tried on an emulator with no fingerprint.
     */
    fun publicKey(c: Context, slot: String): String? {
        val a = alias(slot)
        if (!ks().containsAlias(a)) {
            val auth = canFingerprint(c)
            if (!auth && !debuggable(c)) return null
            val spec = KeyGenParameterSpec.Builder(a, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
            if (auth) spec.setUserAuthenticationRequired(true).setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG).setInvalidatedByBiometricEnrollment(true)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair()
            prefs(c).edit().putBoolean(a, auth).apply()
        }
        return Der.compressed(ks().getCertificate(a).publicKey as ECPublicKey)
    }

    fun forget(c: Context, slot: String) { try { ks().deleteEntry(alias(slot)) } catch (_: Exception) { }; prefs(c).edit().remove(alias(slot)).apply() }

    private val waiting = ConcurrentHashMap<String, Ask>()
    internal class Ask(val slot: String, val title: String, val subtitle: String, val message: ByteArray) {
        val done = CountDownLatch(1)
        /** Counted down when the prompt's screen came up. */
        val shown = CountDownLatch(1)
        @Volatile var out: String? = null
        @Volatile var why = "cancelled"
        /** The screen showing the prompt, so it can be closed when the request ends without an answer. */
        @Volatile var host: FingerprintActivity? = null
    }
    internal fun take(id: String) = waiting[id]

    /**
     * Where a prompt that could not come up by itself is offered instead: Android opens an app's screen only while that
     * app is on screen, so a prompt asked for from the background (a Yes given in a notification, say) is blocked
     * without a word. The app's service shows a notification whose tap opens [open]; [gone] takes it away.
     */
    interface Elsewhere { fun show(id: String, title: String, open: Intent); fun gone(id: String) }
    @Volatile var elsewhere: Elsewhere? = null
    /** How long the prompt's screen may take to come up before it is offered as a notification instead. */
    private const val SHOW_WAIT_MS = 2000L
    private const val ANSWER_WAIT_MINUTES = 2L

    /** Its notification was swiped away: a prompt that is not up yet will not be asked for, so the request ends now. */
    fun dismiss(id: String) { waiting[id]?.takeIf { it.host == null }?.done?.countDown() }

    /**
     * The app has come to the front: every prompt that could not come up by itself (Android blocked it in the
     * background) comes up now, over the app.
     */
    fun reshow(c: Context) { for ((id, a) in waiting) if (a.host == null && a.done.count > 0) c.startActivity(opening(c, id)) }

    private fun opening(c: Context, id: String) = Intent(c, FingerprintActivity::class.java).putExtra("ask", id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /**
     * Signs `message` after showing the fingerprint prompt over whatever is on screen, titled [title], with [subtitle]
     * under it. Blocks until the person answers; never call it on the main thread. Throws `cancelled` if they back out
     * or do not answer in two minutes, and `fingerprint_changed` if a new finger was enrolled since the key was made.
     * When the app is not on screen the prompt cannot come up by itself; it is then offered as a notification.
     */
    fun sign(c: Context, slot: String, title: String, message: ByteArray, subtitle: String = Asking.ACCOUNT_CHANGE): String {
        val sig = try { Signature.getInstance("SHA256withECDSA").apply { initSign(ks().getKey(alias(slot), null) as PrivateKey) } }
            catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) { throw KeykinException("fingerprint_changed") }
            catch (_: NullPointerException) { throw KeykinException("no_chip_key") }
        if (!asksFingerprint(c, slot)) return Der.toCompact(sig.apply { update(message) }.sign())
        val id = java.util.UUID.randomUUID().toString()
        val ask = Ask(slot, title, subtitle, message)
        waiting[id] = ask
        try {
            c.startActivity(opening(c, id))
            if (!ask.shown.await(SHOW_WAIT_MS, TimeUnit.MILLISECONDS) && ask.done.count > 0) elsewhere?.show(id, title, opening(c, id))
            if (!ask.done.await(ANSWER_WAIT_MINUTES, TimeUnit.MINUTES)) throw KeykinException("cancelled")
            return ask.out ?: throw KeykinException(ask.why)
        } finally {
            waiting.remove(id)
            elsewhere?.gone(id)
            // Ended without an answer (it ran out): the prompt goes too, rather than stay up for nothing.
            ask.host?.let { a -> a.runOnUiThread { a.close() } }
        }
    }

    internal fun prompt(a: FingerprintActivity, ask: Ask): BiometricPrompt? {
        val sig = try { Signature.getInstance("SHA256withECDSA").apply { initSign(ks().getKey(alias(ask.slot), null) as PrivateKey) } }
            catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) { ask.why = "fingerprint_changed"; ask.done.countDown(); a.finish(); return null }
        return BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                try { ask.out = Der.toCompact(r.cryptoObject!!.signature!!.apply { update(ask.message) }.sign()) } catch (_: Exception) { ask.why = "failed" }
                ask.done.countDown(); a.finish()
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { ask.done.countDown(); a.finish() }
        }).also { p -> p.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(ask.title).setSubtitle(ask.subtitle.ifEmpty { null })
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Cancel").build(),
            BiometricPrompt.CryptoObject(sig)) }
    }
}

/** Nothing but the system's fingerprint prompt, over the app. Signer process. */
class FingerprintActivity : FragmentActivity() {
    private var ask: Chip.Ask? = null
    private var prompt: BiometricPrompt? = null
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Proc.signerOnly("FingerprintActivity")
        val a = intent.getStringExtra("ask")?.let(Chip::take) ?: return finish()
        // Already up (opened by itself and again from the notification): one prompt is enough.
        if (a.host != null && a.host !== this) return finish()
        ask = a
        a.host = this
        a.shown.countDown()
        Chip.elsewhere?.gone(intent.getStringExtra("ask")!!)
        if (b == null) prompt = Chip.prompt(this, a)
    }
    /** The request ended without an answer: the prompt and this screen go. */
    internal fun close() { try { prompt?.cancelAuthentication() } catch (_: Exception) { }; if (!isFinishing) finish() }
    // Sent to the background (Home, another app on top): the system cancels the prompt without a word, so the
    // change it was for is cancelled too, at once, and nothing waits on a prompt that is no longer there.
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) { ask?.done?.countDown(); finish() } }
    override fun onDestroy() { if (isFinishing) ask?.done?.countDown(); if (ask?.host === this) ask?.host = null; super.onDestroy() }
}
