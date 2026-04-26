package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
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

sealed interface GrepListItem {
    val match: GrepMatch?
}

data class GrepFileHeaderItem(
    val fileName: String,
    val secondaryPath: String?,
    val matchCount: Int,
    val icon: Icon,
) : GrepListItem {
    override val match: GrepMatch? = null
}

data class GrepMatchItem(
    override val match: GrepMatch,
    val fileName: String,
    val secondaryPath: String?,
    val location: String,
    val lineText: String,
    val highlightRanges: List<TextRange>,
) : GrepListItem

fun List<GrepMatch>.toGroupedGrepListItems(basePath: String?): List<GrepListItem> {
    return groupBy(GrepMatch::path)
        .flatMap { (path, matches) ->
            val header = GrepFileHeaderItem(
                fileName = path.fileName?.toString().orEmpty().ifBlank { path.relativePathFrom(basePath) },
                secondaryPath = path.relativeParentPath(basePath),
                matchCount = matches.size,
                icon = path.fileIcon(),
            )
            listOf(header) + matches.map { match ->
                match.toGrepListItem(
                    fileName = header.fileName,
                    secondaryPath = header.secondaryPath,
                )
            }
        }
}

fun firstMatchIndex(items: List<GrepListItem>): Int {
    return items.indexOfFirst { it.match != null }
}

private fun GrepMatch.toGrepListItem(fileName: String, secondaryPath: String?): GrepMatchItem {
    return GrepMatchItem(
        match = this,
        fileName = fileName,
        secondaryPath = secondaryPath,
        location = "$line:$column",
        lineText = lineText,
        highlightRanges = matchRanges,
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
    private val lineTextLabel = HighlightedTextComponent()
    private val fileNameLabel = com.intellij.ui.SimpleColoredComponent()

    init {
        textPanel.isOpaque = false
        panel.border = JBUI.Borders.empty(3, 6)
        panel.add(iconLabel, BorderLayout.WEST)
        panel.add(textPanel, BorderLayout.CENTER)
        textPanel.add(
            lineTextLabel,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            },
        )
        textPanel.add(
            fileNameLabel,
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
        return when (value) {
            is GrepFileHeaderItem -> renderHeader(list, value, isSelected)
            is GrepMatchItem -> renderMatch(list, value, isSelected)
        }
    }

    private fun renderHeader(
        list: JList<out GrepListItem>,
        value: GrepFileHeaderItem,
        isSelected: Boolean,
    ): JPanel {
        val background = if (isSelected) list.selectionBackground else list.background
        val primaryForeground = if (isSelected) list.selectionForeground else list.foreground
        val secondaryForeground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()

        panel.isOpaque = true
        panel.background = background
        panel.border = JBUI.Borders.empty(4, 6, 2, 6)
        iconLabel.icon = value.icon
        iconLabel.isOpaque = false
        iconLabel.background = background
        textPanel.background = background
        textPanel.isOpaque = false

        lineTextLabel.clear()
        lineTextLabel.append(value.fileName, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, primaryForeground))
        val matchText = if (value.matchCount == 1) "1 match" else "${value.matchCount} matches"
        lineTextLabel.append(" $matchText", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondaryForeground))

        fileNameLabel.clear()
        value.secondaryPath?.let { secondaryPath ->
            fileNameLabel.append(secondaryPath, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondaryForeground))
        }

        return panel
    }

    private fun renderMatch(
        list: JList<out GrepListItem>,
        value: GrepMatchItem,
        isSelected: Boolean,
    ): JPanel {
        val background = if (isSelected) list.selectionBackground else list.background
        val primaryForeground = if (isSelected) list.selectionForeground else list.foreground
        val secondaryForeground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()

        panel.isOpaque = true
        panel.background = background
        panel.border = JBUI.Borders.empty(2, 30, 3, 6)
        iconLabel.icon = null
        iconLabel.isOpaque = false
        iconLabel.background = background
        textPanel.background = background
        textPanel.isOpaque = false

        lineTextLabel.applyHighlight(value.lineText, value.highlightRanges, primaryForeground)

        fileNameLabel.clear()
        fileNameLabel.append(value.location, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondaryForeground))
        fileNameLabel.append(" ${value.fileName}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondaryForeground))
        value.secondaryPath?.let { secondaryPath ->
            fileNameLabel.append(" $secondaryPath", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, secondaryForeground))
        }

        return panel
    }
}
