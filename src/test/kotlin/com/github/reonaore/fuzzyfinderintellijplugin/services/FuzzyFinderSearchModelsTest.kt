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

    @Test
    fun buildsRgParametersFromOptions() {
        val parameters = buildRgParameters(
            "Needle",
            GrepSearchOptions(
                includeHidden = true,
                followSymlinks = false,
                respectGitIgnore = false,
                includeExtensions = listOf("kt", ".java", " "),
                excludePatterns = listOf(".git", "node_modules"),
                smartCase = true,
            ),
            Path.of("/repo"),
        )

        assertEquals(
            listOf(
                "--json",
                "--smart-case",
                "--hidden",
                "--no-ignore",
                "--glob", "*.kt",
                "--glob", "*.java",
                "--glob", "!.git",
                "--glob", "!node_modules",
                "--",
                "Needle",
                "/repo",
            ),
            parameters,
        )
    }

    @Test
    fun ignoresBlankRgExtensionFilters() {
        val parameters = buildRgParameters(
            "Needle",
            GrepSearchOptions(includeExtensions = listOf(" ", "")),
            Path.of("/repo"),
        )

        assertEquals(
            listOf(
                "--json",
                "--smart-case",
                "--follow",
                "--glob", "!.git",
                "--",
                "Needle",
                "/repo",
            ),
            parameters,
        )
    }
}
