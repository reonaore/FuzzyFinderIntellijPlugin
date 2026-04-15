package com.github.reonaore.fuzzyfinderintellijplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class FuzzyFinderSettingsConfigurable : Configurable {

    private val fdPathField = JBTextField()
    private val fzfPathField = JBTextField()

    override fun getDisplayName(): String = "Fuzzy Finder"

    override fun createComponent(): JComponent {
        val settings = FuzzyFinderSettingsService.getInstance()
        resetFields(settings.state)

        return JPanel(GridBagLayout()).apply {
            val constraints = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = Insets(4, 0, 4, 0)
            }

            add(JLabel("fd executable path"), constraints)
            constraints.gridy++
            add(fdPathField, constraints)
            constraints.gridy++
            add(JLabel("Leave blank to use 'fd' from PATH."), constraints)

            constraints.gridy++
            constraints.insets = Insets(12, 0, 4, 0)
            add(JLabel("fzf executable path"), constraints)
            constraints.gridy++
            constraints.insets = Insets(4, 0, 4, 0)
            add(fzfPathField, constraints)
            constraints.gridy++
            add(JLabel("Leave blank to use 'fzf' from PATH."), constraints)
        }
    }

    override fun isModified(): Boolean {
        val state = FuzzyFinderSettingsService.getInstance().state
        return fdPathField.text != state.fdExecutablePath || fzfPathField.text != state.fzfExecutablePath
    }

    override fun apply() {
        val state = FuzzyFinderSettingsService.getInstance().state
        state.fdExecutablePath = fdPathField.text.trim()
        state.fzfExecutablePath = fzfPathField.text.trim()
    }

    override fun reset() {
        resetFields(FuzzyFinderSettingsService.getInstance().state)
    }

    private fun resetFields(state: FuzzyFinderSettingsState) {
        fdPathField.text = state.fdExecutablePath
        fzfPathField.text = state.fzfExecutablePath
    }
}
