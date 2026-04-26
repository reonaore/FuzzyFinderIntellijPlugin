package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import junit.framework.TestCase.assertEquals
import org.junit.Test
import java.nio.file.Path

class LiveGrepMatchListTest {

    @Test
    fun preservesMatchRangesForLineHighlighting() {
        val ranges = listOf(TextRange(4, 10), TextRange(15, 21))
        val item = GrepMatch(
            path = Path.of("/repo/src/App.kt"),
            line = 12,
            column = 5,
            lineText = "fun needle and needle",
            matchRanges = ranges,
        ).toGrepListItem("/repo")

        assertEquals("12:5", item.location)
        assertEquals("fun needle and needle", item.lineText)
        assertEquals(ranges, item.highlightRanges)
    }
}
