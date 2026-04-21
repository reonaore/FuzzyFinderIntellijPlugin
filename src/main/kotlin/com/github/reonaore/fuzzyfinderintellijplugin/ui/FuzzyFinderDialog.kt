package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.services.SearchResult
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.event.ListSelectionEvent

class FuzzyFinderDialog(private val project: Project) : DialogWrapper(project, false) {

    private val service = project.service<FuzzyFinderService>()
    private val optionsPanel = FuzzyFinderOptionsPanel { searchTimer.restart() }
    private val previewLoader = FuzzyFinderPreviewLoader()
    private val statusLabel = JBLabel(MyBundle.message("dialog.status.loading"))
    private val resultModel = CollectionListModel<FileListItem>()
    private val resultList = fuzzyFinderFileList(
        resultModel,
        this::updatePreview,
    ) { event ->
        if (event.clickCount == 2) {
            doOKAction()
        }
    }
    private val preview = FuzzyFinderPreview(project)
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private val searchTimer = Timer(SEARCH_DEBOUNCE_MS) { triggerSearch() }.apply {
        isRepeats = false
    }
    private val searchField = fuzzyFinderSearchTextField(placeHolderText = "Search") {
        searchTimer.restart()
    }

    init {
        title = MyBundle.message("dialog.title")
        setOKButtonText(MyBundle.message("dialog.open"))
        isOKActionEnabled = false
        searchTimer.start()
        init()
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
            secondComponent = preview.editor.component
        }

        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(960, 640)
            add(controlsPanel, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
            installCandidateNavigationShortcuts(this)
            installCandidateNavigationShortcuts(searchField)
            installCandidateNavigationShortcuts(searchField.textEditor)
            installCandidateNavigationShortcuts(searchField.textEditor, JComponent.WHEN_FOCUSED)
        }
    }

    override fun init() {
        super.init()
        rootPane?.let { installCandidateNavigationShortcuts(it, JComponent.WHEN_IN_FOCUSED_WINDOW) }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun dispose() {
        searchTimer.stop()
        dialogScope.cancel()
        preview.dispose()
        super.dispose()
    }

    override fun doOKAction() {
        val virtualFile = selectedVirtualFile() ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        super.doOKAction()
    }

    private fun triggerSearch() {
        searchJob?.cancel()
        val query = searchField.text
        val options = optionsPanel.currentOptions()
        statusLabel.text = MyBundle.message("dialog.status.searching")

        searchJob = dialogScope.launch(ModalityState.defaultModalityState().asContextElement()) {
            val res = service.search(query, options)
            applySearchResult(res)
        }.also {
            it.invokeOnCompletion { e ->
                if (e is FuzzyFinderException) {
                    dialogScope.launch(Dispatchers.EDT) {
                        statusLabel.text = MyBundle.message("dialog.status.error")
                        service.notifyError(e.message ?: MyBundle.message("dialog.status.error"))
                    }
                }
            }
        }
    }

    private suspend fun applySearchResult(searchResult: SearchResult) {
        val paths = searchResult.results
        val items = paths.map { path ->
            path.toFileListItem(project.basePath, searchResult.query)
        }
        withContext(Dispatchers.EDT) {
            resultModel.replaceAll(items)
            statusLabel.text = MyBundle.message(
                "dialog.status.resultsDetailed",
                paths.size,
                searchResult.totalCandidates,
            )
            if (paths.isNotEmpty()) {
                resultList.selectedIndex = 0
            } else {
                isOKActionEnabled = false
            }
        }
        updatePreview()
    }

    private fun updatePreview(@Suppress("UNUSED_PARAMETER") event: ListSelectionEvent? = null) {
        previewJob?.cancel()
        previewJob = dialogScope.launch(ModalityState.defaultModalityState().asContextElement()) {
            val selected = readAction {
                resultList.selectedValue?.path
            } ?: run {
                writeAction { isOKActionEnabled = false }
                preview.show(PreviewContent.empty)
                return@launch
            }
            writeAction { isOKActionEnabled = true }
            val previewContent = previewLoader.load(selected)
            preview.show(previewContent)
        }
    }

    private fun selectedVirtualFile(): VirtualFile? {
        val selected = resultList.selectedValue?.path ?: return null
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(selected) ?: return null
        if (virtualFile.isDirectory) return null
        return virtualFile
    }

    private fun installCandidateNavigationShortcuts(
        component: JComponent,
        focusCondition: Int = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
    ) {
        val inputMap = component.getInputMap(focusCondition)
        val actionMap = component.actionMap

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK), ACTION_SELECT_NEXT)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK), ACTION_SELECT_PREVIOUS)

        actionMap.put(ACTION_SELECT_NEXT, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                moveSelectionBy(1)
            }
        })
        actionMap.put(ACTION_SELECT_PREVIOUS, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                moveSelectionBy(-1)
            }
        })
    }

    private fun moveSelectionBy(offset: Int) {
        val lastIndex = resultModel.size - 1
        if (lastIndex < 0) return

        val currentIndex = resultList.selectedIndex.takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + offset).coerceIn(0, lastIndex)
        if (nextIndex == resultList.selectedIndex) return

        resultList.selectedIndex = nextIndex
        resultList.ensureIndexIsVisible(nextIndex)
    }

    private companion object {
        const val ACTION_SELECT_NEXT = "fuzzyFinder.selectNextCandidate"
        const val ACTION_SELECT_PREVIOUS = "fuzzyFinder.selectPreviousCandidate"
        const val SEARCH_DEBOUNCE_MS = 180
    }
}
