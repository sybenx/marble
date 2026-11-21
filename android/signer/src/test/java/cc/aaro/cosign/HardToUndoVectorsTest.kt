package cc.aaro.cosign

import cc.aaro.cosign.core.BoxAsk
import cc.aaro.cosign.core.Policy
import cc.aaro.keykin.core.KeykinException
import cc.aaro.qrst.core.Event
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The hard-to-undo cases shared with Keykin (its vectors/hard-to-undo.json, made by its tools/hard-to-undo-vectors.mjs),
 * against this phone's own rules (Policy.refuse, then Policy.hold), and what a person sees when Marble signs each one
 * with a box that answers as the file says the box does ([BoxAsk]). The box decides; this phone needs no copy of its rules.
 * Each must answer what the file says; a change on either side shows up here or in Keykin's test.
 * The file is a copy (hard-to-undo.SOURCE says from which Keykin commit), so the build never reaches outside this repo.
 */
class HardToUndoVectorsTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val v = JSONObject(text("hard-to-undo.json"))
    private fun objs(a: JSONArray) = List(a.length()) { a.getJSONObject(it) }
    private fun event(o: JSONObject): Event {
        val tags = o.getJSONArray("tags").let { a -> List(a.length()) { i -> a.getJSONArray(i).let { t -> List(t.length()) { t.getString(it) } } } }
        return Event(o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"), tags, o.getString("content"))
    }
    private fun answer(decision: String, says: String?) = "$decision: $says"
    private fun want(c: JSONObject, impl: String) = c.getJSONObject("answers").getJSONObject(impl).let { answer(it.getString("decision"), if (it.isNull("says")) null else it.getString("says")) }

    @Test fun theSameAnswersAsTheFileSays() {
        val now = v.getLong("now")
        val wrong = mutableListOf<String>()
        for (c in objs(v.getJSONArray("cases"))) {
            val e = event(c.getJSONObject("event"))
            val prev = if (c.isNull("prev")) null else event(c.getJSONObject("prev"))
            val mine = Policy.refuse(e, now)?.let { answer("refuse", it) } ?: Policy.hold(e, prev, now)?.let { answer("ask", it) } ?: answer("sign", null)
            if (mine != want(c, "marble")) wrong += "${c.getString("why")}: Policy says \"$mine\", the file \"${want(c, "marble")}\""
        }
        if (wrong.isNotEmpty()) fail("${wrong.size} answers differ from Keykin's vectors/hard-to-undo.json:\n" + wrong.joinToString("\n"))
    }

    /** A box that answers as the file says: `needs_tap` with its words unless the request says tapped, `dated_ahead` always. */
    private fun box(c: JSONObject): (Boolean) -> String = { tapped ->
        val b = c.getJSONObject("answers").getJSONObject("box")
        val says = if (b.isNull("says")) null else b.getString("says")
        when (b.getString("decision")) {
            "refuse" -> throw KeykinException("dated_ahead", says, says)
            "ask" -> if (tapped) "signed" else throw KeykinException("needs_tap", says, says)
            else -> "signed"
        }
    }

    /**
     * What a person sees on Marble (the file's `outcome.marble`): refused, signed with no question, or asked exactly once,
     * in this phone's words when its own rules ask, else in the box's; and on No, nothing signed.
     */
    @Test fun marbleAsksOnceWithTheBoxsWords() {
        val now = v.getLong("now")
        val wrong = mutableListOf<String>()
        for (c in objs(v.getJSONArray("cases"))) {
            val e = event(c.getJSONObject("event"))
            val prev = if (c.isNull("prev")) null else event(c.getJSONObject("prev"))
            val why = c.getString("why")
            val asked = mutableListOf<String>()
            val seen = try {
                if (Policy.refuse(e, now) != null) "refuses"
                else BoxAsk.sign(Policy.hold(e, prev, now), false, "unsaid", { asked += it; true }, box(c)).let { if (it.yes) "asks" else "signs" }
            } catch (x: KeykinException) { if (x.code == "dated_ahead") "refuses" else "failed: ${x.code}" }
            val outcome = c.getJSONObject("outcome").getString("marble")
            if (seen != outcome) wrong += "$why: Marble $seen, the file says $outcome"
            if (asked.size > (if (seen == "asks") 1 else 0)) wrong += "$why: asked ${asked.size} times: $asked"
            val marble = c.getJSONObject("answers").getJSONObject("marble"); val b = c.getJSONObject("answers").getJSONObject("box")
            val words = if (marble.getString("decision") == "ask") marble.getString("says") else if (b.getString("decision") == "ask") b.getString("says") else null
            if (seen == "asks" && asked.singleOrNull() != words) wrong += "$why: asked $asked, expected \"$words\""
            // No leaves nothing signed.
            if (seen == "asks") {
                var signed = false
                val no = try { BoxAsk.sign(Policy.hold(e, prev, now), false, "unsaid", { false }) { t -> box(c)(t).also { signed = true } }; "signed" } catch (x: KeykinException) { x.code }
                if (no != "denied" || signed) wrong += "$why: after No, $no (signed: $signed)"
            }
        }
        if (wrong.isNotEmpty()) fail("${wrong.size} cases differ from Keykin's vectors/hard-to-undo.json:\n" + wrong.joinToString("\n"))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val source = text("hard-to-undo.SOURCE").lines()

    @Test fun theCopyIsAsItWasMade() {
        assertEquals("edited here since it was copied from ${source[0]}", source[1].substringBefore("  "), sha(text("hard-to-undo.json").toByteArray()))
    }

    /** Against a Keykin checkout ($KEYKIN_DIR, else ~/keykin); skipped when there is none, or it has no such file yet. */
    @Test fun theCopyIsNotBehindKeykin() {
        val dir = System.getenv("KEYKIN_DIR")?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home") ?: "", "keykin").path
        val theirs = File(dir, "vectors/hard-to-undo.json")
        assumeTrue("no Keykin checkout with vectors/hard-to-undo.json at $dir", theirs.isFile)
        assertEquals("the copy (from ${source[0]}) differs from $theirs: in Keykin, node tools/hard-to-undo-vectors.mjs --copy <this resources folder>",
            sha(theirs.readBytes()), sha(text("hard-to-undo.json").toByteArray()))
    }
}
