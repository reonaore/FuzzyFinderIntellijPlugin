package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchResult
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.CandidateListLoadingPanel
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.FuzzyFinderPreview
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.fuzzyFinderSearchTextField
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.onTextChanged
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
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
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.ListSelectionEvent

class LiveGrepDialog(
    private val project: Project,
    private val initialQuery: String = "",
) : DialogWrapper(project, false) {

    private val service = project.service<FuzzyFinderService>()
    private val optionsPanel = LiveGrepOptionsPanel()
    private val statusLabel = JBLabel(MyBundle.message("dialog.grep.status.ready"))
    private val resultModel = CollectionListModel<GrepListItem>()
    private val resultList = liveGrepMatchList(
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
        MyBundle.message("dialog.grep.candidates.prompt"),
    )
    private val preview = FuzzyFinderPreview(project)
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renderedPreviewState: LiveGrepPreviewState? = null
    private var isRenderingState = false
    private val searchField = fuzzyFinderSearchTextField(placeHolderText = MyBundle.message("dialog.grep.search.placeholder"))
    private val fzfSearchField = fuzzyFinderSearchTextField(placeHolderText = MyBundle.message("dialog.grep.fuzzy.placeholder"))
    private val viewModel = LiveGrepDialogViewModel(
        backend = FuzzyFinderLiveGrepSearchBackend(service),
        scope = dialogScope,
        initialOptions = optionsPanel.currentOptions(),
    )

    init {
        title = MyBundle.message("dialog.grep.title")
        setOKButtonText(MyBundle.message("dialog.open"))
        isOKActionEnabled = false
        init()
        bind()
        applyInitialQuery()
    }


    private fun bind() {
        searchField.onTextChanged {
            clearFzfQuery()
            viewModel.onUpdateRgQuery(searchField.text)
        }
        fzfSearchField.onTextChanged {
            viewModel.onUpdateFzfQuery(fzfSearchField.text)
        }
        optionsPanel.setOnOptionsChanged {
            clearFzfQuery()
            viewModel.onUpdateOptions(optionsPanel.currentOptions())
        }
        observeState()
    }

    override fun createCenterPanel(): JComponent {
        val searchFieldsPanel = JPanel(GridLayout(0, 1, 0, 4)).apply {
            add(searchField)
            add(fzfSearchField)
        }
        val controlsPanel = JPanel(BorderLayout(0, 8)).apply {
            add(searchFieldsPanel, BorderLayout.NORTH)
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
            installCandidateNavigationShortcuts(fzfSearchField)
            installCandidateNavigationShortcuts(fzfSearchField.textEditor)
            installCandidateNavigationShortcuts(fzfSearchField.textEditor, JComponent.WHEN_FOCUSED)
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
        val match = viewModel.state.value.selectedMatch ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(match.path) ?: return
        if (virtualFile.isDirectory) return

        OpenFileDescriptor(project, virtualFile, match.line - 1, match.column - 1).navigate(true)
        super.doOKAction()
    }

    private fun applyInitialQuery() {
        if (initialQuery.isBlank()) return

        searchField.text = initialQuery
    }

    private fun clearFzfQuery() {
        if (fzfSearchField.text.isEmpty()) return

        fzfSearchField.text = ""
    }

    private fun observeState() {
        dialogScope.launch(dialogModalityContext()) {
            viewModel.state.collectLatest { state ->
                withContext(Dispatchers.EDT) {
                    render(state)
                }
                renderPreview(state.preview)
            }
        }
    }

    private fun render(state: LiveGrepDialogState) {
        val items = state.matches.toGroupedGrepListItems(project.basePath)
        isRenderingState = true
        try {
            resultModel.replaceAll(items)
            renderCandidateListState(state, items)
            statusLabel.text = state.statusText
            isOKActionEnabled = state.canOpenSelectedMatch
            renderSelectedIndex(items.selectedListIndexFor(state.selectedMatch))
        } finally {
            isRenderingState = false
        }
    }

    private fun renderCandidateListState(state: LiveGrepDialogState, items: List<GrepListItem>) {
        if (state.isSearching) {
            candidateListPanel.showSearching(state.hasSearched)
        } else if (state.hasError) {
            candidateListPanel.showError()
        } else if (state.hasSearched) {
            candidateListPanel.showResults(items.isNotEmpty())
        } else {
            candidateListPanel.showInitialEmptyText()
        }
    }

    private fun renderSelectedIndex(selectedIndex: Int) {
        if (resultList.selectedIndex == selectedIndex) return

        resultList.selectedIndex = selectedIndex
        if (selectedIndex >= 0) {
            resultList.ensureIndexIsVisible(selectedIndex)
        }
    }

    private suspend fun renderPreview(previewState: LiveGrepPreviewState) {
        if (renderedPreviewState == previewState) return

        renderedPreviewState = previewState
        preview.show(
            previewState.content,
            scrollToLine = previewState.scrollToLine,
            highlightRanges = previewState.highlightRanges,
        )
    }

    private fun onCandidateSelected(event: ListSelectionEvent) {
        if (isRenderingState || event.valueIsAdjusting) return

        viewModel.onSelectMatch(resultList.selectedValue?.match)
    }

    private fun dialogModalityContext() = ModalityState.stateForComponent(rootPane).asContextElement()

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
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.ALT_DOWN_MASK), ACTION_TOGGLE_SMART_CASE)

        actionMap.put(ACTION_SELECT_NEXT, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                viewModel.onSelectNextMatch()
            }
        })
        actionMap.put(ACTION_SELECT_PREVIOUS, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                viewModel.onSelectPreviousMatch()
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
        actionMap.put(ACTION_TOGGLE_SMART_CASE, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                optionsPanel.toggleSmartCase()
            }
        })
    }

    private companion object {
        const val ACTION_SELECT_NEXT = "liveGrep.selectNextCandidate"
        const val ACTION_SELECT_PREVIOUS = "liveGrep.selectPreviousCandidate"
        const val ACTION_FOCUS_SEARCH_FIELD = "liveGrep.focusSearchField"
        const val ACTION_TOGGLE_INCLUDE_HIDDEN = "liveGrep.toggleIncludeHidden"
        const val ACTION_TOGGLE_FOLLOW_SYMLINKS = "liveGrep.toggleFollowSymlinks"
        const val ACTION_TOGGLE_RESPECT_GITIGNORE = "liveGrep.toggleRespectGitIgnore"
        const val ACTION_TOGGLE_SMART_CASE = "liveGrep.toggleSmartCase"
        val MENU_SHORTCUT_KEY_MASK = if (SystemInfo.isMac) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK
    }
}

private fun List<GrepListItem>.selectedListIndexFor(selectedMatch: GrepMatch?): Int {
    if (selectedMatch == null) {
        return LiveGrepDialogState.NO_SELECTION
    }
    return indexOfFirst { it.match == selectedMatch }
}

private class FuzzyFinderLiveGrepSearchBackend(
    private val service: FuzzyFinderService,
) : LiveGrepSearchBackend {
    override suspend fun grep(query: String, options: GrepSearchOptions): GrepSearchResult {
        return service.grep(query, options, limit = Int.MAX_VALUE)
    }

    override suspend fun filterMatches(query: String, matches: List<GrepMatch>): List<GrepMatch> {
        return service.filterGrepMatches(query, matches)
    }

    override fun notifyError(message: String) {
        service.notifyError(message)
    }
}
