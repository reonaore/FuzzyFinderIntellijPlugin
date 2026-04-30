package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import junit.framework.TestCase.assertEquals
import org.junit.Test
import java.nio.file.Path

class LiveGrepMatchListTest {

    @Test
    fun preservesMatchRangesForLineHighlighting() {
        val ranges = listOf(TextRange(4, 10), TextRange(15, 21))
        val item = listOf(
            GrepMatch(
                path = Path.of("/repo/src/App.kt"),
                line = 12,
                column = 5,
                lineText = "fun needle and needle",
                matchRanges = ranges,
            ),
        ).toGroupedGrepListItems("/repo")
            .filterIsInstance<GrepMatchItem>()
            .single()

        assertEquals("12:5", item.location)
        assertEquals("App.kt", item.fileName)
        assertEquals("src", item.secondaryPath)
        assertEquals("fun needle and needle", item.lineText)
        assertEquals(ranges, item.highlightRanges)
    }

    @Test
    fun groupsMatchesByFileWithFileHeaders() {
        val items = listOf(
            GrepMatch(
                path = Path.of("/repo/src/App.kt"),
                line = 12,
                column = 5,
                lineText = "fun needle",
                matchRanges = listOf(TextRange(4, 10)),
            ),
            GrepMatch(
                path = Path.of("/repo/src/App.kt"),
                line = 24,
                column = 1,
                lineText = "needle()",
                matchRanges = listOf(TextRange(0, 6)),
            ),
            GrepMatch(
                path = Path.of("/repo/test/AppTest.kt"),
                line = 7,
                column = 12,
                lineText = "assertNeedle()",
                matchRanges = listOf(TextRange(6, 12)),
            ),
        ).toGroupedGrepListItems("/repo")

        assertEquals(5, items.size)
        assertEquals("App.kt", (items[0] as GrepFileHeaderItem).fileName)
        assertEquals(2, (items[0] as GrepFileHeaderItem).matchCount)
        assertEquals("12:5", (items[1] as GrepMatchItem).location)
        assertEquals("App.kt", (items[1] as GrepMatchItem).fileName)
        assertEquals("24:1", (items[2] as GrepMatchItem).location)
        assertEquals("App.kt", (items[2] as GrepMatchItem).fileName)
        assertEquals("AppTest.kt", (items[3] as GrepFileHeaderItem).fileName)
        assertEquals(1, (items[3] as GrepFileHeaderItem).matchCount)
        assertEquals("7:12", (items[4] as GrepMatchItem).location)
        assertEquals("AppTest.kt", (items[4] as GrepMatchItem).fileName)
    }

    @Test
    fun returnsFirstMatchIndexAfterHeader() {
        val items = listOf(
            GrepMatch(
                path = Path.of("/repo/src/App.kt"),
                line = 12,
                column = 5,
                lineText = "fun needle",
                matchRanges = listOf(TextRange(4, 10)),
            ),
        ).toGroupedGrepListItems("/repo")

        assertEquals(1, firstMatchIndex(items))
    }
}
