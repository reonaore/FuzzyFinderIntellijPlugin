package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewHighlightRange
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchUpdate
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

data class LiveGrepDialogState(
    val rgQuery: String = "",
    val fzfQuery: String = "",
    val options: GrepSearchOptions,
    val isSearching: Boolean = false,
    val hasError: Boolean = false,
    val hasSearched: Boolean = false,
    val matches: List<GrepMatch> = emptyList(),
    val selectedMatchIndex: Int = NO_SELECTION,
    val selectedMatch: GrepMatch? = null,
    val canOpenSelectedMatch: Boolean = false,
    val preview: LiveGrepPreviewState = LiveGrepPreviewState.Empty,
    val totalMatches: Int = 0,
    val statusText: String = MyBundle.message("dialog.grep.status.ready"),
) {
    companion object {
        const val NO_SELECTION = -1
    }
}

sealed interface LiveGrepPreviewState {
    val content: PreviewContent
    val scrollToLine: Int?
    val highlightRanges: List<PreviewHighlightRange>

    data object Empty : LiveGrepPreviewState {
        override val content: PreviewContent = PreviewContent.empty
        override val scrollToLine: Int? = null
        override val highlightRanges: List<PreviewHighlightRange> = emptyList()
    }

    data class Loading(
        val match: GrepMatch,
        override val content: PreviewContent = PreviewContent(
            text = MyBundle.message("dialog.preview.loading"),
            virtualFile = null,
        ),
        override val scrollToLine: Int? = match.line,
        override val highlightRanges: List<PreviewHighlightRange> = emptyList(),
    ) : LiveGrepPreviewState

    data class Ready(
        val match: GrepMatch,
        override val content: PreviewContent,
        override val scrollToLine: Int,
        override val highlightRanges: List<PreviewHighlightRange>,
    ) : LiveGrepPreviewState
}

internal interface LiveGrepSearchBackend {
    fun grepStream(query: String, options: GrepSearchOptions): Flow<GrepSearchUpdate>

    suspend fun filterMatches(query: String, matches: List<GrepMatch>): List<GrepMatch>

    fun notifyError(message: String)
}

@OptIn(FlowPreview::class)
class LiveGrepDialogViewModel internal constructor(
    private val backend: LiveGrepSearchBackend,
    private val scope: CoroutineScope,
    initialOptions: GrepSearchOptions,
    private val loadPreview: suspend (Path) -> PreviewContent = FuzzyFinderPreviewLoader()::load,
) {
    private val rgQuery = MutableStateFlow("")
    private val fzfQuery = MutableStateFlow("")
    private val options = MutableStateFlow(initialOptions)

    private val _state = MutableStateFlow(
        LiveGrepDialogState(options = initialOptions),
    )
    val state: StateFlow<LiveGrepDialogState> = _state.asStateFlow()
    private var previewJob: Job? = null

    private var cachedRgMatches: List<GrepMatch> = emptyList()
    private var cachedRgTotalMatches: Int = 0
    private var cachedRgQuery: String? = null
    private var cachedRgOptions: GrepSearchOptions? = null
    private var isGrepSearching = false

    init {
        scope.launch {
            combine(
                rgQuery.debounce(SEARCH_DEBOUNCE_MS),
                options,
            ) { latestRgQuery, latestOptions ->
                latestRgQuery to latestOptions
            }
                .distinctUntilChanged()
                .collectLatest { (latestRgQuery, latestOptions) ->
                    searchWithGrep(
                        LiveGrepSearchRequest(
                            rgQuery = latestRgQuery,
                            fzfQuery = fzfQuery.value,
                            options = latestOptions,
                        ),
                    )
                }
        }
        scope.launch {
            fzfQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { latestFzfQuery ->
                    searchWithFzf(
                        LiveGrepSearchRequest(
                            rgQuery = rgQuery.value,
                            fzfQuery = latestFzfQuery,
                            options = options.value,
                        ),
                    )
                }
        }
    }

    fun onUpdateRgQuery(newQuery: String) {
        rgQuery.value = newQuery
    }

    fun onUpdateFzfQuery(newQuery: String) {
        fzfQuery.value = newQuery
    }

    fun onUpdateOptions(newOptions: GrepSearchOptions) {
        options.value = newOptions
    }

    fun onSelectMatch(match: GrepMatch?) {
        selectMatch(match)
    }

    fun onSelectNextMatch() {
        selectMatchByIndex(_state.value.selectedMatchIndex + 1)
    }

    fun onSelectPreviousMatch() {
        val currentIndex = _state.value.selectedMatchIndex.takeIf { it >= 0 } ?: 0
        selectMatchByIndex(currentIndex - 1)
    }

    private suspend fun searchWithGrep(request: LiveGrepSearchRequest) {
        if (request.rgQuery.isBlank()) {
            cachedRgMatches = emptyList()
            cachedRgTotalMatches = 0
            clearPreview()
            _state.value = LiveGrepDialogState(
                rgQuery = request.rgQuery,
                fzfQuery = request.fzfQuery,
                options = request.options,
            )
            return
        }

        showSearching(request)
        try {
            isGrepSearching = true
            cachedRgQuery = request.rgQuery
            cachedRgOptions = request.options
            cachedRgMatches = emptyList()
            cachedRgTotalMatches = 0
            backend.grepStream(request.rgQuery, request.options).collect { update ->
                cachedRgMatches = update.matches
                cachedRgTotalMatches = update.totalMatches
                val latestRequest = request.copy(fzfQuery = fzfQuery.value)
                applyFilteredResult(
                    request = latestRequest,
                    matches = filteredMatches(latestRequest.fzfQuery),
                    isComplete = update.isComplete,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            applyError(request, e)
        } finally {
            isGrepSearching = false
        }
    }

    private suspend fun searchWithFzf(request: LiveGrepSearchRequest) {
        if (request.rgQuery.isBlank()) {
            clearPreview()
            _state.value = LiveGrepDialogState(
                rgQuery = request.rgQuery,
                fzfQuery = request.fzfQuery,
                options = request.options,
            )
            return
        }

        if (!hasFreshGrepCache(request)) {
            _state.value = _state.value.copy(
                rgQuery = request.rgQuery,
                fzfQuery = request.fzfQuery,
                options = request.options,
                isSearching = isGrepSearching,
                hasError = false,
                statusText = if (isGrepSearching) {
                    MyBundle.message("dialog.status.searching")
                } else {
                    _state.value.statusText
                },
            )
            return
        }

        showSearching(request)
        try {
            applyFilteredResult(
                request = request,
                matches = filteredMatches(request.fzfQuery),
                isComplete = !isGrepSearching,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            applyError(request, e)
        }
    }

    private fun hasFreshGrepCache(request: LiveGrepSearchRequest): Boolean {
        return request.rgQuery == cachedRgQuery && request.options == cachedRgOptions
    }

    private suspend fun filteredMatches(query: String): List<GrepMatch> {
        return if (query.isBlank()) {
            cachedRgMatches.take(MAX_RESULTS)
        } else {
            backend.filterMatches(query, cachedRgMatches)
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

    private fun applyFilteredResult(
        request: LiveGrepSearchRequest,
        matches: List<GrepMatch>,
        isComplete: Boolean,
    ) {
        if (matches.isEmpty()) {
            _state.value = _state.value.copy(
                rgQuery = request.rgQuery,
                fzfQuery = request.fzfQuery,
                options = request.options,
                isSearching = !isComplete,
                hasError = false,
                hasSearched = true,
                matches = emptyList(),
                selectedMatchIndex = LiveGrepDialogState.NO_SELECTION,
                selectedMatch = null,
                canOpenSelectedMatch = false,
                preview = LiveGrepPreviewState.Empty,
                totalMatches = cachedRgTotalMatches,
                statusText = MyBundle.message(
                    "dialog.grep.status.resultsDetailed",
                    0,
                    cachedRgTotalMatches,
                ),
            )
            loadSelectedPreview(null)
            return
        }

        val previousSelection = _state.value.selectedMatch
        val selectedMatch = previousSelection?.takeIf(matches::contains) ?: matches.first()
        val selectedMatchIndex = matches.indexOf(selectedMatch)
        _state.value = _state.value.copy(
            rgQuery = request.rgQuery,
            fzfQuery = request.fzfQuery,
            options = request.options,
            isSearching = !isComplete,
            hasError = false,
            hasSearched = true,
            matches = matches,
            selectedMatchIndex = selectedMatchIndex,
            selectedMatch = selectedMatch,
            canOpenSelectedMatch = true,
            preview = if (previousSelection == selectedMatch) {
                refreshedPreviewStateFor(selectedMatch, matches)
            } else {
                previewStateFor(selectedMatch)
            },
            totalMatches = cachedRgTotalMatches,
            statusText = MyBundle.message(
                "dialog.grep.status.resultsDetailed",
                matches.size,
                cachedRgTotalMatches,
            ),
        )
        if (previousSelection != selectedMatch) {
            loadSelectedPreview(selectedMatch)
        }
    }

    private fun refreshedPreviewStateFor(
        selectedMatch: GrepMatch,
        visibleMatches: List<GrepMatch>,
    ): LiveGrepPreviewState {
        val currentPreview = _state.value.preview
        return if (currentPreview is LiveGrepPreviewState.Ready && currentPreview.match == selectedMatch) {
            currentPreview.copy(highlightRanges = previewHighlightsFor(selectedMatch, visibleMatches))
        } else {
            currentPreview
        }
    }

    private fun applyError(request: LiveGrepSearchRequest, error: Throwable) {
        clearPreview()
        _state.value = _state.value.copy(
            rgQuery = request.rgQuery,
            fzfQuery = request.fzfQuery,
            options = request.options,
            isSearching = false,
            hasError = true,
            hasSearched = true,
            matches = emptyList(),
            selectedMatchIndex = LiveGrepDialogState.NO_SELECTION,
            selectedMatch = null,
            canOpenSelectedMatch = false,
            preview = LiveGrepPreviewState.Empty,
            totalMatches = 0,
            statusText = MyBundle.message("dialog.status.error"),
        )
        val message = when (error) {
            is FuzzyFinderException -> error.message
            else -> error.localizedMessage
        } ?: MyBundle.message("dialog.status.error")
        backend.notifyError(message)
    }

    private fun selectMatch(match: GrepMatch?) {
        val matches = _state.value.matches
        val index = match?.let(matches::indexOf)?.takeIf { it >= 0 } ?: LiveGrepDialogState.NO_SELECTION
        if (index == LiveGrepDialogState.NO_SELECTION) {
            clearPreview()
            _state.value = _state.value.copy(
                selectedMatchIndex = LiveGrepDialogState.NO_SELECTION,
                selectedMatch = null,
                canOpenSelectedMatch = false,
                preview = LiveGrepPreviewState.Empty,
            )
            return
        }

        selectMatchAt(index, matches[index])
    }

    private fun selectMatchByIndex(index: Int) {
        val matches = _state.value.matches
        if (matches.isEmpty()) {
            selectMatch(null)
            return
        }

        val nextIndex = index.coerceIn(0, matches.lastIndex)
        selectMatchAt(nextIndex, matches[nextIndex])
    }

    private fun selectMatchAt(index: Int, match: GrepMatch) {
        val currentState = _state.value
        if (currentState.selectedMatchIndex == index && currentState.selectedMatch == match) {
            return
        }

        _state.value = _state.value.copy(
            selectedMatchIndex = index,
            selectedMatch = match,
            canOpenSelectedMatch = true,
            preview = LiveGrepPreviewState.Loading(match),
        )
        loadSelectedPreview(match)
    }

    private fun previewStateFor(match: GrepMatch?): LiveGrepPreviewState {
        return match?.let(LiveGrepPreviewState::Loading) ?: LiveGrepPreviewState.Empty
    }

    private fun clearPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    private fun loadSelectedPreview(match: GrepMatch?) {
        clearPreview()
        if (match == null) {
            return
        }

        previewJob = scope.launch {
            val content = loadPreview(match.path)
            if (_state.value.selectedMatch != match) {
                return@launch
            }
            _state.value = _state.value.copy(
                preview = LiveGrepPreviewState.Ready(
                    match = match,
                    content = content,
                    scrollToLine = match.line,
                    highlightRanges = previewHighlightsFor(match, _state.value.matches),
                ),
            )
        }
    }

    private fun previewHighlightsFor(selected: GrepMatch, visibleMatches: List<GrepMatch>): List<PreviewHighlightRange> {
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
