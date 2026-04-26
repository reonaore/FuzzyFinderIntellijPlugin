package com.github.reonaore.fuzzyfinderintellijplugin.services

import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
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

    private companion object {
        const val MAX_RESULTS = 200
        const val FZF_NO_MATCH_EXIT_CODE = 1
        const val RG_NO_MATCH_EXIT_CODE = 1
    }
}
