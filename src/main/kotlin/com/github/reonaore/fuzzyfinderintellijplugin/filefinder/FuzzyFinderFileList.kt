package com.github.reonaore.fuzzyfinderintellijplugin.filefinder

import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.HighlightedTextComponent
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.contiguousHighlightRanges
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

private const val MAX_EXPANDED_FUZZY_GAP = 1

data class FileListItem(
    val path: Path,
    val fileName: String,
    val secondaryPath: String?,
    val fileNameHighlightRanges: List<TextRange>,
    val secondaryPathHighlightRanges: List<TextRange>,
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

internal fun highlightRangesFor(text: String, query: String): List<TextRange> {
    if (text.isEmpty()) return emptyList()

    val tokens = query.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
    if (tokens.isEmpty()) return emptyList()

    val ranges = tokens
        .flatMap { token ->
            highlightRangesForToken(text, token)
        }
    return mergeHighlightRanges(ranges)
}

private fun highlightRangesForToken(text: String, token: String): List<TextRange> {
    val substringStart = text.indexOf(token, ignoreCase = true)
    if (substringStart >= 0) {
        return listOf(TextRange(substringStart, substringStart + token.length))
    }

    val matchIndexes = fuzzyMatchIndexes(text, token)
    if (matchIndexes.isEmpty()) return emptyList()

    val expandedRange = fuzzyExpandedRange(matchIndexes)
    return if (expandedRange != null) {
        listOf(expandedRange)
    } else {
        contiguousHighlightRanges(matchIndexes.toSet())
    }
}

private fun fuzzyExpandedRange(matchIndexes: List<Int>): TextRange? {
    val startOffset = matchIndexes.first()
    val endOffset = matchIndexes.last() + 1
    val highlightedLength = matchIndexes.size
    val rangeLength = endOffset - startOffset
    val missingCharacters = rangeLength - highlightedLength

    return TextRange(startOffset, endOffset)
        .takeIf { missingCharacters <= MAX_EXPANDED_FUZZY_GAP && rangeLength <= highlightedLength + MAX_EXPANDED_FUZZY_GAP }
}

private fun mergeHighlightRanges(ranges: List<TextRange>): List<TextRange> {
    if (ranges.isEmpty()) return emptyList()

    val sortedRanges = ranges.sortedWith(compareBy(TextRange::startOffset, TextRange::endOffset))
    val mergedRanges = mutableListOf<TextRange>()
    var current = sortedRanges.first()

    sortedRanges.drop(1).forEach { next ->
        if (next.startOffset <= current.endOffset) {
            current = TextRange(current.startOffset, maxOf(current.endOffset, next.endOffset))
        } else {
            mergedRanges += current
            current = next
        }
    }
    mergedRanges += current
    return mergedRanges
}

internal fun Path.toFileListItem(basePath: String?, query: String): FileListItem {
    val relativePath = relativePathFrom(basePath)
    val fileName = fileName?.toString().orEmpty().ifBlank { relativePath }
    val secondaryPath = relativeParentPath(basePath)

    return FileListItem(
        path = this,
        fileName = fileName,
        secondaryPath = secondaryPath,
        fileNameHighlightRanges = highlightRangesFor(fileName, query),
        secondaryPathHighlightRanges = secondaryPath?.let { highlightRangesFor(it, query) }.orEmpty(),
        icon = fileIcon(),
    )
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
    private val secondaryPathLabel = HighlightedTextComponent()

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
        fileNameLabel.applyHighlight(value.fileName, value.fileNameHighlightRanges, primaryForeground)
        secondaryPathLabel.applyHighlight(
            value.secondaryPath?.let { " $it" }.orEmpty(),
            value.secondaryPathHighlightRanges.map { range ->
                TextRange(range.startOffset + 1, range.endOffset + 1)
            },
            secondaryForeground,
        )
        return panel
    }

}
