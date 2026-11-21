package cc.aaro.cosign.web

import cc.aaro.cosign.ui.Saving
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAtTest {
    @Test fun parse() {
        assertEquals("https://v.example/a.mp4" to Saving.Kind.Video, MediaAt.parse("""{"k":"video","u":"https://v.example/a.mp4"}"""))
        assertEquals("https://i.example/a.png" to Saving.Kind.Picture, MediaAt.parse("""{"k":"picture","u":"https://i.example/a.png"}"""))
        assertEquals("" to Saving.Kind.Video, MediaAt.parse("""{"k":"video","u":""}"""))
        assertNull(MediaAt.parse("null")); assertNull(MediaAt.parse(null)); assertNull(MediaAt.parse(""))
        assertNull(MediaAt.parse("""{"k":"text"}""")); assertNull(MediaAt.parse("not json"))
    }

    @Test fun script() {
        val s = MediaAt.script(300f, 450.5f)
        assertTrue(s.endsWith("(300.0, 450.5)"))
        assertTrue("querySelectorAll('img, video')" in s)
    }
}
