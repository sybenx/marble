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
 * against this phone's own rules (Policy.refuse, then Policy.question), and what a person sees when Marble signs each
 * one with a box that answers as the file says the box does ([BoxAsk]), and with no box at all. The box decides, and
 * its answer carries the level; this phone needs no copy of its rules. Level 1 is Yes or No; level 2 is Yes and then
 * the fingerprint, and the box never takes tapped alone for it.
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
    private fun answer(decision: String, says: String?, level: Int?) = "$decision (level $level): $says"
    private fun want(c: JSONObject, impl: String) = c.getJSONObject("answers").getJSONObject(impl).let {
        answer(it.getString("decision"), if (it.isNull("says")) null else it.getString("says"), if (it.isNull("level")) null else it.getInt("level"))
    }

    @Test fun theSameAnswersAsTheFileSays() {
        val now = v.getLong("now")
        val wrong = mutableListOf<String>()
        for (c in objs(v.getJSONArray("cases"))) {
            val e = event(c.getJSONObject("event"))
            val prev = if (c.isNull("prev")) null else event(c.getJSONObject("prev"))
            val mine = Policy.refuse(e, now)?.let { answer("refuse", it, null) } ?: Policy.question(e, prev, now)?.let { answer("ask", it.says, it.level) } ?: answer("sign", null, null)
            if (mine != want(c, "marble")) wrong += "${c.getString("why")}: Policy says \"$mine\", the file \"${want(c, "marble")}\""
        }
        if (wrong.isNotEmpty()) fail("${wrong.size} answers differ from Keykin's vectors/hard-to-undo.json:\n" + wrong.joinToString("\n"))
    }

    /**
     * A box that answers as the file says: at level 1 `needs_tap` with its words unless the request says tapped, at
     * level 2 `needs_fingerprint` with its words unless the fingerprint went with it (whatever tapped says),
     * `dated_ahead` always. [fingerprints] records the words of each fingerprint prompt.
     */
    private fun box(c: JSONObject, fingerprints: MutableList<String> = mutableListOf()): (Boolean, String?) -> String = { tapped, finger ->
        if (finger != null) fingerprints += finger
        val b = c.getJSONObject("answers").getJSONObject("box")
        val says = if (b.isNull("says")) null else b.getString("says")
        when (b.getString("decision")) {
            "refuse" -> throw KeykinException("dated_ahead", says, says)
            "ask" -> when {
                b.getInt("level") == 2 -> if (finger != null) "signed" else throw KeykinException("needs_fingerprint", says, says)
                tapped -> "signed"
                else -> throw KeykinException("needs_tap", says, says)
            }
            else -> "signed"
        }
    }
    private fun says(a: JSONObject) = if (a.getString("decision") == "ask") a.getString("says") else null

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
            val asked = mutableListOf<BoxAsk.Question>(); val fingerprints = mutableListOf<String>()
            val seen = try {
                if (Policy.refuse(e, now) != null) "refuses"
                else BoxAsk.sign(Policy.question(e, prev, now), false, "unsaid", { asked += it; true }, box(c, fingerprints))
                    .let { if (fingerprints.isNotEmpty()) "fingerprint" else if (it.yes) "asks" else "signs" }
            } catch (x: KeykinException) { if (x.code == "dated_ahead") "refuses" else "failed: ${x.code}" }
            val outcome = c.getJSONObject("outcome").getString("marble")
            if (seen != outcome) wrong += "$why: Marble $seen, the file says $outcome"
            val asks = seen == "asks" || seen == "fingerprint"
            if (asked.size > (if (asks) 1 else 0)) wrong += "$why: asked ${asked.size} times: $asked"
            if (fingerprints.size > 1) wrong += "$why: ${fingerprints.size} fingerprints"
            val marble = c.getJSONObject("answers").getJSONObject("marble"); val b = c.getJSONObject("answers").getJSONObject("box")
            val words = says(marble) ?: says(b)
            if (asks && asked.singleOrNull()?.says != words) wrong += "$why: asked $asked, expected \"$words\""
            // The level the person meets is the higher of the two, and the question says it.
            if (asks && asked.single().level != (if (seen == "fingerprint") 2 else 1) && says(marble) == null) wrong += "$why: asked at level ${asked.single().level}, met $seen"
            // The fingerprint prompt says the words of whoever wanted it: this phone's rules, else the box.
            if (seen == "fingerprint" && fingerprints.single() != (says(marble).takeIf { marble.optInt("level") == 2 } ?: says(b))) wrong += "$why: fingerprint said $fingerprints"
            // No leaves nothing signed, and takes no fingerprint.
            if (asks) {
                var signed = false; val none = mutableListOf<String>()
                val no = try { BoxAsk.sign(Policy.question(e, prev, now), false, "unsaid", { false }) { t, f -> box(c, none)(t, f).also { signed = true } }; "signed" } catch (x: KeykinException) { x.code }
                if (no != "denied" || signed || none.isNotEmpty()) wrong += "$why: after No, $no (signed: $signed, fingerprints: $none)"
            }
            // An old phone that knows only tapped never gets a level 2 event signed.
            if (b.optString("decision") == "ask" && b.getInt("level") == 2) {
                val old = try { box(c)(true, null) } catch (x: KeykinException) { x.code }
                if (old != "needs_fingerprint") wrong += "$why: tapped alone gave $old"
            }
        }
        if (wrong.isNotEmpty()) fail("${wrong.size} cases differ from Keykin's vectors/hard-to-undo.json:\n" + wrong.joinToString("\n"))
    }

    /**
     * With no box in the signature (the whole key on this phone, or another device helping while the box is off): this
     * phone's own rules are all there is (the file's `outcome.marbleAlone`). Level 2 is Yes, then the phone's own
     * fingerprint prompt, with its words.
     */
    @Test fun withNoBoxThisPhoneAsksByItsOwnRules() {
        val now = v.getLong("now")
        val wrong = mutableListOf<String>()
        for (c in objs(v.getJSONArray("cases"))) {
            val e = event(c.getJSONObject("event"))
            val prev = if (c.isNull("prev")) null else event(c.getJSONObject("prev"))
            val why = c.getString("why")
            val asked = mutableListOf<BoxAsk.Question>(); val fingerprints = mutableListOf<String>()
            val seen = if (Policy.refuse(e, now) != null) "refuses"
                else BoxAsk.sign(Policy.question(e, prev, now), false, "unsaid", { asked += it; true }) { _, f -> f?.let { fingerprints += it }; "signed" }
                    .let { if (fingerprints.isNotEmpty()) "fingerprint" else if (it.yes) "asks" else "signs" }
            val outcome = c.getJSONObject("outcome").getString("marbleAlone")
            if (seen != outcome) wrong += "$why: Marble with no box $seen, the file says $outcome"
            if (seen == "fingerprint" && fingerprints != asked.map { it.says }) wrong += "$why: asked $asked, fingerprint said $fingerprints"
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
