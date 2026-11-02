package cc.aaro.cosign

import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guessing a post's language on real posts (resources/languages/posts.json, also in marble-content-fixtures):
 * the raw text against the same text cleaned (PostLanguage.clean), each through PostLanguage.decide, which guesses
 * only when one language is clearly ahead. Lingua (20 languages: the set's and their look-alikes) stands in for the phone's detector here.
 */
class LanguagesTest {
    // The set's languages and their nearest look-alikes (all 75 need more memory than a test has).
    private val detector by lazy {
        LanguageDetectorBuilder.fromIsoCodes639_1(*listOf("en", "es", "pt", "ca", "it", "fr", "nl", "de", "af", "ru", "uk", "bg", "ja", "zh", "ko", "vi", "id", "tl", "ar")
            .map { com.github.pemistahl.lingua.api.IsoCode639_1.valueOf(it.uppercase()) }.toTypedArray()).build()
    }
    private fun candidates(text: String) = detector.computeLanguageConfidenceValues(text).entries.take(4)
        .map { it.key.isoCode639_1.toString().lowercase() to it.value.toFloat() }

    data class Score(var right: Int = 0, var wrong: Int = 0, var unsure: Int = 0, var skipped: Int = 0) {
        override fun toString() = "right $right, wrong $wrong, unsure $unsure, too short $skipped"
    }

    @Test fun cleaningHelps() {
        val posts = JSONObject(javaClass.getResource("/languages/posts.json")!!.readText()).getJSONArray("posts")
        val raw = Score(); val cleaned = Score()
        val wrongs = ArrayList<String>()
        for (i in 0 until posts.length()) {
            val p = posts.getJSONObject(i); val text = p.getString("content"); val lang = p.getString("lang")
            for ((score, input, name) in listOf(Triple(raw, text, "raw"), Triple(cleaned, PostLanguage.clean(text), "cleaned"))) {
                if (name == "cleaned" && !PostLanguage.guessable(input)) { score.skipped++; continue }
                // Lingua's values are relative (the top is 1.0): sure when the runner-up is under 0.95 of it.
                val g = PostLanguage.decide(candidates(input), atLeast = 0.99f, ahead = 0.05f)
                when (g.lang) { null -> score.unsure++; lang -> score.right++; else -> { score.wrong++; wrongs += "$name ${p.getString("id").take(8)} $lang→${g.lang}" } }
            }
        }
        println("languages: ${posts.length()} posts\n  raw:     $raw\n  cleaned: $cleaned\n  " + wrongs.joinToString("\n  "))
        assertTrue("cleaning gets no fewer right", cleaned.right >= raw.right)
        assertTrue("cleaning guesses wrong no more often", cleaned.wrong <= raw.wrong)
        assertTrue("cleaned, almost never wrong", cleaned.wrong <= 1)
    }
}
