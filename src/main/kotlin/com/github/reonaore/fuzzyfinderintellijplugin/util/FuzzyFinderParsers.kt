package com.github.reonaore.fuzzyfinderintellijplugin.util

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        val rgLine = runCatching { json.decodeFromString<RgJsonLine>(line) }.getOrNull() ?: return null
        if (rgLine.type != RgMessageType.MATCH) {
            return null
        }

        val data = rgLine.data
        val path = data.path.text.ifBlank { return null }
        val lineText = data.lines.text.trimEnd('\n', '\r')
        val lineNumber = data.lineNumber.toInt().takeIf { it > 0 } ?: return null
        val ranges = data.submatches
            .map { submatch ->
                TextRange(
                    startOffset = utf8ByteOffsetToCharIndex(lineText, submatch.start),
                    endOffset = utf8ByteOffsetToCharIndex(lineText, submatch.end),
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

@Serializable
private data class RgJsonLine(
    val type: RgMessageType = RgMessageType.UNKNOWN,
    val data: RgMatchData = RgMatchData(),
)

@Serializable
private enum class RgMessageType {
    @SerialName("begin")
    BEGIN,

    @SerialName("context")
    CONTEXT,

    @SerialName("end")
    END,

    @SerialName("match")
    MATCH,

    @SerialName("summary")
    SUMMARY,

    UNKNOWN,
}

@Serializable
private data class RgMatchData(
    val path: RgText = RgText(),
    val lines: RgText = RgText(),
    @SerialName("line_number")
    val lineNumber: Long = 0,
    val submatches: List<RgSubmatch> = emptyList(),
)

@Serializable
private data class RgText(
    val text: String = "",
)

@Serializable
private data class RgSubmatch(
    val start: Int = 0,
    val end: Int = 0,
)
