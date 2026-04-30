package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewHighlightRange
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.ListSelectionEvent

class LiveGrepDialog(
    private val project: Project,
    private val initialQuery: String = "",
) : DialogWrapper(project, false) {

    private val service = project.service<FuzzyFinderService>()
    private val optionsPanel = LiveGrepOptionsPanel { handleRgQueryChanged() }
    private val previewLoader = FuzzyFinderPreviewLoader()
    private val statusLabel = JBLabel(MyBundle.message("dialog.grep.status.ready"))
    private val resultModel = CollectionListModel<GrepListItem>()
    private val resultList = liveGrepMatchList(
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
        MyBundle.message("dialog.grep.candidates.prompt"),
    )
    private val preview = FuzzyFinderPreview(project)
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rgSearchJob: Job? = null
    private var fzfSearchJob: Job? = null
    private var previewJob: Job? = null
    private var cachedRgMatches: List<GrepMatch> = emptyList()
    private var visibleMatches: List<GrepMatch> = emptyList()
    private var suppressRgSearchEvents = false
    private var suppressFzfSearchEvents = false
    private val rgSearchTimer = Timer(SEARCH_DEBOUNCE_MS) { triggerRgSearch() }.apply {
        isRepeats = false
    }
    private val fzfSearchTimer = Timer(SEARCH_DEBOUNCE_MS) { triggerFzfSearch() }.apply {
        isRepeats = false
    }
    private val searchField = fuzzyFinderSearchTextField(placeHolderText = MyBundle.message("dialog.grep.search.placeholder")) {
        if (!suppressRgSearchEvents) {
            handleRgQueryChanged()
        }
    }
    private val fzfSearchField = fuzzyFinderSearchTextField(placeHolderText = MyBundle.message("dialog.grep.fuzzy.placeholder")) {
        if (!suppressFzfSearchEvents) {
            fzfSearchTimer.restart()
        }
    }

    init {
        title = MyBundle.message("dialog.grep.title")
        setOKButtonText(MyBundle.message("dialog.open"))
        isOKActionEnabled = false
        init()
        applyInitialQuery()
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
        rgSearchTimer.stop()
        fzfSearchTimer.stop()
        dialogScope.cancel()
        candidateListPanel.dispose()
        preview.dispose()
        super.dispose()
    }

    override fun doOKAction() {
        val match = resultList.selectedValue?.match ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(match.path) ?: return
        if (virtualFile.isDirectory) return

        OpenFileDescriptor(project, virtualFile, match.line - 1, match.column - 1).navigate(true)
        super.doOKAction()
    }

    private fun handleRgQueryChanged() {
        clearFzfQuery()
        rgSearchTimer.restart()
    }

    private fun triggerRgSearch() {
        rgSearchJob?.cancel()
        fzfSearchJob?.cancel()
        fzfSearchTimer.stop()
        val query = searchField.text
        val options = optionsPanel.currentOptions()
        if (query.isBlank()) {
            showGrepPrompt()
            return
        }
        statusLabel.text = MyBundle.message("dialog.status.searching")
        candidateListPanel.showSearching(resultModel.size > 0)

        rgSearchJob = dialogScope.launch(ModalityState.defaultModalityState().asContextElement()) {
            val res = service.grep(query, options, limit = Int.MAX_VALUE)
            cachedRgMatches = res.matches
            val fzfQuery = withContext(Dispatchers.EDT) {
                fzfSearchField.text
            }
            if (fzfQuery.isBlank()) {
                applySearchResult(res.matches.take(MAX_RESULTS), res.totalMatches)
            } else {
                withContext(Dispatchers.EDT) {
                    startFzfSearch(fzfQuery, showSearching = false)
                }
            }
        }.also {
            it.invokeOnCompletion { e ->
                if (e is FuzzyFinderException) {
                    dialogScope.launch(Dispatchers.EDT) {
                        statusLabel.text = MyBundle.message("dialog.status.error")
                        candidateListPanel.showError()
                        service.notifyError(e.message ?: MyBundle.message("dialog.status.error"))
                    }
                }
            }
        }
    }

    private fun applyInitialQuery() {
        if (initialQuery.isBlank()) return

        suppressRgSearchEvents = true
        try {
            searchField.text = initialQuery
            searchField.textEditor.caretPosition = initialQuery.length
        } finally {
            suppressRgSearchEvents = false
        }
        SwingUtilities.invokeLater {
            if (!isDisposed) {
                rgSearchTimer.restart()
            }
        }
    }

    private fun clearFzfQuery() {
        if (fzfSearchField.text.isEmpty()) return

        suppressFzfSearchEvents = true
        try {
            fzfSearchField.text = ""
        } finally {
            suppressFzfSearchEvents = false
        }
    }

    private fun triggerFzfSearch() {
        startFzfSearch(fzfSearchField.text)
    }

    private fun startFzfSearch(query: String, showSearching: Boolean = true) {
        fzfSearchJob?.cancel()
        if (showSearching) {
            statusLabel.text = MyBundle.message("dialog.status.searching")
            candidateListPanel.showSearching(resultModel.size > 0)
        }
        if (query.isBlank()) {
            fzfSearchJob = dialogScope.launch(ModalityState.defaultModalityState().asContextElement()) {
                applySearchResult(cachedRgMatches.take(MAX_RESULTS), cachedRgMatches.size)
            }
            return
        }

        fzfSearchJob = dialogScope.launch(ModalityState.defaultModalityState().asContextElement()) {
            applyFilteredSearchResult(query)
        }.also {
            it.invokeOnCompletion { e ->
                if (e is FuzzyFinderException) {
                    dialogScope.launch(Dispatchers.EDT) {
                        statusLabel.text = MyBundle.message("dialog.status.error")
                        candidateListPanel.showError()
                        service.notifyError(e.message ?: MyBundle.message("dialog.status.error"))
                    }
                }
            }
        }
    }

    private suspend fun applyFilteredSearchResult(query: String) {
        val matches = service.filterGrepMatches(query, cachedRgMatches)
        applySearchResult(matches, cachedRgMatches.size)
    }

    private suspend fun applySearchResult(matches: List<GrepMatch>, totalMatches: Int) {
        val items = matches.toGroupedGrepListItems(project.basePath)
        withContext(Dispatchers.EDT) {
            visibleMatches = matches
            resultModel.replaceAll(items)
            candidateListPanel.showResults(matches.isNotEmpty())
            statusLabel.text = MyBundle.message(
                "dialog.grep.status.resultsDetailed",
                matches.size,
                totalMatches,
            )
            if (matches.isNotEmpty()) {
                resultList.selectedIndex = firstMatchIndex(items)
            } else {
                isOKActionEnabled = false
            }
        }
        updatePreview()
    }

    private fun showGrepPrompt() {
        cachedRgMatches = emptyList()
        visibleMatches = emptyList()
        resultModel.replaceAll(emptyList())
        candidateListPanel.showInitialEmptyText()
        statusLabel.text = MyBundle.message("dialog.grep.status.ready")
        isOKActionEnabled = false
        updatePreview()
    }

    private fun updatePreview(@Suppress("UNUSED_PARAMETER") event: ListSelectionEvent? = null) {
        previewJob?.cancel()
        previewJob = dialogScope.launch(ModalityState.defaultModalityState().asContextElement()) {
            val selected = withContext(Dispatchers.EDT) {
                resultList.selectedValue?.match
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
            val previewContent = previewLoader.load(selected.path)
            preview.show(
                previewContent,
                scrollToLine = selected.line,
                highlightRanges = previewHighlightsFor(selected),
            )
        }
    }

    private fun previewHighlightsFor(selected: GrepMatch): List<PreviewHighlightRange> {
        return visibleMatches
            .asSequence()
            .filter { it.path == selected.path }
            .flatMap { match ->
                match.matchRanges.map { range ->
                    PreviewHighlightRange(line = match.line, range = range)
                }
            }
            .toList()
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
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.ALT_DOWN_MASK), ACTION_TOGGLE_SMART_CASE)

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
        actionMap.put(ACTION_TOGGLE_SMART_CASE, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                optionsPanel.toggleSmartCase()
            }
        })
    }

    private fun moveSelectionBy(offset: Int) {
        val lastIndex = resultModel.size - 1
        if (lastIndex < 0) return

        val currentIndex = resultList.selectedIndex.takeIf { it >= 0 } ?: 0
        val nextIndex = nextMatchIndex(currentIndex, offset, lastIndex)
        if (nextIndex == resultList.selectedIndex) return

        resultList.selectedIndex = nextIndex
        resultList.ensureIndexIsVisible(nextIndex)
    }

    private fun nextMatchIndex(currentIndex: Int, offset: Int, lastIndex: Int): Int {
        var nextIndex = (currentIndex + offset).coerceIn(0, lastIndex)
        while (nextIndex in 0..lastIndex && resultModel.getElementAt(nextIndex).match == null) {
            val candidate = (nextIndex + offset.coerceIn(-1, 1)).coerceIn(0, lastIndex)
            if (candidate == nextIndex) {
                break
            }
            nextIndex = candidate
        }
        return nextIndex
    }

    private companion object {
        const val ACTION_SELECT_NEXT = "liveGrep.selectNextCandidate"
        const val ACTION_SELECT_PREVIOUS = "liveGrep.selectPreviousCandidate"
        const val ACTION_FOCUS_SEARCH_FIELD = "liveGrep.focusSearchField"
        const val ACTION_TOGGLE_INCLUDE_HIDDEN = "liveGrep.toggleIncludeHidden"
        const val ACTION_TOGGLE_FOLLOW_SYMLINKS = "liveGrep.toggleFollowSymlinks"
        const val ACTION_TOGGLE_RESPECT_GITIGNORE = "liveGrep.toggleRespectGitIgnore"
        const val ACTION_TOGGLE_SMART_CASE = "liveGrep.toggleSmartCase"
        const val SEARCH_DEBOUNCE_MS = 180
        const val MAX_RESULTS = 200
        val MENU_SHORTCUT_KEY_MASK = if (SystemInfo.isMac) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK
    }
}
