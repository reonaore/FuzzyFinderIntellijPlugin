package com.github.reonaore.fuzzyfinderintellijplugin

import com.github.reonaore.fuzzyfinderintellijplugin.services.GrepMatch
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
            "/repo/src/App.kt:12:9:fun needle() = Unit\n".toByteArray(),
        )

        assertEquals(
            listOf(GrepMatch(Path.of("/repo/src/App.kt"), 12, 9, "fun needle() = Unit")),
            parsed,
        )
    }

    @Test
    fun parsesRipgrepMatchesWithColonsInPath() {
        val parsed = FuzzyFinderParsers.parseRgMatches(
            "/repo/src/foo:bar/App.kt:12:9:fun needle() = Unit\n".toByteArray(),
        )

        assertEquals(
            listOf(GrepMatch(Path.of("/repo/src/foo:bar/App.kt"), 12, 9, "fun needle() = Unit")),
            parsed,
        )
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
