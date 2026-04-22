package com.walter.spring.ai.ops.util

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class CodeAnalysisResultHandler(
    private val objectMapper: ObjectMapper,
) {
    val lenientMapper = objectMapper.copy().apply {
        configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
        configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true)
    }

    fun <T> parseJsonArray(json: String, elementType: Class<T>): List<T> {
        val type = lenientMapper.typeFactory.constructCollectionType(List::class.java, elementType)
        return lenientMapper.readValue(json, type)
    }

    fun sanitizeControlChars(jsonText: String): String {
        val sb = StringBuilder(jsonText.length)
        var inString = false
        var escape = false
        for (c in jsonText) {
            when {
                escape           -> { sb.append(c); escape = false }
                c == '\\'        -> { sb.append(c); escape = true }
                c == '"'         -> { sb.append(c); inString = !inString }
                inString && c == '\n' -> sb.append("\\n")
                inString && c == '\r' -> sb.append("\\r")
                inString && c == '\t' -> sb.append("\\t")
                inString && c.code < 0x20 ->
                    sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
                else             -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun <T> recoverIssuesFromJson(jsonText: String, elementType: Class<T>): List<T> {
        val recovered = mutableListOf<T>()
        try {
            lenientMapper.createParser(jsonText).use { parser ->
                var token = parser.nextToken()
                if (token == JsonToken.START_ARRAY) token = parser.nextToken()
                while (token == JsonToken.START_OBJECT) {
                    runCatching {
                        val node = lenientMapper.readTree<JsonNode>(parser)
                        recovered.add(lenientMapper.treeToValue(node, elementType))
                    }
                    token = try { parser.nextToken() } catch (_: Exception) { break }
                }
            }
        } catch (_: Exception) { /* ignore outer parse errors */ }
        return recovered
    }
}