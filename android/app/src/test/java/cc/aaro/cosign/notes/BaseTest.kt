package cc.aaro.cosign.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Blossom file format (§7). */
class BaseTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { (it + 100).toByte() }

    @Test fun paddedToStepsThenPadme() {
        assertEquals(32_768, Sealed.padded(1)); assertEquals(32_768, Sealed.padded(32_768)); assertEquals(65_536, Sealed.padded(32_769))
        assertEquals(1 shl 20, Sealed.padded(1 shl 20))
        val p = Sealed.padded((1 shl 20) + 1)
        assert(p >= (1 shl 20) + 1 && p <= ((1 shl 20) * 1.12).toInt())
    }

    @Test fun sealsAndOpens() {
        val body = "hello notes".toByteArray()
        val f = Sealed.seal(body, "base", 1234, key, nonce)
        assertEquals(12 + 32_768 + 16, f.size)
        assertArrayEquals(body, Sealed.open(f, "base", 1234, key))
        // The wrong id or time (AAD), or one changed byte: refused.
        assertNull(Sealed.open(f, "base", 1235, key)); assertNull(Sealed.open(f, "other", 1234, key))
        assertNull(Sealed.open(f.copyOf().also { it[40] = (it[40] + 1).toByte() }, "base", 1234, key))
    }

    @Test fun pointerRoundTrips() {
        val p = BasePointer("ab".repeat(32), 32_796, "cd".repeat(32), listOf("https://blossom.syb.lol"), 1234, "k1")
        assertEquals(p, BasePointer.parse(p.toJson()))
    }
}
