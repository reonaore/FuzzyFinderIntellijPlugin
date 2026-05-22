package com.github.reonaore.fuzzyfinderintellijplugin.tabfinder

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class TabFinderDialogState(
    val query: String = "",
    val candidates: List<OpenTabCandidate> = emptyList(),
    val selectedIndex: Int = NO_SELECTION,
    val selectedCandidate: OpenTabCandidate? = null,
    val canActivateSelectedTab: Boolean = false,
    val hasOpenTabs: Boolean = false,
    val hasError: Boolean = false,
    val statusText: String = MyBundle.message("dialog.tabs.status.open", 0),
) {
    companion object {
        const val NO_SELECTION = -1
    }
}

internal interface TabFinderBackend {
    suspend fun filterCandidates(query: String, candidates: List<OpenTabCandidate>): List<OpenTabCandidate>

    fun notifyError(message: String)
}

@OptIn(FlowPreview::class)
class TabFinderDialogViewModel internal constructor(
    private val backend: TabFinderBackend,
    private val scope: CoroutineScope,
) {
    private val query = MutableStateFlow("")
    private val openTabs = MutableStateFlow<List<OpenTabCandidate>>(emptyList())
    private val _state = MutableStateFlow(TabFinderDialogState())
    val state: StateFlow<TabFinderDialogState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(
                query.debounce(SEARCH_DEBOUNCE_MS),
                openTabs,
            ) { latestQuery, latestOpenTabs ->
                latestQuery to latestOpenTabs
            }
                .distinctUntilChanged()
                .collect { (latestQuery, latestOpenTabs) ->
                    filter(latestQuery, latestOpenTabs)
                }
        }
    }

    fun onUpdateQuery(newQuery: String) {
        query.value = newQuery
    }

    fun onUpdateOpenTabs(candidates: List<OpenTabCandidate>) {
        openTabs.value = candidates
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

    private suspend fun filter(query: String, openTabs: List<OpenTabCandidate>) {
        try {
            val results = backend.filterCandidates(query, openTabs)
            val previousSelection = _state.value.selectedCandidate?.file
            val selectedCandidate = results.firstOrNull { it.file == previousSelection } ?: results.firstOrNull()
            val selectedIndex = selectedCandidate?.let(results::indexOf) ?: TabFinderDialogState.NO_SELECTION
            val hasOpenTabs = openTabs.isNotEmpty()
            _state.value = TabFinderDialogState(
                query = query,
                candidates = results,
                selectedIndex = selectedIndex,
                selectedCandidate = selectedCandidate,
                canActivateSelectedTab = selectedCandidate?.file?.isValid == true,
                hasOpenTabs = hasOpenTabs,
                hasError = false,
                statusText = if (query.isBlank()) {
                    MyBundle.message("dialog.tabs.status.open", openTabs.size)
                } else {
                    MyBundle.message("dialog.tabs.status.results", results.size, openTabs.size)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = _state.value.copy(
                query = query,
                candidates = emptyList(),
                selectedIndex = TabFinderDialogState.NO_SELECTION,
                selectedCandidate = null,
                canActivateSelectedTab = false,
                hasOpenTabs = openTabs.isNotEmpty(),
                hasError = true,
                statusText = MyBundle.message("dialog.status.error"),
            )
            val message = when (e) {
                is FuzzyFinderException -> e.message
                else -> e.localizedMessage
            } ?: MyBundle.message("dialog.status.error")
            backend.notifyError(message)
        }
    }

    private fun selectCandidate(index: Int) {
        val candidates = _state.value.candidates
        if (candidates.isEmpty()) {
            _state.value = _state.value.copy(
                selectedIndex = TabFinderDialogState.NO_SELECTION,
                selectedCandidate = null,
                canActivateSelectedTab = false,
            )
            return
        }

        val nextIndex = index.coerceIn(0, candidates.lastIndex)
        val candidate = candidates[nextIndex]
        _state.value = _state.value.copy(
            selectedIndex = nextIndex,
            selectedCandidate = candidate,
            canActivateSelectedTab = candidate.file.isValid,
        )
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180L
    }
}
