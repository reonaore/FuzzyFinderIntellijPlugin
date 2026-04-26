package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchOptions
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class LiveGrepOptionsPanel(
    private val onOptionsChanged: () -> Unit,
) {
    private val includeHiddenCheckBox = JCheckBox(FuzzyFinderOptionsPanel.mnemonicLabel("Hidden", 'H'))
    private val followSymlinksCheckBox = JCheckBox(FuzzyFinderOptionsPanel.mnemonicLabel("Follow symlinks", 's'))
    private val respectGitIgnoreCheckBox = JCheckBox(FuzzyFinderOptionsPanel.mnemonicLabel(".gitignore", 'g'))
    private val smartCaseCheckBox = JCheckBox(FuzzyFinderOptionsPanel.mnemonicLabel("Smart case", 'c'))
    private val excludeField = JBTextField(DEFAULT_EXCLUDES)

    init {
        followSymlinksCheckBox.isSelected = true
        respectGitIgnoreCheckBox.isSelected = true
        smartCaseCheckBox.isSelected = true
        includeHiddenCheckBox.mnemonic = KeyEvent.VK_H
        followSymlinksCheckBox.mnemonic = KeyEvent.VK_S
        respectGitIgnoreCheckBox.mnemonic = KeyEvent.VK_G
        smartCaseCheckBox.mnemonic = KeyEvent.VK_C
        includeHiddenCheckBox.toolTipText = ALT_H_TOOLTIP
        followSymlinksCheckBox.toolTipText = ALT_S_TOOLTIP
        respectGitIgnoreCheckBox.toolTipText = ALT_G_TOOLTIP
        smartCaseCheckBox.toolTipText = ALT_C_TOOLTIP

        includeHiddenCheckBox.addActionListener { onOptionsChanged() }
        followSymlinksCheckBox.addActionListener { onOptionsChanged() }
        respectGitIgnoreCheckBox.addActionListener { onOptionsChanged() }
        smartCaseCheckBox.addActionListener { onOptionsChanged() }
        excludeField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                onOptionsChanged()
            }
        })
    }

    fun component(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(includeHiddenCheckBox)
            add(followSymlinksCheckBox)
            add(respectGitIgnoreCheckBox)
            add(smartCaseCheckBox)
            add(JLabel("Exclude"))
            excludeField.columns = 20
            add(excludeField)
        }
    }

    fun currentOptions(): GrepSearchOptions {
        return GrepSearchOptions(
            includeHidden = includeHiddenCheckBox.isSelected,
            followSymlinks = followSymlinksCheckBox.isSelected,
            respectGitIgnore = respectGitIgnoreCheckBox.isSelected,
            smartCase = smartCaseCheckBox.isSelected,
            excludePatterns = excludeField.text
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty),
        )
    }

    fun toggleIncludeHidden() {
        toggle(includeHiddenCheckBox)
    }

    fun toggleFollowSymlinks() {
        toggle(followSymlinksCheckBox)
    }

    fun toggleRespectGitIgnore() {
        toggle(respectGitIgnoreCheckBox)
    }

    fun toggleSmartCase() {
        toggle(smartCaseCheckBox)
    }

    private fun toggle(checkBox: JCheckBox) {
        checkBox.doClick(0)
    }

    internal fun includeHiddenLabelText(): String = includeHiddenCheckBox.text

    internal fun followSymlinksLabelText(): String = followSymlinksCheckBox.text

    internal fun respectGitIgnoreLabelText(): String = respectGitIgnoreCheckBox.text

    internal fun smartCaseLabelText(): String = smartCaseCheckBox.text

    internal fun smartCaseTooltipText(): String? = smartCaseCheckBox.toolTipText

    private companion object {
        const val DEFAULT_EXCLUDES = ".git"
        const val ALT_H_TOOLTIP = "Alt+H"
        const val ALT_S_TOOLTIP = "Alt+S"
        const val ALT_G_TOOLTIP = "Alt+G"
        const val ALT_C_TOOLTIP = "Alt+C"
    }
}
