package cn.orzmc.realtime.core.update

/**
 * The subset of JSON the update sources need.
 *
 * The plugin has no runtime dependencies, so the three platform payloads are read
 * by this small strict parser instead of pulling in a serialization library. It is
 * deliberately not lenient: trailing commas, comments, single quotes and control
 * characters inside strings are rejected, and both nesting depth and input size are
 * capped, because the payloads come from the network.
 */
sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(
        val value: Boolean,
    ) : JsonValue

    data class Num(
        val raw: String,
    ) : JsonValue {
        fun toLong(): Long? = raw.toLongOrNull()

        fun toDouble(): Double? = raw.toDoubleOrNull()
    }

    data class Str(
        val value: String,
    ) : JsonValue

    data class Arr(
        val values: List<JsonValue>,
    ) : JsonValue

    data class Obj(
        val fields: Map<String, JsonValue>,
    ) : JsonValue
}

class JsonException(
    message: String,
) : IllegalArgumentException(message)

/** Convenience accessors; every one returns null instead of throwing on a shape mismatch. */
fun JsonValue?.asObject(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue?.asArray(): List<JsonValue>? = (this as? JsonValue.Arr)?.values

fun JsonValue?.asString(): String? = (this as? JsonValue.Str)?.value

fun JsonValue?.asLong(): Long? = (this as? JsonValue.Num)?.toLong()

fun JsonValue?.asBoolean(): Boolean? = (this as? JsonValue.Bool)?.value

fun JsonValue.Obj.field(name: String): JsonValue? = fields[name]

fun JsonValue.Obj.string(name: String): String? = fields[name].asString()

fun JsonValue.Obj.long(name: String): Long? = fields[name].asLong()

fun JsonValue.Obj.boolean(name: String): Boolean? = fields[name].asBoolean()

fun JsonValue.Obj.obj(name: String): JsonValue.Obj? = fields[name].asObject()

fun JsonValue.Obj.array(name: String): List<JsonValue>? = fields[name].asArray()

object Json {
    /** Deepest nesting accepted; platform payloads are far shallower. */
    const val MAX_DEPTH: Int = 48

    /** Largest payload accepted by default, in characters. */
    const val MAX_LENGTH: Int = 1 shl 20

    fun parse(
        text: CharSequence,
        maxLength: Int = MAX_LENGTH,
    ): JsonValue {
        if (text.length > maxLength) {
            throw JsonException("JSON payload is ${text.length} characters, limit is $maxLength")
        }
        return Reader(text).readDocument()
    }

    private class Reader(
        private val text: CharSequence,
    ) {
        private var index = 0
        private var depth = 0

        fun readDocument(): JsonValue {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            if (index != text.length) throw error("unexpected trailing content")
            return value
        }

        private fun readValue(): JsonValue {
            if (index >= text.length) throw error("unexpected end of input")
            return when (val char = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (char == '-' || char.isDigit()) readNumber() else throw error("unexpected '$char'")
            }
        }

        private fun readObject(): JsonValue.Obj {
            enter()
            index++ // '{'
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                depth--
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw error("object key must be a string")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') throw error("expected ':' after object key")
                index++
                skipWhitespace()
                fields[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        depth--
                        return JsonValue.Obj(fields)
                    }
                    else -> throw error("expected ',' or '}' in object")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            enter()
            index++ // '['
            val values = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                depth--
                return JsonValue.Arr(values)
            }
            while (true) {
                skipWhitespace()
                values.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        depth--
                        return JsonValue.Arr(values)
                    }
                    else -> throw error("expected ',' or ']' in array")
                }
            }
        }

        private fun readString(): String {
            index++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) throw error("unterminated string")
                when (val char = text[index]) {
                    '"' -> {
                        index++
                        return out.toString()
                    }
                    '\\' -> {
                        index++
                        if (index >= text.length) throw error("unterminated escape")
                        when (val escape = text[index]) {
                            '"', '\\', '/' -> {
                                out.append(escape)
                                index++
                            }
                            'b' -> {
                                out.append('\b')
                                index++
                            }
                            'f' -> {
                                out.append('\u000C')
                                index++
                            }
                            'n' -> {
                                out.append('\n')
                                index++
                            }
                            'r' -> {
                                out.append('\r')
                                index++
                            }
                            't' -> {
                                out.append('\t')
                                index++
                            }
                            'u' -> {
                                index++
                                out.append(readUnicodeEscape())
                            }
                            else -> throw error("invalid escape '\\$escape'")
                        }
                    }
                    else -> {
                        if (char < ' ') throw error("control character in string")
                        out.append(char)
                        index++
                    }
                }
            }
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > text.length) throw error("truncated \\u escape")
            var code = 0
            for (offset in 0 until 4) {
                val digit = Character.digit(text[index + offset], 16)
                if (digit < 0) throw error("invalid \\u escape")
                code = code * 16 + digit
            }
            index += 4
            return code.toChar()
        }

        private fun readNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') index++
            if (index >= text.length) throw error("truncated number")
            if (peek() == '0') {
                index++
            } else if (peek()?.isDigit() == true) {
                while (index < text.length && text[index].isDigit()) index++
            } else {
                throw error("invalid number")
            }
            if (index < text.length && text[index] == '.') {
                index++
                if (index >= text.length || !text[index].isDigit()) throw error("truncated number fraction")
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                if (index >= text.length || !text[index].isDigit()) throw error("truncated number exponent")
                while (index < text.length && text[index].isDigit()) index++
            }
            return JsonValue.Num(text.substring(start, index))
        }

        private fun literal(
            word: String,
            value: JsonValue,
        ): JsonValue {
            if (index + word.length > text.length) throw error("truncated literal")
            for (offset in word.indices) {
                if (text[index + offset] != word[offset]) throw error("invalid literal")
            }
            index += word.length
            return value
        }

        private fun enter() {
            depth++
            if (depth > MAX_DEPTH) throw error("nesting deeper than $MAX_DEPTH levels")
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index++
                    else -> return
                }
            }
        }

        private fun error(message: String): JsonException = JsonException("$message at offset $index")
    }
}
