package com.github.reonaore.fuzzyfinderintellijplugin

import com.github.reonaore.fuzzyfinderintellijplugin.settings.FuzzyFinderSettingsState
import com.github.reonaore.fuzzyfinderintellijplugin.settings.FuzzyFinderSettingsService
import com.github.reonaore.fuzzyfinderintellijplugin.settings.SupportedCommand
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdEntryType
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.buildFdParameters
import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import java.nio.file.Paths

class FuzzyFinderParsersTest {

    @org.junit.Test
    fun parsesNulSeparatedPaths() {
        val parsed = FuzzyFinderParsers.parseNulSeparatedPaths("/tmp/a\u0000/tmp/b\u0000".toByteArray())

        assertEquals(listOf(Paths.get("/tmp/a"), Paths.get("/tmp/b")), parsed)
    }

    @org.junit.Test
    fun serializesPathsAsNulSeparatedBytes() {
        val serialized = FuzzyFinderParsers.toNulSeparatedBytes(listOf(Paths.get("/tmp/a"), Paths.get("/tmp/b")))

        assertEquals("/tmp/a\u0000/tmp/b\u0000", serialized.toString(Charsets.UTF_8))
    }

    @org.junit.Test
    fun appendsPreviewSuffixWhenTruncated() {
        val preview = FuzzyFinderParsers.appendPreviewSuffix("body", truncated = true)

        assertTrue(preview.contains("preview truncated"))
    }

    @org.junit.Test
    fun buildsFdParametersFromOptions() {
        val parameters = buildFdParameters(
            FdSearchOptions(
                entryType = FdEntryType.EXECUTABLES,
                includeHidden = true,
                followSymlinks = false,
                respectGitIgnore = false,
                excludePatterns = listOf(".git", "node_modules"),
            ),
            "/repo",
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

    @org.junit.Test
    fun usesConfiguredExecutablePathWhenPresent() {
        val settings = FuzzyFinderSettingsService()
        settings.loadState(FuzzyFinderSettingsState(fdExecutablePath = "/opt/homebrew/bin/fd"))

        assertEquals("/opt/homebrew/bin/fd", settings.executablePath(SupportedCommand.FD))
    }

    @org.junit.Test
    fun fallsBackToDefaultExecutableWhenBlank() {
        val settings = FuzzyFinderSettingsService()

        assertEquals("fzf", settings.executablePath(SupportedCommand.FZF))
    }
}
