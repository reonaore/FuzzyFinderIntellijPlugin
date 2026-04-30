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

        viewModel.onQueryChanged("f")
        withTimeout(TEST_TIMEOUT_MS) {
            firstSearchStarted.await()
        }

        viewModel.onQueryChanged("fo")

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

        viewModel.onQueryChanged("query")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.hasError }
        }

        assertFalse(viewModel.state.value.isSearching)
        assertTrue(viewModel.state.value.hasSearched)
        assertEquals(listOf("fd failed"), notifications)
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) {
            delay(10)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
