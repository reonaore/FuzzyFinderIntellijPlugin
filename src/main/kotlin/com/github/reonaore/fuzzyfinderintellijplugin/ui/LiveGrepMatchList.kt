package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionEvent

typealias GrepMatchList = CollectionListModel<GrepListItem>

data class GrepListItem(
    val match: GrepMatch,
    val fileName: String,
    val secondaryPath: String?,
    val location: String,
    val lineText: String,
    val icon: Icon,
)

fun GrepMatch.toGrepListItem(basePath: String?): GrepListItem {
    return GrepListItem(
        match = this,
        fileName = path.fileName?.toString().orEmpty().ifBlank { path.relativePathFrom(basePath) },
        secondaryPath = path.relativeParentPath(basePath),
        location = "$line:$column",
        lineText = lineText.trim(),
        icon = path.fileIcon(),
    )
}

fun liveGrepMatchList(
    data: GrepMatchList = GrepMatchList(),
    onCellSelected: ((ListSelectionEvent) -> Unit)? = null,
    onCellClicked: ((MouseEvent) -> Unit)? = null,
): JBList<GrepListItem> {
    val component = JBList(data).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = GrepListItemRenderer()
        onCellSelected?.let {
            addListSelectionListener(it)
        }
        onCellClicked?.let {
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = it(e)
            })
        }
    }
    return component
}

private class GrepListItemRenderer : ListCellRenderer<GrepListItem> {
    private val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
    private val iconLabel = JLabel()
    private val textPanel = JPanel(GridBagLayout())
    private val fileNameLabel = com.intellij.ui.SimpleColoredComponent()
    private val lineTextLabel = com.intellij.ui.SimpleColoredComponent()

    init {
        textPanel.isOpaque = false
        panel.border = JBUI.Borders.empty(3, 6)
        panel.add(iconLabel, BorderLayout.WEST)
        panel.add(textPanel, BorderLayout.CENTER)
        textPanel.add(
            fileNameLabel,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            },
        )
        textPanel.add(
            lineTextLabel,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 1
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            },
        )
    }

    override fun getListCellRendererComponent(
        list: JList<out GrepListItem>,
        value: GrepListItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): JPanel {
        val background = if (isSelected) list.selectionBackground else list.background
        val primaryForeground = if (isSelected) list.selectionForeground else list.foreground
        val secondaryForeground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()

        panel.isOpaque = true
        panel.background = background
        iconLabel.icon = value.icon
        iconLabel.isOpaque = false
        iconLabel.background = background
        textPanel.background = background
        textPanel.isOpaque = false

        fileNameLabel.clear()
        fileNameLabel.append(value.fileName, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, primaryForeground))
        fileNameLabel.append(" ${value.location}", SimpleTextAttributes(SimpleTextAttributes.STYLE_SEARCH_MATCH, primaryForeground))
        value.secondaryPath?.let { secondaryPath ->
            fileNameLabel.append(" $secondaryPath", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondaryForeground))
        }

        lineTextLabel.clear()
        lineTextLabel.append(value.lineText, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondaryForeground))
        return panel
    }
}
