// The door to the Rust FROST library (../../frost, built with `--features jni`). The package and class
// name are fixed by the library's symbol, Java_keykin_FrostNative_call.
package keykin

import org.json.JSONArray

object FrostNative {
    init {
        // On a computer (unit tests) the library's path is given; on Android it is in the APK.
        val path = System.getProperty("keykin.frostlib")
        if (path != null) System.load(path) else System.loadLibrary("keykin_frost")
    }
    @JvmStatic external fun call(name: String, args: String): String
    fun run(name: String, vararg a: Any): String = call(name, JSONArray(a.map { it.toString() }).toString())
}
