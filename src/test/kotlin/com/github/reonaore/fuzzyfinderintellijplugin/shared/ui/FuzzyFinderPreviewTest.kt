package com.github.reonaore.fuzzyfinderintellijplugin.shared.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewHighlightRange
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import junit.framework.TestCase.assertEquals
import org.junit.Test

class FuzzyFinderPreviewTest {

    @Test
    fun calculatesPreviewHighlightOffsetsForVisibleMatches() {
        val offsets = previewHighlightTextOffsets(
            text = "first line\nneedle here\nlast line",
            highlightRanges = listOf(
                PreviewHighlightRange(line = 2, range = TextRange(0, 6)),
                PreviewHighlightRange(line = 2, range = TextRange(7, 11)),
            ),
        )

        assertEquals(listOf(TextRange(11, 17), TextRange(18, 22)), offsets)
    }

    @Test
    fun ignoresMatchesOutsideLoadedPreviewText() {
        val offsets = previewHighlightTextOffsets(
            text = "first line\nneedle here\n\n... [preview truncated]",
            highlightRanges = listOf(
                PreviewHighlightRange(line = 2, range = TextRange(0, 6)),
                PreviewHighlightRange(line = 20, range = TextRange(0, 6)),
            ),
        )

        assertEquals(listOf(TextRange(11, 17)), offsets)
    }

    @Test
    fun clampsPreviewHighlightOffsetsToLineEnd() {
        val offsets = previewHighlightTextOffsets(
            text = "needle",
            highlightRanges = listOf(
                PreviewHighlightRange(line = 1, range = TextRange(0, 100)),
            ),
        )

        assertEquals(listOf(TextRange(0, 6)), offsets)
    }
}
