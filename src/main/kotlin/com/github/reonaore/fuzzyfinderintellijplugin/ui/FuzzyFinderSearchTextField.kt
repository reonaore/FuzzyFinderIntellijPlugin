package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import javax.swing.event.DocumentEvent

fun fuzzyFinderSearchTextField(
    onTextChanged: ((event: DocumentEvent) -> Unit)? = null,
): SearchTextField {
    return SearchTextField().also { field ->
        onTextChanged?.let {
            field.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    it(e)
                }
            })
        }
    }
}
