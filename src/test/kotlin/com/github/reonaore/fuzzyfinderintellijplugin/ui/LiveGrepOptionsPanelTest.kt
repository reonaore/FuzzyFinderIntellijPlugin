package com.github.reonaore.fuzzyfinderintellijplugin.ui

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class LiveGrepOptionsPanelTest {

    @Test
    fun defaultsToSmartCaseAndProjectFriendlyScopeOptions() {
        val panel = LiveGrepOptionsPanel { }
        val options = panel.currentOptions()

        assertFalse(options.includeHidden)
        assertTrue(options.followSymlinks)
        assertTrue(options.respectGitIgnore)
        assertTrue(options.smartCase)
        assertEquals(listOf(".git"), options.excludePatterns)
    }

    @Test
    fun togglesSmartCaseAndNotifiesChanges() {
        var changes = 0
        val panel = LiveGrepOptionsPanel { changes++ }

        panel.toggleSmartCase()

        assertFalse(panel.currentOptions().smartCase)
        assertEquals(1, changes)
    }

    @Test
    fun exposesMnemonicLabelAndTooltipText() {
        val panel = LiveGrepOptionsPanel { }

        assertTrue(panel.smartCaseLabelText().contains("<u>c</u>"))
        assertEquals("Alt+C", panel.smartCaseTooltipText())
    }
}
