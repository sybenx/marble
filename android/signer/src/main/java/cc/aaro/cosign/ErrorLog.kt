package cc.aaro.cosign

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Every error Marble hits, kept on this phone so it can be worked out afterwards (Aaron, 2026-11-21: "every time an
 * error pops up on marble we should record it so you can troubleshoot"). People aren't shown codes or exception
 * text; the detail goes here instead.
 *
 * One JSON object a line in `Android/data/<package>/files/errors.log`, which adb can pull from a release build
 * (`adb pull /sdcard/Android/data/cc.aaro.cosign/files/errors.log`) and other apps can't read. Each line also goes to
 * logcat under [TAG]. Never message text, payloads or keys: where it happened, which app asked, what kind of
 * request, the error's code and class and message, and the top of its stack. Kept under about 256 KB: past that the
 * older half is dropped. Both processes write; each line is one append.
 */
object ErrorLog {
    const val TAG = "MarbleError"
    private const val MAX = 256 * 1024
    @Volatile private var dir: File? = null
    private val lock = Any()

    fun init(c: Context) { if (dir == null) dir = try { c.applicationContext.getExternalFilesDir(null) } catch (_: Exception) { null } }

    /** Where the log is, or null before [init] or with no external storage. */
    fun file(): File? = dir?.let { File(it, "errors.log") }

    /**
     * [where]: the part of Marble ("nip55", "provider", "signer", "crash"). [e]: the error, if there is one.
     * [info]: short facts about the request (app package, request type, a code); never what was in it.
     */
    fun record(where: String, e: Throwable? = null, vararg info: Pair<String, Any?>) {
        val o = JSONObject().put("t", java.time.Instant.now().toString()).put("where", where).put("proc", Proc.name)
        for ((k, v) in info) if (v != null) o.put(k, v)
        if (e != null) {
            (e as? cc.aaro.keykin.core.KeykinException)?.let { o.put("code", it.code) }
            o.put("error", e.javaClass.name).put("message", e.message ?: "")
            o.put("stack", e.stackTrace.take(8).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
            e.cause?.let { c -> o.put("cause", "${c.javaClass.name}: ${c.message ?: ""}") }
        }
        val line = o.toString()
        Log.w(TAG, line)
        val f = file() ?: return
        try {
            synchronized(lock) {
                if (f.length() > MAX) f.readLines().let { all -> f.writeText(all.drop(all.size / 2).joinToString("\n", postfix = "\n")) }
                FileOutputStream(f, true).use { it.write((line + "\n").toByteArray()) }
            }
        } catch (_: Exception) { }
    }

    /** Crashes, too: recorded, then handed on as before (Android still shows its dialog and ends the process). */
    fun catchCrashes() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { record("crash", e, "thread" to t.name) } catch (_: Throwable) { }
            before?.uncaughtException(t, e)
        }
    }
}
