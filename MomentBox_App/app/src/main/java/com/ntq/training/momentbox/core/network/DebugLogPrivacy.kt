package com.ntq.training.momentbox.core.network

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl

object DebugLogPrivacy {
    const val LIMIT = 4096
    fun sensitive(name: String): Boolean {
        val key = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        return key in setOf("pass", "pwd", "cookie", "setcookie") ||
                listOf(
                    "password",
                    "token",
                    "secret",
                    "apikey",
                    "authorization",
                    "cookie"
                ).any(key::endsWith)
    }

    fun bounded(value: String, limit: Int = LIMIT): String {
        if (limit <= 0) return ""
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return value
        val marker = if (limit >= 14) "…[truncated]" else if (limit >= 3) "…" else ".".repeat(limit)
        var end = (limit - marker.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + marker
    }

    fun headers(headers: Headers): Map<String, String> {
        var remaining = LIMIT
        val result = linkedMapOf<String, String>()
        for (name in headers.names().sortedWith(String.CASE_INSENSITIVE_ORDER)) {
            val key = bounded(name, 256)
            val overhead = key.toByteArray(Charsets.UTF_8).size + 3 // ': ' và newline trong viewer.
            if (remaining <= overhead) break
            val raw = if (sensitive(name)) "***" else headers.values(name).joinToString(", ")
            val value = bounded(raw, remaining - overhead)
            result[key] = value
            remaining -= overhead + value.toByteArray(Charsets.UTF_8).size
            if (value != raw) break
        }
        return result.toMap()
    }

    fun url(url: HttpUrl): String {
        val safe = url.newBuilder().username("").password("")
        url.queryParameterNames.filter(::sensitive).forEach { safe.setQueryParameter(it, "***") }
        return bounded(safe.build().toString())
    }

    fun json(raw: String): String {
        if (raw.toByteArray(Charsets.UTF_8).size > LIMIT) return "[body omitted: exceeds 4KiB]"
        // Chặn depth quá lớn trước Gson/tree walk để preview debug không làm tràn stack app.
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped =
                    true else if (char == '\"') quoted = false
            } else when (char) {
                '\"' -> quoted = true
                '[', '{' -> {
                    depth++
                    if (depth > 32) return "[JSON omitted: depth exceeds32]"
                }
                ']', '}' -> depth--
            }
        }
        fun redact(value: JsonElement) {
            if (value.isJsonObject) value.asJsonObject.let { obj ->
                obj.keySet().toList().forEach { key ->
                    if (sensitive(key)) obj.add(key, JsonPrimitive("***")) else redact(obj[key])
                }
            } else if (value.isJsonArray) value.asJsonArray.forEach(::redact)
        }
        return try {
            bounded(JsonParser.parseString(raw).also(::redact).toString())
        } catch (_: Exception) {
            "[non-JSON body omitted]"
        }
    }
}
