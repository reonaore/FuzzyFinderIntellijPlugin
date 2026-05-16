package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class LiveGrepSearchOptionsFieldTest {

    @Test
    fun defaultsToSmartCaseWordsMode() {
        val field = LiveGrepSearchOptionsField("Search")

        assertTrue(field.isSmartCaseSelected())
        assertEquals(LiveGrepQueryMode.WORDS, field.currentQueryMode())
    }

    @Test
    fun togglesSmartCaseAndRegexAndNotifiesChanges() {
        var changes = 0
        val field = LiveGrepSearchOptionsField("Search")
        field.setOnOptionsChanged { changes++ }

        field.toggleSmartCase()
        field.toggleRegex()

        assertFalse(field.isSmartCaseSelected())
        assertEquals(LiveGrepQueryMode.REGEX, field.currentQueryMode())
        assertEquals(2, changes)
    }

    @Test
    fun exposesFocusableSearchOptionButtonsWithTooltips() {
        val field = LiveGrepSearchOptionsField("Search")
        val extensions = field.extensions()

        assertEquals(2, extensions.size)
        assertEquals("Smart case (Alt+C)", extensions[0].tooltip)
        assertEquals("Regex (Alt+R)", extensions[1].tooltip)
        assertTrue(extensions.all { it.isFocusable })
    }
}
