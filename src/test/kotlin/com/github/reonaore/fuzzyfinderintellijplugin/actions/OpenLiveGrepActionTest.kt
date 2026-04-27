package com.github.reonaore.fuzzyfinderintellijplugin.actions

import junit.framework.TestCase.assertEquals
import org.junit.Test

class OpenLiveGrepActionTest {

    @Test
    fun usesSelectedTextAsInitialQuery() {
        assertEquals("needle", initialLiveGrepQueryFromSelection("needle"))
    }

    @Test
    fun trimsSelectedTextBeforeUsingItAsInitialQuery() {
        assertEquals("needle", initialLiveGrepQueryFromSelection("  needle  "))
    }

    @Test
    fun usesFirstNonBlankLineFromMultilineSelection() {
        assertEquals("needle", initialLiveGrepQueryFromSelection("\n  \n  needle  \nother"))
    }

    @Test
    fun returnsEmptyQueryWhenSelectionIsBlankOrMissing() {
        assertEquals("", initialLiveGrepQueryFromSelection(null))
        assertEquals("", initialLiveGrepQueryFromSelection(" \n\t "))
    }
}
