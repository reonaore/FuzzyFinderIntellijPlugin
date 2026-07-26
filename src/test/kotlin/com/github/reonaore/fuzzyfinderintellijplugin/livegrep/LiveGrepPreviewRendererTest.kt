package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class LiveGrepPreviewRendererTest {

    @Test
    fun retriesRenderingWhenPreviousAttemptIsCanceled() = runTest {
        val firstRenderStarted = CompletableDeferred<Unit>()
        var renderCount = 0
        val renderer = LiveGrepPreviewRenderer {
            renderCount += 1
            if (renderCount == 1) {
                firstRenderStarted.complete(Unit)
                awaitCancellation()
            }
        }

        val firstRender = launch {
            renderer.render(LiveGrepPreviewState.Empty)
        }
        firstRenderStarted.await()
        firstRender.cancelAndJoin()

        renderer.render(LiveGrepPreviewState.Empty)

        assertEquals(2, renderCount)
    }

    @Test
    fun rerendersPreviousStateWhenDifferentRenderIsCanceled() = runTest {
        val stateA = LiveGrepPreviewState.Empty
        val stateB = LiveGrepPreviewState.Loading(
            GrepMatch(
                path = Path.of("/tmp/App.kt"),
                line = 1,
                column = 1,
                lineText = "needle",
                matchRanges = emptyList(),
            ),
        )
        val secondRenderStarted = CompletableDeferred<Unit>()
        val renderedStates = mutableListOf<LiveGrepPreviewState>()
        val renderer = LiveGrepPreviewRenderer { state ->
            renderedStates += state
            if (state == stateB) {
                secondRenderStarted.complete(Unit)
                awaitCancellation()
            }
        }

        renderer.render(stateA)
        val secondRender = launch {
            renderer.render(stateB)
        }
        secondRenderStarted.await()
        secondRender.cancelAndJoin()

        renderer.render(stateA)

        assertEquals(listOf(stateA, stateB, stateA), renderedStates)
    }

    @Test
    fun skipsStateThatWasRenderedSuccessfully() = runTest {
        var renderCount = 0
        val renderer = LiveGrepPreviewRenderer {
            renderCount += 1
        }

        renderer.render(LiveGrepPreviewState.Empty)
        renderer.render(LiveGrepPreviewState.Empty)

        assertEquals(1, renderCount)
    }
}
