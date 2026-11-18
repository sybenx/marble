package cc.aaro.cosign.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a signed-in site may do with your key: posting always, private lists and DMs only with their switches (0.15.1). */
class SiteScopesTest {
    private val me = "a".repeat(64)
    private val stranger = "b".repeat(64)
    private val ct = "ciphertext"

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
}
