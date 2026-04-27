package com.github.reonaore.fuzzyfinderintellijplugin.services

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class FuzzyFinderSearchEngineTest {

    @Test
    fun calculatesTotalCandidatesFromFdOutput() = runBlocking {
        val runner = RecordingCommandRunner(
            outputs = listOf(
                "/repo/a.txt\u0000/repo/b.txt\u0000".toByteArray(),
                "/repo/b.txt\u0000".toByteArray(),
            ),
        )
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "fd",
            fzfExecutable = "fzf",
            rgExecutable = "rg",
            runner = runner,
        )

        val result = engine.search(
            query = "b",
            options = FdSearchOptions(),
            root = Path.of("/repo"),
        )

        assertEquals(2, result.totalCandidates)
        assertEquals("b", result.query)
        assertEquals(listOf(Path.of("/repo/b.txt")), result.results)
    }

    @Test
    fun limitsResultsAfterParsingFzfOutput() = runBlocking {
        val runner = RecordingCommandRunner(
            outputs = listOf(
                "/repo/a.txt\u0000/repo/b.txt\u0000/repo/c.txt\u0000".toByteArray(),
                "/repo/a.txt\u0000/repo/b.txt\u0000/repo/c.txt\u0000".toByteArray(),
            ),
        )
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "fd",
            fzfExecutable = "fzf",
            rgExecutable = "rg",
            runner = runner,
        )

        val result = engine.search(
            query = "",
            options = FdSearchOptions(),
            root = Path.of("/repo"),
            limit = 2,
        )

        assertEquals(listOf(Path.of("/repo/a.txt"), Path.of("/repo/b.txt")), result.results)
    }

    @Test
    fun returnsEmptyResultsWhenNoCandidatesAreDiscovered() = runBlocking {
        val runner = RecordingCommandRunner(
            outputs = listOf(
                ByteArray(0),
                ByteArray(0),
            ),
        )
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "fd",
            fzfExecutable = "fzf",
            rgExecutable = "rg",
            runner = runner,
        )

        val result = engine.search(
            query = "anything",
            options = FdSearchOptions(),
            root = Path.of("/repo"),
        )

        assertEquals(0, result.totalCandidates)
        assertEquals(emptyList<Path>(), result.results)
    }

    @Test
    fun passesQueryOptionsAndNoMatchCodeToRunner() = runBlocking {
        val options = FdSearchOptions(
            entryType = FdEntryType.DIRECTORIES,
            includeHidden = true,
            followSymlinks = false,
            respectGitIgnore = false,
            excludePatterns = listOf(".git", "build"),
        )
        val runner = RecordingCommandRunner(
            outputs = listOf(
                ByteArray(0),
                ByteArray(0),
            ),
        )
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "/usr/local/bin/fd",
            fzfExecutable = "/usr/local/bin/fzf",
            rgExecutable = "rg",
            runner = runner,
        )

        engine.search(
            query = "needle",
            options = options,
            root = Path.of("/repo"),
        )

        assertEquals(2, runner.calls.size)
        assertEquals(
            CommandSpec(
                executable = "/usr/local/bin/fd",
                parameters = buildFdParameters(options, Path.of("/repo")),
            ),
            runner.calls[0].command,
        )
        assertEquals(
            CommandSpec(
                executable = "/usr/local/bin/fzf",
                parameters = listOf("--filter", "needle", "--scheme=path", "--read0", "--print0"),
            ),
            runner.calls[1].command,
        )
        assertEquals(setOf(1), runner.calls[1].noMatchExitCodes)
    }

    @Test
    fun grepParsesRipgrepMatchesAndLimitsResults() = runBlocking {
        val runner = RecordingCommandRunner(
            outputs = listOf(
                """
                {"type":"match","data":{"path":{"text":"/repo/src/App.kt"},"lines":{"text":"fun needle() = Unit\n"},"line_number":12,"submatches":[{"match":{"text":"needle"},"start":4,"end":10}]}}
                {"type":"match","data":{"path":{"text":"/repo/src/Other.kt"},"lines":{"text":"needle()\n"},"line_number":24,"submatches":[{"match":{"text":"needle"},"start":0,"end":6}]}}
                """.trimIndent().toByteArray(),
            ),
        )
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "fd",
            fzfExecutable = "fzf",
            rgExecutable = "rg",
            runner = runner,
        )

        val result = engine.grep(
            query = "needle",
            options = GrepSearchOptions(),
            root = Path.of("/repo"),
            limit = 1,
        )

        assertEquals(2, result.totalMatches)
        assertEquals("needle", result.query)
        assertEquals(
            listOf(
                GrepMatch(
                    path = Path.of("/repo/src/App.kt"),
                    line = 12,
                    column = 5,
                    lineText = "fun needle() = Unit",
                    matchRanges = listOf(TextRange(4, 10)),
                ),
            ),
            result.matches,
        )
    }

    @Test
    fun grepPassesQueryOptionsAndNoMatchCodeToRunner() = runBlocking {
        val options = GrepSearchOptions(
            includeHidden = true,
            followSymlinks = false,
            respectGitIgnore = false,
            excludePatterns = listOf(".git", "build"),
            smartCase = true,
        )
        val runner = RecordingCommandRunner(outputs = listOf(ByteArray(0)))
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "fd",
            fzfExecutable = "fzf",
            rgExecutable = "/usr/local/bin/rg",
            runner = runner,
        )

        engine.grep(
            query = "needle",
            options = options,
            root = Path.of("/repo"),
        )

        assertEquals(1, runner.calls.size)
        assertEquals(
            CommandSpec(
                executable = "/usr/local/bin/rg",
                parameters = buildRgParameters("needle", options, Path.of("/repo")),
            ),
            runner.calls[0].command,
        )
        assertEquals(setOf(1), runner.calls[0].noMatchExitCodes)
    }

    @Test
    fun grepReturnsEmptyResultsForBlankQueryWithoutRunningRg() = runBlocking {
        val runner = RecordingCommandRunner(outputs = emptyList())
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "fd",
            fzfExecutable = "fzf",
            rgExecutable = "rg",
            runner = runner,
        )

        val result = engine.grep(
            query = " ",
            options = GrepSearchOptions(),
            root = Path.of("/repo"),
        )

        assertEquals(0, result.totalMatches)
        assertEquals(emptyList<GrepMatch>(), result.matches)
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun filterGrepMatchesReturnsLimitedCandidatesWithoutRunningFzfForBlankQuery() = runBlocking {
        val runner = RecordingCommandRunner(outputs = emptyList())
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "fd",
            fzfExecutable = "fzf",
            rgExecutable = "rg",
            runner = runner,
        )
        val matches = listOf(
            grepMatch("/repo/src/App.kt", 12, 5, "fun needle"),
            grepMatch("/repo/src/Other.kt", 24, 1, "other needle"),
        )

        val result = engine.filterGrepMatches(
            query = " ",
            matches = matches,
            root = Path.of("/repo"),
            limit = 1,
        )

        assertEquals(listOf(matches[0]), result)
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun filterGrepMatchesRunsFzfWithIndexedMatchRecordsAndReordersMatches() = runBlocking {
        val runner = RecordingCommandRunner(
            outputs = listOf(
                "1\tsrc/Other.kt:24:1: other needle\u00000\tsrc/App.kt:12:5: fun needle\u0000".toByteArray(),
            ),
        )
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "fd",
            fzfExecutable = "/usr/local/bin/fzf",
            rgExecutable = "rg",
            runner = runner,
        )
        val matches = listOf(
            grepMatch("/repo/src/App.kt", 12, 5, "fun needle"),
            grepMatch("/repo/src/Other.kt", 24, 1, "other needle"),
        )

        val result = engine.filterGrepMatches(
            query = "oth",
            matches = matches,
            root = Path.of("/repo"),
        )

        assertEquals(listOf(matches[1], matches[0]), result)
        assertEquals(1, runner.calls.size)
        assertEquals(
            CommandSpec(
                executable = "/usr/local/bin/fzf",
                parameters = listOf(
                    "--filter", "oth",
                    "--read0",
                    "--print0",
                    "--delimiter", "\t",
                    "--with-nth", "2..",
                ),
            ),
            runner.calls.single().command,
        )
        assertEquals(setOf(1), runner.calls.single().noMatchExitCodes)
        assertEquals(
            "0\tsrc/App.kt:12:5: fun needle\u00001\tsrc/Other.kt:24:1: other needle\u0000",
            runner.calls.single().stdin?.toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun filterGrepMatchesReturnsEmptyListForFzfNoMatchOutput() = runBlocking {
        val runner = RecordingCommandRunner(outputs = listOf(ByteArray(0)))
        val engine = FuzzyFinderSearchEngine(
            fdExecutable = "fd",
            fzfExecutable = "fzf",
            rgExecutable = "rg",
            runner = runner,
        )

        val result = engine.filterGrepMatches(
            query = "missing",
            matches = listOf(grepMatch("/repo/src/App.kt", 12, 5, "fun needle")),
            root = Path.of("/repo"),
        )

        assertEquals(emptyList<GrepMatch>(), result)
        assertEquals(1, runner.calls.size)
        assertEquals(setOf(1), runner.calls.single().noMatchExitCodes)
    }

    private fun grepMatch(path: String, line: Int, column: Int, lineText: String): GrepMatch {
        return GrepMatch(
            path = Path.of(path),
            line = line,
            column = column,
            lineText = lineText,
            matchRanges = listOf(TextRange((column - 1).coerceAtLeast(0), column)),
        )
    }

    private data class Invocation(
        val command: CommandSpec,
        val stdin: ByteArray?,
        val noMatchExitCodes: Set<Int>,
    )

    private class RecordingCommandRunner(
        private val outputs: List<ByteArray>,
    ) : CommandRunner {
        val calls = mutableListOf<Invocation>()

        override suspend fun run(
            command: CommandSpec,
            stdin: ByteArray?,
            noMatchExitCodes: Set<Int>,
        ): ByteArray {
            calls += Invocation(command, stdin, noMatchExitCodes)
            return outputs[calls.lastIndex]
        }
    }
}
