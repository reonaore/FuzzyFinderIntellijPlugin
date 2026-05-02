package com.github.reonaore.fuzzyfinderintellijplugin.filefinder

import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class FuzzyFinderDialogViewModelTest {
    @Test
    fun cancelsSupersededSearchWhenQueryChanges() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val firstSearchStarted = CompletableDeferred<Unit>()
        val firstSearchCanceled = CompletableDeferred<Unit>()
        val viewModel = FuzzyFinderDialogViewModel(
            scope = scope,
            initialOptions = FdSearchOptions(),
            runSearch = { query, _ ->
                if (query == "f") {
                    firstSearchStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstSearchCanceled.complete(Unit)
                    }
                }
                SearchResult(
                    totalCandidates = 1,
                    query = query,
                    results = listOf(Path.of("/tmp/$query.txt")),
                )
            },
            notifyError = {},
        )

        viewModel.onUpdateQuery("f")
        withTimeout(TEST_TIMEOUT_MS) {
            firstSearchStarted.await()
        }

        viewModel.onUpdateQuery("fo")

        withTimeout(TEST_TIMEOUT_MS) {
            firstSearchCanceled.await()
            waitUntil { viewModel.state.value.query == "fo" && !viewModel.state.value.isSearching }
        }
        assertEquals(listOf(Path.of("/tmp/fo.txt")), viewModel.state.value.paths)
    }

    @Test
    fun marksStateAsErrorWhenSearchFails() = runBlocking {
        val notifications = mutableListOf<String>()
        val viewModel = FuzzyFinderDialogViewModel(
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = FdSearchOptions(),
            runSearch = { _, _ -> throw FuzzyFinderException("fd failed") },
            notifyError = notifications::add,
        )

        viewModel.onUpdateQuery("query")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.hasError }
        }

        assertFalse(viewModel.state.value.isSearching)
        assertTrue(viewModel.state.value.hasSearched)
        assertEquals(emptyList<Path>(), viewModel.state.value.paths)
        assertEquals(FuzzyFinderDialogState.NO_SELECTION, viewModel.state.value.selectedIndex)
        assertNull(viewModel.state.value.selectedPath)
        assertFalse(viewModel.state.value.canOpenSelectedFile)
        assertNull(viewModel.state.value.previewPath)
        assertEquals(listOf("fd failed"), notifications)
    }

    @Test
    fun selectsFirstCandidateWhenSearchReturnsResults() = runBlocking {
        val firstPath = Path.of("/tmp/first.txt")
        val secondPath = Path.of("/tmp/second.txt")
        val viewModel = viewModelWithResults(firstPath, secondPath)

        viewModel.onUpdateQuery("query")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.hasSearched }
        }

        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(firstPath, viewModel.state.value.selectedPath)
        assertTrue(viewModel.state.value.canOpenSelectedFile)
        assertEquals(firstPath, viewModel.state.value.previewPath)
    }

    @Test
    fun clearsSelectionWhenSearchReturnsNoResults() = runBlocking {
        val viewModel = viewModelWithResults()

        viewModel.onUpdateQuery("query")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.hasSearched }
        }

        assertEquals(FuzzyFinderDialogState.NO_SELECTION, viewModel.state.value.selectedIndex)
        assertNull(viewModel.state.value.selectedPath)
        assertFalse(viewModel.state.value.canOpenSelectedFile)
        assertNull(viewModel.state.value.previewPath)
    }

    @Test
    fun clampsCandidateSelectionWithinResultRange() = runBlocking {
        val firstPath = Path.of("/tmp/first.txt")
        val secondPath = Path.of("/tmp/second.txt")
        val viewModel = viewModelWithResults(firstPath, secondPath)

        viewModel.onUpdateQuery("query")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.hasSearched }
        }

        viewModel.onSelectPreviousCandidate()
        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(firstPath, viewModel.state.value.selectedPath)

        viewModel.onSelectNextCandidate()
        assertEquals(1, viewModel.state.value.selectedIndex)
        assertEquals(secondPath, viewModel.state.value.selectedPath)
        assertEquals(secondPath, viewModel.state.value.previewPath)

        viewModel.onSelectNextCandidate()
        assertEquals(1, viewModel.state.value.selectedIndex)
        assertEquals(secondPath, viewModel.state.value.selectedPath)
    }

    @Test
    fun resetsSelectionToFirstCandidateWhenSearchResultsChange() = runBlocking {
        val viewModel = FuzzyFinderDialogViewModel(
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = FdSearchOptions(),
            runSearch = { query, _ ->
                val results = if (query == "first") {
                    listOf(Path.of("/tmp/first-a.txt"), Path.of("/tmp/first-b.txt"))
                } else {
                    listOf(Path.of("/tmp/second-a.txt"), Path.of("/tmp/second-b.txt"))
                }
                SearchResult(
                    totalCandidates = results.size,
                    query = query,
                    results = results,
                )
            },
            notifyError = {},
        )

        viewModel.onUpdateQuery("first")
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.query == "first" && viewModel.state.value.hasSearched }
        }
        viewModel.onSelectNextCandidate()
        assertEquals(1, viewModel.state.value.selectedIndex)

        viewModel.onUpdateQuery("second")
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.query == "second" && !viewModel.state.value.isSearching }
        }

        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(Path.of("/tmp/second-a.txt"), viewModel.state.value.selectedPath)
        assertEquals(Path.of("/tmp/second-a.txt"), viewModel.state.value.previewPath)
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) {
            delay(10)
        }
    }

    private fun viewModelWithResults(vararg paths: Path): FuzzyFinderDialogViewModel {
        return FuzzyFinderDialogViewModel(
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = FdSearchOptions(),
            runSearch = { query, _ ->
                SearchResult(
                    totalCandidates = paths.size,
                    query = query,
                    results = paths.toList(),
                )
            },
            notifyError = {},
        )
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
