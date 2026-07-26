package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

internal class LiveGrepPreviewRenderer(
    private val showPreview: suspend (LiveGrepPreviewState) -> Unit,
) {
    private var renderedState: LiveGrepPreviewState? = null

    suspend fun render(state: LiveGrepPreviewState) {
        if (renderedState == state) return

        showPreview(state)
        renderedState = state
    }
}
