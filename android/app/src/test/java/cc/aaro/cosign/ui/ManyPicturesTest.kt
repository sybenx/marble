package cc.aaro.cosign.ui

import cc.aaro.cosign.core.Event
import cc.aaro.cosign.core.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** A post with ten pictures (nevent1qqsgqpdw…, "SURF 'N TURF"): all ten are its media, not the first four. */
class ManyPicturesTest {
    @Test fun everyPicture() {
        val e = Event.fromMap(Json.parse(javaClass.getResource("/fixtures/many-pictures/post.json")!!.readText()))!!
        val b = Body.of(e)
        assertEquals(10, b.images.size)
        assertEquals(Regex("https://\\S+\\.jpg").findAll(e.content).map { it.value }.toList(), b.images.map { it.url })
    }
}
