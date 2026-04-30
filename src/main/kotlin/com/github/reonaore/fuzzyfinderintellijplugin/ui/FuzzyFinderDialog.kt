package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfo
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
import kotlinx.coroutines.flow.collectLatest
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
import javax.swing.event.ListSelectionEvent

class FuzzyFinderDialog(private val project: Project) : DialogWrapper(project, false) {

    private val service = project.service<FuzzyFinderService>()
    private val optionsPanel = FuzzyFinderOptionsPanel()
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
    private val candidateListPanel = CandidateListLoadingPanel(
        resultList,
        ScrollPaneFactory.createScrollPane(resultList),
    )
    private val preview = FuzzyFinderPreview(project)
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var previewJob: Job? = null
    private val searchField = fuzzyFinderSearchTextField(placeHolderText = "Search")
    private val viewModel = FuzzyFinderDialogViewModel(service, dialogScope, optionsPanel.currentOptions())

    init {
        title = MyBundle.message("dialog.title")
        setOKButtonText(MyBundle.message("dialog.open"))
        isOKActionEnabled = false
        init()
        bind()
    }

    override fun createCenterPanel(): JComponent {
        val controlsPanel = JPanel(BorderLayout(0, 8)).apply {
            add(searchField, BorderLayout.NORTH)
            add(optionsPanel.component(), BorderLayout.CENTER)
        }

        val splitter = JBSplitter(false, 0.42f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(candidateListPanel.component, BorderLayout.CENTER)
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
            installCandidateNavigationShortcuts(optionsPanel.extensionsFieldComponent(), JComponent.WHEN_FOCUSED)
            installCandidateNavigationShortcuts(optionsPanel.excludeFieldComponent(), JComponent.WHEN_FOCUSED)
        }
    }

    override fun init() {
        super.init()
        rootPane?.let { installCandidateNavigationShortcuts(it, JComponent.WHEN_IN_FOCUSED_WINDOW) }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun dispose() {
        dialogScope.cancel()
        candidateListPanel.dispose()
        preview.dispose()
        super.dispose()
    }

    override fun doOKAction() {
        val virtualFile = selectedVirtualFile() ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        super.doOKAction()
    }

    private fun bind() {
        searchField.onTextChanged {
            viewModel.onQueryChanged(searchField.text)
        }
        optionsPanel.setOnOptionsChanged {
            viewModel.onOptionsChanged(optionsPanel.currentOptions())
        }
        bindViewModel()
        triggerInitialSearch()
    }

    private fun triggerInitialSearch() {
        viewModel.onQueryChanged(searchField.text)
    }

    private fun bindViewModel() {
        dialogScope.launch(dialogModalityContext()) {
            viewModel.state.collectLatest { state ->
                val items = state.paths.map { path ->
                    path.toFileListItem(project.basePath, state.query)
                }
                withContext(Dispatchers.EDT) {
                    resultModel.replaceAll(items)
                    if (state.isSearching) {
                        candidateListPanel.showSearching(state.hasSearched)
                    } else {
                        candidateListPanel.showResults(items.isNotEmpty())
                    }
                    statusLabel.text = state.statusText
                    if (state.paths.isNotEmpty()) {
                        resultList.selectedIndex = 0
                    } else {
                        isOKActionEnabled = false
                    }
                }
                updatePreview()
            }
        }
    }

    private fun updatePreview(@Suppress("UNUSED_PARAMETER") event: ListSelectionEvent? = null) {
        previewJob?.cancel()
        previewJob = dialogScope.launch(dialogModalityContext()) {
            val selected = withContext(Dispatchers.EDT) {
                resultList.selectedValue?.path
            } ?: run {
                withContext(Dispatchers.EDT) {
                    isOKActionEnabled = false
                }
                preview.show(PreviewContent.empty)
                return@launch
            }
            withContext(Dispatchers.EDT) {
                isOKActionEnabled = true
            }
            val previewContent = previewLoader.load(selected)
            preview.show(previewContent)
        }
    }

    private fun dialogModalityContext() = ModalityState.stateForComponent(rootPane).asContextElement()

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
        inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU_SHORTCUT_KEY_MASK),
            ACTION_FOCUS_SEARCH_FIELD,
        )
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, KeyEvent.ALT_DOWN_MASK), ACTION_TOGGLE_INCLUDE_HIDDEN)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.ALT_DOWN_MASK), ACTION_TOGGLE_FOLLOW_SYMLINKS)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.ALT_DOWN_MASK), ACTION_TOGGLE_RESPECT_GITIGNORE)

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
        actionMap.put(ACTION_FOCUS_SEARCH_FIELD, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                searchField.textEditor.requestFocusInWindow()
                searchField.textEditor.selectAll()
            }
        })
        actionMap.put(ACTION_TOGGLE_INCLUDE_HIDDEN, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                optionsPanel.toggleIncludeHidden()
            }
        })
        actionMap.put(ACTION_TOGGLE_FOLLOW_SYMLINKS, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                optionsPanel.toggleFollowSymlinks()
            }
        })
        actionMap.put(ACTION_TOGGLE_RESPECT_GITIGNORE, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                optionsPanel.toggleRespectGitIgnore()
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
        const val ACTION_FOCUS_SEARCH_FIELD = "fuzzyFinder.focusSearchField"
        const val ACTION_TOGGLE_INCLUDE_HIDDEN = "fuzzyFinder.toggleIncludeHidden"
        const val ACTION_TOGGLE_FOLLOW_SYMLINKS = "fuzzyFinder.toggleFollowSymlinks"
        const val ACTION_TOGGLE_RESPECT_GITIGNORE = "fuzzyFinder.toggleRespectGitIgnore"
        val MENU_SHORTCUT_KEY_MASK = if (SystemInfo.isMac) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK
    }
}
