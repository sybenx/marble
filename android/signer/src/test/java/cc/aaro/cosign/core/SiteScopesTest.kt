package cc.aaro.cosign.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import cc.aaro.qrst.core.hexToBytes
import org.junit.Test

/** What a signed-in site may do with your key: posting always, private lists and DMs only with their switches (0.15.1). */
class SiteScopesTest {
    private val me = "a".repeat(64)
    private val stranger = "b".repeat(64)
    private val ct = "ciphertext"

    @Test fun privateListsStartOnWhenTheCodeAsksToDecrypt() {
        assertTrue(SiteScopes.asksLists("sign_event:1,sign_event:7,sign_event:4811,sign_event:30811,sign_event:24242,nip44_decrypt,get_public_key"))
        assertTrue(SiteScopes.asksLists(" nip04_decrypt "))
        assertFalse(SiteScopes.asksLists("sign_event:1,sign_event:7,get_public_key"))
        assertFalse("encrypting alone doesn't", SiteScopes.asksLists("nip44_encrypt"))
        assertFalse(SiteScopes.asksLists(""))
        assertFalse(SiteScopes.asksLists(null))
    }

    @Test fun decryptWithAStrangerIsRefusedWithoutDms() {
        for (m in listOf("nip44_decrypt", "nip04_decrypt", "nip44_encrypt", "nip04_encrypt")) {
            assertEquals(m, "dms", SiteScopes.lacks(m, listOf(stranger, ct), me, lists = false, dms = false))
            // Private lists don't open anyone else's messages.
            assertEquals(m, "dms", SiteScopes.lacks(m, listOf(stranger, ct), me, lists = true, dms = false))
        }
    }

    @Test fun decryptWithYourOwnKeyNeedsPrivateLists() {
        assertEquals("lists", SiteScopes.lacks("nip44_decrypt", listOf(me, ct), me, lists = false, dms = false))
        assertNull(SiteScopes.lacks("nip44_decrypt", listOf(me, ct), me, lists = true, dms = false))
        assertNull(SiteScopes.lacks("nip04_decrypt", listOf(me.uppercase(), ct), me, lists = true, dms = false))
        assertNull("writing a private list", SiteScopes.lacks("nip44_encrypt", listOf(me, ct), me, lists = true, dms = false))
    }

    @Test fun dmsOpenEveryone() {
        assertNull(SiteScopes.lacks("nip44_decrypt", listOf(stranger, ct), me, lists = false, dms = true))
        assertNull(SiteScopes.lacks("nip04_encrypt", listOf(stranger, ct), me, lists = false, dms = true))
        assertNull(SiteScopes.lacks("nip44_decrypt", listOf(me, ct), me, lists = false, dms = true))
    }

    @Test fun aDmIsRefusedThenAllowed() {
        for (k in listOf(4, 13, 14, 15, 1059)) {
            assertEquals("kind $k", "dms", SiteScopes.lacks("sign_event", listOf("{}"), me, lists = true, dms = false, kind = k))
            assertNull("kind $k", SiteScopes.lacks("sign_event", listOf("{}"), me, lists = false, dms = true, kind = k))
        }
    }

    @Test fun postingIsAlwaysOn() {
        for (k in listOf(0, 1, 3, 6, 7, 10000, 10003, 30023)) assertNull("kind $k", SiteScopes.lacks("sign_event", listOf("{}"), me, lists = false, dms = false, kind = k))
        assertNull(SiteScopes.lacks("get_public_key", emptyList(), me, lists = false, dms = false))
    }

    @Test fun aSiteWithItsOwnAccountReadsOnlyThatAccountsLists() {
        val child = "c".repeat(64)
        assertNull(SiteScopes.lacks("nip44_decrypt", listOf(child, ct), child, lists = true, dms = false))
        assertEquals("your main key is someone else to it", "dms", SiteScopes.lacks("nip44_decrypt", listOf(me, ct), child, lists = true, dms = false))
    }

    @Test fun refusalsSayWhichSwitch() {
        assertEquals("not_allowed: DMs are off for this site", SiteScopes.refusal("dms"))
        assertEquals("not_allowed: private lists are off for this site", SiteScopes.refusal("lists"))
    }

    // Vault data sealed to you with a one-time key (vaults §3.5, §3.6): answered for a site with private lists on.
    private val vectors = org.json.JSONObject(java.io.File("../vaults/src/test/resources/vaults.json").readText())
    private fun open(v: org.json.JSONObject, ct: String) = cc.aaro.qrst.core.Nip44.decrypt(ct, v.getString("conversation_key").hexToBytes())
    private val kr = vectors.getJSONObject("keyring")
    private val krOwner = org.json.JSONObject(kr.getString("payload")).getString("owner")
    private val krPlain = open(kr, org.json.JSONObject(kr.getString("event_content")).getJSONObject("self").getString("ct"))
    private val v = "1f".repeat(32); private val g = "2".repeat(64); private val kid = "3".repeat(32); private val k = "4".repeat(64); private val d = "5".repeat(32)

    @Test fun aRealKeyringSealOpensToVaultData() {
        assertEquals(kr.getString("payload"), krPlain)
        assertTrue(SiteScopes.vaultData(krPlain, krOwner))
        assertTrue(SiteScopes.vaultData(krPlain, krOwner.uppercase()))
        // The same entry opened for another account isn't theirs.
        assertFalse(SiteScopes.vaultData(krPlain, stranger))
    }

    @Test fun aRealKeyWrapIsVaultData() {
        val w = vectors.getJSONObject("wrap_to_reader")
        assertTrue(SiteScopes.vaultData(w.getString("plaintext"), stranger))
        assertTrue(SiteScopes.vaultData("""{"vault":"$v","keys":{"$kid":"$k"}}""", me))
    }

    @Test fun aReadersKeyringIsVaultData() =
        assertTrue(SiteScopes.vaultData("""{"vault":"$v","genesis":"$g","owner":"$stranger","role":"reader","start":"$kid","keys":{"$kid":"$k"},"inboxes":{"$kid":"$stranger"},"d":"$d"}""", me))

    @Test fun onlyADecryptMissingDmsWithPrivateListsOnIsOpened() {
        assertTrue(SiteScopes.mayOpenAsVault("dms", "nip44_decrypt", lists = true))
        assertFalse("private lists off", SiteScopes.mayOpenAsVault("dms", "nip44_decrypt", lists = false))
        for (m in listOf("nip04_decrypt", "nip44_encrypt", "nip04_encrypt", "sign_event")) assertFalse(m, SiteScopes.mayOpenAsVault("dms", m, lists = true))
        assertFalse(SiteScopes.mayOpenAsVault("lists", "nip44_decrypt", lists = true))
    }

    @Test fun messagesAreNeverVaultData() {
        for (dm in listOf("hey, are you around?", "", "null", "42", "[1,2]", "\"vault\"",
            // A NIP-17 seal or rumor (what a gift wrap opens to): an event, not vault data, so the sender stays private.
            """{"kind":13,"pubkey":"$stranger","content":"AgN2","tags":[],"created_at":1,"id":"${"6".repeat(64)}","sig":"x"}""",
            """{"kind":14,"pubkey":"$stranger","content":"{\\"vault\\":\\"$v\\",\\"keys\\":{}}","tags":[["p","$me"]],"created_at":1}"""))
            assertFalse(dm, SiteScopes.vaultData(dm, me))
    }

    @Test fun anOwnerEntryNeedsItsGenerationsAndYouAsOwner() {
        val base = """"vault":"$v","genesis":"$g","role":"owner","d":"$d""""
        val gens = """"gens":[{"id":"$kid","secret":"$k","prev":null,"because":{}}]"""
        assertTrue(SiteScopes.vaultData("""{$base,"owner":"$me",$gens}""", me))
        assertFalse("no gens", SiteScopes.vaultData("""{$base,"owner":"$me"}""", me))
        assertFalse("empty gens", SiteScopes.vaultData("""{$base,"owner":"$me","gens":[]}""", me))
        assertFalse("someone else as owner", SiteScopes.vaultData("""{$base,"owner":"$stranger",$gens}""", me))
        assertFalse("a reader entry with gens", SiteScopes.vaultData("""{"vault":"$v","genesis":"$g","role":"reader","d":"$d","owner":"$stranger","keys":{"$kid":"$k"},$gens}""", me))
    }

    @Test fun membersAddedLaterAreAllowed() {
        // §2: an unknown member is ignored, so a field a later app adds doesn't lock browsers out.
        val p = org.json.JSONObject(kr.getString("payload")).put("pinned", true).put("color", "blue")
        p.getJSONArray("gens").getJSONObject(0).put("note", "x").getJSONObject("because").put("later", 1)
        assertTrue(SiteScopes.vaultData(p.toString(), krOwner))
        assertTrue(SiteScopes.vaultData("""{"vault":"$v","keys":{"$kid":"$k"},"inboxes":{"$kid":"$stranger"},"note":"hi"}""", me))
        assertTrue(SiteScopes.vaultData("""{"vault":"$v","genesis":"$g","owner":"$stranger","role":"reader","keys_ref":{"alg":"nv1","key":"$k","size":10,"parts":["$k"],"v":2},"d":"$d","x":[1]}""", me))
        // A known member of the wrong type is still refused.
        assertFalse(SiteScopes.vaultData(org.json.JSONObject(kr.getString("payload")).put("relays", "wss://one").toString(), krOwner))
    }

    @Test fun nearMissesAreRefused() {
        val good = """{"vault":"$v","genesis":"$g","owner":"$me","role":"owner","gens":[{"id":"$kid","secret":"$k","prev":null,"because":{}}],"d":"$d"}"""
        assertTrue(SiteScopes.vaultData(good, me))
        val misses = listOf(
            good.replace("\"owner\":\"$me\"", "\"owner\":\"$stranger\""),                    // an owner entry naming someone else
            good.replace(",\"d\":\"$d\"", ""),                                                // no d
            good.replace("\"gens\":[{\"id\":\"$kid\",\"secret\":\"$k\",\"prev\":null,\"because\":{}}]", "\"gens\":[]"),  // an owner with no generations
            good.replace("\"role\":\"owner\"", "\"role\":\"admin\""),
            good.dropLast(1) + ",\"vault\":\"$v\"}",                                             // a duplicate key
            good.replace("\"vault\":", "vault:"),                                               // bare key (org.json would take it)
            good.replace("\"", "'"),                                                             // single quotes
            good + " x",                                                                          // trailing text
            good.replace(v, v.uppercase()),                                                       // hex must be lowercase
            good.replace(v, v.dropLast(2)),                                                       // short id
            good.replace("\"prev\":null", "\"prev\":\"nope\""),
            """{"vault":"$v","keys":{"${"3".repeat(64)}":"$k"}}""",                                // a kid of the wrong length
            """{"vault":"$v","keys":{}}""",
            """{"vault":"$v","genesis":"$g","owner":"$stranger","role":"reader","gens":[],"keys":{"$kid":"$k"},"d":"$d"}""") // a reader with gens
        for (m in misses) { assertTrue("a real near miss", m != good); assertFalse(m, SiteScopes.vaultData(m, me)) }
    }
}
