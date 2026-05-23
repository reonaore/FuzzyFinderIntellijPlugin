package com.github.reonaore.fuzzyfinderintellijplugin.tabfinder

import com.intellij.testFramework.LightVirtualFile
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class TabFinderModelsTest {
    @Test
    fun buildsRelativeDisplayTextForProjectFiles() {
        val file = TestVirtualFile("App.kt", "/repo/src/App.kt")

        val candidate = file.toOpenTabCandidate("/repo")

        assertEquals("App.kt", candidate.fileName)
        assertEquals("src", candidate.secondaryPath)
        assertEquals("App.kt src/App.kt", candidate.displayText)
    }

    @Test
    fun fallsBackToPresentablePathOutsideProject() {
        val file = TestVirtualFile("Scratch.kt", "temp:///Scratch.kt")

        val candidate = file.toOpenTabCandidate("/repo")

        assertEquals("Scratch.kt", candidate.fileName)
        assertEquals("temp://", candidate.secondaryPath)
        assertEquals("Scratch.kt temp:///Scratch.kt", candidate.displayText)
    }

    @Test
    fun omitsSecondaryPathForTopLevelFiles() {
        val file = TestVirtualFile("README.md", "/repo/README.md")

        val candidate = file.toOpenTabCandidate("/repo")

        assertNull(candidate.secondaryPath)
    }

    private class TestVirtualFile(
        name: String,
        private val customPath: String,
    ) : LightVirtualFile(name) {
        override fun getPath(): String = customPath

        override fun getPresentableUrl(): String = customPath
    }
}
