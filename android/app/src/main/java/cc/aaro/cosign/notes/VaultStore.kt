package cc.aaro.cosign.notes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where an account's vaults keep their state on this phone (owner secrets, commits, decrypted blobs): one file per
 * name, each AES-GCM under a key in the Android Keystore that never leaves it (vaults §3.2: generations kept only
 * encrypted at rest). Not in backups (the app's backup rules leave files/ out). Read once, then kept in memory.
 */
class VaultStore(context: Context, account: String) : cc.aaro.vaults.Store {
    private val dir = File(context.noBackupFilesDir, "vaults/" + account.take(16)).also { it.mkdirs() }
    private val cache = ConcurrentHashMap<String, String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private fun file(k: String) = File(dir, MessageDigest.getInstance("SHA-256").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }.take(40))

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    override fun get(k: String): String? {
        cache[k]?.let { return it }
        if (k in missing) return null
        val f = file(k)
        if (!f.exists()) { missing += k; return null }
        return try {
            val b = f.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b, 0, 12)); updateAAD(k.toByteArray()) }
            String(c.doFinal(b, 12, b.size - 12)).also { cache[k] = it }
        } catch (_: Exception) { null }
    }

    @Synchronized override fun put(k: String, v: String?) {
        val f = file(k)
        if (v == null) { cache.remove(k); missing += k; f.delete(); return }
        if (cache[k] == v) return
        cache[k] = v; missing -= k
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(k.toByteArray()) }
        val body = c.iv + c.doFinal(v.toByteArray())
        val tmp = File(f.path + ".tmp"); tmp.writeBytes(body); tmp.renameTo(f)
    }

    /** Everything this account's vaults keep here, gone (signing out). */
    fun wipe() { cache.clear(); missing.clear(); dir.deleteRecursively() }

    companion object { private const val ALIAS = "marble-vaults" }
}
