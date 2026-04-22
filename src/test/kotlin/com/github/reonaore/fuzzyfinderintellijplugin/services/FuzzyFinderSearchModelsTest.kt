package com.github.reonaore.fuzzyfinderintellijplugin.services

import junit.framework.TestCase.assertEquals
import org.junit.Test
import java.nio.file.Path

class FuzzyFinderSearchModelsTest {

    @Test
    fun buildsFdParametersFromOptions() {
        val parameters = buildFdParameters(
            FdSearchOptions(
                entryType = FdEntryType.EXECUTABLES,
                includeHidden = true,
                followSymlinks = false,
                respectGitIgnore = false,
                excludePatterns = listOf(".git", "node_modules"),
            ),
            Path.of("/repo"),
        )

        assertEquals(
            listOf(
                "--type", "x",
                "--absolute-path",
                "--hidden",
                "--no-ignore",
                "--exclude", ".git",
                "--exclude", "node_modules",
                "--print0", ".", "/repo",
            ),
            parameters,
        )
    }
}
