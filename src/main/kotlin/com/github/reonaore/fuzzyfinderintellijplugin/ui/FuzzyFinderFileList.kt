package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionEvent

typealias PathList = CollectionListModel<Path>

fun Path.relativePathFrom(basePath: String?): String {
    if (basePath == null) {
        return this.toString()
    }
    return Path.of(basePath)
        .relativize(this)
        .toString()
}

fun fuzzyFinderFileList(
    data: PathList = PathList(),
    basePath: String? = null,
    onCellSelected: ((ListSelectionEvent) -> Unit)? = null,
    onCellClicked: ((MouseEvent) -> Unit)? = null,
): JBList<Path> {
    val component = JBList(data).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        installCellRenderer { path ->
            JBLabel(path.relativePathFrom(basePath))
        }
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
