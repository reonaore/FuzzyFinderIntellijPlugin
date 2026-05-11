package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewHighlightRange
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchResult
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchUpdate
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import com.github.reonaore.fuzzyfinderintellijplugin.shared.ui.PreviewContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LiveGrepDialogViewModelTest {
    @Test
    fun cancelsSupersededGrepWhenQueryChanges() = runTest {
        val firstSearchStarted = CompletableDeferred<Unit>()
        val firstSearchCanceled = CompletableDeferred<Unit>()
        val viewModel = LiveGrepDialogViewModel(
            backend = TestLiveGrepSearchBackend(
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
            ),
            scope = backgroundScope,
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("f")
        advancePastSearchDebounce()
        firstSearchStarted.await()

        viewModel.onUpdateRgQuery("fo")
        advancePastSearchDebounce()

        firstSearchCanceled.await()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.rgQuery == "fo" && !viewModel.state.value.isSearching)
        assertEquals(listOf(grepMatch("/tmp/fo.txt", "fo")), viewModel.state.value.matches)
    }

    @Test
    fun filtersCachedGrepMatchesWhenFzfQueryChanges() = runTest {
        val sourceMatches = listOf(
            grepMatch("/tmp/App.kt", "needle"),
            grepMatch("/tmp/Other.kt", "other"),
        )
        val viewModel = LiveGrepDialogViewModel(
            backend = TestLiveGrepSearchBackend(
                runGrep = { query, _ ->
                    GrepSearchResult(
                        totalMatches = sourceMatches.size,
                        query = query,
                        matches = sourceMatches,
                    )
                },
                filterMatchesAction = { query, matches ->
                    matches.filter { it.lineText.contains(query) }
                },
            ),
            scope = backgroundScope,
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()
        advanceUntilIdle()
        assertEquals(sourceMatches, viewModel.state.value.matches)

        viewModel.onUpdateFzfQuery("other")
        advancePastSearchDebounce()

        assertEquals("other", viewModel.state.value.fzfQuery)
        assertEquals(listOf(sourceMatches[1]), viewModel.state.value.matches)
        assertEquals(2, viewModel.state.value.totalMatches)
    }

    @Test
    fun doesNotCancelRunningGrepWhenFzfQueryChanges() = runTest {
        val sourceMatches = listOf(
            grepMatch("/tmp/App.kt", "needle"),
            grepMatch("/tmp/Other.kt", "other"),
        )
        val grepResult = CompletableDeferred<GrepSearchResult>()
        val grepFinished = CompletableDeferred<Unit>()
        val viewModel = LiveGrepDialogViewModel(
            backend = TestLiveGrepSearchBackend(
                runGrep = { _, _ ->
                    try {
                        grepResult.await()
                    } finally {
                        grepFinished.complete(Unit)
                    }
                },
                filterMatchesAction = { query, matches ->
                    matches.filter { it.lineText.contains(query) }
                },
            ),
            scope = backgroundScope,
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()
        assertTrue(viewModel.state.value.rgQuery == "needle" && viewModel.state.value.isSearching)

        viewModel.onUpdateFzfQuery("other")
        advancePastSearchDebounce()

        assertFalse(grepFinished.isCompleted)

        grepResult.complete(
            GrepSearchResult(
                totalMatches = sourceMatches.size,
                query = "needle",
                matches = sourceMatches,
            ),
        )
        runCurrent()
        advanceUntilIdle()

        assertEquals("other", viewModel.state.value.fzfQuery)
        assertEquals(listOf(sourceMatches[1]), viewModel.state.value.matches)
    }

    @Test
    fun showsPartialGrepResultsBeforeStreamCompletes() = runTest {
        val firstMatch = grepMatch("/tmp/App.kt", "needle")
        val secondMatch = grepMatch("/tmp/Other.kt", "other needle")
        val continueStream = CompletableDeferred<Unit>()
        val viewModel = LiveGrepDialogViewModel(
            backend = TestLiveGrepSearchBackend(
                streamGrep = { query, _ ->
                    flow {
                        emit(
                            GrepSearchUpdate(
                                totalMatches = 1,
                                query = query,
                                matches = listOf(firstMatch),
                                isComplete = false,
                            ),
                        )
                        continueStream.await()
                        emit(
                            GrepSearchUpdate(
                                totalMatches = 2,
                                query = query,
                                matches = listOf(firstMatch, secondMatch),
                                isComplete = true,
                            ),
                        )
                    }
                },
            ),
            scope = backgroundScope,
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()

        assertEquals(listOf(firstMatch), viewModel.state.value.matches)
        assertTrue(viewModel.state.value.isSearching)

        continueStream.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(firstMatch, secondMatch), viewModel.state.value.matches)
        assertFalse(viewModel.state.value.isSearching)
        assertEquals(2, viewModel.state.value.totalMatches)
    }

    @Test
    fun filtersPartialGrepResultsWhileStreamIsRunning() = runTest {
        val firstMatch = grepMatch("/tmp/App.kt", "needle")
        val secondMatch = grepMatch("/tmp/Other.kt", "other needle")
        val continueStream = CompletableDeferred<Unit>()
        val viewModel = LiveGrepDialogViewModel(
            backend = TestLiveGrepSearchBackend(
                streamGrep = { query, _ ->
                    flow {
                        emit(
                            GrepSearchUpdate(
                                totalMatches = 2,
                                query = query,
                                matches = listOf(firstMatch, secondMatch),
                                isComplete = false,
                            ),
                        )
                        continueStream.await()
                        emit(
                            GrepSearchUpdate(
                                totalMatches = 2,
                                query = query,
                                matches = listOf(firstMatch, secondMatch),
                                isComplete = true,
                            ),
                        )
                    }
                },
                filterMatchesAction = { query, matches ->
                    matches.filter { it.lineText.contains(query) }
                },
            ),
            scope = backgroundScope,
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()
        assertEquals(listOf(firstMatch, secondMatch), viewModel.state.value.matches)

        viewModel.onUpdateFzfQuery("other")
        advancePastSearchDebounce()

        assertEquals("other", viewModel.state.value.fzfQuery)
        assertEquals(listOf(secondMatch), viewModel.state.value.matches)
        assertTrue(viewModel.state.value.isSearching)

        continueStream.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(secondMatch), viewModel.state.value.matches)
        assertFalse(viewModel.state.value.isSearching)
    }

    @Test
    fun marksStateAsErrorWhenGrepFails() = runTest {
        val notifications = mutableListOf<String>()
        val viewModel = LiveGrepDialogViewModel(
            backend = TestLiveGrepSearchBackend(
                runGrep = { _, _ -> throw FuzzyFinderException("rg failed") },
                notifyErrorAction = notifications::add,
            ),
            scope = backgroundScope,
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("query")
        advancePastSearchDebounce()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isSearching)
        assertTrue(viewModel.state.value.hasSearched)
        assertEquals(emptyList<GrepMatch>(), viewModel.state.value.matches)
        assertEquals(LiveGrepDialogState.NO_SELECTION, viewModel.state.value.selectedMatchIndex)
        assertNull(viewModel.state.value.selectedMatch)
        assertFalse(viewModel.state.value.canOpenSelectedMatch)
        assertTrue(viewModel.state.value.preview is LiveGrepPreviewState.Empty)
        assertEquals(listOf("rg failed"), notifications)
    }

    @Test
    fun selectsFirstMatchAndLoadsPreviewWhenGrepReturnsResults() = runTest {
        val firstMatch = grepMatch("/tmp/App.kt", "needle")
        val secondMatch = grepMatch("/tmp/Other.kt", "other")
        val viewModel = viewModelWithMatches(firstMatch, secondMatch)

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.selectedMatchIndex)
        assertEquals(firstMatch, viewModel.state.value.selectedMatch)
        assertTrue(viewModel.state.value.canOpenSelectedMatch)
        assertEquals(firstMatch, (viewModel.state.value.preview as LiveGrepPreviewState.Ready).match)
    }

    @Test
    fun clearsSelectionAndPreviewWhenGrepReturnsNoResults() = runTest {
        val viewModel = viewModelWithMatches()

        viewModel.onUpdateRgQuery("missing")
        advancePastSearchDebounce()
        advanceUntilIdle()

        assertEquals(LiveGrepDialogState.NO_SELECTION, viewModel.state.value.selectedMatchIndex)
        assertNull(viewModel.state.value.selectedMatch)
        assertFalse(viewModel.state.value.canOpenSelectedMatch)
        assertTrue(viewModel.state.value.preview is LiveGrepPreviewState.Empty)
    }

    @Test
    fun clampsMatchSelectionWithinVisibleMatches() = runTest {
        val firstMatch = grepMatch("/tmp/App.kt", "needle")
        val secondMatch = grepMatch("/tmp/Other.kt", "other")
        val viewModel = viewModelWithMatches(firstMatch, secondMatch)

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()
        advanceUntilIdle()
        assertEquals(firstMatch, viewModel.state.value.selectedMatch)

        viewModel.onSelectPreviousMatch()
        assertEquals(0, viewModel.state.value.selectedMatchIndex)
        assertEquals(firstMatch, viewModel.state.value.selectedMatch)

        viewModel.onSelectNextMatch()
        assertEquals(1, viewModel.state.value.selectedMatchIndex)
        assertEquals(secondMatch, viewModel.state.value.selectedMatch)

        viewModel.onSelectNextMatch()
        assertEquals(1, viewModel.state.value.selectedMatchIndex)
        assertEquals(secondMatch, viewModel.state.value.selectedMatch)
    }

    @Test
    fun doesNotReloadPreviewWhenClampedSelectionIsUnchanged() = runTest {
        val match = grepMatch("/tmp/App.kt", "needle")
        val loadCount = AtomicInteger()
        val viewModel = viewModelWithMatches(
            match,
            loadPreview = { path ->
                loadCount.incrementAndGet()
                PreviewContent(path.toString(), null)
            },
        )

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()
        advanceUntilIdle()

        viewModel.onSelectPreviousMatch()
        viewModel.onSelectNextMatch()

        assertEquals(0, viewModel.state.value.selectedMatchIndex)
        assertEquals(match, viewModel.state.value.selectedMatch)
        assertEquals(1, loadCount.get())
        assertTrue(viewModel.state.value.preview is LiveGrepPreviewState.Ready)
    }

    @Test
    fun resetsSelectionToFirstFilteredMatchWhenFzfQueryChanges() = runTest {
        val sourceMatches = listOf(
            grepMatch("/tmp/App.kt", "needle"),
            grepMatch("/tmp/Other.kt", "other"),
        )
        val viewModel = LiveGrepDialogViewModel(
            backend = TestLiveGrepSearchBackend(
                runGrep = { query, _ ->
                    GrepSearchResult(
                        totalMatches = sourceMatches.size,
                        query = query,
                        matches = sourceMatches,
                    )
                },
                filterMatchesAction = { query, matches ->
                    matches.filter { it.lineText.contains(query) }
                },
            ),
            scope = backgroundScope,
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()
        advanceUntilIdle()
        assertEquals(sourceMatches, viewModel.state.value.matches)

        viewModel.onSelectNextMatch()
        assertEquals(sourceMatches[1], viewModel.state.value.selectedMatch)

        viewModel.onUpdateFzfQuery("needle")
        advancePastSearchDebounce()

        assertEquals("needle", viewModel.state.value.fzfQuery)
        assertEquals(listOf(sourceMatches[0]), viewModel.state.value.matches)

        assertEquals(0, viewModel.state.value.selectedMatchIndex)
        assertEquals(sourceMatches[0], viewModel.state.value.selectedMatch)
    }

    @Test
    fun cancelsStalePreviewLoadWhenSelectionChanges() = runTest {
        val firstMatch = grepMatch("/tmp/App.kt", "needle")
        val secondMatch = grepMatch("/tmp/Other.kt", "other")
        val firstPreviewStarted = CompletableDeferred<Unit>()
        val firstPreviewCanceled = CompletableDeferred<Unit>()
        val secondPreviewStarted = CompletableDeferred<Unit>()
        val viewModel = viewModelWithMatches(
            firstMatch,
            secondMatch,
            loadPreview = { path ->
                if (path == firstMatch.path) {
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

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()
        firstPreviewStarted.await()

        viewModel.onSelectNextMatch()
        runCurrent()

        firstPreviewCanceled.await()
        secondPreviewStarted.await()
        advanceUntilIdle()

        val preview = viewModel.state.value.preview as LiveGrepPreviewState.Ready
        assertEquals(secondMatch, preview.match)
        assertEquals("second preview", preview.content.text)
    }

    @Test
    fun buildsReadyPreviewScrollLineAndHighlightsForMatchesInSameFile() = runTest {
        val firstMatch = grepMatch(
            path = "/tmp/App.kt",
            lineText = "needle one",
            line = 3,
            range = TextRange(0, 6),
        )
        val secondMatch = grepMatch(
            path = "/tmp/App.kt",
            lineText = "needle two",
            line = 8,
            range = TextRange(7, 10),
        )
        val otherFileMatch = grepMatch(
            path = "/tmp/Other.kt",
            lineText = "needle other",
            line = 2,
            range = TextRange(0, 6),
        )
        val viewModel = viewModelWithMatches(firstMatch, secondMatch, otherFileMatch)

        viewModel.onUpdateRgQuery("needle")
        advancePastSearchDebounce()
        advanceUntilIdle()

        val preview = viewModel.state.value.preview as LiveGrepPreviewState.Ready
        assertEquals(3, preview.scrollToLine)
        assertEquals(
            listOf(
                PreviewHighlightRange(line = 3, range = TextRange(0, 6)),
                PreviewHighlightRange(line = 8, range = TextRange(7, 10)),
            ),
            preview.highlightRanges,
        )
    }

    private fun TestScope.advancePastSearchDebounce() {
        advanceTimeBy(SEARCH_DEBOUNCE_WAIT_MS)
        runCurrent()
    }

    private fun grepMatch(
        path: String,
        lineText: String,
        line: Int = 1,
        range: TextRange = TextRange(0, lineText.length),
    ): GrepMatch {
        return GrepMatch(
            path = Path.of(path),
            line = line,
            column = 1,
            lineText = lineText,
            matchRanges = listOf(range),
        )
    }

    private fun TestScope.viewModelWithMatches(
        vararg matches: GrepMatch,
        loadPreview: suspend (Path) -> PreviewContent = { path -> PreviewContent(path.toString(), null) },
    ): LiveGrepDialogViewModel {
        return LiveGrepDialogViewModel(
            backend = TestLiveGrepSearchBackend(
                runGrep = { query, _ ->
                    GrepSearchResult(
                        totalMatches = matches.size,
                        query = query,
                        matches = matches.toList(),
                    )
                },
            ),
            scope = backgroundScope,
            initialOptions = GrepSearchOptions(),
            loadPreview = loadPreview,
        )
    }

    private class TestLiveGrepSearchBackend(
        private val streamGrep: ((String, GrepSearchOptions) -> Flow<GrepSearchUpdate>)? = null,
        private val runGrep: suspend (String, GrepSearchOptions) -> GrepSearchResult = { query, _ ->
            GrepSearchResult(
                totalMatches = 0,
                query = query,
                matches = emptyList(),
            )
        },
        private val filterMatchesAction: suspend (String, List<GrepMatch>) -> List<GrepMatch> = { _, matches -> matches },
        private val notifyErrorAction: (String) -> Unit = {},
    ) : LiveGrepSearchBackend {
        override fun grepStream(query: String, options: GrepSearchOptions): Flow<GrepSearchUpdate> {
            return streamGrep?.invoke(query, options) ?: flow {
                val result = runGrep(query, options)
                emit(
                    GrepSearchUpdate(
                        totalMatches = result.totalMatches,
                        query = result.query,
                        matches = result.matches,
                        isComplete = true,
                    ),
                )
            }
        }

        override suspend fun filterMatches(query: String, matches: List<GrepMatch>): List<GrepMatch> {
            return filterMatchesAction(query, matches)
        }

        override fun notifyError(message: String) {
            notifyErrorAction(message)
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_WAIT_MS = 250L
    }
}
