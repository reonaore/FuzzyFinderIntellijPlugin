package com.github.reonaore.fuzzyfinderintellijplugin.util

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

object FuzzyFinderParsers {

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
