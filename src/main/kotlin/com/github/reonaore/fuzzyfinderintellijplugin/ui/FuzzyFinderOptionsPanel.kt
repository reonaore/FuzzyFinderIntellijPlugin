package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.FdEntryType
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import java.awt.FlowLayout
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
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JLabel("Type"))
            add(typeComboBox)
            add(includeHiddenCheckBox)
            add(followSymlinksCheckBox)
            add(respectGitIgnoreCheckBox)
            add(JLabel("Extensions"))
            extensionsField.columns = 12
            add(extensionsField)
            add(JLabel("Exclude"))
            excludeField.columns = 20
            add(excludeField)
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

    internal fun followSymlinksLabelText(): String = followSymlinksCheckBox.text

    internal fun respectGitIgnoreLabelText(): String = respectGitIgnoreCheckBox.text

    internal fun includeHiddenTooltipText(): String? = includeHiddenCheckBox.toolTipText

    internal fun followSymlinksTooltipText(): String? = followSymlinksCheckBox.toolTipText

    internal fun respectGitIgnoreTooltipText(): String? = respectGitIgnoreCheckBox.toolTipText

    internal fun setExtensionsText(text: String) {
        extensionsField.text = text
    }

    internal fun extensionsText(): String = extensionsField.text

    companion object {
        const val DEFAULT_EXCLUDES = ".git"
        const val ALT_H_TOOLTIP = "Alt+H"
        const val ALT_S_TOOLTIP = "Alt+S"
        const val ALT_G_TOOLTIP = "Alt+G"

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
