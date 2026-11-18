// I-JSON and canonical JSON (RFC 8785) as vaults uses them (spec §2): integers within ±2⁵³ only, no floats,
// lowercase hex. Anything malformed is ignored whole, so parsing throws [Malformed] and the caller drops the thing.
package cc.aaro.vaults

import cc.aaro.keykin.core.jsonString
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class Malformed(why: String) : Exception(why)

private const val MAX_INT = 9007199254740992L // 2^53

/** Canonical JSON: keys sorted by UTF-16 code units, no spaces, strings as JSON.stringify writes them. */
fun canon(v: Any?): String = StringBuilder().also { canonInto(it, v) }.toString()

private fun canonInto(o: StringBuilder, v: Any?) {
    when (v) {
        null, JSONObject.NULL -> o.append("null")
        is Boolean -> o.append(v)
        is Int -> o.append(v)
        is Long -> { if (v > MAX_INT || v < -MAX_INT) throw Malformed("integer out of range"); o.append(v) }
        is String -> o.append(jsonString(v))
        is JSONArray -> { o.append('['); for (i in 0 until v.length()) { if (i > 0) o.append(','); canonInto(o, v.get(i)) }; o.append(']') }
        is JSONObject -> {
            o.append('{'); var first = true
            for (k in v.keys().asSequence().toList().sorted()) { if (!first) o.append(','); first = false; o.append(jsonString(k)).append(':'); canonInto(o, v.get(k)) }
            o.append('}')
        }
        is Map<*, *> -> {
            o.append('{'); var first = true
            for (k in v.keys.map { it as String }.sorted()) { if (!first) o.append(','); first = false; o.append(jsonString(k)).append(':'); canonInto(o, v[k]) }
            o.append('}')
        }
        is List<*> -> { o.append('['); v.forEachIndexed { i, x -> if (i > 0) o.append(','); canonInto(o, x) }; o.append(']') }
        else -> throw Malformed("not I-JSON: ${v::class.java.simpleName}")
    }
}

/** Throws [Malformed] if [v] holds a float or an integer out of range, anywhere. */
fun checkInts(v: Any?) {
    when (v) {
        is JSONObject -> v.keys().forEach { checkInts(v.get(it)) }
        is JSONArray -> for (i in 0 until v.length()) checkInts(v.get(i))
        is Int -> {}
        is Long -> if (v > MAX_INT || v < -MAX_INT) throw Malformed("integer out of range")
        is Number -> throw Malformed("not an integer")
        else -> {}
    }
}

/** A JSON object from [s], checked as I-JSON (duplicate keys and floats refused). */
fun obj(s: String): JSONObject {
    val o = try { JSONTokener(s).nextValue() } catch (e: Exception) { throw Malformed("json: ${e.message}") }
    if (o !is JSONObject) throw Malformed("not an object")
    checkInts(o)
    return o
}
fun arr(s: String): JSONArray {
    val o = try { JSONTokener(s).nextValue() } catch (e: Exception) { throw Malformed("json: ${e.message}") }
    if (o !is JSONArray) throw Malformed("not an array")
    checkInts(o)
    return o
}

private val HEX_RE = Regex("^[0-9a-f]*$")
fun isHex(s: String?, bytes: Int) = s != null && s.length == bytes * 2 && HEX_RE.matches(s)

// Typed reads that throw Malformed.
fun JSONObject.str(k: String): String = opt(k) as? String ?: throw Malformed("$k: not a string")
fun JSONObject.strOrNull(k: String): String? = when (val v = opt(k)) { null, JSONObject.NULL -> null; is String -> v; else -> throw Malformed("$k: not a string") }
fun JSONObject.hexOf(k: String, bytes: Int): String = str(k).also { if (!isHex(it, bytes)) throw Malformed("$k: not $bytes-byte hex") }
fun JSONObject.long(k: String): Long = when (val v = opt(k)) { is Int -> v.toLong(); is Long -> v; else -> throw Malformed("$k: not an integer") }
fun JSONObject.longOr(k: String, d: Long): Long = if (isNull(k)) d else long(k)
fun JSONObject.bool(k: String): Boolean = when (val v = opt(k)) { null, JSONObject.NULL -> false; is Boolean -> v; else -> throw Malformed("$k: not a boolean") }
fun JSONObject.objOr(k: String): JSONObject = when (val v = opt(k)) { null, JSONObject.NULL -> JSONObject(); is JSONObject -> v; else -> throw Malformed("$k: not an object") }
fun JSONObject.objOrNull(k: String): JSONObject? = when (val v = opt(k)) { null, JSONObject.NULL -> null; is JSONObject -> v; else -> throw Malformed("$k: not an object") }
fun JSONObject.arrOr(k: String): JSONArray = when (val v = opt(k)) { null, JSONObject.NULL -> JSONArray(); is JSONArray -> v; else -> throw Malformed("$k: not an array") }
fun JSONObject.strs(k: String): List<String> = arrOr(k).let { a -> List(a.length()) { a.opt(it) as? String ?: throw Malformed("$k: not strings") } }
fun JSONArray.objs(): List<JSONObject> = List(length()) { opt(it) as? JSONObject ?: throw Malformed("not an object") }
fun JSONArray.strings(): List<String> = List(length()) { opt(it) as? String ?: throw Malformed("not a string") }
/** A deep copy (org.json shares nested objects). */
fun JSONObject.copy(): JSONObject = obj(canon(this))
fun jarr(items: Iterable<Any?>) = JSONArray().also { a -> items.forEach { a.put(it ?: JSONObject.NULL) } }
fun jobj(vararg kv: Pair<String, Any?>) = JSONObject().also { o -> kv.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }
