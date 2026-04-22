package com.github.reonaore.fuzzyfinderintellijplugin.ui

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test
import java.nio.file.Path

class FuzzyFinderFileListTest {

    @Test
    fun resolvesRelativePathFromProjectBase() {
        val path = Path.of("/repo/src/main/App.kt")

        assertEquals("src/main/App.kt", path.relativePathFrom("/repo"))
    }

    @Test
    fun resolvesRelativeParentPathFromProjectBase() {
        val path = Path.of("/repo/src/main/App.kt")

        assertEquals("src/main", path.relativeParentPath("/repo"))
    }

    @Test
    fun omitsSecondaryPathForTopLevelFile() {
        val path = Path.of("/repo/App.kt")

        assertNull(path.relativeParentPath("/repo"))
    }

    @Test
    fun returnsEmptyHighlightsWhenQueryDoesNotMatchFilename() {
        assertEquals(emptyList<Int>(), fuzzyMatchIndexes("app.py", "zzz"))
    }

    @Test
    fun returnsFuzzyCharacterIndexesForFilenameMatches() {
        assertEquals(listOf(0, 1, 13), fuzzyMatchIndexes("application.py", "apy"))
    }

    @Test
    fun ignoresWhitespaceInQueryWhenHighlighting() {
        assertEquals(listOf(0, 1), fuzzyMatchIndexes("App.kt", "a p"))
    }

    @Test
    fun groupsContiguousHighlightIndexesIntoSingleRanges() {
        assertEquals(
            listOf(0..2, 5..6, 9..9),
            contiguousHighlightRanges(setOf(0, 1, 2, 5, 6, 9)),
        )
    }

    @Test
    fun returnsEmptyRangesWhenThereAreNoHighlights() {
        assertEquals(emptyList<IntRange>(), contiguousHighlightRanges(emptySet()))
    }
}
