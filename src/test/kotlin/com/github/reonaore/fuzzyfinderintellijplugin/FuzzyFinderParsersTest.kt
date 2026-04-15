package com.github.reonaore.fuzzyfinderintellijplugin

import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import java.nio.file.Paths

class FuzzyFinderParsersTest {

    @org.junit.Test
    fun parsesNulSeparatedPaths() {
        val parsed = FuzzyFinderParsers.parseNulSeparatedPaths("/tmp/a\u0000/tmp/b\u0000".toByteArray())

        assertEquals(listOf(Paths.get("/tmp/a"), Paths.get("/tmp/b")), parsed)
    }

    @org.junit.Test
    fun serializesPathsAsNulSeparatedBytes() {
        val serialized = FuzzyFinderParsers.toNulSeparatedBytes(listOf(Paths.get("/tmp/a"), Paths.get("/tmp/b")))

        assertEquals("/tmp/a\u0000/tmp/b\u0000", serialized.toString(Charsets.UTF_8))
    }

    @org.junit.Test
    fun appendsPreviewSuffixWhenTruncated() {
        val preview = FuzzyFinderParsers.appendPreviewSuffix("body", truncated = true)

        assertTrue(preview.contains("preview truncated"))
    }
}
