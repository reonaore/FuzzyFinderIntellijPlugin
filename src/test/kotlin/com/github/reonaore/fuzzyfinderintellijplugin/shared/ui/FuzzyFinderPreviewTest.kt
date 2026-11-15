package com.github.reonaore.fuzzyfinderintellijplugin.shared.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewHighlightRange
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewLineHighlight
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewLineHighlightKind
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotSame
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

    @Test
    fun calculatesPreviewLineHighlightIndexesAndIgnoresOutOfRangeLines() {
        val indexes = previewLineHighlightIndexes(
            lineCount = 3,
            lineHighlights = listOf(
                PreviewLineHighlight(line = 1, kind = PreviewLineHighlightKind.MATCH),
                PreviewLineHighlight(line = 3, kind = PreviewLineHighlightKind.SELECTED),
                PreviewLineHighlight(line = 4, kind = PreviewLineHighlightKind.MATCH),
            ),
        )

        assertEquals(
            listOf(
                PreviewLineHighlightIndex(lineIndex = 0, kind = PreviewLineHighlightKind.MATCH),
                PreviewLineHighlightIndex(lineIndex = 2, kind = PreviewLineHighlightKind.SELECTED),
            ),
            indexes,
        )
    }

    @Test
    fun normalizesPreviewTextLineSeparatorsForEditorDocuments() {
        val text = "@rem\r\n@rem Copyright\r\n\r\nset APP_HOME=%DIRNAME%"

        assertEquals(
            "@rem\n@rem Copyright\n\nset APP_HOME=%DIRNAME%",
            normalizePreviewTextForEditor(text),
        )
    }

    @Test
    fun blendsPreviewHighlightColorIntoEditorBackground() {
        assertEquals(
            Color(45, 65, 85),
            blendColors(
                background = Color(20, 40, 60),
                foreground = Color(120, 140, 160),
                foregroundOpacity = 0.25f,
            ),
        )
    }

    @Test
    fun usesPreferredEditorSchemeAttributesForSearchMatches() {
        val preferred = TextAttributes(Color.BLACK, Color.YELLOW, null, null, 0)
        val fallback = TextAttributes(Color.WHITE, Color.BLUE, null, null, 0)

        val result = previewSearchTextAttributes(preferred, fallback, Color.RED, Color.GREEN)

        assertEquals(preferred, result)
        assertNotSame(preferred, result)
    }

    @Test
    fun usesFallbackEditorSchemeAttributesWhenPreferredAttributesAreUnavailable() {
        val fallback = TextAttributes(Color.WHITE, Color.BLUE, null, null, 0)

        val result = previewSearchTextAttributes(null, fallback, Color.RED, Color.GREEN)

        assertEquals(fallback, result)
        assertNotSame(fallback, result)
    }

    @Test
    fun fallsBackToSelectionColorsWhenSearchAttributesAreUnavailable() {
        val result = previewSearchTextAttributes(null, null, Color.WHITE, Color.BLUE)

        assertEquals(Color.WHITE, result.foregroundColor)
        assertEquals(Color.BLUE, result.backgroundColor)
    }
}
