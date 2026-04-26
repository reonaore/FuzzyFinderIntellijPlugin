package com.github.reonaore.fuzzyfinderintellijplugin.settings

import junit.framework.TestCase.assertEquals
import org.junit.Test

class FuzzyFinderSettingsServiceTest {

    @Test
    fun usesConfiguredExecutablePathWhenPresent() {
        val settings = FuzzyFinderSettingsService()
        settings.loadState(FuzzyFinderSettingsState(fdExecutablePath = "/opt/homebrew/bin/fd"))

        assertEquals("/opt/homebrew/bin/fd", settings.executablePath(SupportedCommand.FD))
    }

    @Test
    fun fallsBackToDefaultExecutableWhenBlank() {
        val settings = FuzzyFinderSettingsService()

        assertEquals("fzf", settings.executablePath(SupportedCommand.FZF))
        assertEquals("rg", settings.executablePath(SupportedCommand.RG))
    }

    @Test
    fun usesConfiguredRipgrepExecutablePathWhenPresent() {
        val settings = FuzzyFinderSettingsService()
        settings.loadState(FuzzyFinderSettingsState(rgExecutablePath = "/opt/homebrew/bin/rg"))

        assertEquals("/opt/homebrew/bin/rg", settings.executablePath(SupportedCommand.RG))
    }
}
