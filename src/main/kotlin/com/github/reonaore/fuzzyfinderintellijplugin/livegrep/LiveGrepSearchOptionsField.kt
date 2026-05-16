package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import com.intellij.icons.AllIcons
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import javax.swing.Icon

class LiveGrepSearchOptionsField(
    placeholderText: String,
) {
    private var onOptionsChangedCallback: () -> Unit = {}
    private var smartCaseSelected = true
    private var regexSelected = false

    val textField = ExtendableTextField().apply {
        emptyText.text = placeholderText
        addExtension(toggleExtension(AllIcons.Actions.MatchCase, AllIcons.Actions.MatchCaseHovered, AllIcons.Actions.MatchCaseSelected, SMART_CASE_TOOLTIP, { smartCaseSelected }) {
            smartCaseSelected = !smartCaseSelected
        })
        addExtension(toggleExtension(AllIcons.Actions.Regex, AllIcons.Actions.RegexHovered, AllIcons.Actions.RegexSelected, REGEX_TOOLTIP, { regexSelected }) {
            regexSelected = !regexSelected
        })
    }

    fun setOnOptionsChanged(onOptionsChanged: () -> Unit) {
        onOptionsChangedCallback = onOptionsChanged
    }

    fun isSmartCaseSelected(): Boolean = smartCaseSelected

    fun currentQueryMode(): LiveGrepQueryMode {
        return if (regexSelected) LiveGrepQueryMode.REGEX else LiveGrepQueryMode.WORDS
    }

    fun toggleSmartCase() {
        smartCaseSelected = !smartCaseSelected
        notifyChanged()
    }

    fun toggleRegex() {
        regexSelected = !regexSelected
        notifyChanged()
    }

    internal fun extensions(): List<ExtendableTextComponent.Extension> = textField.extensions

    private fun toggleExtension(
        defaultIcon: Icon,
        hoveredIcon: Icon,
        selectedIcon: Icon,
        tooltip: String,
        isSelected: () -> Boolean,
        onClick: () -> Unit,
    ): ExtendableTextComponent.Extension {
        return object : ExtendableTextComponent.Extension {
            override fun getIcon(hovered: Boolean): Icon {
                return when {
                    isSelected() -> selectedIcon
                    hovered -> hoveredIcon
                    else -> defaultIcon
                }
            }

            override fun getTooltip(): String = tooltip

            override fun isSelected(): Boolean = isSelected()

            override fun isFocusable(): Boolean = true

            override fun getActionOnClick(): Runnable = Runnable {
                onClick()
                notifyChanged()
            }
        }
    }

    private fun notifyChanged() {
        onOptionsChangedCallback()
        textField.repaint()
    }

    private companion object {
        const val SMART_CASE_TOOLTIP = "Smart case (Alt+C)"
        const val REGEX_TOOLTIP = "Regex (Alt+R)"
    }
}
