package com.github.reonaore.fuzzyfinderintellijplugin.filefinder

import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.SearchResult
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.PreviewContent
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
            loadPreview = { path -> PreviewContent(path.toString(), null) },
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
            loadPreview = { path -> PreviewContent(path.toString(), null) },
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
        assertTrue(viewModel.state.value.preview is FuzzyFinderPreviewState.Empty)
        assertEquals(listOf("fd failed"), notifications)
    }

    @Test
    fun selectsFirstCandidateWhenSearchReturnsResults() = runBlocking {
        val firstPath = Path.of("/tmp/first.txt")
        val secondPath = Path.of("/tmp/second.txt")
        val viewModel = viewModelWithResults(firstPath, secondPath)

        viewModel.onUpdateQuery("query")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.preview is FuzzyFinderPreviewState.Ready }
        }

        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(firstPath, viewModel.state.value.selectedPath)
        assertTrue(viewModel.state.value.canOpenSelectedFile)
        assertEquals(firstPath, (viewModel.state.value.preview as FuzzyFinderPreviewState.Ready).path)
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
        assertTrue(viewModel.state.value.preview is FuzzyFinderPreviewState.Empty)
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
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil {
                val preview = viewModel.state.value.preview
                preview is FuzzyFinderPreviewState.Ready && preview.path == secondPath
            }
        }
        assertEquals(secondPath, (viewModel.state.value.preview as FuzzyFinderPreviewState.Ready).path)

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
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateQuery("first")
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil {
                viewModel.state.value.query == "first" &&
                    !viewModel.state.value.isSearching &&
                    viewModel.state.value.selectedPath == Path.of("/tmp/first-a.txt")
            }
        }
        viewModel.onSelectNextCandidate()
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.selectedIndex == 1 }
        }

        viewModel.onUpdateQuery("second")
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.query == "second" && !viewModel.state.value.isSearching }
        }

        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(Path.of("/tmp/second-a.txt"), viewModel.state.value.selectedPath)
        assertEquals(Path.of("/tmp/second-a.txt"), (viewModel.state.value.preview as FuzzyFinderPreviewState.Ready).path)
    }

    @Test
    fun marksPreviewAsLoadingThenReadyWhenCandidateIsSelected() = runBlocking {
        val path = Path.of("/tmp/preview.txt")
        val previewLoadRequested = CompletableDeferred<Unit>()
        val previewContent = CompletableDeferred<PreviewContent>()
        val viewModel = viewModelWithResults(
            path,
            loadPreview = {
                previewLoadRequested.complete(Unit)
                previewContent.await()
            },
        )

        viewModel.onUpdateQuery("query")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.preview is FuzzyFinderPreviewState.Loading }
            previewLoadRequested.await()
        }
        assertEquals(path, (viewModel.state.value.preview as FuzzyFinderPreviewState.Loading).path)
        assertEquals(path, viewModel.state.value.selectedPath)
        assertTrue(viewModel.state.value.canOpenSelectedFile)

        previewContent.complete(PreviewContent("preview body", null))

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.preview is FuzzyFinderPreviewState.Ready }
        }
        val preview = viewModel.state.value.preview as FuzzyFinderPreviewState.Ready
        assertEquals(path, preview.path)
        assertEquals("preview body", preview.content.text)
    }

    @Test
    fun cancelsStalePreviewLoadWhenSelectionChanges() = runBlocking {
        val firstPath = Path.of("/tmp/first.txt")
        val secondPath = Path.of("/tmp/second.txt")
        val firstPreviewStarted = CompletableDeferred<Unit>()
        val firstPreviewCanceled = CompletableDeferred<Unit>()
        val secondPreviewStarted = CompletableDeferred<Unit>()
        val viewModel = viewModelWithResults(
            firstPath,
            secondPath,
            loadPreview = { path ->
                if (path == firstPath) {
                    firstPreviewStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstPreviewCanceled.complete(Unit)
                    }
                }
                secondPreviewStarted.complete(Unit)
                PreviewContent("second preview", null)
            },
        )

        viewModel.onUpdateQuery("query")
        withTimeout(TEST_TIMEOUT_MS) {
            firstPreviewStarted.await()
        }

        viewModel.onSelectNextCandidate()

        withTimeout(TEST_TIMEOUT_MS) {
            firstPreviewCanceled.await()
            secondPreviewStarted.await()
            waitUntil { viewModel.state.value.preview is FuzzyFinderPreviewState.Ready }
        }

        val preview = viewModel.state.value.preview as FuzzyFinderPreviewState.Ready
        assertEquals(secondPath, preview.path)
        assertEquals("second preview", preview.content.text)
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) {
            delay(10)
        }
    }

    private fun viewModelWithResults(
        vararg paths: Path,
        loadPreview: suspend (Path) -> PreviewContent = { path -> PreviewContent(path.toString(), null) },
    ): FuzzyFinderDialogViewModel {
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
            loadPreview = loadPreview,
        )
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
    }
}
