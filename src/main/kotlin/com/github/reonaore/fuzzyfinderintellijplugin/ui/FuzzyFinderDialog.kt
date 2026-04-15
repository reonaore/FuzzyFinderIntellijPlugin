package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.ListSelectionEvent

class FuzzyFinderDialog(private val project: Project) : DialogWrapper(project, false) {

    private val service = project.service<FuzzyFinderService>()
    private val searchField = JBTextField()
    private val statusLabel = JBLabel(STATUS_LOADING)
    private val resultModel = CollectionListModel<Path>()
    private val resultList = JBList(resultModel)
    private val previewArea = JBTextArea()
    private val requestId = AtomicInteger()
    private val searchTimer = Timer(SEARCH_DEBOUNCE_MS) { triggerSearch() }

    init {
        title = DIALOG_TITLE
        setOKButtonText(OPEN_BUTTON_TEXT)
        isOKActionEnabled = false
        init()
        configureUi()
        loadCandidates()
    }

    override fun createCenterPanel(): JComponent {
        val splitter = JBSplitter(false, 0.42f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(ScrollPaneFactory.createScrollPane(resultList), BorderLayout.CENTER)
            }
            secondComponent = JBScrollPane(previewArea)
        }

        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(960, 640)
            add(searchField, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun doOKAction() {
        val selected = resultList.selectedValue ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(selected) ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        super.doOKAction()
    }

    private fun configureUi() {
        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
                val path = value as? Path ?: return@also
                this.text = project.basePath?.let {
                    runCatching { Path.of(it).relativize(path).toString() }.getOrDefault(path.toString())
                } ?: path.toString()
                toolTipText = path.toString()
            }
        }
        resultList.addListSelectionListener(this::updatePreview)

        searchField.emptyText.text = SEARCH_PLACEHOLDER
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                searchTimer.restart()
            }
        })

        previewArea.isEditable = false
        previewArea.font = Font(
            EditorColorsManager.getInstance().globalScheme.editorFontName,
            Font.PLAIN,
            EditorColorsManager.getInstance().globalScheme.editorFontSize,
        )
        previewArea.text = PREVIEW_EMPTY

        searchTimer.isRepeats = false

        resultList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openSelection")
        resultList.actionMap.put("openSelection", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                if (resultList.selectedValue != null) {
                    doOKAction()
                }
            }
        })
    }

    private fun loadCandidates() {
        statusLabel.text = STATUS_LOADING
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val candidates = service.ensureCandidates()
                SwingUtilities.invokeLater {
                    updateResults(candidates.take(INITIAL_RESULT_LIMIT))
                    statusLabel.text = "Loaded ${candidates.size} files."
                }
            } catch (error: FuzzyFinderException) {
                SwingUtilities.invokeLater {
                    statusLabel.text = STATUS_ERROR
                    service.notifyError(error.message ?: STATUS_ERROR)
                }
            }
        }
    }

    private fun triggerSearch() {
        val query = searchField.text
        val currentRequest = requestId.incrementAndGet()
        statusLabel.text = STATUS_SEARCHING

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val results = service.filterCandidates(query)
                SwingUtilities.invokeLater {
                    if (currentRequest != requestId.get()) return@invokeLater
                    updateResults(results)
                    statusLabel.text = "Showing ${results.size} results."
                }
            } catch (error: FuzzyFinderException) {
                SwingUtilities.invokeLater {
                    if (currentRequest != requestId.get()) return@invokeLater
                    statusLabel.text = STATUS_ERROR
                    service.notifyError(error.message ?: STATUS_ERROR)
                }
            }
        }
    }

    private fun updateResults(paths: List<Path>) {
        resultModel.replaceAll(paths)
        if (paths.isNotEmpty()) {
            resultList.selectedIndex = 0
        } else {
            isOKActionEnabled = false
            previewArea.text = PREVIEW_EMPTY
        }
    }

    private fun updatePreview(@Suppress("UNUSED_PARAMETER") event: ListSelectionEvent) {
        val selected = resultList.selectedValue
        isOKActionEnabled = selected != null
        previewArea.text = selected?.let(::loadPreviewText) ?: PREVIEW_EMPTY
        previewArea.caretPosition = 0
    }

    private fun loadPreviewText(path: Path): String {
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path)
            ?: return PREVIEW_MISSING

        if (virtualFile.fileType.isBinary) {
            return PREVIEW_BINARY
        }

        return try {
            readPreview(virtualFile)
        } catch (_: IOException) {
            PREVIEW_MISSING
        }
    }

    private fun readPreview(file: VirtualFile): String {
        file.inputStream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                val builder = StringBuilder()
                var totalChars = 0
                val buffer = CharArray(READ_BUFFER_SIZE)

                while (totalChars < PREVIEW_CHAR_LIMIT) {
                    val remaining = minOf(buffer.size, PREVIEW_CHAR_LIMIT - totalChars)
                    val read = reader.read(buffer, 0, remaining)
                    if (read <= 0) {
                        break
                    }
                    builder.append(buffer, 0, read)
                    totalChars += read
                }

                return FuzzyFinderParsers.appendPreviewSuffix(builder.toString(), totalChars >= PREVIEW_CHAR_LIMIT)
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180
        const val INITIAL_RESULT_LIMIT = 200
        const val PREVIEW_CHAR_LIMIT = 12000
        const val READ_BUFFER_SIZE = 2048
        const val DIALOG_TITLE = "Fuzzy Finder"
        const val OPEN_BUTTON_TEXT = "Open"
        const val SEARCH_PLACEHOLDER = "Type to filter files"
        const val STATUS_LOADING = "Loading files..."
        const val STATUS_SEARCHING = "Searching..."
        const val STATUS_ERROR = "Search failed."
        const val PREVIEW_EMPTY = "No file selected."
        const val PREVIEW_MISSING = "Preview unavailable: file not found."
        const val PREVIEW_BINARY = "Preview unavailable: binary file."
    }
}
