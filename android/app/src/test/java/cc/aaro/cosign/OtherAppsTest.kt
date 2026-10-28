package cc.aaro.cosign

import cc.aaro.cosign.core.Event
import cc.aaro.cosign.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Something from an app Marble doesn't draw. Fixture: the user's "I love #nostr" post quoting a SNOcrash.art 3D object (kind 33331). */
class OtherAppsTest {
    private fun fixture(name: String): Event =
        Event.fromMap(Json.parse(javaClass.getResource("/fixtures/other-app-3d-object/$name")!!.readText()))!!

    private val post = fixture("post.json")
    private val obj = fixture("object.json")

    @Test fun thePostQuotesTheObject() {
        assertEquals(1, post.kind)
        val q = Regex("nostr:(naddr1[02-9ac-hj-np-z]+)").find(post.content)!!.groupValues[1]
        assertEquals(Ref.Addr(33331, obj.pubkey, obj.firstTag("d")!!), Ref.parse(q))
        // In one line (a kept post in Notes): never the raw string.
        assertEquals("I love #nostr \n↪ quoted", readable(post.content))
    }

    @Test fun theObjectIsItsAltNeverItsJson() {
        assertEquals(33331, obj.kind)
        assertFalse(OtherApps.drawn(obj.kind))
        assertEquals("a 3D object: AJ FU, 40 vertices", OtherApps.what(obj))
        assertTrue(obj.content.trimStart().startsWith("{") || obj.content.trimStart().startsWith("["))
    }

    @Test fun withNoAltItSaysWhichApp() {
        val bare = obj.copy(tags = obj.tags.filter { it[0] != "alt" })
        assertEquals("A post from another app · SNOcrash.art", OtherApps.what(bare))
        assertEquals("A post from another app", OtherApps.what(bare.copy(tags = bare.tags.filter { it[0] != "client" })))
    }

    @Test fun openGoesToTheAppThatMadeIt() {
        // No handler is published for 33331 and the client tag names none: its name is a site, so there.
        assertNull(OtherApps.clientHandler(obj))
        assertEquals("https://snocrash.art", OtherApps.clientSite(obj))
        assertNull(OtherApps.clientSite(post))  // "Amethyst" isn't a site
        assertEquals("https://marble.syb.lol/" + naddr(obj.firstTag("d")!!, obj.pubkey, 33331), marbleLink(obj))
    }

    @Test fun aHandlersWebLinkForTheRightKindOfLink() {
        val h = obj.copy(kind = 31990, tags = listOf(listOf("d", "x"), listOf("k", "33331"),
            listOf("web", "https://app.example/n/<bech32>", "nevent"), listOf("web", "https://app.example/a/<bech32>", "naddr"), listOf("web", "http://plain.example/<bech32>")))
        assertTrue(OtherApps.handles(h, 33331))
        assertFalse(OtherApps.handles(h, 1))
        assertEquals("https://app.example/a/" + naddr(obj.firstTag("d")!!, obj.pubkey, 33331), OtherApps.webLink(h, obj))
        val clientTag = obj.copy(tags = obj.tags.filter { it[0] != "client" } + listOf(listOf("client", "Some app", "31990:${obj.pubkey}:x", "wss://relay.example")))
        assertEquals(Ref.Addr(31990, obj.pubkey, "x"), OtherApps.clientHandler(clientTag))
    }
}
