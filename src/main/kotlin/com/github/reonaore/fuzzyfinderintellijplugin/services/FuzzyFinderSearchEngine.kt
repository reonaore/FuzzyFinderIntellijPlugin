package com.github.reonaore.fuzzyfinderintellijplugin.services

import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class FuzzyFinderSearchEngine(
    private val fdExecutable: String,
    private val fzfExecutable: String,
    private val rgExecutable: String,
    private val runner: CommandRunner,
) {

    suspend fun search(
        query: String,
        options: FdSearchOptions,
        root: Path,
        limit: Int = MAX_RESULTS,
    ): SearchResult {
        val candidates = runner.run(
            command = CommandSpec(
                executable = fdExecutable,
                parameters = buildFdParameters(options, root),
            ),
        )
        val stdout = runner.run(
            command = CommandSpec(
                executable = fzfExecutable,
                parameters = listOf("--filter", query, "--scheme=path", "--read0", "--print0"),
            ),
            stdin = candidates,
            noMatchExitCodes = setOf(FZF_NO_MATCH_EXIT_CODE),
        )

        val totalCount = FuzzyFinderParsers.parseNulSeparatedPaths(candidates).size
        val results = FuzzyFinderParsers.parseNulSeparatedPaths(stdout).take(limit)

        return SearchResult(
            totalCandidates = totalCount,
            query = query,
            results = results,
        )
    }

    suspend fun grep(
        query: String,
        options: GrepSearchOptions,
        root: Path,
        limit: Int = MAX_RESULTS,
    ): GrepSearchResult {
        if (query.isBlank()) {
            return GrepSearchResult(
                totalMatches = 0,
                query = query,
                matches = emptyList(),
            )
        }

        val stdout = runner.run(
            command = CommandSpec(
                executable = rgExecutable,
                parameters = buildRgParameters(query, options, root),
            ),
            noMatchExitCodes = setOf(RG_NO_MATCH_EXIT_CODE),
        )
        val matches = FuzzyFinderParsers.parseRgMatches(stdout)

        return GrepSearchResult(
            totalMatches = matches.size,
            query = query,
            matches = matches.take(limit),
        )
    }

    fun grepStream(
        query: String,
        options: GrepSearchOptions,
        root: Path,
    ): Flow<GrepSearchUpdate> {
        if (query.isBlank()) {
            return flowOf(
                GrepSearchUpdate(
                    totalMatches = 0,
                    query = query,
                    matches = emptyList(),
                    isComplete = true,
                ),
            )
        }

        return flow {
            val matches = mutableListOf<GrepMatch>()
            var emittedCount = 0
            var lastEmitAt = 0L

            runner.streamLines(
                command = CommandSpec(
                    executable = rgExecutable,
                    parameters = buildStreamingRgParameters(query, options, root),
                ),
                noMatchExitCodes = setOf(RG_NO_MATCH_EXIT_CODE),
            ).collect { line ->
                val match = FuzzyFinderParsers.parseRgJsonLine(line) ?: return@collect
                matches += match

                val now = System.nanoTime()
                val shouldEmit = matches.size == 1 ||
                    matches.size - emittedCount >= STREAM_BATCH_SIZE ||
                    now - lastEmitAt >= STREAM_BATCH_INTERVAL_NANOS
                if (shouldEmit) {
                    emit(
                        GrepSearchUpdate(
                            totalMatches = matches.size,
                            query = query,
                            matches = matches.toList(),
                            isComplete = false,
                        ),
                    )
                    emittedCount = matches.size
                    lastEmitAt = now
                }
            }

            emit(
                GrepSearchUpdate(
                    totalMatches = matches.size,
                    query = query,
                    matches = matches.toList(),
                    isComplete = true,
                ),
            )
        }
    }

    suspend fun filterGrepMatches(
        query: String,
        matches: List<GrepMatch>,
        root: Path,
        limit: Int = MAX_RESULTS,
    ): List<GrepMatch> {
        if (query.isBlank()) {
            return matches.take(limit)
        }

        val stdout = runner.run(
            command = CommandSpec(
                executable = fzfExecutable,
                parameters = listOf(
                    "--filter", query,
                    "--read0",
                    "--print0",
                    "--delimiter", "\t",
                    "--with-nth", "2..",
                ),
            ),
            stdin = toIndexedGrepMatchRecords(matches, root),
            noMatchExitCodes = setOf(FZF_NO_MATCH_EXIT_CODE),
        )

        return parseFilteredGrepMatchIndexes(stdout)
            .mapNotNull(matches::getOrNull)
            .take(limit)
    }

    private fun toIndexedGrepMatchRecords(matches: List<GrepMatch>, root: Path): ByteArray {
        if (matches.isEmpty()) {
            return ByteArray(0)
        }

        return buildString {
            matches.forEachIndexed { index, match ->
                append(index)
                append('\t')
                append(grepMatchDisplayText(match, root))
                append('\u0000')
            }
        }.toByteArray(StandardCharsets.UTF_8)
    }

    private fun grepMatchDisplayText(match: GrepMatch, root: Path): String {
        val displayPath = runCatching { root.relativize(match.path).toString() }.getOrDefault(match.path.toString())
        return "$displayPath:${match.line}:${match.column}: ${match.lineText}"
    }

    private fun parseFilteredGrepMatchIndexes(stdout: ByteArray): List<Int> {
        if (stdout.isEmpty()) {
            return emptyList()
        }

        return stdout
            .toString(StandardCharsets.UTF_8)
            .split('\u0000')
            .asSequence()
            .filter(String::isNotEmpty)
            .mapNotNull { record ->
                record.substringBefore('\t').toIntOrNull()
            }
            .toList()
    }

    private companion object {
        const val MAX_RESULTS = 200
        const val FZF_NO_MATCH_EXIT_CODE = 1
        const val RG_NO_MATCH_EXIT_CODE = 1
        const val STREAM_BATCH_SIZE = 50
        const val STREAM_BATCH_INTERVAL_NANOS = 100_000_000L
    }
}

private fun buildStreamingRgParameters(query: String, options: GrepSearchOptions, root: Path): List<String> {
    val parameters = buildRgParameters(query, options, root).toMutableList()
    val jsonIndex = parameters.indexOf("--json")
    parameters.add(jsonIndex + 1, "--line-buffered")
    return parameters
}
