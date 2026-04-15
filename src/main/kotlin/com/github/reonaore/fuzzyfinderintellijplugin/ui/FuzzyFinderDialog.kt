package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.FdEntryType
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.services.SearchResult
import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
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
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.ListSelectionEvent

class FuzzyFinderDialog(private val project: Project) : DialogWrapper(project, false) {

    private val service = project.service<FuzzyFinderService>()
    private val searchField = JBTextField()
    private val typeComboBox = JComboBox(FdEntryType.entries.toTypedArray())
    private val includeHiddenCheckBox = JCheckBox("Hidden")
    private val followSymlinksCheckBox = JCheckBox("Follow symlinks")
    private val respectGitIgnoreCheckBox = JCheckBox("Respect .gitignore")
    private val excludeField = JBTextField(DEFAULT_EXCLUDES)
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
            add(createOptionsPanel(), BorderLayout.CENTER)
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
        val selected = resultList.selectedValue ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(selected) ?: return
        if (virtualFile.isDirectory) return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        super.doOKAction()
    }

    private fun configureUi() {
        configureOptionControls()

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

    private fun createOptionsPanel(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JLabel("Type"))
            add(typeComboBox)
            add(includeHiddenCheckBox)
            add(followSymlinksCheckBox)
            add(respectGitIgnoreCheckBox)
            add(JLabel("Exclude"))
            excludeField.columns = 20
            add(excludeField)
        }
    }

    private fun configureOptionControls() {
        typeComboBox.selectedItem = FdEntryType.FILES
        includeHiddenCheckBox.isSelected = false
        followSymlinksCheckBox.isSelected = true
        respectGitIgnoreCheckBox.isSelected = true

        typeComboBox.addActionListener { searchTimer.restart() }
        includeHiddenCheckBox.addActionListener { searchTimer.restart() }
        followSymlinksCheckBox.addActionListener { searchTimer.restart() }
        respectGitIgnoreCheckBox.addActionListener { searchTimer.restart() }
        excludeField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                searchTimer.restart()
            }
        })
    }

    private fun loadCandidates() {
        triggerSearch()
    }

    private fun triggerSearch() {
        val query = searchField.text
        val options = currentOptions()
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

    private fun currentOptions(): FdSearchOptions {
        return FdSearchOptions(
            entryType = typeComboBox.selectedItem as? FdEntryType ?: FdEntryType.FILES,
            includeHidden = includeHiddenCheckBox.isSelected,
            followSymlinks = followSymlinksCheckBox.isSelected,
            respectGitIgnore = respectGitIgnoreCheckBox.isSelected,
            excludePatterns = excludeField.text
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty),
        )
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
        val selected = resultList.selectedValue
        val selectedFile = selected?.let(LocalFileSystem.getInstance()::findFileByNioFile)
        isOKActionEnabled = selectedFile != null && !selectedFile.isDirectory
        if (selectedFile == null) {
            updatePreviewContent(PREVIEW_EMPTY)
            return
        }

        updatePreviewContent(selected.let(::loadPreviewText), selectedFile)
    }

    private fun loadPreviewText(path: Path): String {
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path)
            ?: return PREVIEW_MISSING

        if (virtualFile.isDirectory) {
            return PREVIEW_DIRECTORY
        }

        if (virtualFile.fileType.isBinary) {
            return PREVIEW_BINARY
        }

        return try {
            readPreview(virtualFile)
        } catch (_: IOException) {
            PREVIEW_MISSING
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
        const val PREVIEW_DIRECTORY = "Preview unavailable: directory."
        const val PREVIEW_BINARY = "Preview unavailable: binary file."
        const val DEFAULT_EXCLUDES = ".git"
    }
}
