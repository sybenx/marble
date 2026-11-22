package cc.aaro.cosign

import cc.aaro.keykin.core.Bech32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** Private zaps as Amethyst makes them (PrivateZap): the anon tag read as NIP-04, and only for the one zapped. */
class PrivateZapTest {
    private val me = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val from = "2286e398e68d825a838512387d2aae96f064e3808df349d3ea3091770d26aefe"
    /** The anon tag of a private zap Amethyst sent to a Marble phone on 2026-11-21 (a public zap request). */
    private val real = "pzap1tv728uhv9ghjz9zcqlg4d4saxglhc2d33xu3y53dejc5dpkjuetwryje6ze73dt24j7l6w93nfgza2jjgk2cqselkkczrqjectm52ke7373rnvuaw5y6df9h9u55nhfck7fpc3vekdgq4e25jmg75mvv2vq527ykfu4v03umcg05zj7xz0pv6kaw76pl4anjgm07h45pp505jv2jm2ec5nzuwlg9qk3363ftk6345amj2amwvyf2zhvs3w52wcy6xrkhvru69dwa9t30d62wc6qevx72mh9eahm3jdyzh55eakc3mzc0jdaeeqf6jq7xj3yy67fhr3nr7mra6fr0tsja66mk6spyfv4g6tm9vaxlkq326ugnfh7rprgjxyj4f6pqwv00qq8h66seur7lcz9caujcdlsmfeu8ka0w8lyhektsq2e8txmjd9k5t7wygdcfhhw5v6fd5heql0hzlrjn0tjac3cx2sm69z794jlrxj4g60v8mtfzw8nd4esq0cqt6pzpqg2c2t8hm8h3nuz5a2agtu42se3su5g2062p0nrsqxy3ks9v52j9ta0jge9rg2zh60ydyqd6t6ah8fvssezsuwpc4vex965fxtjukxw6tn7tlwj5m6h4hmnjprk0kde7rlgcj2vepr278fkhxhgex8k4yhlt2gvuj7xnqjfpugmuxl48qc2re6fpd3kw8xchuzh5hew72e3epsl9fc2l664zl2t76g0utpqcut20tqfvddffwlju67trtkdn2ljvv4xjxvecf6vl04yze2n3t5jak4anpey5ku47kgeexchqkzxmdj4pz00vr8u9689cfrf8q82nz9rz3egascc4a9rpsvvw99cjn5w4v5yp0mxx4q063umtefdu9z89kjqtsuseedyurlzcsgttktp5tzujf9y6ajaqyzr34c038teqwg5jcexcs7hvmhlgjq5wkxr807ejrvs3l442r4m7j4a7s7rshl0cy3g4ze4q8xz0d0s3gu073qqe5ef6rtz3x7w2fwf0l3579z6vnkctruf02pcun7y9ey0ks8ek538w2fqwc8lspucjwua0q7e46a57ktasydr74cehqmcwdhqmyswh975asj6l8repmc9gfr9dy7g7nph3k6uud83q9t40cyy90uclmumcj4795a037cz7enmmyl2pwjr6errl6090egthsnngqkylggm8srtg9ly7fp9tssyy7j78af70yk5u6tqgq8vqa_iv10ze5cfvr6gmesf4drmak2y3j5czxvc60"

    private fun zap(anon: String?, p: String = me, kind: Int = 9734) = mapOf(
        "kind" to kind, "pubkey" to from,
        "tags" to listOfNotNull(listOf("p", p), listOf("amount", "64000"), anon?.let { listOf("anon", it) }),
    )

    @Test fun readsAmethystsFormat() {
        val n = PrivateZap.toNip04(real)
        assertNotNull(n)
        val (ct, iv) = n!!.split("?iv=")
        assertEquals(16, Base64.getDecoder().decode(iv).size)
        assertEquals(0, Base64.getDecoder().decode(ct).size % 16)
    }

    @Test fun sameBytesBack() {
        val ct = ByteArray(48) { it.toByte() }; val iv = ByteArray(16) { (200 + it).toByte() }
        val n = PrivateZap.toNip04(Bech32.encode("pzap", ct) + "_" + Bech32.encode("iv", iv))!!
        val (c, i) = n.split("?iv=")
        assertArrayEquals(ct, Base64.getDecoder().decode(c))
        assertArrayEquals(iv, Base64.getDecoder().decode(i))
    }

    @Test fun onlyForTheOneZapped() {
        assertEquals(from, PrivateZap.forMe(zap(real), me)!!.second)
        assertNull(PrivateZap.forMe(zap(real, p = from), me))
        assertNull(PrivateZap.forMe(zap(real, kind = 1), me))
        assertNull(PrivateZap.forMe(zap(null), me))
        assertNull(PrivateZap.forMe(zap(""), me)) // anonymous, not private: nothing inside
    }

    @Test fun damagedIsNull() {
        assertNull(PrivateZap.toNip04(real.dropLast(1) + if (real.last() == 'q') "p" else "q")) // checksum
        assertNull(PrivateZap.toNip04(real.substringBefore('_')))
        assertNull(PrivateZap.toNip04("npub1xyz_iv1abc"))
    }
}
