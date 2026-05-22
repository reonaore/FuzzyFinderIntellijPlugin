package com.github.reonaore.fuzzyfinderintellijplugin.tabfinder

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.fuzzyFinderSearchTextField
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.onTextChanged
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.CollectionListModel
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
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.ListSelectionEvent

class TabFinderDialog(
    private val project: Project,
) : DialogWrapper(project, false) {
    private val service = project.service<FuzzyFinderService>()
    private val statusLabel = JBLabel(MyBundle.message("dialog.tabs.status.open", 0))
    private val resultModel = CollectionListModel<TabListItem>()
    private val resultList = tabFinderList(
        resultModel,
        this::onCandidateSelected,
    ) { event ->
        if (event.clickCount == 2) {
            doOKAction()
        }
    }
    private val searchField = fuzzyFinderSearchTextField(MyBundle.message("dialog.tabs.search.placeholder"))
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val viewModel = TabFinderDialogViewModel(
        backend = FuzzyFinderTabSearchBackend(service),
        scope = dialogScope,
    )
    private val messageBusConnection = project.messageBus.connect()
    private var isRenderingState = false

    init {
        title = MyBundle.message("dialog.tabs.title")
        setOKButtonText(MyBundle.message("dialog.open"))
        isOKActionEnabled = false
        init()
        bind()
        reloadOpenTabs()
    }

    override fun createCenterPanel(): JComponent {
        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(640, 420)
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(resultList), BorderLayout.CENTER)
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
        messageBusConnection.disconnect()
        dialogScope.cancel()
        super.dispose()
    }

    override fun doOKAction() {
        val selected = viewModel.state.value.selectedCandidate?.file ?: return
        if (!selected.isValid) return

        FileEditorManager.getInstance(project).openFile(selected, true)
        super.doOKAction()
    }

    private fun bind() {
        searchField.onTextChanged {
            viewModel.onUpdateQuery(searchField.text)
        }
        observeState()
        messageBusConnection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
                    reloadOpenTabs()
                }

                override fun fileClosed(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
                    reloadOpenTabs()
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    reloadOpenTabs()
                }
            },
        )
    }

    private fun observeState() {
        dialogScope.launch(dialogModalityContext()) {
            viewModel.state.collectLatest { state ->
                withContext(Dispatchers.EDT) {
                    render(state)
                }
            }
        }
    }

    private fun render(state: TabFinderDialogState) {
        val items = state.candidates.map { it.toTabListItem(state.query) }
        isRenderingState = true
        try {
            resultModel.replaceAll(items)
            resultList.emptyText.text = if (state.hasOpenTabs) {
                MyBundle.message("dialog.candidates.empty")
            } else {
                MyBundle.message("dialog.tabs.empty")
            }
            statusLabel.text = state.statusText
            isOKActionEnabled = state.canActivateSelectedTab
            renderSelectedIndex(state.selectedIndex)
        } finally {
            isRenderingState = false
        }
    }

    private fun renderSelectedIndex(selectedIndex: Int) {
        if (resultList.selectedIndex == selectedIndex) return

        resultList.selectedIndex = selectedIndex
        if (selectedIndex >= 0) {
            resultList.ensureIndexIsVisible(selectedIndex)
        }
    }

    private fun reloadOpenTabs() {
        val files = FileEditorManager.getInstance(project)
            .openFiles
            .filter { it.isValid }
            .map { it.toOpenTabCandidate(project.basePath) }
        viewModel.onUpdateOpenTabs(files)
    }

    private fun onCandidateSelected(event: ListSelectionEvent) {
        if (isRenderingState || event.valueIsAdjusting) return

        viewModel.onSelectCandidate(resultList.selectedIndex)
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
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU_SHORTCUT_KEY_MASK), ACTION_FOCUS_SEARCH_FIELD)

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
    }

    private companion object {
        const val ACTION_SELECT_NEXT = "tabFinder.selectNextCandidate"
        const val ACTION_SELECT_PREVIOUS = "tabFinder.selectPreviousCandidate"
        const val ACTION_FOCUS_SEARCH_FIELD = "tabFinder.focusSearchField"
        val MENU_SHORTCUT_KEY_MASK = if (SystemInfo.isMac) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK
    }
}

private class FuzzyFinderTabSearchBackend(
    private val service: FuzzyFinderService,
) : TabFinderBackend {
    override suspend fun filterCandidates(
        query: String,
        candidates: List<OpenTabCandidate>,
    ): List<OpenTabCandidate> {
        return service.filterIndexedRecords(query, candidates.map(OpenTabCandidate::displayText))
            .mapNotNull(candidates::getOrNull)
    }

    override fun notifyError(message: String) {
        service.notifyError(message)
    }
}
