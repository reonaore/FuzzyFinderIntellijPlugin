package com.github.reonaore.fuzzyfinderintellijplugin.filefinder

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.CandidateSearchUpdate
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.FuzzyFinderPreviewLoader
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.PreviewContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.nio.file.Path

data class FuzzyFinderDialogState(
    val query: String = "",
    val options: FdSearchOptions,
    val isSearching: Boolean = false,
    val hasError: Boolean = false,
    val hasSearched: Boolean = false,
    val paths: List<Path> = emptyList(),
    val selectedIndex: Int = NO_SELECTION,
    val selectedPath: Path? = null,
    val canOpenSelectedFile: Boolean = false,
    val preview: FuzzyFinderPreviewState = FuzzyFinderPreviewState.Empty,
    val totalCandidates: Int = 0,
    val statusText: String = MyBundle.message("dialog.status.loading"),
) {
    companion object {
        const val NO_SELECTION = -1
    }
}

sealed interface FuzzyFinderPreviewState {
    val content: PreviewContent

    data object Empty : FuzzyFinderPreviewState {
        override val content: PreviewContent = PreviewContent.empty
    }

    data class Loading(
        val path: Path,
        override val content: PreviewContent = PreviewContent(
            text = MyBundle.message("dialog.preview.loading"),
            virtualFile = null,
        ),
    ) : FuzzyFinderPreviewState

    data class Ready(
        val path: Path,
        override val content: PreviewContent,
    ) : FuzzyFinderPreviewState
}

internal interface FuzzyFinderSearchBackend {
    fun candidateStream(options: FdSearchOptions): Flow<CandidateSearchUpdate>

    suspend fun filterCandidates(query: String, candidates: List<Path>): List<Path>

    fun notifyError(message: String)
}

@OptIn(FlowPreview::class)
class FuzzyFinderDialogViewModel internal constructor(
    private val backend: FuzzyFinderSearchBackend,
    private val scope: CoroutineScope,
    initialOptions: FdSearchOptions,
    private val loadPreview: suspend (Path) -> PreviewContent,
) {
    constructor(
        service: FuzzyFinderService,
        scope: CoroutineScope,
        initialOptions: FdSearchOptions,
    ) : this(
        backend = FuzzyFinderServiceSearchBackend(service),
        scope = scope,
        initialOptions = initialOptions,
        loadPreview = FuzzyFinderPreviewLoader()::load,
    )

    private val query = MutableStateFlow("")
    private val options = MutableStateFlow(initialOptions)

    private val _state = MutableStateFlow(
        FuzzyFinderDialogState(options = initialOptions),
    )
    val state: StateFlow<FuzzyFinderDialogState> = _state.asStateFlow()
    private var previewJob: Job? = null
    private var cachedCandidates: List<Path> = emptyList()
    private var cachedOptions: FdSearchOptions? = null
    private var isCandidateLoading = false

    init {
        scope.launch {
            options
                .collectLatest { latestOptions ->
                    collectCandidates(latestOptions)
                }
        }
        scope.launch {
            query
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { latestQuery ->
                    search(latestQuery, options.value)
                }
        }
    }

    fun onUpdateQuery(newQuery: String) {
        query.value = newQuery
    }

    fun onUpdateOptions(newOptions: FdSearchOptions) {
        options.value = newOptions
    }

    fun onSelectCandidate(index: Int) {
        selectCandidate(index)
    }

    fun onSelectNextCandidate() {
        selectCandidate(_state.value.selectedIndex + 1)
    }

    fun onSelectPreviousCandidate() {
        val currentIndex = _state.value.selectedIndex.takeIf { it >= 0 } ?: 0
        selectCandidate(currentIndex - 1)
    }

    private suspend fun collectCandidates(options: FdSearchOptions) {
        isCandidateLoading = true
        cachedOptions = options
        cachedCandidates = emptyList()
        showSearching(query.value, options)
        try {
            backend.candidateStream(options).collect { update ->
                cachedCandidates = update.candidates
                val latestQuery = query.value
                applySearchResult(
                    query = latestQuery,
                    options = options,
                    results = filteredCandidates(latestQuery),
                    totalCandidates = update.totalCandidates,
                    isComplete = update.isComplete,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            applyError(query.value, options, e)
        } finally {
            isCandidateLoading = false
        }
    }

    private suspend fun search(query: String, options: FdSearchOptions) {
        if (cachedOptions != options) {
            _state.value = _state.value.copy(
                query = query,
                options = options,
                isSearching = isCandidateLoading,
                hasError = false,
            )
            return
        }

        showSearching(query, options)
        try {
            applySearchResult(
                query = query,
                options = options,
                results = filteredCandidates(query),
                totalCandidates = cachedCandidates.size,
                isComplete = !isCandidateLoading,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            applyError(query, options, e)
        }
    }

    private suspend fun filteredCandidates(query: String): List<Path> {
        return backend.filterCandidates(query, cachedCandidates)
    }

    private fun showSearching(query: String, options: FdSearchOptions) {
        _state.value = _state.value.copy(
            query = query,
            options = options,
            isSearching = true,
            hasError = false,
            statusText = MyBundle.message("dialog.status.searching"),
        )
    }

    private fun applyError(query: String, options: FdSearchOptions, error: Throwable) {
        clearPreview()
        _state.value = _state.value.copy(
            query = query,
            options = options,
            isSearching = false,
            hasError = true,
            hasSearched = true,
            paths = emptyList(),
            selectedIndex = FuzzyFinderDialogState.NO_SELECTION,
            selectedPath = null,
            canOpenSelectedFile = false,
            preview = FuzzyFinderPreviewState.Empty,
            totalCandidates = 0,
            statusText = MyBundle.message("dialog.status.error"),
        )
        val message = when (error) {
            is FuzzyFinderException -> error.message
            else -> error.localizedMessage
        } ?: MyBundle.message("dialog.status.error")
        backend.notifyError(message)
    }

    private fun applySearchResult(
        query: String,
        options: FdSearchOptions,
        results: List<Path>,
        totalCandidates: Int,
        isComplete: Boolean,
    ) {
        if (results.isEmpty()) {
            _state.value = _state.value.copy(
                query = query,
                options = options,
                isSearching = !isComplete,
                hasError = false,
                hasSearched = true,
                paths = emptyList(),
                selectedIndex = FuzzyFinderDialogState.NO_SELECTION,
                selectedPath = null,
                canOpenSelectedFile = false,
                preview = FuzzyFinderPreviewState.Empty,
                totalCandidates = totalCandidates,
                statusText = MyBundle.message(
                    "dialog.status.resultsDetailed",
                    0,
                    totalCandidates,
                ),
            )
            loadSelectedPreview(null)
            return
        }

        val previousSelection = _state.value.selectedPath
        val selectedPath = previousSelection?.takeIf(results::contains) ?: results.first()
        val selectedIndex = results.indexOf(selectedPath)
        _state.value = _state.value.copy(
            query = query,
            options = options,
            isSearching = !isComplete,
            hasError = false,
            hasSearched = true,
            paths = results,
            selectedIndex = selectedIndex,
            selectedPath = selectedPath,
            canOpenSelectedFile = true,
            preview = if (previousSelection == selectedPath) {
                _state.value.preview
            } else {
                previewStateFor(selectedPath)
            },
            totalCandidates = totalCandidates,
            statusText = MyBundle.message(
                "dialog.status.resultsDetailed",
                results.size,
                totalCandidates,
            ),
        )
        if (previousSelection != selectedPath) {
            loadSelectedPreview(selectedPath)
        }
    }

    private fun selectCandidate(index: Int) {
        val paths = _state.value.paths
        if (paths.isEmpty()) {
            clearPreview()
            _state.value = _state.value.copy(
                selectedIndex = FuzzyFinderDialogState.NO_SELECTION,
                selectedPath = null,
                canOpenSelectedFile = false,
                preview = FuzzyFinderPreviewState.Empty,
            )
            return
        }

        val nextIndex = index.coerceIn(0, paths.lastIndex)
        val selectedPath = paths[nextIndex]
        _state.value = _state.value.copy(
            selectedIndex = nextIndex,
            selectedPath = selectedPath,
            canOpenSelectedFile = true,
            preview = FuzzyFinderPreviewState.Loading(selectedPath),
        )
        loadSelectedPreview(selectedPath)
    }

    private fun previewStateFor(path: Path?): FuzzyFinderPreviewState {
        return path?.let(FuzzyFinderPreviewState::Loading) ?: FuzzyFinderPreviewState.Empty
    }

    private fun clearPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    private fun loadSelectedPreview(path: Path?) {
        clearPreview()
        if (path == null) {
            return
        }

        previewJob = scope.launch {
            val content = loadPreview(path)
            if (_state.value.selectedPath != path) {
                return@launch
            }
            _state.value = _state.value.copy(
                preview = FuzzyFinderPreviewState.Ready(path, content),
            )
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180L
    }
}

private class FuzzyFinderServiceSearchBackend(
    private val service: FuzzyFinderService,
) : FuzzyFinderSearchBackend {
    override fun candidateStream(options: FdSearchOptions): Flow<CandidateSearchUpdate> {
        return service.candidateStream(options)
    }

    override suspend fun filterCandidates(query: String, candidates: List<Path>): List<Path> {
        return service.filterCandidates(query, candidates)
    }

    override fun notifyError(message: String) {
        service.notifyError(message)
    }
}
