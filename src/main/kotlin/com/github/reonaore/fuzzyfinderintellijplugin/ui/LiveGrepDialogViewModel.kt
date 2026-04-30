package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderService
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class LiveGrepDialogState(
    val rgQuery: String = "",
    val fzfQuery: String = "",
    val options: GrepSearchOptions,
    val isSearching: Boolean = false,
    val hasError: Boolean = false,
    val hasSearched: Boolean = false,
    val matches: List<GrepMatch> = emptyList(),
    val totalMatches: Int = 0,
    val statusText: String = MyBundle.message("dialog.grep.status.ready"),
)

@OptIn(FlowPreview::class)
class LiveGrepDialogViewModel internal constructor(
    scope: CoroutineScope,
    initialOptions: GrepSearchOptions,
    private val runGrep: suspend (String, GrepSearchOptions) -> GrepSearchResult,
    private val filterMatches: suspend (String, List<GrepMatch>) -> List<GrepMatch>,
    private val notifyError: (String) -> Unit,
) {
    constructor(
        service: FuzzyFinderService,
        scope: CoroutineScope,
        initialOptions: GrepSearchOptions,
    ) : this(
        scope = scope,
        initialOptions = initialOptions,
        runGrep = { query, options -> service.grep(query, options, limit = Int.MAX_VALUE) },
        filterMatches = service::filterGrepMatches,
        notifyError = service::notifyError,
    )

    private val rgQuery = MutableStateFlow("")
    private val fzfQuery = MutableStateFlow("")
    private val options = MutableStateFlow(initialOptions)

    private val _state = MutableStateFlow(
        LiveGrepDialogState(options = initialOptions),
    )
    val state: StateFlow<LiveGrepDialogState> = _state.asStateFlow()

    private var cachedRgMatches: List<GrepMatch> = emptyList()
    private var cachedRgTotalMatches: Int = 0

    init {
        scope.launch {
            var previousRgQuery: String? = null
            var previousOptions: GrepSearchOptions? = null

            combine(
                rgQuery.debounce(SEARCH_DEBOUNCE_MS),
                fzfQuery.debounce(SEARCH_DEBOUNCE_MS),
                options,
            ) { latestRgQuery, latestFzfQuery, latestOptions ->
                LiveGrepSearchRequest(latestRgQuery, latestFzfQuery, latestOptions)
            }.collectLatest { request ->
                val shouldRunGrep = request.rgQuery != previousRgQuery || request.options != previousOptions
                if (shouldRunGrep) {
                    previousRgQuery = request.rgQuery
                    previousOptions = request.options
                    searchWithGrep(request)
                } else {
                    searchWithFzf(request)
                }
            }
        }
    }

    fun onRgQueryChanged(newQuery: String) {
        rgQuery.value = newQuery
    }

    fun onFzfQueryChanged(newQuery: String) {
        fzfQuery.value = newQuery
    }

    fun onOptionsChanged(newOptions: GrepSearchOptions) {
        options.value = newOptions
    }

    private suspend fun searchWithGrep(request: LiveGrepSearchRequest) {
        if (request.rgQuery.isBlank()) {
            cachedRgMatches = emptyList()
            cachedRgTotalMatches = 0
            _state.value = LiveGrepDialogState(
                rgQuery = request.rgQuery,
                fzfQuery = request.fzfQuery,
                options = request.options,
            )
            return
        }

        showSearching(request)
        try {
            val result = runGrep(request.rgQuery, request.options)
            cachedRgMatches = result.matches
            cachedRgTotalMatches = result.totalMatches
            applyFilteredResult(request, filteredMatches(request.fzfQuery))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            applyError(request, e)
        }
    }

    private suspend fun searchWithFzf(request: LiveGrepSearchRequest) {
        if (request.rgQuery.isBlank()) {
            _state.value = LiveGrepDialogState(
                rgQuery = request.rgQuery,
                fzfQuery = request.fzfQuery,
                options = request.options,
            )
            return
        }

        showSearching(request)
        try {
            applyFilteredResult(request, filteredMatches(request.fzfQuery))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            applyError(request, e)
        }
    }

    private suspend fun filteredMatches(query: String): List<GrepMatch> {
        return if (query.isBlank()) {
            cachedRgMatches.take(MAX_RESULTS)
        } else {
            filterMatches(query, cachedRgMatches)
        }
    }

    private fun showSearching(request: LiveGrepSearchRequest) {
        _state.value = _state.value.copy(
            rgQuery = request.rgQuery,
            fzfQuery = request.fzfQuery,
            options = request.options,
            isSearching = true,
            hasError = false,
            statusText = MyBundle.message("dialog.status.searching"),
        )
    }

    private fun applyFilteredResult(request: LiveGrepSearchRequest, matches: List<GrepMatch>) {
        _state.value = _state.value.copy(
            rgQuery = request.rgQuery,
            fzfQuery = request.fzfQuery,
            options = request.options,
            isSearching = false,
            hasError = false,
            hasSearched = true,
            matches = matches,
            totalMatches = cachedRgTotalMatches,
            statusText = MyBundle.message(
                "dialog.grep.status.resultsDetailed",
                matches.size,
                cachedRgTotalMatches,
            ),
        )
    }

    private fun applyError(request: LiveGrepSearchRequest, error: Throwable) {
        _state.value = _state.value.copy(
            rgQuery = request.rgQuery,
            fzfQuery = request.fzfQuery,
            options = request.options,
            isSearching = false,
            hasError = true,
            hasSearched = true,
            matches = emptyList(),
            totalMatches = 0,
            statusText = MyBundle.message("dialog.status.error"),
        )
        val message = when (error) {
            is FuzzyFinderException -> error.message
            else -> error.localizedMessage
        } ?: MyBundle.message("dialog.status.error")
        notifyError(message)
    }

    private data class LiveGrepSearchRequest(
        val rgQuery: String,
        val fzfQuery: String,
        val options: GrepSearchOptions,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180L
        const val MAX_RESULTS = 200
    }
}
