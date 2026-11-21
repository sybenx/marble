package cc.aaro.cosign.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavingTest {
    @Test fun byName() {
        val n = Saving.name("https://image.nostr.build/abc.jpg?w=10", null)!!
        assertEquals("abc.jpg", n.name); assertEquals("image/jpeg", n.type); assertEquals(Saving.Kind.Picture, n.kind)
        assertEquals(Saving.Kind.Video, Saving.name("https://v.example/clip.MOV", null)!!.kind)
    }

    @Test fun serverWins() {
        // A Blossom address: a hash, no extension, served as a video.
        val n = Saving.name("https://blossom.example/" + "f".repeat(64), "video/mp4; charset=binary")!!
        assertEquals("f".repeat(64).take(80) + ".mp4", n.name); assertEquals(Saving.Kind.Video, n.kind)
        // Named .jpg but really a WebP.
        assertEquals("x.webp", Saving.name("https://a.b/x.jpg", "image/webp")!!.name)
    }

    @Test fun hintOrNothing() {
        assertNull(Saving.name("https://a.b/page", "text/html"))
        assertEquals("image/jpeg", Saving.name("https://a.b/page", "text/html", Saving.Kind.Picture)!!.type)
        assertEquals("video/mp4", Saving.name("https://a.b/v", null, Saving.Kind.Video)!!.type)
    }

    @Test fun safeNames() {
        assertEquals("a_b_c.png", Saving.name("https://a.b/a%20b%3Fc.png", null)!!.name)
        assertEquals("marble.jpg", Saving.name("https://a.b/", "image/jpeg")!!.name)
    }
}
