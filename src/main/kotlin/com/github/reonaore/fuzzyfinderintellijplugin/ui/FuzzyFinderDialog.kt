package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.services.SearchResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
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
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    private val optionsPanel = FuzzyFinderOptionsPanel { searchTimer.restart() }
    private val previewLoader = FuzzyFinderPreviewLoader()
    private val statusLabel = JBLabel(STATUS_LOADING)
    private val resultModel = CollectionListModel<Path>()
    private val resultList = JBList(resultModel)
    private val previewDocument: Document = EditorFactory.getInstance().createDocument(PREVIEW_EMPTY)
    private val previewEditor = EditorFactory.getInstance().createViewer(previewDocument, project) as EditorEx
    private val requestId = AtomicInteger()
    private val streamedCandidates = mutableListOf<Path>()
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
        val controlsPanel = JPanel(BorderLayout(0, 8)).apply {
            add(searchField, BorderLayout.NORTH)
            add(optionsPanel.component(), BorderLayout.CENTER)
        }

        val splitter = JBSplitter(false, 0.42f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(ScrollPaneFactory.createScrollPane(resultList), BorderLayout.CENTER)
            }
            secondComponent = createPreviewComponent()
        }

        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(960, 640)
            add(controlsPanel, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(previewEditor)
        super.dispose()
    }

    override fun doOKAction() {
        val virtualFile = selectedVirtualFile() ?: return
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
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && resultList.selectedValue != null) {
                    doOKAction()
                }
            }
        })

        searchField.emptyText.text = SEARCH_PLACEHOLDER
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                searchTimer.restart()
            }
        })
        configureSearchNavigation()
        configurePreviewEditor()

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

    private fun createPreviewComponent(): JComponent = previewEditor.component

    private fun configurePreviewEditor() {
        previewEditor.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isWhitespacesShown = false
            isCaretRowShown = false
            additionalColumnsCount = 1
            additionalLinesCount = 1
        }
        previewEditor.setCaretEnabled(false)
    }

    private fun configureSearchNavigation() {
        registerSearchAction("selectNextResult", KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK)) {
            moveSelection(1)
        }
        registerSearchAction("selectPreviousResult", KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK)) {
            moveSelection(-1)
        }
        registerSearchAction("openSelectedResult", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)) {
            if (resultList.selectedValue != null) {
                doOKAction()
            }
        }
    }

    private fun registerSearchAction(actionId: String, keyStroke: KeyStroke, handler: () -> Unit) {
        searchField.inputMap.put(keyStroke, actionId)
        searchField.actionMap.put(actionId, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                handler()
            }
        })
    }

    private fun moveSelection(delta: Int) {
        val size = resultModel.size
        if (size == 0) return

        val currentIndex = resultList.selectedIndex.takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + delta).coerceIn(0, size - 1)
        resultList.selectedIndex = nextIndex
        resultList.ensureIndexIsVisible(nextIndex)
    }

    private fun loadCandidates() {
        triggerSearch()
    }

    private fun triggerSearch() {
        val query = searchField.text
        val options = optionsPanel.currentOptions()
        val currentRequest = requestId.incrementAndGet()
        val cachedCandidates = service.getCachedCandidates(options)

        if (query.isBlank() && cachedCandidates == null) {
            startStreamingLoad(currentRequest, options)
            return
        }

        statusLabel.text = STATUS_SEARCHING

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val searchResult = service.search(query, options)
                onMatchingRequest(currentRequest) {
                    applySearchResult(searchResult)
                }
            } catch (error: FuzzyFinderException) {
                onMatchingRequest(currentRequest) {
                    statusLabel.text = STATUS_ERROR
                    service.notifyError(error.message ?: STATUS_ERROR)
                }
            }
        }
    }

    private fun startStreamingLoad(currentRequest: Int, options: FdSearchOptions) {
        streamedCandidates.clear()
        updateResults(emptyList())
        statusLabel.text = STATUS_LOADING

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val candidates = service.streamCandidates(options) { batch, total ->
                    onMatchingRequest(currentRequest) {
                        if (searchField.text.isNotBlank()) return@onMatchingRequest
                        streamedCandidates += batch
                        updateResults(streamedCandidates.take(INITIAL_RESULT_LIMIT))
                        statusLabel.text = "Loading files... $total found"
                    }
                }

                onMatchingRequest(currentRequest) {
                    if (searchField.text.isBlank()) {
                        applySearchResult(SearchResult(candidates.size, candidates.take(INITIAL_RESULT_LIMIT)))
                    } else {
                        triggerSearch()
                    }
                }
            } catch (error: FuzzyFinderException) {
                onMatchingRequest(currentRequest) {
                    statusLabel.text = STATUS_ERROR
                    service.notifyError(error.message ?: STATUS_ERROR)
                }
            }
        }
    }

    private fun onMatchingRequest(request: Int, action: () -> Unit) {
        SwingUtilities.invokeLater {
            if (request != requestId.get()) return@invokeLater
            action()
        }
    }

    private fun applySearchResult(searchResult: SearchResult) {
        updateResults(searchResult.results)
        statusLabel.text = "Showing ${searchResult.results.size} results from ${searchResult.totalCandidates} candidates."
    }

    private fun updateResults(paths: List<Path>) {
        resultModel.replaceAll(paths)
        if (paths.isNotEmpty()) {
            resultList.selectedIndex = 0
        } else {
            isOKActionEnabled = false
            updatePreviewContent(PREVIEW_EMPTY)
        }
    }

    private fun updatePreview(@Suppress("UNUSED_PARAMETER") event: ListSelectionEvent) {
        val selected = resultList.selectedValue ?: run {
            isOKActionEnabled = false
            updatePreviewContent(PREVIEW_EMPTY)
            return
        }

        val previewContent = previewLoader.load(selected)
        val selectedFile = previewContent.virtualFile
        isOKActionEnabled = selectedFile != null && !selectedFile.isDirectory
        updatePreviewContent(previewContent.text, selectedFile)
    }

    private fun updatePreviewContent(text: String, virtualFile: VirtualFile? = null) {
        ApplicationManager.getApplication().runWriteAction {
            previewDocument.setText(text)
        }
        val highlighter = if (virtualFile != null && !virtualFile.isDirectory && !virtualFile.fileType.isBinary) {
            EditorHighlighterFactory.getInstance().createEditorHighlighter(project, virtualFile)
        } else {
            EditorHighlighterFactory.getInstance().createEditorHighlighter(project, PlainTextFileType.INSTANCE)
        }
        previewEditor.highlighter = highlighter
        previewEditor.caretModel.moveToOffset(0)
        previewEditor.scrollingModel.scrollVertically(0)
    }

    private fun selectedVirtualFile(): VirtualFile? {
        val selected = resultList.selectedValue ?: return null
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(selected) ?: return null
        if (virtualFile.isDirectory) return null
        return virtualFile
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180
        const val INITIAL_RESULT_LIMIT = 200
        const val DIALOG_TITLE = "Fuzzy Finder"
        const val OPEN_BUTTON_TEXT = "Open"
        const val SEARCH_PLACEHOLDER = "Type to filter files"
        const val STATUS_LOADING = "Loading files..."
        const val STATUS_SEARCHING = "Searching..."
        const val STATUS_ERROR = "Search failed."
        const val PREVIEW_EMPTY = "No file selected."
    }
}
