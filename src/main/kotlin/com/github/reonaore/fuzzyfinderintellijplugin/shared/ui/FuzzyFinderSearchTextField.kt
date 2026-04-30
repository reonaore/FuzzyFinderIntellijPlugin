package com.github.reonaore.fuzzyfinderintellijplugin.shared.ui

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import javax.swing.event.DocumentEvent

fun fuzzyFinderSearchTextField(
    placeHolderText: String? = null,
): SearchTextField {
    return SearchTextField().also { field ->
        placeHolderText?.also { field.textEditor.emptyText.text = it }
    }
}

fun SearchTextField.onTextChanged(onTextChanged: (event: DocumentEvent) -> Unit) {
    addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
            onTextChanged(e)
        }
    })
}
