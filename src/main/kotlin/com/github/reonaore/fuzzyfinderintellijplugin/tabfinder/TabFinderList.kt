package com.github.reonaore.fuzzyfinderintellijplugin.tabfinder

import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.HighlightedTextComponent
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.event.ListSelectionEvent

typealias TabList = CollectionListModel<TabListItem>

fun tabFinderList(
    data: TabList = TabList(),
    onCellSelected: ((ListSelectionEvent) -> Unit)? = null,
    onCellClicked: ((MouseEvent) -> Unit)? = null,
): JBList<TabListItem> {
    return JBList(data).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = TabListItemRenderer()
        onCellSelected?.let { addListSelectionListener(it) }
        onCellClicked?.let {
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = it(e)
            })
        }
    }
}

private class TabListItemRenderer : ListCellRenderer<TabListItem> {
    private val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
    private val iconLabel = JLabel()
    private val textPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
    private val fileNameLabel = HighlightedTextComponent()
    private val secondaryPathLabel = JLabel()

    init {
        textPanel.isOpaque = false
        panel.border = JBUI.Borders.empty(2, 6)
        panel.add(iconLabel, BorderLayout.WEST)
        panel.add(textPanel, BorderLayout.CENTER)
        textPanel.add(fileNameLabel)
        textPanel.add(secondaryPathLabel)
    }

    override fun getListCellRendererComponent(
        list: JList<out TabListItem>,
        value: TabListItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): JPanel {
        val background = if (isSelected) list.selectionBackground else list.background
        val primaryForeground = if (isSelected) list.selectionForeground else list.foreground
        val secondaryForeground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()

        panel.isOpaque = true
        panel.background = background
        iconLabel.icon = value.candidate.file.fileType.icon
        secondaryPathLabel.text = value.candidate.secondaryPath?.let { " $it" }.orEmpty()
        secondaryPathLabel.foreground = secondaryForeground
        secondaryPathLabel.isVisible = value.candidate.secondaryPath != null
        fileNameLabel.foreground = primaryForeground
        fileNameLabel.background = background
        fileNameLabel.applyHighlight(value.candidate.fileName, value.highlightRanges, primaryForeground)
        return panel
    }
}
