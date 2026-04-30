package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import java.awt.GridBagLayout
import java.awt.event.KeyEvent
import javax.swing.JPanel
import org.junit.Test

class LiveGrepOptionsPanelTest {

    @Test
    fun defaultsToSmartCaseAndProjectFriendlyScopeOptions() {
        val panel = LiveGrepOptionsPanel()
        val options = panel.currentOptions()

        assertFalse(options.includeHidden)
        assertTrue(options.followSymlinks)
        assertTrue(options.respectGitIgnore)
        assertTrue(options.smartCase)
        assertEquals(emptyList<String>(), options.includeExtensions)
        assertEquals(listOf(".git"), options.excludePatterns)
    }

    @Test
    fun parsesCommaSeparatedExtensionFilters() {
        val panel = LiveGrepOptionsPanel()

        panel.setExtensionsText(" kt, .java,  ,md ")

        assertEquals(listOf("kt", ".java", "md"), panel.currentOptions().includeExtensions)
    }

    @Test
    fun notifiesWhenExtensionFiltersChange() {
        var changes = 0
        val panel = LiveGrepOptionsPanel()
        panel.setOnOptionsChanged { changes++ }

        panel.setExtensionsText("kt")

        assertEquals(listOf("kt"), panel.currentOptions().includeExtensions)
        assertEquals(1, changes)
    }

    @Test
    fun togglesSmartCaseAndNotifiesChanges() {
        var changes = 0
        val panel = LiveGrepOptionsPanel()
        panel.setOnOptionsChanged { changes++ }

        panel.toggleSmartCase()

        assertFalse(panel.currentOptions().smartCase)
        assertEquals(1, changes)
    }

    @Test
    fun exposesMnemonicLabelAndTooltipText() {
        val panel = LiveGrepOptionsPanel()

        assertTrue(panel.smartCaseLabelText().contains("<u>c</u>"))
        assertEquals("Alt+C", panel.smartCaseTooltipText())
    }

    @Test
    fun filterLabelsUnderlineTheShortcutCharacter() {
        val panel = LiveGrepOptionsPanel()

        assertEquals("<html><u>E</u>xtensions</html>", panel.extensionsLabelText())
        assertEquals("<html>E<u>x</u>clude</html>", panel.excludeLabelText())
    }

    @Test
    fun filterLabelsFocusTheirFieldsWithAltShortcuts() {
        val panel = LiveGrepOptionsPanel()

        assertEquals(KeyEvent.VK_E, panel.extensionsLabelMnemonic())
        assertSame(panel.extensionsFieldComponent(), panel.extensionsLabelTarget())
        assertEquals("Alt+E", panel.extensionsTooltipText())
        assertEquals(KeyEvent.VK_X, panel.excludeLabelMnemonic())
        assertSame(panel.excludeFieldComponent(), panel.excludeLabelTarget())
        assertEquals("Alt+X", panel.excludeTooltipText())
    }

    @Test
    fun placesFilterFieldsAboveScopeCheckboxes() {
        val panel = LiveGrepOptionsPanel()
        val component = panel.component() as JPanel
        val layout = component.layout as GridBagLayout

        assertEquals(0, layout.getConstraints(panel.extensionsFieldComponent()).gridy)
        assertEquals(0, layout.getConstraints(panel.excludeFieldComponent()).gridy)
        assertEquals(1, layout.getConstraints(panel.includeHiddenComponent()).gridy)
    }
}
