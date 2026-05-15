package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import junit.framework.TestCase.assertEquals
import org.junit.Test

class LiveGrepQueryTest {
    @Test
    fun buildsOrderedPartialMatchRegexFromWords() {
        assertEquals("foo.*bar", buildLiveGrepQuery("  foo   bar  ", LiveGrepQueryMode.WORDS))
    }

    @Test
    fun escapesRegexCharactersInWordsMode() {
        assertEquals(
            "foo\\.bar.*\\(baz\\)",
            buildLiveGrepQuery("foo.bar (baz)", LiveGrepQueryMode.WORDS),
        )
    }

    @Test
    fun preservesInputInRegexMode() {
        assertEquals("foo.+bar", buildLiveGrepQuery("foo.+bar", LiveGrepQueryMode.REGEX))
    }
}
