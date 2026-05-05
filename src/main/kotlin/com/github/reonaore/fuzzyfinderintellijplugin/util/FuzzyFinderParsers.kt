package com.github.reonaore.fuzzyfinderintellijplugin.util

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

object FuzzyFinderParsers {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parseNulSeparatedPaths(stdout: ByteArray): List<Path> {
        if (stdout.isEmpty()) {
            return emptyList()
        }

        return stdout
            .toString(StandardCharsets.UTF_8)
            .split('\u0000')
            .asSequence()
            .filter { it.isNotEmpty() }
            .map(Paths::get)
            .toList()
    }

    fun parseRgMatches(stdout: ByteArray): List<GrepMatch> {
        if (stdout.isEmpty()) {
            return emptyList()
        }

        return stdout
            .toString(StandardCharsets.UTF_8)
            .lineSequence()
            .mapNotNull(::parseRgJsonLine)
            .toList()
    }

    private fun parseRgJsonLine(line: String): GrepMatch? {
        val rgLine = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        if (rgLine.stringAt("type") != "match") {
            return null
        }

        val data = rgLine.objectAt("data") ?: return null
        val path = data.objectAt("path")?.stringAt("text")?.ifBlank { return null } ?: return null
        val lineText = data.objectAt("lines")?.stringAt("text")?.trimEnd('\n', '\r') ?: return null
        val lineNumber = data.longAt("line_number")?.toInt()?.takeIf { it > 0 } ?: return null
        val ranges = data.arrayAt("submatches")
            .orEmpty()
            .mapNotNull(JsonElement::asObjectOrNull)
            .mapNotNull { submatch ->
                val start = submatch.intAt("start") ?: return@mapNotNull null
                val end = submatch.intAt("end") ?: return@mapNotNull null
                TextRange(
                    startOffset = utf8ByteOffsetToCharIndex(lineText, start),
                    endOffset = utf8ByteOffsetToCharIndex(lineText, end),
                )
            }
            .filter { it.startOffset <= it.endOffset }
        if (ranges.isEmpty()) {
            return null
        }

        return GrepMatch(
            path = Paths.get(path),
            line = lineNumber,
            column = ranges.first().startOffset + 1,
            lineText = lineText,
            matchRanges = ranges,
        )
    }

    private fun utf8ByteOffsetToCharIndex(text: String, byteOffset: Int): Int {
        if (byteOffset <= 0) return 0

        var bytes = 0
        var index = 0
        while (index < text.length) {
            val nextIndex = text.offsetByCodePoints(index, 1)
            val charBytes = text.substring(index, nextIndex).toByteArray(StandardCharsets.UTF_8).size
            if (bytes + charBytes > byteOffset) {
                return index
            }
            bytes += charBytes
            index = nextIndex
        }
        return text.length
    }

    fun toNulSeparatedBytes(paths: List<Path>): ByteArray {
        if (paths.isEmpty()) {
            return ByteArray(0)
        }

        return buildString {
            paths.forEach { path ->
                append(path.toString())
                append('\u0000')
            }
        }.toByteArray(StandardCharsets.UTF_8)
    }

    fun appendPreviewSuffix(text: String, truncated: Boolean): String {
        if (!truncated) {
            return text
        }

        return "$text\n\n... [preview truncated]"
    }
}

private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonObject.objectAt(name: String): JsonObject? = get(name)?.asObjectOrNull()

private fun JsonObject.arrayAt(name: String): List<JsonElement>? = runCatching { get(name)?.jsonArray }.getOrNull()

private fun JsonObject.stringAt(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject.intAt(name: String): Int? = get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject.longAt(name: String): Long? = get(name)?.jsonPrimitive?.longOrNull
