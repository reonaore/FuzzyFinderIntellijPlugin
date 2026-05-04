package com.github.reonaore.fuzzyfinderintellijplugin.filefinder

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.CandidateListLoadingPanel
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.FuzzyFinderPreview
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.PreviewContent
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.contiguousHighlightRanges
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.fuzzyFinderSearchTextField
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.onTextChanged
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.ListSelectionEvent

class FuzzyFinderDialog(private val project: Project) : DialogWrapper(project, false) {

    private val service = project.service<FuzzyFinderService>()
    private val optionsPanel = FuzzyFinderOptionsPanel()
    private val statusLabel = JBLabel(MyBundle.message("dialog.status.loading"))
    private val resultModel = CollectionListModel<FileListItem>()
    private val resultList = fuzzyFinderFileList(
        resultModel,
        this::onCandidateSelected,
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
    private var renderedPreviewContent: PreviewContent? = null
    private var isRenderingState = false
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
            viewModel.onUpdateQuery(searchField.text)
        }
        optionsPanel.setOnOptionsChanged {
            viewModel.onUpdateOptions(optionsPanel.currentOptions())
        }
        observeState()
        triggerInitialSearch()
    }

    private fun triggerInitialSearch() {
        viewModel.onUpdateQuery(searchField.text)
    }

    private fun observeState() {
        dialogScope.launch(dialogModalityContext()) {
            viewModel.state.collectLatest { state ->
                withContext(Dispatchers.EDT) {
                    render(state)
                }
                renderPreview(state.preview.content)
            }
        }
    }

    private fun render(state: FuzzyFinderDialogState) {
        val items = state.paths.map { path ->
            toFileListItem(path, project.basePath, state.query)
        }

        isRenderingState = true
        try {
            resultModel.replaceAll(items)
            renderCandidateListState(state, items)
            statusLabel.text = state.statusText
            isOKActionEnabled = state.canOpenSelectedFile
            renderSelectedIndex(state.selectedIndex)
        } finally {
            isRenderingState = false
        }
    }

    private fun renderCandidateListState(state: FuzzyFinderDialogState, items: List<FileListItem>) {
        if (state.isSearching) {
            candidateListPanel.showSearching(state.hasSearched)
        } else if (state.hasError) {
            candidateListPanel.showError()
        } else {
            candidateListPanel.showResults(items.isNotEmpty())
        }
    }

    private fun renderSelectedIndex(selectedIndex: Int) {
        if (resultList.selectedIndex == selectedIndex) return

        resultList.selectedIndex = selectedIndex
        if (selectedIndex >= 0) {
            resultList.ensureIndexIsVisible(selectedIndex)
        }
    }

    private suspend fun renderPreview(previewContent: PreviewContent) {
        if (renderedPreviewContent == previewContent) return

        renderedPreviewContent = previewContent
        preview.show(previewContent)
    }

    private fun onCandidateSelected(event: ListSelectionEvent) {
        if (isRenderingState || event.valueIsAdjusting) return

        viewModel.onSelectCandidate(resultList.selectedIndex)
    }

    private fun dialogModalityContext() = ModalityState.stateForComponent(rootPane).asContextElement()

    private fun selectedVirtualFile(): VirtualFile? {
        val selected = viewModel.state.value.selectedPath ?: return null
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
                viewModel.onSelectNextCandidate()
            }
        })
        actionMap.put(ACTION_SELECT_PREVIOUS, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                viewModel.onSelectPreviousCandidate()
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

private fun toFileListItem(path: Path, basePath: String?, query: String): FileListItem {
    val relativePath = path.relativePathFrom(basePath)
    val fileName = path.fileName?.toString().orEmpty().ifBlank { relativePath }
    val secondaryPath = path.relativeParentPath(basePath)

    return FileListItem(
        path = path,
        fileName = fileName,
        secondaryPath = secondaryPath,
        highlightRanges = contiguousHighlightRanges(fuzzyMatchIndexes(fileName, query).toSet()),
        icon = path.fileIcon(),
    )
}
