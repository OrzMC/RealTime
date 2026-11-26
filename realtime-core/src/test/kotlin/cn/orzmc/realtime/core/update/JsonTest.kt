package cn.orzmc.realtime.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonTest {
    @Test
    fun `reads nested platform payloads`() {
        val payload =
            """
            {
              "name": "0.7.0",
              "sizeBytes": 2183111,
              "public": true,
              "externalUrl": null,
              "downloads": { "PAPER": { "fileInfo": { "sha256Hash": "abc" } } },
              "dependencies": ["paper", "folia"]
            }
            """.trimIndent()

        val root = Json.parse(payload).asObject()!!
        assertEquals("0.7.0", root.string("name"))
        assertEquals(2183111L, root.long("sizeBytes"))
        assertEquals(true, root.boolean("public"))
        assertNull(root.field("externalUrl")?.asString())
        assertEquals(
            "abc",
            root
                .obj("downloads")
                ?.obj("PAPER")
                ?.obj("fileInfo")
                ?.string("sha256Hash"),
        )
        assertEquals(listOf("paper", "folia"), root.array("dependencies")?.map { it.asString() })

        // Wrong shapes are null, never an exception.
        assertNull(root.string("sizeBytes"))
        assertNull(root.long("name"))
        assertNull(root.obj("missing"))
        assertNull(root.array("name"))
    }

    @Test
    fun `handles escapes and unicode`() {
        val value = Json.parse("""{"text":"a\"b\\c\/d\n\u00e9\ud83d\ude00"}""").asObject()!!.string("text")
        assertEquals("a\"b\\c/d\n\u00e9\ud83d\ude00", value)
        assertEquals("", Json.parse("\"\"").asString())
    }

    @Test
    fun `reads numbers the way HTTP APIs publish them`() {
        assertEquals(-1L, Json.parse("-1").asLong())
        assertEquals(0L, Json.parse("0").asLong())
        assertEquals(2183111L, Json.parse("2183111").asLong())
        assertEquals(
            12L,
            Json
                .parse("[12]")
                .asArray()!!
                .single()
                .asLong(),
        )
        // Exponents and fractions keep their raw text: platforms only need integers.
        assertNull(Json.parse("1e1").asLong())
        assertEquals(10.0, (Json.parse("1e1") as JsonValue.Num).toDouble())
        assertEquals(1.5, (Json.parse("1.5") as JsonValue.Num).toDouble())
    }

    @Test
    fun `rejects malformed input instead of guessing`() {
        assertThrows(JsonException::class.java) { Json.parse("") }
        assertThrows(JsonException::class.java) { Json.parse("{") }
        assertThrows(JsonException::class.java) { Json.parse("{\"a\":1,}") }
        assertThrows(JsonException::class.java) { Json.parse("[1,]") }
        assertThrows(JsonException::class.java) { Json.parse("{'a':1}") }
        assertThrows(JsonException::class.java) { Json.parse("\"unterminated") }
        assertThrows(JsonException::class.java) { Json.parse("tru") }
        assertThrows(JsonException::class.java) { Json.parse("{} trailing") }
        assertThrows(JsonException::class.java) { Json.parse("{\"a\" 1}") }
        assertThrows(JsonException::class.java) { Json.parse("01") }
        assertThrows(JsonException::class.java) { Json.parse("\"a\u0001b\"") }
        assertThrows(JsonException::class.java) { Json.parse("{\"a\":\"\\q\"}") }
    }

    @Test
    fun `refuses payloads that are too large or too deeply nested`() {
        assertThrows(JsonException::class.java) { Json.parse("1".repeat(100), maxLength = 50) }

        val deep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1)
        val failure = assertThrows(JsonException::class.java) { Json.parse(deep) }
        assertTrue(failure.message!!.contains("nesting"))

        // The limit itself is fine.
        val atLimit = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)
        assertTrue(Json.parse(atLimit) is JsonValue.Arr)
    }

    @Test
    fun `keeps duplicate keys last-wins like every other JSON reader`() {
        assertEquals("2", Json.parse("""{"a":"1","a":"2"}""").asObject()!!.string("a"))
    }
}
