package com.github.reonaore.fuzzyfinderintellijplugin.shared.ui

import com.intellij.ui.components.JBList
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane
import org.junit.Test

class CandidateListLoadingPanelTest {

    @Test
    fun usesNoResultsAsDefaultInitialEmptyText() {
        val list = JBList<String>()

        candidatePanel(list)

        assertEquals("No results", list.emptyText.text)
    }

    @Test
    fun usesCustomInitialEmptyTextBeforeSearchRuns() {
        val list = JBList<String>()

        candidatePanel(list, initialEmptyText = "Type to search project text.")

        assertEquals("Type to search project text.", list.emptyText.text)
    }

    @Test
    fun showsSearchingForEmptyCandidateList() {
        val panel = candidatePanel()

        panel.showSearching(hasExistingCandidates = false)

        assertEquals(CandidateListLoadingState.Searching(false), panel.currentState())
        assertTrue(panel.overlayIsVisible())
        assertEquals("Searching...", panel.overlayText())
        assertTrue(panel.spinnerIsVisible())
    }

    @Test
    fun showsUpdatingForExistingCandidateList() {
        val panel = candidatePanel()

        panel.showSearching(hasExistingCandidates = true)

        assertEquals(CandidateListLoadingState.Searching(true), panel.currentState())
        assertTrue(panel.overlayIsVisible())
        assertEquals("Updating results...", panel.overlayText())
        assertTrue(panel.spinnerIsVisible())
    }

    @Test
    fun hidesOverlayAndKeepsNoResultsEmptyTextAfterResultsApply() {
        val list = JBList<String>()
        val panel = candidatePanel(list, initialEmptyText = "Type to search project text.")

        panel.showSearching(hasExistingCandidates = false)
        panel.showResults(hasCandidates = false)

        assertEquals(CandidateListLoadingState.Idle, panel.currentState())
        assertFalse(panel.overlayIsVisible())
        assertEquals("No results", list.emptyText.text)
    }

    @Test
    fun restoresCustomInitialEmptyTextWhenReturningToIdlePrompt() {
        val list = JBList<String>()
        val panel = candidatePanel(list, initialEmptyText = "Type to search project text.")

        panel.showSearching(hasExistingCandidates = false)
        panel.showInitialEmptyText()

        assertEquals(CandidateListLoadingState.Idle, panel.currentState())
        assertFalse(panel.overlayIsVisible())
        assertFalse(panel.spinnerIsVisible())
        assertEquals("Type to search project text.", list.emptyText.text)
    }

    @Test
    fun showsErrorWithoutSpinner() {
        val list = JBList<String>()
        val panel = candidatePanel(list)

        panel.showError()

        assertEquals(CandidateListLoadingState.Error, panel.currentState())
        assertTrue(panel.overlayIsVisible())
        assertEquals("Search failed", panel.overlayText())
        assertFalse(panel.spinnerIsVisible())
        assertEquals("Search failed", list.emptyText.text)
    }

    private fun candidatePanel(
        list: JBList<String> = JBList(),
        initialEmptyText: String = "No results",
    ): CandidateListLoadingPanel {
        return CandidateListLoadingPanel(list, JScrollPane(list), FakeCandidateBusyIndicator(), initialEmptyText)
    }

    private class FakeCandidateBusyIndicator : CandidateBusyIndicator {
        override val component: JComponent = JLabel()

        override fun start() {
            component.isVisible = true
        }

        override fun stop() {
            component.isVisible = false
        }

        override fun dispose() = Unit
    }
}
