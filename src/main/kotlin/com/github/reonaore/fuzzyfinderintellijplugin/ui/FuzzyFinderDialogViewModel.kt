package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.services.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.nio.file.Path

data class FuzzyFinderDialogState(
    val query: String = "",
    val options: FdSearchOptions,
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val paths: List<Path> = emptyList(),
    val totalCandidates: Int = 0,
    val statusText: String = MyBundle.message("dialog.status.loading"),
)

@OptIn(FlowPreview::class)
class FuzzyFinderDialogViewModel(
    private val service: FuzzyFinderService,
    private val scope: CoroutineScope,
    initialOptions: FdSearchOptions,
) {
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
                .collect { (latestQuery, latestOptions) ->
                    search(latestQuery, latestOptions)
                }
        }
    }

    fun onQueryChanged(newQuery: String) {
        query.value = newQuery
    }

    fun onOptionsChanged(newOptions: FdSearchOptions) {
        options.value = newOptions
    }

    private suspend fun search(query: String, options: FdSearchOptions) {
        _state.value = _state.value.copy(
            query = query,
            options = options,
            isSearching = true,
            statusText = MyBundle.message("dialog.status.searching"),
        )

        runCatching { service.search(query, options) }
            .onSuccess { applySearchResult(query, options, it) }
            .onFailure { throwable ->
                _state.value = _state.value.copy(
                    query = query,
                    options = options,
                    isSearching = false,
                    hasSearched = true,
                    statusText = MyBundle.message("dialog.status.error"),
                )
                val message = when (throwable) {
                    is FuzzyFinderException -> throwable.message
                    else -> throwable.localizedMessage
                } ?: MyBundle.message("dialog.status.error")
                service.notifyError(message)
            }
    }

    private fun applySearchResult(query: String, options: FdSearchOptions, searchResult: SearchResult) {
        _state.value = _state.value.copy(
            query = query,
            options = options,
            isSearching = false,
            hasSearched = true,
            paths = searchResult.results,
            totalCandidates = searchResult.totalCandidates,
            statusText = MyBundle.message(
                "dialog.status.resultsDetailed",
                searchResult.results.size,
                searchResult.totalCandidates,
            ),
        )
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180L
    }
}
