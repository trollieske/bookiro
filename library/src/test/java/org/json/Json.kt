package org.json

/**
 * Test-only minimal shadow of `org.json` for the JVM unit tests of
 * [com.bookrio.library.data.BookImportRepository].
 *
 * The android.jar shipped to unit tests has org.json with every method throwing
 * `... not mocked`, so the audiobook consolidation would abort mid-way. This
 * class implements only the operations the repair code under test performs
 * (put/length/toString). It is NOT a general JSON implementation and is never
 * part of the app.
 */
class JSONObject {

    private val entries = LinkedHashMap<String, Any?>()

    fun put(key: String, value: Any?): JSONObject {
        entries[key] = if (value == null) NULL else value
        return this
    }

    // The bytecode compiled against android.jar binds the primitive overloads.
    fun put(key: String, value: Int): JSONObject = put(key, value as Any?)
    fun put(key: String, value: Long): JSONObject = put(key, value as Any?)
    fun put(key: String, value: Double): JSONObject = put(key, value as Any?)
    fun put(key: String, value: Boolean): JSONObject = put(key, value as Any?)

    fun optString(key: String): String = entries[key]?.toString().orEmpty()
    fun optInt(key: String, fallback: Int = 0): Int = (entries[key] as? Number)?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = (entries[key] as? Number)?.toLong() ?: fallback
    fun has(key: String): Boolean = entries.containsKey(key)
    fun isNull(key: String): Boolean = entries[key] == null || entries[key] == NULL

    fun length(): Int = entries.size

    override fun toString(): String =
        entries.entries.joinToString(",", "{", "}") { (key, value) -> "\"${escape(key)}\":${render(value)}" }

    companion object {
        val NULL: Any = object {
            override fun toString(): String = "null"
        }
    }
}

class JSONArray {

    private val items = ArrayList<Any?>()

    fun put(value: Any?): JSONArray {
        items.add(if (value == null) JSONObject.NULL else value)
        return this
    }

    fun put(value: Int): JSONArray = put(value as Any?)
    fun put(value: Long): JSONArray = put(value as Any?)
    fun put(value: Double): JSONArray = put(value as Any?)
    fun put(value: Boolean): JSONArray = put(value as Any?)

    fun optJSONObject(index: Int): JSONObject? = items.getOrNull(index) as? JSONObject
    fun optString(index: Int): String = items.getOrNull(index)?.toString().orEmpty()

    fun length(): Int = items.size

    override fun toString(): String = items.joinToString(",", "[", "]") { render(it) }
}

private fun render(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is Number, is Boolean -> value.toString()
    is JSONObject, is JSONArray -> value.toString()
    else -> "\"${escape(value.toString())}\""
}

private fun escape(value: String): String {
    val out = StringBuilder(value.length + 8)
    for (ch in value) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> out.append(ch)
        }
    }
    return out.toString()
}