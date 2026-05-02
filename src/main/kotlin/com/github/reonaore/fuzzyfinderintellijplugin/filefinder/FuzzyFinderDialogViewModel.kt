package com.github.reonaore.fuzzyfinderintellijplugin.filefinder

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.services.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val previewPath: Path? = null,
    val totalCandidates: Int = 0,
    val statusText: String = MyBundle.message("dialog.status.loading"),
) {
    companion object {
        const val NO_SELECTION = -1
    }
}

@OptIn(FlowPreview::class)
class FuzzyFinderDialogViewModel internal constructor(
    scope: CoroutineScope,
    initialOptions: FdSearchOptions,
    private val runSearch: suspend (String, FdSearchOptions) -> SearchResult,
    private val notifyError: (String) -> Unit,
) {
    constructor(
        service: FuzzyFinderService,
        scope: CoroutineScope,
        initialOptions: FdSearchOptions,
    ) : this(
        scope = scope,
        initialOptions = initialOptions,
        runSearch = service::search,
        notifyError = service::notifyError,
    )

    private val query = MutableStateFlow("")
    private val options = MutableStateFlow(initialOptions)

    private val _state = MutableStateFlow(
        FuzzyFinderDialogState(options = initialOptions),
    )
    val state: StateFlow<FuzzyFinderDialogState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(
                query.debounce(SEARCH_DEBOUNCE_MS),
                options,
            ) { latestQuery, latestOptions -> latestQuery to latestOptions }
                .distinctUntilChanged()
                .collectLatest { (latestQuery, latestOptions) ->
                    search(latestQuery, latestOptions)
                }
        }
    }

    fun onQueryChanged(newQuery: String) {
        onUpdateQuery(newQuery)
    }

    fun onUpdateQuery(newQuery: String) {
        query.value = newQuery
    }

    fun onOptionsChanged(newOptions: FdSearchOptions) {
        onUpdateOptions(newOptions)
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

    private suspend fun search(query: String, options: FdSearchOptions) {
        _state.value = _state.value.copy(
            query = query,
            options = options,
            isSearching = true,
            hasError = false,
            statusText = MyBundle.message("dialog.status.searching"),
        )

        try {
            applySearchResult(query, options, runSearch(query, options))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
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
                previewPath = null,
                totalCandidates = 0,
                statusText = MyBundle.message("dialog.status.error"),
            )
            val message = when (e) {
                is FuzzyFinderException -> e.message
                else -> e.localizedMessage
            } ?: MyBundle.message("dialog.status.error")
            notifyError(message)
        }
    }

    private fun applySearchResult(query: String, options: FdSearchOptions, searchResult: SearchResult) {
        val selectedPath = searchResult.results.firstOrNull()
        _state.value = _state.value.copy(
            query = query,
            options = options,
            isSearching = false,
            hasError = false,
            hasSearched = true,
            paths = searchResult.results,
            selectedIndex = if (selectedPath == null) {
                FuzzyFinderDialogState.NO_SELECTION
            } else {
                0
            },
            selectedPath = selectedPath,
            canOpenSelectedFile = selectedPath != null,
            previewPath = selectedPath,
            totalCandidates = searchResult.totalCandidates,
            statusText = MyBundle.message(
                "dialog.status.resultsDetailed",
                searchResult.results.size,
                searchResult.totalCandidates,
            ),
        )
    }

    private fun selectCandidate(index: Int) {
        val paths = _state.value.paths
        if (paths.isEmpty()) {
            _state.value = _state.value.copy(
                selectedIndex = FuzzyFinderDialogState.NO_SELECTION,
                selectedPath = null,
                canOpenSelectedFile = false,
                previewPath = null,
            )
            return
        }

        val nextIndex = index.coerceIn(0, paths.lastIndex)
        val selectedPath = paths[nextIndex]
        _state.value = _state.value.copy(
            selectedIndex = nextIndex,
            selectedPath = selectedPath,
            canOpenSelectedFile = true,
            previewPath = selectedPath,
        )
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180L
    }
}
