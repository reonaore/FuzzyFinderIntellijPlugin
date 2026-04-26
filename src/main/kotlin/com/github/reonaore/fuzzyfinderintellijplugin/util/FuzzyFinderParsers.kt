package com.github.reonaore.fuzzyfinderintellijplugin.util

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

object FuzzyFinderParsers {
    private val rgMatchPattern = Regex("""^(.+):(\d+):(\d+):(.*)$""")

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
            .mapNotNull(::parseRgMatchLine)
            .toList()
    }

    private fun parseRgMatchLine(line: String): GrepMatch? {
        val match = rgMatchPattern.matchEntire(line) ?: return null
        val (path, lineNumber, column, lineText) = match.destructured
        return GrepMatch(
            path = Paths.get(path),
            line = lineNumber.toIntOrNull() ?: return null,
            column = column.toIntOrNull() ?: return null,
            lineText = lineText,
        )
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
