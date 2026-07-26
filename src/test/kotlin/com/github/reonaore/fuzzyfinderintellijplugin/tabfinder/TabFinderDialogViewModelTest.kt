package com.github.reonaore.fuzzyfinderintellijplugin.tabfinder

import com.github.reonaore.fuzzyfinderintellijplugin.services.FuzzyFinderException
import com.intellij.testFramework.LightVirtualFile
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TabFinderDialogViewModelTest {
    @Test
    fun filtersOpenTabsAndSelectsFirstResult() = runTest {
        val first = candidate("App.kt", "/repo/src/App.kt")
        val second = candidate("Readme.md", "/repo/README.md")
        val viewModel = TabFinderDialogViewModel(
            backend = TestTabFinderBackend(filterAction = { query, candidates ->
                candidates.filter { it.displayText.contains(query, ignoreCase = true) }
            }),
            scope = backgroundScope,
        )

        viewModel.onUpdateOpenTabs(listOf(first, second))
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()
        viewModel.onUpdateQuery("read")
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()

        assertEquals(listOf(second), viewModel.state.value.candidates)
        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(second, viewModel.state.value.selectedCandidate)
        assertTrue(viewModel.state.value.canActivateSelectedTab)
    }

    @Test
    fun resetsSelectionToFirstTabWhenQueryChanges() = runTest {
        val first = candidate("App.kt", "/repo/src/App.kt")
        val second = candidate("Readme.md", "/repo/README.md")
        val viewModel = viewModelWith(first, second)

        viewModel.onSelectNextCandidate()
        viewModel.onUpdateQuery("repo")
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()

        assertEquals(first, viewModel.state.value.selectedCandidate)
        assertEquals(0, viewModel.state.value.selectedIndex)
    }

    @Test
    fun keepsAutomaticallySelectedTabAtFirstIndexDuringOpenTabUpdates() = runTest {
        val initialFirst = candidate("App.kt", "/repo/src/App.kt")
        val second = candidate("Readme.md", "/repo/README.md")
        val newFirst = candidate("Build.kt", "/repo/buildSrc/Build.kt")
        val viewModel = viewModelWith(initialFirst, second)

        viewModel.onUpdateOpenTabs(listOf(newFirst, initialFirst, second))
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(newFirst, viewModel.state.value.selectedCandidate)
    }

    @Test
    fun keepsUserSelectedTabDuringOpenTabUpdates() = runTest {
        val first = candidate("App.kt", "/repo/src/App.kt")
        val selected = candidate("Readme.md", "/repo/README.md")
        val newFirst = candidate("Build.kt", "/repo/buildSrc/Build.kt")
        val viewModel = viewModelWith(first, selected)

        viewModel.onSelectCandidate(1)
        viewModel.onUpdateOpenTabs(listOf(newFirst, first, selected))
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.selectedIndex)
        assertEquals(selected, viewModel.state.value.selectedCandidate)
    }

    @Test
    fun fallsBackToFirstTabWhenSelectionDisappears() = runTest {
        val first = candidate("App.kt", "/repo/src/App.kt")
        val second = candidate("Readme.md", "/repo/README.md")
        val newFirst = candidate("Build.kt", "/repo/buildSrc/Build.kt")
        val viewModel = viewModelWith(first, second)

        viewModel.onSelectNextCandidate()
        viewModel.onUpdateOpenTabs(listOf(first))
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()

        assertEquals(first, viewModel.state.value.selectedCandidate)
        assertEquals(0, viewModel.state.value.selectedIndex)

        viewModel.onUpdateOpenTabs(listOf(newFirst, first))
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()

        assertEquals(newFirst, viewModel.state.value.selectedCandidate)
        assertEquals(0, viewModel.state.value.selectedIndex)
    }

    @Test
    fun wrapsNextCandidateSelectionAtEndAndClampsPreviousAtStart() = runTest {
        val first = candidate("App.kt", "/repo/src/App.kt")
        val second = candidate("Readme.md", "/repo/README.md")
        val viewModel = viewModelWith(first, second)

        viewModel.onSelectPreviousCandidate()
        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(first, viewModel.state.value.selectedCandidate)

        viewModel.onSelectNextCandidate()
        assertEquals(1, viewModel.state.value.selectedIndex)
        assertEquals(second, viewModel.state.value.selectedCandidate)

        viewModel.onSelectNextCandidate()
        assertEquals(0, viewModel.state.value.selectedIndex)
        assertEquals(first, viewModel.state.value.selectedCandidate)
    }

    @Test
    fun reportsNoOpenTabsWhenCandidateListIsEmpty() = runTest {
        val viewModel = viewModelWith()

        viewModel.onSelectNextCandidate()

        assertFalse(viewModel.state.value.hasOpenTabs)
        assertEquals(emptyList<OpenTabCandidate>(), viewModel.state.value.candidates)
        assertEquals(TabFinderDialogState.NO_SELECTION, viewModel.state.value.selectedIndex)
        assertNull(viewModel.state.value.selectedCandidate)
    }

    @Test
    fun marksStateAsErrorWhenFilteringFails() = runTest {
        val notifications = mutableListOf<String>()
        val viewModel = TabFinderDialogViewModel(
            backend = TestTabFinderBackend(
                filterAction = { _, _ -> throw FuzzyFinderException("fzf failed") },
                notifyErrorAction = notifications::add,
            ),
            scope = backgroundScope,
        )

        viewModel.onUpdateOpenTabs(listOf(candidate("App.kt", "/repo/src/App.kt")))
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.hasError)
        assertEquals(listOf("fzf failed"), notifications)
    }

    private suspend fun TestScope.viewModelWith(vararg candidates: OpenTabCandidate): TabFinderDialogViewModel {
        val viewModel = TabFinderDialogViewModel(
            backend = TestTabFinderBackend(filterAction = { _, current -> current }),
            scope = backgroundScope,
        )
        viewModel.onUpdateOpenTabs(candidates.toList())
        advanceTimeBy(SEARCH_DEBOUNCE_TIMEOUT_MS)
        advanceUntilIdle()
        return viewModel
    }

    private fun candidate(name: String, path: String): OpenTabCandidate {
        val file = LightVirtualFile(name)
        return OpenTabCandidate(
            file = file,
            fileName = name,
            secondaryPath = path.substringBeforeLast('/').removePrefix("/repo/").ifBlank { null },
            displayText = "$name $path",
        )
    }

    private class TestTabFinderBackend(
        private val filterAction: suspend (String, List<OpenTabCandidate>) -> List<OpenTabCandidate> = { _, candidates -> candidates },
        private val notifyErrorAction: (String) -> Unit = {},
    ) : TabFinderBackend {
        override suspend fun filterCandidates(
            query: String,
            candidates: List<OpenTabCandidate>,
        ): List<OpenTabCandidate> = filterAction(query, candidates)

        override fun notifyError(message: String) {
            notifyErrorAction(message)
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_TIMEOUT_MS = 1_000L
    }
}
