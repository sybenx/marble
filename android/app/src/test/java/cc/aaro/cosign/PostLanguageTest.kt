package cc.aaro.cosign

import cc.aaro.qrst.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostLanguageTest {
    @Test fun tagFirst() {
        assertEquals("es", PostLanguage.tagged(Event("a", 1, 1, listOf(listOf("L", "ISO-639-1"), listOf("l", "es", "ISO-639-1")), "hola")))
        assertNull(PostLanguage.tagged(Event("a", 1, 1, listOf(listOf("l", "spam", "ugc")), "hola")))
    }

    @Test fun cleansEverythingButWords() {
        val t = "Hoy probé #nostr con @fiatjaf 🚀🔥 :pepe: mira https://example.com/x?y=1 y nostr:npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"
        assertEquals("Hoy probé con mira y", PostLanguage.clean(t))
        assertFalse(PostLanguage.guessable(PostLanguage.clean("gm 🌅 #nostr https://x.com")))
        assertTrue(PostLanguage.guessable("Hoy probé el nuevo cliente"))
    }

    @Test fun guessesOnlyWhenClearlyAhead() {
        assertEquals("es", PostLanguage.decide(listOf("es" to 0.92f, "pt" to 0.05f)).lang)
        val unsure = PostLanguage.decide(listOf("es" to 0.5f, "pt" to 0.4f, "gl" to 0.1f, "und" to 0.9f))
        assertNull(unsure.lang); assertEquals(listOf("es", "pt", "gl"), unsure.likeliest)
        assertNull(PostLanguage.decide(listOf("und" to 1f)).lang)
    }

    @Test fun protectsAndRestores() {
        val t = "Mira https://example.com/a #nostr @jo :pepe: y nostr:note1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"
        val (p, kept) = PostLanguage.protect(t)
        assertEquals("Mira ⟦0⟧ ⟦1⟧ ⟦2⟧ ⟦3⟧ y ⟦4⟧", p)
        // A translator that spaced one out and dropped another.
        val back = PostLanguage.restore("Look ⟦ 0 ⟧ ⟦1⟧ ⟦2⟧ ⟦3⟧ and", kept)
        assertEquals("Look https://example.com/a #nostr @jo :pepe: and nostr:note1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m", back)
    }
}
