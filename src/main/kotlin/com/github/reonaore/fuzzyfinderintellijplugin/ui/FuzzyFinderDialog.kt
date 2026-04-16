package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.services.SearchResult
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
import java.nio.file.Path
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.ListSelectionEvent
import kotlin.coroutines.resume

class FuzzyFinderDialog(private val project: Project) : DialogWrapper(project, false) {

    private val service = project.service<FuzzyFinderService>()
    private val optionsPanel = FuzzyFinderOptionsPanel { searchTimer.restart() }
    private val previewLoader = FuzzyFinderPreviewLoader()
    private val statusLabel = JBLabel(MyBundle.message("dialog.status.loading"))
    private val resultModel = CollectionListModel<Path>()
    private val resultList = fuzzyFinderFileList(
        resultModel,
        project.basePath,
        this::updatePreview,

        ) { event ->
        if (event.clickCount == 2) {
            doOKAction()
        }
    }
    private val preview = FuzzyFinderPreview(project)
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val streamedCandidates = mutableListOf<Path>()
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var searchGeneration = 0L
    private var previewGeneration = 0L
    private val searchTimer = Timer(SEARCH_DEBOUNCE_MS) { triggerSearch() }.apply {
        isRepeats = false
    }
    private val searchField = fuzzyFinderSearchTextField {
        searchTimer.restart()
    }

    init {
        title = MyBundle.message("dialog.title")
        setOKButtonText(MyBundle.message("dialog.open"))
        isOKActionEnabled = false
        init()
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
            secondComponent = preview.editor.component
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
        preview.dispose()
        super.dispose()
    }

    override fun doOKAction() {
        val virtualFile = selectedVirtualFile() ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
        super.doOKAction()
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
            preview.show(PreviewContent.empty)
        }
    }

    private fun updatePreview(@Suppress("UNUSED_PARAMETER") event: ListSelectionEvent) {
        val selected = resultList.selectedValue ?: run {
            previewJob?.cancel()
            isOKActionEnabled = false
            preview.show(PreviewContent.empty)
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
                    preview.show(previewContent)
                }
            } catch (_: CancellationException) {
            }
        }
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
