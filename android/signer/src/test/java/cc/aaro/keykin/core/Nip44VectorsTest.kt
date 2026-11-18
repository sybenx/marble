package cc.aaro.keykin.core

import cc.aaro.cosign.unreadable
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-44 against the spec's own vectors (nip44-vectors.json, from github.com/paulmillr/nip44, MIT).
 * Marble's vaults encrypt and decrypt with this same code, so they would keep working even if it
 * drifted from the standard; messages from other apps would not. This is what says it hasn't.
 */
class Nip44VectorsTest {
    private val v2 = JSONObject(javaClass.getResource("/nip44-vectors.json")!!.readText()).getJSONObject("v2")
    private fun objs(a: JSONArray) = List(a.length()) { a.getJSONObject(it) }

    @Test fun conversationKeys() {
        for (t in objs(v2.getJSONObject("valid").getJSONArray("get_conversation_key")))
            assertEquals(t.getString("conversation_key"), hex(Nip44.conversationKey(Secp.ecdhX(unhex(t.getString("sec1")), t.getString("pub2")))))
    }

    @Test fun encryptAndDecrypt() {
        for (t in objs(v2.getJSONObject("valid").getJSONArray("encrypt_decrypt"))) {
            val pub2 = Secp.pub(unhex(t.getString("sec2")))
            val key = Nip44.conversationKey(Secp.ecdhX(unhex(t.getString("sec1")), pub2))
            assertEquals(t.getString("conversation_key"), hex(key))
            assertEquals(t.getString("payload"), Nip44.encrypt(t.getString("plaintext"), key, unhex(t.getString("nonce"))))
            assertEquals(t.getString("plaintext"), Nip44.decrypt(t.getString("payload"), key))
        }
    }

    /** Every damaged message is "bad_message", which is what the NIP-55 answers treat as unreadable. */
    @Test fun damagedMessagesAreBadMessage() {
        for (t in objs(v2.getJSONObject("invalid").getJSONArray("decrypt"))) {
            val e = try { Nip44.decrypt(t.getString("payload"), unhex(t.getString("conversation_key"))); null } catch (e: KeykinException) { e }
            assertEquals(t.getString("note"), "bad_message", e?.code)
            assertTrue(t.getString("note"), unreadable(e!!))
        }
    }

    /** Servers away or an app's access gone still go to the sheet, which says which. */
    @Test fun onlyTheMessageItselfIsUnreadable() {
        assertFalse(unreadable(IllegalStateException("Couldn't reach your servers")))
        assertFalse(unreadable(KeykinException("revoked")))
        assertTrue(unreadable(javax.crypto.BadPaddingException("pad block corrupted")))
    }
}
