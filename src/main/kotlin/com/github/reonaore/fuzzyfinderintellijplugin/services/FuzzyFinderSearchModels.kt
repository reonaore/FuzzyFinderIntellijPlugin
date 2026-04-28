package com.github.reonaore.fuzzyfinderintellijplugin.services

import java.nio.file.Path

class FuzzyFinderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class SearchResult(
    val totalCandidates: Int,
    val query: String,
    val results: List<Path>,
)

data class GrepSearchResult(
    val totalMatches: Int,
    val query: String,
    val matches: List<GrepMatch>,
)

data class GrepMatch(
    val path: Path,
    val line: Int,
    val column: Int,
    val lineText: String,
    val matchRanges: List<TextRange>,
)

data class TextRange(
    val startOffset: Int,
    val endOffset: Int,
)

data class PreviewHighlightRange(
    val line: Int,
    val range: TextRange,
)

data class FdSearchOptions(
    val entryType: FdEntryType = FdEntryType.FILES,
    val includeHidden: Boolean = false,
    val followSymlinks: Boolean = true,
    val respectGitIgnore: Boolean = true,
    val includeExtensions: List<String> = emptyList(),
    val excludePatterns: List<String> = listOf(".git"),
)

data class GrepSearchOptions(
    val includeHidden: Boolean = false,
    val followSymlinks: Boolean = true,
    val respectGitIgnore: Boolean = true,
    val includeExtensions: List<String> = emptyList(),
    val excludePatterns: List<String> = listOf(".git"),
    val smartCase: Boolean = true,
)

enum class FdEntryType(val presentableName: String, val fdValue: String?) {
    ANY("Any", null),
    FILES("Files", "f"),
    DIRECTORIES("Directories", "d"),
    SYMLINKS("Symlinks", "l"),
    EXECUTABLES("Executables", "x"),
    EMPTY("Empty", "e");

    override fun toString(): String = presentableName
}

internal fun buildRgParameters(query: String, options: GrepSearchOptions, root: Path): List<String> {
    val parameters = mutableListOf(
        "--json",
    )

    if (options.smartCase) {
        parameters += "--smart-case"
    }
    if (options.includeHidden) {
        parameters += "--hidden"
    }
    if (options.followSymlinks) {
        parameters += "--follow"
    }
    if (!options.respectGitIgnore) {
        parameters += "--no-ignore"
    }
    options.includeExtensions
        .map(::normalizeExtension)
        .filter(String::isNotEmpty)
        .forEach { extension ->
            parameters += listOf("--glob", "*.$extension")
        }
    options.excludePatterns
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { pattern ->
            parameters += listOf("--glob", "!$pattern")
        }

    parameters += listOf("--", query, root.toString())
    return parameters
}

private fun normalizeExtension(extension: String): String {
    return extension.trim().removePrefix(".")
}

internal fun buildFdParameters(options: FdSearchOptions, root: Path): List<String> {
    val parameters = mutableListOf<String>()

    options.entryType.fdValue?.let { entryType ->
        parameters += listOf("--type", entryType)
    }
    parameters += "--absolute-path"
    if (options.includeHidden) {
        parameters += "--hidden"
    }
    if (options.followSymlinks) {
        parameters += "--follow"
    }
    if (!options.respectGitIgnore) {
        parameters += "--no-ignore"
    }
    options.includeExtensions
        .map(::normalizeExtension)
        .filter(String::isNotEmpty)
        .forEach { extension ->
            parameters += listOf("--extension", extension)
        }
    options.excludePatterns
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { pattern ->
            parameters += listOf("--exclude", pattern)
        }

    parameters += listOf("--print0", ".", root.toString())
    return parameters
}
