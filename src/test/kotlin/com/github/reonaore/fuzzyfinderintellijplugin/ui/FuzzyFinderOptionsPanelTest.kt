package com.github.reonaore.fuzzyfinderintellijplugin.ui

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class FuzzyFinderOptionsPanelTest {

    @Test
    fun togglingIncludeHiddenUpdatesSearchOptionsAndNotifiesListener() {
        var changes = 0
        val panel = FuzzyFinderOptionsPanel { changes++ }

        assertFalse(panel.currentOptions().includeHidden)

        panel.toggleIncludeHidden()

        assertTrue(panel.currentOptions().includeHidden)
        assertEquals(1, changes)
    }

    @Test
    fun togglingFollowSymlinksUpdatesSearchOptionsAndNotifiesListener() {
        var changes = 0
        val panel = FuzzyFinderOptionsPanel { changes++ }

        assertTrue(panel.currentOptions().followSymlinks)

        panel.toggleFollowSymlinks()

        assertFalse(panel.currentOptions().followSymlinks)
        assertEquals(1, changes)
    }

    @Test
    fun togglingRespectGitIgnoreUpdatesSearchOptionsAndNotifiesListener() {
        var changes = 0
        val panel = FuzzyFinderOptionsPanel { changes++ }

        assertTrue(panel.currentOptions().respectGitIgnore)

        panel.toggleRespectGitIgnore()

        assertFalse(panel.currentOptions().respectGitIgnore)
        assertEquals(1, changes)
    }

    @Test
    fun checkboxLabelsUnderlineTheShortcutCharacter() {
        val panel = FuzzyFinderOptionsPanel { }

        assertEquals("<html><u>H</u>idden</html>", panel.includeHiddenLabelText())
        assertEquals("<html>Follow <u>s</u>ymlinks</html>", panel.followSymlinksLabelText())
        assertEquals("<html>.<u>g</u>itignore</html>", panel.respectGitIgnoreLabelText())
    }

    @Test
    fun checkboxTooltipsShowExplicitAltShortcut() {
        val panel = FuzzyFinderOptionsPanel { }

        assertNotNull(panel.includeHiddenTooltipText())
        assertEquals("Alt+H", panel.includeHiddenTooltipText())
        assertEquals("Alt+S", panel.followSymlinksTooltipText())
        assertEquals("Alt+G", panel.respectGitIgnoreTooltipText())
    }
}
