package com.github.reonaore.fuzzyfinderintellijplugin.shared.ui

import junit.framework.TestCase.assertEquals
import org.junit.Test

class FuzzyFinderSearchTextFieldTest {

    @Test
    fun onTextChangedIsCalledWhenTextChanges() {
        var changes = 0
        val field = fuzzyFinderSearchTextField(placeHolderText = "Search")

        onTextChanged(field) { changes++ }
        field.text = "abc"

        assertEquals(1, changes)
    }
}
