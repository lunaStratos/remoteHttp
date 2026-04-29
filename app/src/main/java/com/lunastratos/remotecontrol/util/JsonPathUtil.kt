package com.lunastratos.remotecontrol.util

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object JsonPathUtil {

    /**
     * Walk a dot-delimited path through [raw] (which must parse as a JSON object or array)
     * and return the leaf value as its JSON string form.
     *
     * Numeric segments index into arrays: `data.items.0.value`.
     * A leading dot is tolerated. Returns `null` on parse error or any missing segment.
     */
    fun extract(raw: String, path: String): String? {
        if (path.isBlank()) return null
        return try {
            var cur: Any? = JSONTokener(raw).nextValue()
            for (token in path.trim('.').split('.')) {
                if (token.isEmpty()) continue
                cur = when (val node = cur) {
                    is JSONObject -> if (node.has(token)) node.get(token) else return null
                    is JSONArray -> {
                        val i = token.toIntOrNull() ?: return null
                        if (i in 0 until node.length()) node.get(i) else return null
                    }
                    else -> return null
                }
            }
            cur?.toString()
        } catch (t: Throwable) {
            null
        }
    }
}
