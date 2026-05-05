package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewHighlightRange
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchOptions
import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepSearchResult
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
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
import java.util.concurrent.atomic.AtomicInteger

class LiveGrepDialogViewModelTest {
    @Test
    fun cancelsSupersededGrepWhenQueryChanges() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
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
            scope = scope,
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("f")
        withTimeout(TEST_TIMEOUT_MS) {
            firstSearchStarted.await()
        }

        viewModel.onUpdateRgQuery("fo")

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
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("needle")
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.matches == sourceMatches }
        }

        viewModel.onUpdateFzfQuery("other")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil {
                viewModel.state.value.fzfQuery == "other" &&
                    viewModel.state.value.matches == listOf(sourceMatches[1])
            }
        }
        assertEquals(2, viewModel.state.value.totalMatches)
    }

    @Test
    fun doesNotCancelRunningGrepWhenFzfQueryChanges() = runBlocking {
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
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("needle")
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.rgQuery == "needle" && viewModel.state.value.isSearching }
        }

        viewModel.onUpdateFzfQuery("other")
        delay(SEARCH_DEBOUNCE_WAIT_MS)

        assertFalse(grepFinished.isCompleted)

        grepResult.complete(
            GrepSearchResult(
                totalMatches = sourceMatches.size,
                query = "needle",
                matches = sourceMatches,
            ),
        )

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil {
                viewModel.state.value.fzfQuery == "other" &&
                    viewModel.state.value.matches == listOf(sourceMatches[1])
            }
        }
    }

    @Test
    fun marksStateAsErrorWhenGrepFails() = runBlocking {
        val notifications = mutableListOf<String>()
        val viewModel = LiveGrepDialogViewModel(
            backend = TestLiveGrepSearchBackend(
                runGrep = { _, _ -> throw FuzzyFinderException("rg failed") },
                notifyErrorAction = notifications::add,
            ),
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("query")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.hasError }
        }

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
    fun selectsFirstMatchAndLoadsPreviewWhenGrepReturnsResults() = runBlocking {
        val firstMatch = grepMatch("/tmp/App.kt", "needle")
        val secondMatch = grepMatch("/tmp/Other.kt", "other")
        val viewModel = viewModelWithMatches(firstMatch, secondMatch)

        viewModel.onUpdateRgQuery("needle")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.preview is LiveGrepPreviewState.Ready }
        }

        assertEquals(0, viewModel.state.value.selectedMatchIndex)
        assertEquals(firstMatch, viewModel.state.value.selectedMatch)
        assertTrue(viewModel.state.value.canOpenSelectedMatch)
        assertEquals(firstMatch, (viewModel.state.value.preview as LiveGrepPreviewState.Ready).match)
    }

    @Test
    fun clearsSelectionAndPreviewWhenGrepReturnsNoResults() = runBlocking {
        val viewModel = viewModelWithMatches()

        viewModel.onUpdateRgQuery("missing")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.hasSearched }
        }

        assertEquals(LiveGrepDialogState.NO_SELECTION, viewModel.state.value.selectedMatchIndex)
        assertNull(viewModel.state.value.selectedMatch)
        assertFalse(viewModel.state.value.canOpenSelectedMatch)
        assertTrue(viewModel.state.value.preview is LiveGrepPreviewState.Empty)
    }

    @Test
    fun clampsMatchSelectionWithinVisibleMatches() = runBlocking {
        val firstMatch = grepMatch("/tmp/App.kt", "needle")
        val secondMatch = grepMatch("/tmp/Other.kt", "other")
        val viewModel = viewModelWithMatches(firstMatch, secondMatch)

        viewModel.onUpdateRgQuery("needle")
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.selectedMatch == firstMatch }
        }

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
    fun doesNotReloadPreviewWhenClampedSelectionIsUnchanged() = runBlocking {
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
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.preview is LiveGrepPreviewState.Ready }
        }

        viewModel.onSelectPreviousMatch()
        viewModel.onSelectNextMatch()

        assertEquals(0, viewModel.state.value.selectedMatchIndex)
        assertEquals(match, viewModel.state.value.selectedMatch)
        assertEquals(1, loadCount.get())
        assertTrue(viewModel.state.value.preview is LiveGrepPreviewState.Ready)
    }

    @Test
    fun resetsSelectionToFirstFilteredMatchWhenFzfQueryChanges() = runBlocking {
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
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = GrepSearchOptions(),
            loadPreview = { path -> PreviewContent(path.toString(), null) },
        )

        viewModel.onUpdateRgQuery("needle")
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.matches == sourceMatches }
        }
        viewModel.onSelectNextMatch()
        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.selectedMatch == sourceMatches[1] }
        }

        viewModel.onUpdateFzfQuery("needle")

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil {
                viewModel.state.value.fzfQuery == "needle" &&
                    viewModel.state.value.matches == listOf(sourceMatches[0])
            }
        }

        assertEquals(0, viewModel.state.value.selectedMatchIndex)
        assertEquals(sourceMatches[0], viewModel.state.value.selectedMatch)
    }

    @Test
    fun cancelsStalePreviewLoadWhenSelectionChanges() = runBlocking {
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
        withTimeout(TEST_TIMEOUT_MS) {
            firstPreviewStarted.await()
        }

        viewModel.onSelectNextMatch()

        withTimeout(TEST_TIMEOUT_MS) {
            firstPreviewCanceled.await()
            secondPreviewStarted.await()
            waitUntil { viewModel.state.value.preview is LiveGrepPreviewState.Ready }
        }

        val preview = viewModel.state.value.preview as LiveGrepPreviewState.Ready
        assertEquals(secondMatch, preview.match)
        assertEquals("second preview", preview.content.text)
    }

    @Test
    fun buildsReadyPreviewScrollLineAndHighlightsForMatchesInSameFile() = runBlocking {
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

        withTimeout(TEST_TIMEOUT_MS) {
            waitUntil { viewModel.state.value.preview is LiveGrepPreviewState.Ready }
        }

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

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) {
            delay(10)
        }
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

    private fun viewModelWithMatches(
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
            scope = CoroutineScope(Job() + Dispatchers.Default),
            initialOptions = GrepSearchOptions(),
            loadPreview = loadPreview,
        )
    }

    private class TestLiveGrepSearchBackend(
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
        override suspend fun grep(query: String, options: GrepSearchOptions): GrepSearchResult {
            return runGrep(query, options)
        }

        override suspend fun filterMatches(query: String, matches: List<GrepMatch>): List<GrepMatch> {
            return filterMatchesAction(query, matches)
        }

        override fun notifyError(message: String) {
            notifyErrorAction(message)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 2_000L
        const val SEARCH_DEBOUNCE_WAIT_MS = 250L
    }
}
