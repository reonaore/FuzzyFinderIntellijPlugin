package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.FdEntryType
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import java.awt.FlowLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class FuzzyFinderOptionsPanel(
    private val onOptionsChanged: () -> Unit,
) {
    private val typeComboBox = JComboBox(FdEntryType.entries.toTypedArray())
    private val includeHiddenCheckBox = JCheckBox("Hidden")
    private val followSymlinksCheckBox = JCheckBox("Follow symlinks")
    private val respectGitIgnoreCheckBox = JCheckBox("Respect .gitignore")
    private val excludeField = JBTextField(DEFAULT_EXCLUDES)

    init {
        typeComboBox.selectedItem = FdEntryType.FILES
        followSymlinksCheckBox.isSelected = true
        respectGitIgnoreCheckBox.isSelected = true

        typeComboBox.addActionListener { onOptionsChanged() }
        includeHiddenCheckBox.addActionListener { onOptionsChanged() }
        followSymlinksCheckBox.addActionListener { onOptionsChanged() }
        respectGitIgnoreCheckBox.addActionListener { onOptionsChanged() }
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
            excludePatterns = excludeField.text
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty),
        )
    }

    private companion object {
        const val DEFAULT_EXCLUDES = ".git"
    }
}
