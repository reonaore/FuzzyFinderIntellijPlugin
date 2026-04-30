package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchResult
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
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

class LiveGrepDialogViewModelTest {
    @Test
    fun cancelsSupersededGrepWhenQueryChanges() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val firstSearchStarted = CompletableDeferred<Unit>()
        val firstSearchCanceled = CompletableDeferred<Unit>()
        val viewModel = LiveGrepDialogViewModel(
            scope = scope,
            initialOptions = GrepSearchOptions(),
            runGrep = { query, _ ->
                if (query == "f") {
                    firstSearchStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstSearchCanceled.complete(Unit)
                    }
                }
                GrepSearchResult(
                    totalMatches = 1,
                    query = query,
                    matches = listOf(grepMatch("/tmp/$query.txt", query)),
                )
            },
            filterMatches = { _, matches -> matches },
            notifyError = {},
        )

        viewModel.onRgQueryChanged("f")
        withTimeout(TEST_TIMEOUT_MS) {
            firstSearchStarted.await()
        }

        viewModel.onRgQueryChanged("fo")

        withTimeout(TEST_TIMEOUT_MS) {
            firstSearchCanceled.await()
            waitUntil { viewModel.state.value.rgQuery == "fo" && !viewModel.state.value.isSearching }
        }
        assertEquals(listOf(grepMatch("/tmp/fo.txt", "fo")), viewModel.state.value.matches)
    }

    @Test
    fun filtersCachedGrepMatchesWhenFzfQueryChanges() = runBlocking {
        val sourceMatches = listOf(
            grepMatch("/tmp/App.kt", "needle"),
            grepMatch("/tmp/Other.kt", "other"),
        )
        val viewModel = LiveGrepDialogViewModel(
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = GrepSearchOptions(),
            runGrep = { query, _ ->
                GrepSearchResult(
                    totalMatches = sourceMatches.size,
                    query = query,
                    matches = sourceMatches,
                )
            },
            filterMatches = { query, matches ->
                matches.filter { it.lineText.contains(query) }
            },
            notifyError = {},
        )

        viewModel.onRgQueryChanged("needle")
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.matches == sourceMatches }
        }

        viewModel.onFzfQueryChanged("other")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil {
                viewModel.state.value.fzfQuery == "other" &&
                    viewModel.state.value.matches == listOf(sourceMatches[1])
            }
        }
        assertEquals(2, viewModel.state.value.totalMatches)
    }

    @Test
    fun marksStateAsErrorWhenGrepFails() = runBlocking {
        val notifications = mutableListOf<String>()
        val viewModel = LiveGrepDialogViewModel(
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = GrepSearchOptions(),
            runGrep = { _, _ -> throw FuzzyFinderException("rg failed") },
            filterMatches = { _, matches -> matches },
            notifyError = notifications::add,
        )

        viewModel.onRgQueryChanged("query")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.hasError }
        }

        assertFalse(viewModel.state.value.isSearching)
        assertTrue(viewModel.state.value.hasSearched)
        assertEquals(emptyList<GrepMatch>(), viewModel.state.value.matches)
        assertEquals(listOf("rg failed"), notifications)
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) {
            delay(10)
        }
    }

    private fun grepMatch(path: String, lineText: String): GrepMatch {
        return GrepMatch(
            path = Path.of(path),
            line = 1,
            column = 1,
            lineText = lineText,
            matchRanges = listOf(TextRange(0, lineText.length)),
        )
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
