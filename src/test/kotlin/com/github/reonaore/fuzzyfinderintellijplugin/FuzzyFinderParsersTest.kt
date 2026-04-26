package com.github.reonaore.fuzzyfinderintellijplugin

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

class FuzzyFinderParsersTest {

    @Test
    fun parsesNulSeparatedPaths() {
        val parsed = FuzzyFinderParsers.parseNulSeparatedPaths("/tmp/a\u0000/tmp/b\u0000".toByteArray())

        assertEquals(listOf(Paths.get("/tmp/a"), Paths.get("/tmp/b")), parsed)
    }

    @Test
    fun serializesPathsAsNulSeparatedBytes() {
        val serialized = FuzzyFinderParsers.toNulSeparatedBytes(listOf(Paths.get("/tmp/a"), Paths.get("/tmp/b")))

        assertEquals("/tmp/a\u0000/tmp/b\u0000", serialized.toString(Charsets.UTF_8))
    }

    @Test
    fun appendsPreviewSuffixWhenTruncated() {
        val preview = FuzzyFinderParsers.appendPreviewSuffix("body", truncated = true)

        assertTrue(preview.contains("preview truncated"))
    }

    @Test
    fun parsesRipgrepMatches() {
        val parsed = FuzzyFinderParsers.parseRgMatches(
            """
            {"type":"match","data":{"path":{"text":"/repo/src/App.kt"},"lines":{"text":"fun needle() = Unit\n"},"line_number":12,"submatches":[{"match":{"text":"needle"},"start":4,"end":10}]}}
            """.trimIndent().toByteArray(),
        )

        assertEquals(
            listOf(
                GrepMatch(
                    path = Path.of("/repo/src/App.kt"),
                    line = 12,
                    column = 5,
                    lineText = "fun needle() = Unit",
                    matchRanges = listOf(TextRange(4, 10)),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun parsesRipgrepMatchesWithColonsInPath() {
        val parsed = FuzzyFinderParsers.parseRgMatches(
            """
            {"type":"match","data":{"path":{"text":"/repo/src/foo:bar/App.kt"},"lines":{"text":"fun needle() = Unit\n"},"line_number":12,"submatches":[{"match":{"text":"needle"},"start":4,"end":10}]}}
            """.trimIndent().toByteArray(),
        )

        assertEquals(
            listOf(
                GrepMatch(
                    path = Path.of("/repo/src/foo:bar/App.kt"),
                    line = 12,
                    column = 5,
                    lineText = "fun needle() = Unit",
                    matchRanges = listOf(TextRange(4, 10)),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun parsesRipgrepMatchesWithMultipleRangesOnOneLine() {
        val parsed = FuzzyFinderParsers.parseRgMatches(
            """
            {"type":"match","data":{"path":{"text":"/repo/src/App.kt"},"lines":{"text":"needle needle\n"},"line_number":7,"submatches":[{"match":{"text":"needle"},"start":0,"end":6},{"match":{"text":"needle"},"start":7,"end":13}]}}
            """.trimIndent().toByteArray(),
        )

        assertEquals(
            listOf(
                GrepMatch(
                    path = Path.of("/repo/src/App.kt"),
                    line = 7,
                    column = 1,
                    lineText = "needle needle",
                    matchRanges = listOf(TextRange(0, 6), TextRange(7, 13)),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun convertsRipgrepUtf8ByteOffsetsToTextOffsets() {
        val parsed = FuzzyFinderParsers.parseRgMatches(
            """
            {"type":"match","data":{"path":{"text":"/repo/src/App.kt"},"lines":{"text":"🙂needle\n"},"line_number":7,"submatches":[{"match":{"text":"needle"},"start":4,"end":10}]}}
            """.trimIndent().toByteArray(),
        )

        assertEquals(
            listOf(
                GrepMatch(
                    path = Path.of("/repo/src/App.kt"),
                    line = 7,
                    column = 3,
                    lineText = "🙂needle",
                    matchRanges = listOf(TextRange(2, 8)),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun preservesRipgrepZeroWidthMatches() {
        val parsed = FuzzyFinderParsers.parseRgMatches(
            """
            {"type":"match","data":{"path":{"text":"/repo/src/App.kt"},"lines":{"text":"fun needle\n"},"line_number":7,"submatches":[{"match":{"text":""},"start":0,"end":0}]}}
            """.trimIndent().toByteArray(),
        )

        assertEquals(
            listOf(
                GrepMatch(
                    path = Path.of("/repo/src/App.kt"),
                    line = 7,
                    column = 1,
                    lineText = "fun needle",
                    matchRanges = listOf(TextRange(0, 0)),
                ),
            ),
            parsed,
        )
    }

    @Test
    fun ignoresNonMatchRipgrepJsonMessages() {
        val parsed = FuzzyFinderParsers.parseRgMatches(
            """
            {"type":"begin","data":{"path":{"text":"/repo/src/App.kt"}}}
            {"type":"end","data":{"path":{"text":"/repo/src/App.kt"},"binary_offset":null,"stats":{"elapsed":{"secs":0,"nanos":1,"human":"0.000000001s"},"searches":1,"searches_with_match":0,"bytes_searched":0,"bytes_printed":0,"matched_lines":0,"matches":0}}}
            """.trimIndent().toByteArray(),
        )

        assertEquals(emptyList<GrepMatch>(), parsed)
    }

    @Test
    fun returnsEmptyRipgrepMatchesForEmptyOutput() {
        assertEquals(emptyList<GrepMatch>(), FuzzyFinderParsers.parseRgMatches(ByteArray(0)))
    }

    @Test
    fun resolvesBundleMessagesForDialogAndErrors() {
        assertNotNull(MyBundle.message("dialog.status.loadingProgress", 42))
        assertNotNull(MyBundle.message("dialog.status.resultsDetailed", 10, 100))
        assertNotNull(MyBundle.message("dialog.grep.status.resultsDetailed", 10, 100))
        assertNotNull(MyBundle.message("error.commandFailed", "cmd", 1, "stderr"))
    }
}
