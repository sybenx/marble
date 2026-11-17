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
    internal class Ask(val slot: String, val title: String, val message: ByteArray) { val done = CountDownLatch(1); @Volatile var out: String? = null; @Volatile var why = "cancelled" }
    internal fun take(id: String) = waiting[id]

    /**
     * Signs `message` after showing the fingerprint prompt over whatever is on screen. Blocks until the
     * person answers; never call it on the main thread. Throws `cancelled` if they back out and
     * `fingerprint_changed` if a new finger was enrolled since the key was made.
     */
    fun sign(c: Context, slot: String, title: String, message: ByteArray): String {
        val sig = try { Signature.getInstance("SHA256withECDSA").apply { initSign(ks().getKey(alias(slot), null) as PrivateKey) } }
            catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) { throw KeykinException("fingerprint_changed") }
            catch (_: NullPointerException) { throw KeykinException("no_chip_key") }
        if (!asksFingerprint(c, slot)) return Der.toCompact(sig.apply { update(message) }.sign())
        val id = java.util.UUID.randomUUID().toString()
        val ask = Ask(slot, title, message)
        waiting[id] = ask
        try {
            c.startActivity(Intent(c, FingerprintActivity::class.java).putExtra("ask", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            if (!ask.done.await(2, TimeUnit.MINUTES)) throw KeykinException("cancelled")
            return ask.out ?: throw KeykinException(ask.why)
        } finally { waiting.remove(id) }
    }

    internal fun prompt(a: FragmentActivity, ask: Ask) {
        val sig = try { Signature.getInstance("SHA256withECDSA").apply { initSign(ks().getKey(alias(ask.slot), null) as PrivateKey) } }
            catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) { ask.why = "fingerprint_changed"; ask.done.countDown(); a.finish(); return }
        BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                try { ask.out = Der.toCompact(r.cryptoObject!!.signature!!.apply { update(ask.message) }.sign()) } catch (_: Exception) { ask.why = "failed" }
                ask.done.countDown(); a.finish()
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { ask.done.countDown(); a.finish() }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(ask.title).setSubtitle("Changes to your account need your fingerprint")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Cancel").build(),
            BiometricPrompt.CryptoObject(sig))
    }
}

/** Nothing but the system's fingerprint prompt, over the app. Signer process. */
class FingerprintActivity : FragmentActivity() {
    private var ask: Chip.Ask? = null
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Proc.signerOnly("FingerprintActivity")
        val a = intent.getStringExtra("ask")?.let(Chip::take) ?: return finish()
        ask = a
        if (b == null) Chip.prompt(this, a)
    }
    override fun onDestroy() { if (isFinishing) ask?.done?.countDown(); super.onDestroy() }
}
