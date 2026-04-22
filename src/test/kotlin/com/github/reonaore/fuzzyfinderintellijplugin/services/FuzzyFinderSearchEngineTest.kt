package com.github.reonaore.fuzzyfinderintellijplugin.services

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Test
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
