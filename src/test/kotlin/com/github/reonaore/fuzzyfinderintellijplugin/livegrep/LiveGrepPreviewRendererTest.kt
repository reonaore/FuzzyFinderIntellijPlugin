package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

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
