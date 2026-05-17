package com.github.reonaore.fuzzyfinderintellijplugin.filefinder

import com.github.reonaore.fuzzyfinderintellijplugin.services.FdSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.CandidateSearchUpdate
import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.PreviewContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        val updatedSearchStarted = CompletableDeferred<Unit>()
        val updatedSearchCanceled = CompletableDeferred<Unit>()
        val viewModel = FuzzyFinderDialogViewModel(
            backend = TestFuzzyFinderSearchBackend(
                streamCandidates = { options ->
                    flow {
                        if (options.includeHidden) {
                            updatedSearchStarted.complete(Unit)
                        }
                        try {
                            awaitCancellation()
                        } finally {
                            if (options.includeHidden) {
                                updatedSearchCanceled.complete(Unit)
                            }
                        }
                    }
                },
                filterCandidatesAction = { query, _ ->
                    listOf(Path.of("/tmp/$query.txt"))
                },
            ),
            scope = scope,
            initialOptions = FdSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateOptions(FdSearchOptions(includeHidden = true))
        withTimeout(TEST_TIMEOUT_MS) {
            updatedSearchStarted.await()
        }

        viewModel.onUpdateOptions(FdSearchOptions(includeHidden = false))

        withTimeout(TEST_TIMEOUT_MS) {
            updatedSearchCanceled.await()
        }
    }

    @Test
    fun marksStateAsErrorWhenSearchFails() = runBlocking {
        val notifications = mutableListOf<String>()
        val viewModel = FuzzyFinderDialogViewModel(
            backend = TestFuzzyFinderSearchBackend(
                streamCandidates = { flow { throw FuzzyFinderException("fd failed") } },
                notifyErrorAction = notifications::add,
            ),
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = FdSearchOptions(),
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
    fun showsPartialCandidatesBeforeStreamCompletes() = runBlocking {
        val firstPath = Path.of("/tmp/first.txt")
        val secondPath = Path.of("/tmp/second.txt")
        val continueStream = CompletableDeferred<Unit>()
        val viewModel = FuzzyFinderDialogViewModel(
            backend = TestFuzzyFinderSearchBackend(
                streamCandidates = {
                    flow {
                        emit(CandidateSearchUpdate(1, listOf(firstPath), false))
                        continueStream.await()
                        emit(CandidateSearchUpdate(2, listOf(firstPath, secondPath), true))
                    }
                },
            ),
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = FdSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.paths == listOf(firstPath) && viewModel.state.value.isSearching }
        }

        continueStream.complete(Unit)

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.paths == listOf(firstPath, secondPath) && !viewModel.state.value.isSearching }
        }
    }

    @Test
    fun ignoresStaleStreamedResultsWhenQueryChangesDuringFiltering() = runBlocking {
        val oldPath = Path.of("/tmp/old.txt")
        val newPath = Path.of("/tmp/new.txt")
        val emitCandidates = CompletableDeferred<Unit>()
        val oldFilterStarted = CompletableDeferred<Unit>()
        val oldFilterCanFinish = CompletableDeferred<Unit>()
        val oldFilterFinished = CompletableDeferred<Unit>()
        val viewModel = FuzzyFinderDialogViewModel(
            backend = TestFuzzyFinderSearchBackend(
                streamCandidates = {
                    flow {
                        emitCandidates.await()
                        emit(CandidateSearchUpdate(1, listOf(Path.of("/tmp/candidate.txt")), false))
                        awaitCancellation()
                    }
                },
                filterCandidatesAction = { query, _ ->
                    when (query) {
                        "old" -> {
                            oldFilterStarted.complete(Unit)
                            oldFilterCanFinish.await()
                            oldFilterFinished.complete(Unit)
                            listOf(oldPath)
                        }

                        "new" -> listOf(newPath)
                        else -> emptyList()
                    }
                },
            ),
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = FdSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateQuery("old")
        emitCandidates.complete(Unit)

        withTimeout(TEST_TIMEOUT_MS) {
            oldFilterStarted.await()
        }

        viewModel.onUpdateQuery("new")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.query == "new" && viewModel.state.value.selectedPath == newPath }
        }

        oldFilterCanFinish.complete(Unit)

        withTimeout(TEST_TIMEOUT_MS) {
            oldFilterFinished.await()
        }

        delay(50)
        assertEquals("new", viewModel.state.value.query)
        assertEquals(newPath, viewModel.state.value.selectedPath)
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetsSelectionToFirstCandidateWhenSearchResultsChange() = runTest {
        val viewModel = FuzzyFinderDialogViewModel(
            backend = TestFuzzyFinderSearchBackend(
                streamCandidates = {
                    flow {
                        emit(
                            CandidateSearchUpdate(
                                totalCandidates = 2,
                                candidates = listOf(Path.of("/tmp/first-a.txt"), Path.of("/tmp/first-b.txt")),
                                isComplete = true,
                            ),
                        )
                    }
                },
                filterCandidatesAction = { query, _ ->
                    if (query == "first") {
                        listOf(Path.of("/tmp/first-a.txt"), Path.of("/tmp/first-b.txt"))
                    } else {
                        listOf(Path.of("/tmp/second-a.txt"), Path.of("/tmp/second-b.txt"))
                    }
                },
            ),
            scope = backgroundScope,
            initialOptions = FdSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateQuery("first")
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()
        assertEquals("first", viewModel.state.value.query)
        assertFalse(viewModel.state.value.isSearching)
        assertEquals(Path.of("/tmp/first-a.txt"), viewModel.state.value.selectedPath)

        viewModel.onSelectNextCandidate()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.selectedIndex)

        viewModel.onUpdateQuery("second")
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()

        assertEquals("second", viewModel.state.value.query)
        assertFalse(viewModel.state.value.isSearching)
        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(Path.of("/tmp/second-a.txt"), viewModel.state.value.selectedPath)
        assertEquals(Path.of("/tmp/second-a.txt"), (viewModel.state.value.preview as FuzzyFinderPreviewState.Ready).path)
    }

    @Test
    fun keepsSelectedCandidateWhenItRemainsInStreamedResults() = runBlocking {
        val firstPath = Path.of("/tmp/first.txt")
        val secondPath = Path.of("/tmp/second.txt")
        val continueStream = CompletableDeferred<Unit>()
        val viewModel = FuzzyFinderDialogViewModel(
            backend = TestFuzzyFinderSearchBackend(
                streamCandidates = {
                    flow {
                        emit(CandidateSearchUpdate(2, listOf(firstPath, secondPath), false))
                        continueStream.await()
                        emit(CandidateSearchUpdate(3, listOf(firstPath, secondPath, Path.of("/tmp/third.txt")), true))
                    }
                },
            ),
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = FdSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.paths.size == 2 }
        }
        viewModel.onSelectNextCandidate()
        assertEquals(secondPath, viewModel.state.value.selectedPath)

        continueStream.complete(Unit)

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { !viewModel.state.value.isSearching }
        }
        assertEquals(secondPath, viewModel.state.value.selectedPath)
        assertEquals(1, viewModel.state.value.selectedIndex)
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
            backend = TestFuzzyFinderSearchBackend(
                streamCandidates = {
                    flow {
                        emit(
                            CandidateSearchUpdate(
                                totalCandidates = paths.size,
                                candidates = paths.toList(),
                                isComplete = true,
                            ),
                        )
                    }
                },
                filterCandidatesAction = { _, candidates -> candidates },
            ),
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = FdSearchOptions(),
            loadPreview = loadPreview,
        )
    }

    private class TestFuzzyFinderSearchBackend(
        private val streamCandidates: (FdSearchOptions) -> Flow<CandidateSearchUpdate> = {
            flow {
                emit(CandidateSearchUpdate(0, emptyList(), true))
            }
        },
        private val filterCandidatesAction: suspend (String, List<Path>) -> List<Path> = { _, candidates -> candidates },
        private val notifyErrorAction: (String) -> Unit = {},
    ) : FuzzyFinderSearchBackend {
        override fun candidateStream(options: FdSearchOptions): Flow<CandidateSearchUpdate> = streamCandidates(options)

        override suspend fun filterCandidates(query: String, candidates: List<Path>): List<Path> {
            return filterCandidatesAction(query, candidates)
        }

        override fun notifyError(message: String) {
            notifyErrorAction(message)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 5_000L
        const val SEARCH_DEBOUNCE_TIMEOUT_MS = 1_000L
    }
}
