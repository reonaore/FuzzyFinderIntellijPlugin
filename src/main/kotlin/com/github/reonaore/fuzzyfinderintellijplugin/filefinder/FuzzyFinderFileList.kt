package com.github.reonaore.fuzzyfinderintellijplugin.filefinder

import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.HighlightedTextComponent
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.event.ListSelectionEvent

typealias PathList = CollectionListModel<FileListItem>

data class FileListItem(
    val path: Path,
    val fileName: String,
    val secondaryPath: String?,
    val highlightRanges: List<com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange>,
    val icon: Icon,
)

fun Path.relativePathFrom(basePath: String?): String {
    if (basePath == null) {
        return this.toString()
    }
    return Path.of(basePath)
        .relativize(this)
        .toString()
}

internal fun Path.relativeParentPath(basePath: String?): String? {
    val relativePath = relativePathFrom(basePath)
    val separatorIndex = relativePath.lastIndexOfAny(charArrayOf('/', '\\'))
    return relativePath
        .takeIf { separatorIndex >= 0 }
        ?.substring(0, separatorIndex)
        ?.ifBlank { null }
}

internal fun Path.fileIcon(): Icon {
    if (toFile().isDirectory) {
        return AllIcons.Nodes.Folder
    }
    val fileName = fileName?.toString().orEmpty()
    return FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon ?: AllIcons.FileTypes.Any_type
}

internal fun fuzzyMatchIndexes(text: String, query: String): List<Int> {
    if (text.isEmpty()) return emptyList()

    val normalizedQuery = query.filterNot(Char::isWhitespace)
    if (normalizedQuery.isEmpty()) return emptyList()

    val matches = mutableListOf<Int>()
    var textIndex = 0
    for (queryChar in normalizedQuery) {
        var matched = false
        while (textIndex < text.length) {
            if (text[textIndex].equals(queryChar, ignoreCase = true)) {
                matches += textIndex
                textIndex++
                matched = true
                break
            }
            textIndex++
        }
        if (!matched) {
            return emptyList()
        }
    }
    return matches
}

fun fuzzyFinderFileList(
    data: PathList = PathList(),
    onCellSelected: ((ListSelectionEvent) -> Unit)? = null,
    onCellClicked: ((MouseEvent) -> Unit)? = null,
): JBList<FileListItem> {
    val component = JBList(data).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = FileListItemRenderer()
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

private class FileListItemRenderer : ListCellRenderer<FileListItem> {
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
        list: JList<out FileListItem>,
        value: FileListItem,
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
        secondaryPathLabel.text = value.secondaryPath?.let { " $it" }.orEmpty()
        secondaryPathLabel.foreground = secondaryForeground
        secondaryPathLabel.isVisible = value.secondaryPath != null

        iconLabel.isOpaque = false
        iconLabel.background = background
        textPanel.background = background
        textPanel.isOpaque = false
        secondaryPathLabel.background = background
        secondaryPathLabel.isOpaque = false
        fileNameLabel.foreground = primaryForeground
        fileNameLabel.background = background
        fileNameLabel.applyHighlight(value.fileName, value.highlightRanges, primaryForeground)
        return panel
    }
}
