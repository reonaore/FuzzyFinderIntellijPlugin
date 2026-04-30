package com.github.reonaore.fuzzyfinderintellijplugin.filefinder

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import java.awt.GridBagLayout
import java.awt.event.KeyEvent
import javax.swing.JPanel
import org.junit.Test

class FuzzyFinderOptionsPanelTest {

    @Test
    fun togglingIncludeHiddenUpdatesSearchOptionsAndNotifiesListener() {
        var changes = 0
        val panel = FuzzyFinderOptionsPanel().apply { setOnOptionsChanged { changes++ } }

        assertFalse(panel.currentOptions().includeHidden)

        panel.toggleIncludeHidden()

        assertTrue(panel.currentOptions().includeHidden)
        assertEquals(1, changes)
    }

    @Test
    fun togglingFollowSymlinksUpdatesSearchOptionsAndNotifiesListener() {
        var changes = 0
        val panel = FuzzyFinderOptionsPanel().apply { setOnOptionsChanged { changes++ } }

        assertTrue(panel.currentOptions().followSymlinks)

        panel.toggleFollowSymlinks()

        assertFalse(panel.currentOptions().followSymlinks)
        assertEquals(1, changes)
    }

    @Test
    fun togglingRespectGitIgnoreUpdatesSearchOptionsAndNotifiesListener() {
        var changes = 0
        val panel = FuzzyFinderOptionsPanel().apply { setOnOptionsChanged { changes++ } }

        assertTrue(panel.currentOptions().respectGitIgnore)

        panel.toggleRespectGitIgnore()

        assertFalse(panel.currentOptions().respectGitIgnore)
        assertEquals(1, changes)
    }

    @Test
    fun parsesCommaSeparatedExtensionFilters() {
        val panel = FuzzyFinderOptionsPanel()

        panel.setExtensionsText(" kt, .java,  ,md ")

        assertEquals(listOf("kt", ".java", "md"), panel.currentOptions().includeExtensions)
    }

    @Test
    fun notifiesWhenExtensionFiltersChange() {
        var changes = 0
        val panel = FuzzyFinderOptionsPanel().apply { setOnOptionsChanged { changes++ } }

        panel.setExtensionsText("kt")

        assertEquals("kt", panel.extensionsText())
        assertEquals(1, changes)
    }

    @Test
    fun parsesCommaSeparatedExcludeFilters() {
        val panel = FuzzyFinderOptionsPanel()

        panel.setExcludeText(" build, out,  ,target ")

        assertEquals(listOf("build", "out", "target"), panel.currentOptions().excludePatterns)
    }

    @Test
    fun notifiesWhenExcludeFiltersChange() {
        var changes = 0
        val panel = FuzzyFinderOptionsPanel().apply { setOnOptionsChanged { changes++ } }

        panel.setExcludeText("build")

        assertEquals("build", panel.excludeText())
        assertTrue(changes > 0)
    }

    @Test
    fun excludeFilterFieldIsEditable() {
        val panel = FuzzyFinderOptionsPanel()

        assertTrue(panel.excludeFieldIsEditable())
    }

    @Test
    fun checkboxLabelsUnderlineTheShortcutCharacter() {
        val panel = FuzzyFinderOptionsPanel()

        assertEquals("<html><u>H</u>idden</html>", panel.includeHiddenLabelText())
        assertEquals("<html>Follow <u>s</u>ymlinks</html>", panel.followSymlinksLabelText())
        assertEquals("<html>.<u>g</u>itignore</html>", panel.respectGitIgnoreLabelText())
    }

    @Test
    fun filterLabelsUnderlineTheShortcutCharacter() {
        val panel = FuzzyFinderOptionsPanel()

        assertEquals("<html><u>E</u>xtensions</html>", panel.extensionsLabelText())
        assertEquals("<html>E<u>x</u>clude</html>", panel.excludeLabelText())
    }

    @Test
    fun checkboxTooltipsShowExplicitAltShortcut() {
        val panel = FuzzyFinderOptionsPanel()

        assertNotNull(panel.includeHiddenTooltipText())
        assertEquals("Alt+H", panel.includeHiddenTooltipText())
        assertEquals("Alt+S", panel.followSymlinksTooltipText())
        assertEquals("Alt+G", panel.respectGitIgnoreTooltipText())
    }

    @Test
    fun filterLabelsFocusTheirFieldsWithAltShortcuts() {
        val panel = FuzzyFinderOptionsPanel()

        assertEquals(KeyEvent.VK_E, panel.extensionsLabelMnemonic())
        assertSame(panel.extensionsFieldComponent(), panel.extensionsLabelTarget())
        assertEquals("Alt+E", panel.extensionsTooltipText())
        assertEquals(KeyEvent.VK_X, panel.excludeLabelMnemonic())
        assertSame(panel.excludeFieldComponent(), panel.excludeLabelTarget())
        assertEquals("Alt+X", panel.excludeTooltipText())
    }

    @Test
    fun placesFilterFieldsAboveScopeOptions() {
        val panel = FuzzyFinderOptionsPanel()
        val component = panel.component() as JPanel
        val layout = component.layout as GridBagLayout

        assertEquals(0, layout.getConstraints(panel.extensionsFieldComponent()).gridy)
        assertEquals(0, layout.getConstraints(panel.excludeFieldComponent()).gridy)
        assertEquals(1, layout.getConstraints(panel.includeHiddenComponent()).gridy)
    }
}
