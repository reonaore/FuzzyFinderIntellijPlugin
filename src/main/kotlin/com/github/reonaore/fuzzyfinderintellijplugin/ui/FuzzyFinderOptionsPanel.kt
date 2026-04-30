package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.FdEntryType
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class FuzzyFinderOptionsPanel(
    private val onOptionsChanged: () -> Unit,
) {
    private val typeComboBox = JComboBox(FdEntryType.entries.toTypedArray())
    private val includeHiddenCheckBox = JCheckBox(mnemonicLabel("Hidden", 'H'))
    private val followSymlinksCheckBox = JCheckBox(mnemonicLabel("Follow symlinks", 's'))
    private val respectGitIgnoreCheckBox = JCheckBox(mnemonicLabel(".gitignore", 'g'))
    private val extensionsField = JBTextField()
    private val excludeField = JBTextField(DEFAULT_EXCLUDES)
    private val extensionsLabel = JLabel(mnemonicLabel("Extensions", 'E')).apply {
        displayedMnemonic = KeyEvent.VK_E
        labelFor = extensionsField
        toolTipText = ALT_E_TOOLTIP
    }
    private val excludeLabel = JLabel(mnemonicLabel("Exclude", 'x')).apply {
        displayedMnemonic = KeyEvent.VK_X
        labelFor = excludeField
        toolTipText = ALT_X_TOOLTIP
    }

    init {
        typeComboBox.selectedItem = FdEntryType.FILES
        followSymlinksCheckBox.isSelected = true
        respectGitIgnoreCheckBox.isSelected = true
        includeHiddenCheckBox.mnemonic = KeyEvent.VK_H
        followSymlinksCheckBox.mnemonic = KeyEvent.VK_S
        respectGitIgnoreCheckBox.mnemonic = KeyEvent.VK_G
        includeHiddenCheckBox.toolTipText = ALT_H_TOOLTIP
        followSymlinksCheckBox.toolTipText = ALT_S_TOOLTIP
        respectGitIgnoreCheckBox.toolTipText = ALT_G_TOOLTIP

        typeComboBox.addActionListener { onOptionsChanged() }
        includeHiddenCheckBox.addActionListener { onOptionsChanged() }
        followSymlinksCheckBox.addActionListener { onOptionsChanged() }
        respectGitIgnoreCheckBox.addActionListener { onOptionsChanged() }
        extensionsField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                onOptionsChanged()
            }
        })
        excludeField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                onOptionsChanged()
            }
        })
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
                gridwidth = 2,
                weightx = 1.0,
                fill = GridBagConstraints.HORIZONTAL,
            )
            addOptionComponent(JLabel("Type"), gridx = 0, gridy = 1, topInset = 4)
            addOptionComponent(typeComboBox, gridx = 1, gridy = 1, topInset = 4)
            addOptionComponent(includeHiddenCheckBox, gridx = 2, gridy = 1, topInset = 4)
            addOptionComponent(followSymlinksCheckBox, gridx = 3, gridy = 1, topInset = 4)
            addOptionComponent(
                respectGitIgnoreCheckBox,
                gridx = 4,
                gridy = 1,
                weightx = 1.0,
                topInset = 4,
            )
        }
    }

    fun currentOptions(): FdSearchOptions {
        return FdSearchOptions(
            entryType = typeComboBox.selectedItem as? FdEntryType ?: FdEntryType.FILES,
            includeHidden = includeHiddenCheckBox.isSelected,
            followSymlinks = followSymlinksCheckBox.isSelected,
            respectGitIgnore = respectGitIgnoreCheckBox.isSelected,
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

    fun toggleIncludeHidden() {
        toggle(includeHiddenCheckBox)
    }

    fun toggleFollowSymlinks() {
        toggle(followSymlinksCheckBox)
    }

    fun toggleRespectGitIgnore() {
        toggle(respectGitIgnoreCheckBox)
    }

    private fun toggle(checkBox: JCheckBox) {
        checkBox.doClick(0)
    }

    internal fun includeHiddenLabelText(): String = includeHiddenCheckBox.text

    internal fun includeHiddenComponent(): JComponent = includeHiddenCheckBox

    internal fun followSymlinksLabelText(): String = followSymlinksCheckBox.text

    internal fun respectGitIgnoreLabelText(): String = respectGitIgnoreCheckBox.text

    internal fun includeHiddenTooltipText(): String? = includeHiddenCheckBox.toolTipText

    internal fun followSymlinksTooltipText(): String? = followSymlinksCheckBox.toolTipText

    internal fun respectGitIgnoreTooltipText(): String? = respectGitIgnoreCheckBox.toolTipText

    internal fun setExtensionsText(text: String) {
        extensionsField.text = text
    }

    internal fun extensionsText(): String = extensionsField.text

    internal fun setExcludeText(text: String) {
        excludeField.text = text
    }

    internal fun excludeText(): String = excludeField.text

    internal fun excludeFieldIsEditable(): Boolean = excludeField.isEditable

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

    companion object {
        const val DEFAULT_EXCLUDES = ".git"
        const val ALT_H_TOOLTIP = "Alt+H"
        const val ALT_S_TOOLTIP = "Alt+S"
        const val ALT_G_TOOLTIP = "Alt+G"
        const val ALT_E_TOOLTIP = "Alt+E"
        const val ALT_X_TOOLTIP = "Alt+X"

        fun mnemonicLabel(label: String, mnemonicChar: Char): String {
            var underlined = false
            return buildString(label.length + 13) {
                append("<html>")
                label.forEach { char ->
                    val escaped = when (char) {
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '&' -> "&amp;"
                        else -> char.toString()
                    }
                    if (!underlined && char.equals(mnemonicChar, ignoreCase = true)) {
                        append("<u>")
                        append(escaped)
                        append("</u>")
                        underlined = true
                    } else {
                        append(escaped)
                    }
                }
                append("</html>")
            }
        }
    }
}

private fun JPanel.addOptionComponent(
    component: JComponent,
    gridx: Int,
    gridy: Int,
    gridwidth: Int = 1,
    weightx: Double = 0.0,
    fill: Int = GridBagConstraints.NONE,
    topInset: Int = 0,
) {
    add(
        component,
        GridBagConstraints().apply {
            this.gridx = gridx
            this.gridy = gridy
            this.gridwidth = gridwidth
            this.weightx = weightx
            this.fill = fill
            anchor = GridBagConstraints.WEST
            insets = Insets(topInset, 0, 0, 8)
        },
    )
}
