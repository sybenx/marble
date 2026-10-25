package cc.aaro.cosign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The line between the two processes (Proc.kt), checked in the app's source: the manifest
 * puts the app's screens and services for the key in ":signer", the code that runs in the
 * main process never names key material, and the app's signer-side code never names the
 * feed, pictures or video. The Cosign library checks its own side (and its manifest) the same way.
 */
class ProcessBoundaryTest {
    private val src = File("src/main/java/cc/aaro/cosign")
    private fun read(path: String) = File(src, path).readText()
        // Comments may name anything.
        .replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    /** What holds or uses a share. */
    private val keyMaterial = Regex("""\b(Signer|Signers|Secrets|FrostNative|Frost\.(sign|commit|ecdh\w*|aggregate)|Lists|Relays|Dkg|Refresh|Backup|signers)\b""")
    /** What shows strangers' posts, pictures and video. */
    private val untrusted = Regex("""\b(Social|People|Sources|Fetch|Images|Translate|Places|TubeFeed|ExoPlayer|WebView|media3|Coil|instance\.accounts)\b""")

    /** Runs in both: the Application that sets each one up. */
    private val either = listOf("CosignApp.kt")
    /** Runs only in the signer process. */
    private val signerOnly = listOf("SignerActivity.kt", "CosignService.kt", "Nip55Activity.kt", "Push.kt", "DecisionReceiver.kt", "BootReceiver.kt")

    /** Everything else runs in the main process (a new screen too, without being listed here): the UI, the feed, what both draw. */
    private val mainSide get() = src.walk().filter { it.isFile && it.name.endsWith(".kt") }.map { it.relativeTo(src).path }
        .filter { it !in signerOnly && it !in either }.toList()

    @Test fun mainProcessCodeNeverNamesKeyMaterial() {
        assertTrue("ui/Discover.kt" in mainSide && "ui/Screens.kt" in mainSide)
        for (f in mainSide) {
            val hits = keyMaterial.findAll(read(f)).map { it.value }.toSet()
            assertTrue("$f names ${hits.joinToString()}", hits.isEmpty())
        }
    }

    @Test fun signerCodeNeverNamesTheFeed() {
        for (f in signerOnly) {
            val hits = untrusted.findAll(read(f)).map { it.value }.toSet()
            assertTrue("$f names ${hits.joinToString()}", hits.isEmpty())
        }
    }

    @Test fun manifestPutsTheKeyInTheSignerProcess() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val components = Regex("""<(activity|service|provider|receiver)\b([^>]*)>""").findAll(manifest)
            .associate { m -> Regex("""android:name="([^"]+)"""").find(m.groupValues[2])!!.groupValues[1] to Regex("""android:process="([^"]+)"""").find(m.groupValues[2])?.groupValues?.get(1) }
        for (c in listOf(".SignerActivity", ".Nip55Activity", ".CosignService", ".DecisionReceiver", ".Push", ".BootReceiver"))
            assertEquals(c, ":signer", components[c])
        assertEquals("the UI", null, components[".MainActivity"])
    }
}
