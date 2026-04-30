package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchOptions
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class LiveGrepOptionsPanel {
    private var onOptionsChangedCallback: () -> Unit = {}
    private val includeHiddenCheckBox = JCheckBox(FuzzyFinderOptionsPanel.mnemonicLabel("Hidden", 'H'))
    private val followSymlinksCheckBox = JCheckBox(FuzzyFinderOptionsPanel.mnemonicLabel("Follow symlinks", 's'))
    private val respectGitIgnoreCheckBox = JCheckBox(FuzzyFinderOptionsPanel.mnemonicLabel(".gitignore", 'g'))
    private val smartCaseCheckBox = JCheckBox(FuzzyFinderOptionsPanel.mnemonicLabel("Smart case", 'c'))
    private val extensionsField = JBTextField()
    private val excludeField = JBTextField(DEFAULT_EXCLUDES)
    private val extensionsLabel = JLabel(FuzzyFinderOptionsPanel.mnemonicLabel("Extensions", 'E')).apply {
        displayedMnemonic = KeyEvent.VK_E
        labelFor = extensionsField
        toolTipText = ALT_E_TOOLTIP
    }
    private val excludeLabel = JLabel(FuzzyFinderOptionsPanel.mnemonicLabel("Exclude", 'x')).apply {
        displayedMnemonic = KeyEvent.VK_X
        labelFor = excludeField
        toolTipText = ALT_X_TOOLTIP
    }

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

        includeHiddenCheckBox.addActionListener { onOptionsChangedCallback() }
        followSymlinksCheckBox.addActionListener { onOptionsChangedCallback() }
        respectGitIgnoreCheckBox.addActionListener { onOptionsChangedCallback() }
        smartCaseCheckBox.addActionListener { onOptionsChangedCallback() }
        extensionsField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                onOptionsChangedCallback()
            }
        })
        excludeField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                onOptionsChangedCallback()
            }
        })
    }

    fun setOnOptionsChanged(onOptionsChanged: () -> Unit) {
        this.onOptionsChangedCallback = onOptionsChanged
    }

    fun component(): JComponent {
        return JPanel(GridBagLayout()).apply {
            extensionsField.columns = 12
            excludeField.columns = 20

            addOptionComponent(extensionsLabel, gridx = 0, gridy = 0)
            addOptionComponent(extensionsField, gridx = 1, gridy = 0)
            addOptionComponent(excludeLabel, gridx = 2, gridy = 0)
            addOptionComponent(
                excludeField,
                gridx = 3,
                gridy = 0,
                weightx = 1.0,
                fill = GridBagConstraints.HORIZONTAL,
            )
            addOptionComponent(includeHiddenCheckBox, gridx = 0, gridy = 1, topInset = 4)
            addOptionComponent(followSymlinksCheckBox, gridx = 1, gridy = 1, topInset = 4)
            addOptionComponent(respectGitIgnoreCheckBox, gridx = 2, gridy = 1, topInset = 4)
            addOptionComponent(smartCaseCheckBox, gridx = 3, gridy = 1, weightx = 1.0, topInset = 4)
        }
    }

    fun currentOptions(): GrepSearchOptions {
        return GrepSearchOptions(
            includeHidden = includeHiddenCheckBox.isSelected,
            followSymlinks = followSymlinksCheckBox.isSelected,
            respectGitIgnore = respectGitIgnoreCheckBox.isSelected,
            smartCase = smartCaseCheckBox.isSelected,
            includeExtensions = parseCommaSeparatedText(extensionsField.text),
            excludePatterns = parseCommaSeparatedText(excludeField.text),
        )
    }

    private fun parseCommaSeparatedText(text: String): List<String> {
        return text
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    internal fun setExtensionsText(text: String) {
        extensionsField.text = text
    }

    internal fun extensionsText(): String = extensionsField.text

    internal fun setExcludeText(text: String) {
        excludeField.text = text
    }

    internal fun excludeText(): String = excludeField.text

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

    internal fun includeHiddenComponent(): JComponent = includeHiddenCheckBox

    internal fun followSymlinksLabelText(): String = followSymlinksCheckBox.text

    internal fun respectGitIgnoreLabelText(): String = respectGitIgnoreCheckBox.text

    internal fun smartCaseLabelText(): String = smartCaseCheckBox.text

    internal fun smartCaseTooltipText(): String? = smartCaseCheckBox.toolTipText

    internal fun extensionsLabelText(): String = extensionsLabel.text

    internal fun extensionsLabelMnemonic(): Int = extensionsLabel.displayedMnemonic

    internal fun extensionsLabelTarget(): JComponent? = extensionsLabel.labelFor as? JComponent

    internal fun extensionsFieldComponent(): JComponent = extensionsField

    internal fun extensionsTooltipText(): String? = extensionsLabel.toolTipText

    internal fun excludeLabelText(): String = excludeLabel.text

    internal fun excludeLabelMnemonic(): Int = excludeLabel.displayedMnemonic

    internal fun excludeLabelTarget(): JComponent? = excludeLabel.labelFor as? JComponent

    internal fun excludeFieldComponent(): JComponent = excludeField

    internal fun excludeTooltipText(): String? = excludeLabel.toolTipText

    private companion object {
        const val DEFAULT_EXCLUDES = ".git"
        const val ALT_H_TOOLTIP = "Alt+H"
        const val ALT_S_TOOLTIP = "Alt+S"
        const val ALT_G_TOOLTIP = "Alt+G"
        const val ALT_C_TOOLTIP = "Alt+C"
        const val ALT_E_TOOLTIP = "Alt+E"
        const val ALT_X_TOOLTIP = "Alt+X"
    }
}

private fun JPanel.addOptionComponent(
    component: JComponent,
    gridx: Int,
    gridy: Int,
    weightx: Double = 0.0,
    fill: Int = GridBagConstraints.NONE,
    topInset: Int = 0,
) {
    add(
        component,
        GridBagConstraints().apply {
            this.gridx = gridx
            this.gridy = gridy
            this.weightx = weightx
            this.fill = fill
            anchor = GridBagConstraints.WEST
            insets = Insets(topInset, 0, 0, 8)
        },
    )
}
