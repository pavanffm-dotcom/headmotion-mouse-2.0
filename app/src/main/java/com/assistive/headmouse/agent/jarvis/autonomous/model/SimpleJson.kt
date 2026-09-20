package com.assistive.headmouse.agent.jarvis.autonomous.model

/**
 * Lightweight, zero-dependency pure-Kotlin JSON serializer and parser.
 * Works identically on standard JVM unit tests (where android.jar stubs return null)
 * and on Android devices, eliminating stubbing NullPointerExceptions.
 */
object SimpleJson {

    fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (c < ' ') {
                        append(String.format("\\u%04x", c.code))
                    } else {
                        append(c)
                    }
                }
            }
        }
    }

    fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append("\\u")
                                i += 2
                            }
                        } else {
                            sb.append("\\u")
                            i += 2
                        }
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> {
            value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                "\"${escape(k.toString())}\":${toJson(v)}"
            }
        }
        is Iterable<*> -> {
            value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
        }
        is Array<*> -> {
            value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
        }
        else -> "\"${escape(value.toString())}\""
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(json: String): Map<String, Any?> {
        val parsed = parse(json)
        return (parsed as? Map<String, Any?>) ?: emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    fun parseArray(json: String): List<Any?> {
        val parsed = parse(json)
        return (parsed as? List<Any?>) ?: emptyList()
    }

    fun parse(jsonStr: String): Any? {
        val trimmed = jsonStr.trim()
        if (trimmed.isEmpty()) return null
        val tokens = tokenize(trimmed)
        var idx = 0

        fun parseValue(): Any? {
            if (idx >= tokens.size) return null
            val t = tokens[idx++]
            return when {
                t == "{" -> {
                    val map = mutableMapOf<String, Any?>()
                    while (idx < tokens.size && tokens[idx] != "}") {
                        val keyToken = parseValue()
                        val key = keyToken?.toString() ?: ""
                        if (idx < tokens.size && tokens[idx] == ":") idx++ // skip ':'
                        val valToken = parseValue()
                        map[key] = valToken
                        if (idx < tokens.size && tokens[idx] == ",") idx++ // skip ','
                    }
                    if (idx < tokens.size && tokens[idx] == "}") idx++
                    map
                }
                t == "[" -> {
                    val list = mutableListOf<Any?>()
                    while (idx < tokens.size && tokens[idx] != "]") {
                        val item = parseValue()
                        list.add(item)
                        if (idx < tokens.size && tokens[idx] == ",") idx++
                    }
                    if (idx < tokens.size && tokens[idx] == "]") idx++
                    list
                }
                t.startsWith("\"") && t.endsWith("\"") && t.length >= 2 -> {
                    unescape(t.substring(1, t.length - 1))
                }
                t == "true" -> true
                t == "false" -> false
                t == "null" -> null
                t.toIntOrNull() != null -> t.toInt()
                t.toLongOrNull() != null -> t.toLong()
                t.toDoubleOrNull() != null -> t.toDouble()
                else -> t
            }
        }

        return parseValue()
    }

    private fun tokenize(s: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val len = s.length

        while (i < len) {
            val c = s[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            when (c) {
                '{', '}', '[', ']', ':', ',' -> {
                    tokens.add(c.toString())
                    i++
                }
                '"' -> {
                    val start = i
                    i++
                    var escaped = false
                    while (i < len) {
                        val ch = s[i]
                        if (escaped) {
                            escaped = false
                        } else if (ch == '\\') {
                            escaped = true
                        } else if (ch == '"') {
                            i++
                            break
                        }
                        i++
                    }
                    tokens.add(s.substring(start, i))
                }
                else -> {
                    val start = i
                    while (i < len && !s[i].isWhitespace() && s[i] !in "{}[],:") {
                        i++
                    }
                    tokens.add(s.substring(start, i))
                }
            }
        }

        return tokens
    }
}
