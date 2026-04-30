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

fun onTextChanged(field: SearchTextField, onTextChanged: (event: DocumentEvent) -> Unit) {
    field.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
            onTextChanged(e)
        }
    })
}
