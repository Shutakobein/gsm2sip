package com.callagent.gateway.sms

import org.json.JSONObject

/**
 * Row recovery for the JSON blobs the stores keep in SharedPreferences.
 *
 * Every store here reads its whole blob with `JSONArray(raw)`, and that call
 * throws on a single damaged byte.  SharedPreferences writes the file with a
 * temp-and-rename, so a torn file is rare, but a blob can also be damaged by a
 * hand-edit, or by an entry written by a build that has since been changed.  When
 * it throws, the caller used to lose every message in the blob — one broken row
 * stranded a week of unread SMS behind it, on a gateway whose only copy of those
 * messages is that blob.
 *
 * [objects] is the fallback: it walks the text and returns the balanced top-level
 * `{...}` objects, keeping the rows that are intact and losing only the broken
 * one.  It knows about strings and escapes, because `{` inside a message body is
 * ordinary text.
 */
internal object JsonArrayRows {

    /** Top-level JSON objects inside an array string, in order. */
    fun objects(raw: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in raw.indices) {
            val c = raw[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' -> if (depth++ == 0) start = i
                c == '}' -> if (depth > 0 && --depth == 0 && start >= 0) {
                    out += raw.substring(start, i + 1)
                    start = -1
                }
            }
        }
        return out
    }

    /** Parse each row on its own, dropping the ones that do not parse. */
    fun parse(raw: String): List<JSONObject> =
        objects(raw).mapNotNull { row -> try { JSONObject(row) } catch (_: Exception) { null } }
}
