package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
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
import kotlin.coroutines.resume

class FuzzyFinderDialog(private val project: Project) : DialogWrapper(project, false) {

    private val service = project.service<FuzzyFinderService>()
    private val searchField = JBTextField()
    private val optionsPanel = FuzzyFinderOptionsPanel { searchTimer.restart() }
    private val previewLoader = FuzzyFinderPreviewLoader()
    private val statusLabel = JBLabel(MyBundle.message("dialog.status.loading"))
    private val resultModel = CollectionListModel<Path>()
    private val resultList = JBList(resultModel)
    private val previewDocument: Document = EditorFactory.getInstance().createDocument(MyBundle.message("dialog.preview.empty"))
    private val previewEditor = EditorFactory.getInstance().createViewer(previewDocument, project) as EditorEx
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val streamedCandidates = mutableListOf<Path>()
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var searchGeneration = 0L
    private var previewGeneration = 0L
    private val searchTimer = Timer(SEARCH_DEBOUNCE_MS) { triggerSearch() }

    init {
        title = MyBundle.message("dialog.title")
        setOKButtonText(MyBundle.message("dialog.open"))
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
        searchTimer.stop()
        dialogScope.cancel()
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

        searchField.emptyText.text = MyBundle.message("dialog.search.placeholder")
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
        val generation = ++searchGeneration
        val query = searchField.text
        val options = optionsPanel.currentOptions()
        val cachedCandidates = service.getCachedCandidates(options)
        searchJob?.cancel()

        if (query.isBlank() && cachedCandidates == null) {
            startStreamingLoad(options, generation)
            return
        }

        statusLabel.text = MyBundle.message("dialog.status.searching")

        searchJob = dialogScope.launch {
            try {
                val searchResult = service.search(query, options)
                onEdt {
                    if (generation != searchGeneration) return@onEdt
                    applySearchResult(searchResult)
                }
            } catch (_: CancellationException) {
            } catch (error: FuzzyFinderException) {
                onEdt {
                    if (generation != searchGeneration) return@onEdt
                    statusLabel.text = MyBundle.message("dialog.status.error")
                    service.notifyError(error.message ?: MyBundle.message("dialog.status.error"))
                }
            }
        }
    }

    private fun startStreamingLoad(options: FdSearchOptions, generation: Long) {
        streamedCandidates.clear()
        updateResults(emptyList())
        statusLabel.text = MyBundle.message("dialog.status.loading")

        searchJob = dialogScope.launch {
            try {
                val candidates = service.streamCandidates(options) { batch, total ->
                    onEdt {
                        if (generation != searchGeneration) return@onEdt
                        if (searchField.text.isNotBlank()) return@onEdt
                        streamedCandidates += batch
                        updateResults(streamedCandidates.take(INITIAL_RESULT_LIMIT))
                        statusLabel.text = MyBundle.message("dialog.status.loadingProgress", total)
                    }
                }

                onEdt {
                    if (generation != searchGeneration) return@onEdt
                    if (searchField.text.isBlank()) {
                        applySearchResult(SearchResult(candidates.size, candidates.take(INITIAL_RESULT_LIMIT)))
                    } else {
                        triggerSearch()
                    }
                }
            } catch (_: CancellationException) {
            } catch (error: FuzzyFinderException) {
                onEdt {
                    if (generation != searchGeneration) return@onEdt
                    statusLabel.text = MyBundle.message("dialog.status.error")
                    service.notifyError(error.message ?: MyBundle.message("dialog.status.error"))
                }
            }
        }
    }

    private fun applySearchResult(searchResult: SearchResult) {
        updateResults(searchResult.results)
        statusLabel.text = MyBundle.message(
            "dialog.status.resultsDetailed",
            searchResult.results.size,
            searchResult.totalCandidates,
        )
    }

    private fun updateResults(paths: List<Path>) {
        resultModel.replaceAll(paths)
        if (paths.isNotEmpty()) {
            resultList.selectedIndex = 0
        } else {
            isOKActionEnabled = false
            updatePreviewContent(MyBundle.message("dialog.preview.empty"))
        }
    }

    private fun updatePreview(@Suppress("UNUSED_PARAMETER") event: ListSelectionEvent) {
        val selected = resultList.selectedValue ?: run {
            previewJob?.cancel()
            isOKActionEnabled = false
            updatePreviewContent(MyBundle.message("dialog.preview.empty"))
            return
        }

        isOKActionEnabled = false
        val generation = ++previewGeneration
        previewJob?.cancel()
        previewJob = dialogScope.launch {
            try {
                val previewContent = previewLoader.load(selected)
                onEdt {
                    if (generation != previewGeneration) return@onEdt
                    if (selected != resultList.selectedValue) return@onEdt
                    val selectedFile = previewContent.virtualFile
                    isOKActionEnabled = selectedFile != null && !selectedFile.isDirectory
                    updatePreviewContent(previewContent.text, selectedFile)
                }
            } catch (_: CancellationException) {
            }
        }
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

    private suspend fun onEdt(action: () -> Unit) {
        suspendCancellableCoroutine { continuation ->
            SwingUtilities.invokeLater {
                if (!continuation.isActive) return@invokeLater
                action()
                continuation.resume(Unit)
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180
        const val INITIAL_RESULT_LIMIT = 200
    }
}
