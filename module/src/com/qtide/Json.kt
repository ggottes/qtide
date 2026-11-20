package com.qtide

/**
 * Just enough json for the services' answers: objects, arrays, strings, numbers, booleans and null.
 *
 * The module api carries no dependencies on purpose (a module is loaded into mochy's own process),
 * so a library here would have to be dexed alongside anyway. This is a few dozen lines and reads
 * every answer these services send.
 */
internal object Json {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value()
        return value
    }

    /** an object from a string, or null when the text isn't one */
    fun obj(text: String?): Map<String, Any?>? {
        if (text.isNullOrBlank()) return null
        return runCatching { parse(text) as? Map<String, Any?> }.getOrNull()
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun value(): Any? {
            skipSpace()
            if (at >= text.length) return null
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else null
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            at++ // {
            skipSpace()
            if (at < text.length && text[at] == '}') {
                at++
                return out
            }
            while (at < text.length) {
                skipSpace()
                val key = string() ?: break
                skipSpace()
                if (at < text.length && text[at] == ':') at++
                out[key] = value()
                skipSpace()
                if (at < text.length && text[at] == ',') {
                    at++
                    continue
                }
                if (at < text.length && text[at] == '}') at++
                break
            }
            return out
        }

        private fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            at++ // [
            skipSpace()
            if (at < text.length && text[at] == ']') {
                at++
                return out
            }
            while (at < text.length) {
                out.add(value())
                skipSpace()
                if (at < text.length && text[at] == ',') {
                    at++
                    continue
                }
                if (at < text.length && text[at] == ']') at++
                break
            }
            return out
        }

        private fun string(): String? {
            if (at >= text.length || text[at] != '"') return null
            at++
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (at >= text.length) return out.toString()
                        when (val next = text[at++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                val hex = text.substring(at, minOf(at + 4, text.length))
                                at += hex.length
                                out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            }
                            else -> out.append(next)
                        }
                    }
                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        private fun number(): Any? {
            val start = at
            if (at < text.length && text[at] == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] == '.' || text[at] == 'e' || text[at] == 'E' || text[at] == '+' || text[at] == '-')) at++
            val raw = text.substring(start, at)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (text.startsWith(word, at)) {
                at += word.length
                return value
            }
            at++
            return null
        }
    }

    // ---- reading an answer ----

    @Suppress("UNCHECKED_CAST")
    fun objOf(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun text(map: Map<String, Any?>?, name: String): String? = when (val value = map?.get(name)) {
        is String -> value
        is Number -> value.toString()
        else -> null
    }

    fun list(map: Map<String, Any?>?, name: String): List<Any?>? = map?.get(name) as? List<Any?>

    /** a json true/false, which the reader keeps as a Boolean, or the text "true"/"false" some services send */
    fun bool(map: Map<String, Any?>?, name: String): Boolean = when (val value = map?.get(name)) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        is Number -> value.toInt() != 0
        else -> false
    }

    fun long(map: Map<String, Any?>?, name: String): Long? = when (val value = map?.get(name)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    /** the little json a module sends: strings, numbers, booleans, lists and nested maps */
    fun write(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean -> value.toString()
        is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> write(k.toString()) + ":" + write(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { write(it) }
        else -> write(value.toString())
    }
}
